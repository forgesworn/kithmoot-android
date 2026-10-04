package dev.forgesworn.kithmoot.update

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.SigningInfo
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.BoundedDigest
import dev.forgesworn.kithmoot.protocol.UpdateManifest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * In-app updates for a copy installed from the downloads page.
 *
 * [UpdateFlow] decides what to trust; this wires it to Android. Checks run
 * shortly after launch and then at most every six hours while KithMoot is on
 * screen, never in the background. Nothing downloads until the person presses
 * Install, since an APK is well over a hundred megabytes.
 *
 * A copy Zapstore installed is Zapstore's to update: the app says a release is
 * out and opens Zapstore, and never downloads one itself.
 *
 * Installing replaces the running app, which ends whatever it was doing, so
 * nothing is handed to the installer while a call is under way: the same gate
 * the desktop's restart prompt has.
 */
class AppUpdates(private val app: Application) {

    sealed interface State {
        data object Idle : State
        data object Checking : State
        /** The last check found nothing newer. */
        data object Current : State
        data class Available(val versionName: String, val viaZapstore: Boolean) : State
        data class Downloading(val versionName: String, val fraction: Float) : State
        /** Downloaded and verified, waiting for the call to end. */
        data class Ready(val versionName: String) : State
        data class Installing(val versionName: String) : State
        /** [versionName] is set when the failure is about an update the person asked for. */
        data class Failed(val message: String, val versionName: String? = null, val retry: Boolean = false) : State
    }

    private val mutableState = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = mutableState.asStateFlow()

    private val mutableCallActive = MutableStateFlow(false)
    /** True while a call is joined, joining or changing: see [setCallActive]. */
    val callActive: StateFlow<Boolean> = mutableCallActive.asStateFlow()

    private val mutableDismissed = MutableStateFlow<String?>(null)
    /** The version whose home notice the person put off with Later, this run. */
    val dismissed: StateFlow<String?> = mutableDismissed.asStateFlow()

    private val prefs = app.getSharedPreferences("kithmoot.updates.v1", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val dir = File(app.cacheDir, "updates")

    private val debuggable = app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    /** Debug builds share the production application id but not its key, so an
     *  update could never install over one; they check only when asked. */
    val automaticByDefault = !debuggable && app.packageName == PRODUCTION_ID

    private val mutableAutomatic = MutableStateFlow(prefs.getBoolean(KEY_AUTOMATIC, automaticByDefault))
    val automatic: StateFlow<Boolean> = mutableAutomatic.asStateFlow()

    val installedFrom: InstalledFrom by lazy {
        val source = runCatching { app.packageManager.getInstallSourceInfo(app.packageName) }.getOrNull()
        val owner = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) source?.updateOwnerPackageName else null
        if (source?.installingPackageName == ZAPSTORE || owner == ZAPSTORE) InstalledFrom.ZAPSTORE else InstalledFrom.DIRECT
    }

    private val packageInfo by lazy { app.packageManager.getPackageInfo(app.packageName, 0) }
    val versionName: String get() = packageInfo.versionName.orEmpty()

    private val flow by lazy {
        UpdateFlow(OkHttpUpdateNetwork(), app.packageName, packageInfo.longVersionCode, installedFrom)
    }

    private val startedAt = SystemClock.elapsedRealtime()
    private var lastCheck: Long? = null
    private var loop: Job? = null
    private var work: Job? = null
    private var offered: UpdateManifest.AndroidUpdate? = null
    private var apk: File? = null
    /** Install was pressed and sent the person to allow installs from KithMoot. */
    private var awaitingPermission = false

    init {
        // Whatever an earlier run downloaded is either installed by now or stale.
        scope.launch(Dispatchers.IO) { dir.deleteRecursively() }
    }

    fun setAutomatic(on: Boolean) {
        prefs.edit().putBoolean(KEY_AUTOMATIC, on).apply()
        mutableAutomatic.value = on
        onForeground(foreground)
    }

    private var foreground = false

    /** MainActivity's visibility: checks run only while KithMoot is on screen. */
    fun onForeground(shown: Boolean) {
        foreground = shown
        loop?.cancel()
        loop = null
        if (!shown) return
        if (awaitingPermission && app.packageManager.canRequestPackageInstalls()) {
            awaitingPermission = false
            install()
        }
        // Back from the installer's confirmation without an answer from it:
        // the verified file is still there, so Install works again.
        (mutableState.value as? State.Installing)?.let { if (apk?.isFile == true) mutableState.value = State.Ready(it.versionName) }
        if (!automatic.value) return
        loop = scope.launch {
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                val wait = lastCheck?.let { CHECK_INTERVAL_MS - (now - it) } ?: (FIRST_CHECK_DELAY_MS - (now - startedAt))
                delay(wait.coerceAtLeast(0))
                runCheck()
            }
        }
    }

    /** Settings' Check for updates. */
    fun checkNow() {
        if (work?.isActive == true) return
        work = scope.launch { runCheck() }
    }

    private suspend fun runCheck() {
        lastCheck = SystemClock.elapsedRealtime()
        when (mutableState.value) {
            is State.Checking, is State.Downloading, is State.Ready, is State.Installing -> return
            else -> Unit
        }
        val before = mutableState.value
        mutableState.value = State.Checking
        mutableState.value = try {
            when (val result = withContext(Dispatchers.IO) { flow.check() }) {
                UpdateFlow.Check.Current -> { offered = null; State.Current }
                is UpdateFlow.Check.Offered -> {
                    offered = result.update
                    State.Available(result.update.versionName, result.viaZapstore)
                }
            }
        } catch (e: UpdateUnverified) {
            Log.w(TAG, "update check refused: ${e.message}")
            offered = null
            State.Failed(UNVERIFIED)
        } catch (e: Exception) {
            Log.w(TAG, "update check failed: $e")
            // An update already on offer stays on offer through a failed recheck.
            if (offered != null && (before is State.Available || before is State.Failed)) before else State.Failed(CHECK_FAILED)
        }
    }

    /** MainActivity: true while a call is joined, joining or changing. A
     *  download that finished during a call waits for Install to be pressed
     *  again, rather than putting the installer up the moment it ends. */
    fun setCallActive(active: Boolean) {
        mutableCallActive.value = active
    }

    fun dismiss(versionName: String) { mutableDismissed.value = versionName }

    /**
     * The Install button: asks for the unknown-apps permission if it is
     * missing, downloads and verifies the APK if that is not done yet, then
     * hands it to the installer once no call is under way.
     */
    fun install() {
        val update = offered ?: return
        if (installedFrom == InstalledFrom.ZAPSTORE || work?.isActive == true) return
        if (!app.packageManager.canRequestPackageInstalls()) {
            awaitingPermission = true
            val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { app.startActivity(settings) }.onFailure {
                awaitingPermission = false
                mutableState.value = State.Failed(PERMISSION, update.versionName, retry = true)
            }
            return
        }
        work = scope.launch {
            val file = apk?.takeIf { it.isFile } ?: download(update) ?: return@launch
            if (callActive.value) { mutableState.value = State.Ready(update.versionName); return@launch }
            mutableState.value = State.Installing(update.versionName)
            try {
                withContext(Dispatchers.IO) { commit(update, file) }
            } catch (e: UpdateUnverified) {
                Log.w(TAG, "update refused at install: ${e.message}")
                forget()
                mutableState.value = State.Failed(UNVERIFIED, update.versionName)
            } catch (e: Exception) {
                Log.w(TAG, "update install failed: $e")
                mutableState.value = State.Failed(INSTALL_FAILED, update.versionName, retry = true)
            }
        }
    }

    private suspend fun download(update: UpdateManifest.AndroidUpdate): File? {
        mutableState.value = State.Downloading(update.versionName, 0f)
        return try {
            val file = withContext(Dispatchers.IO) {
                var shown = 0L
                flow.download(update, dir) { bytes ->
                    // A state per percent, not per 64 KiB chunk.
                    if (bytes - shown >= update.apkBytes / 100 || bytes == update.apkBytes) {
                        shown = bytes
                        mutableState.value = State.Downloading(update.versionName, bytes.toFloat() / update.apkBytes)
                    }
                }
            }
            val refusal = withContext(Dispatchers.IO) { archiveRefusal(inspect(file), app.packageName, update, runningCertificates()) }
            if (refusal != null) {
                file.delete()
                throw UpdateUnverified(refusal)
            }
            apk = file
            file
        } catch (e: UpdateUnverified) {
            Log.w(TAG, "update download refused: ${e.message}")
            forget()
            mutableState.value = State.Failed(UNVERIFIED, update.versionName)
            null
        } catch (e: Exception) {
            Log.w(TAG, "update download failed: $e")
            mutableState.value = State.Failed(DOWNLOAD_FAILED, update.versionName, retry = true)
            null
        }
    }

    /** An update that failed verification is not offered again until a check offers it afresh. */
    private fun forget() {
        offered = null
        apk?.delete()
        apk = null
    }

    private fun inspect(file: File): ArchiveFacts? {
        val info = app.packageManager.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
            ?: return null
        val signing = info.signingInfo ?: return null
        return ArchiveFacts(info.packageName, info.longVersionCode, signers(signing), certificates(signing))
    }

    private fun runningCertificates(): Set<String> = runCatching {
        app.packageManager.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
            .signingInfo?.let(::signers).orEmpty()
    }.getOrDefault(emptySet())

    private fun signers(signing: SigningInfo): Set<String> = signing.apkContentsSigners.orEmpty().map { sha256(it.toByteArray()) }.toSet()

    private fun certificates(signing: SigningInfo): Set<String> =
        signers(signing) + if (signing.hasMultipleSigners()) emptySet() else signing.signingCertificateHistory.orEmpty().map { sha256(it.toByteArray()) }

    /**
     * Writes [file] into an installer session, hashing it again on the way so
     * the bytes the installer gets are the bytes that were verified, not
     * whatever sits in the cache by now.
     */
    private fun commit(update: UpdateManifest.AndroidUpdate, file: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(file.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            try {
                val digest = BoundedDigest(update.apkBytes)
                session.openWrite("base.apk", 0, file.length()).use { out ->
                    file.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            digest.update(buffer, 0, read)
                            out.write(buffer, 0, read)
                        }
                    }
                    session.fsync(out)
                }
                if (digest.bytes != update.apkBytes || digest.finish() != update.apkSha256) throw UpdateUnverified("the downloaded file changed before install")
                // Mutable so the installer can add its status, and explicit,
                // which Android 14 requires of a mutable PendingIntent.
                val status = Intent(app, UpdateInstallReceiver::class.java).setPackage(app.packageName)
                val pending = PendingIntent.getBroadcast(app, id, status, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                session.commit(pending.intentSender)
            } catch (e: Throwable) {
                session.abandon()
                if (e is BoundedDigest.TooLong) throw UpdateUnverified("the downloaded file changed before install")
                throw e
            }
        }
    }

    /** The installer's answer, through [UpdateInstallReceiver]. */
    fun onInstallStatus(intent: Intent) {
        val version = offered?.versionName
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm == null) {
                    mutableState.value = State.Failed(INSTALL_FAILED, version, retry = true)
                    return
                }
                runCatching { app.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure {
                    Log.w(TAG, "could not show the install confirmation: $it")
                    mutableState.value = State.Failed(INSTALL_FAILED, version, retry = true)
                }
            }
            // Usually never seen: Android stops this process to replace it.
            PackageInstaller.STATUS_SUCCESS -> { forget(); mutableState.value = State.Idle }
            // The person said no. The verified file stays, so Install works again.
            PackageInstaller.STATUS_FAILURE_ABORTED -> mutableState.value = version?.let { State.Ready(it) } ?: State.Idle
            else -> {
                Log.w(TAG, "update install failed: $status ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
                mutableState.value = State.Failed(INSTALL_FAILED, version, retry = true)
            }
        }
    }

    /** Opens Zapstore, or returns false when it cannot be launched. */
    fun openZapstore(): Boolean {
        val launch = app.packageManager.getLaunchIntentForPackage(ZAPSTORE) ?: return false
        return runCatching { app.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    companion object {
        private const val TAG = "KithMootUpdates"
        const val PRODUCTION_ID = "dev.forgesworn.kithmoot"
        /** Zapstore's application id: `applicationId` in zapstore/zapstore's android/app/build.gradle.kts. */
        const val ZAPSTORE = "dev.zapstore.app"
        private const val KEY_AUTOMATIC = "automatic"
        private const val FIRST_CHECK_DELAY_MS = 20_000L
        private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

        const val CHECK_FAILED = "Could not check for updates. Try again later."
        const val UNVERIFIED = "An update was offered that KithMoot could not verify, so it was not installed."
        const val DOWNLOAD_FAILED = "The update could not be downloaded. Try again."
        const val INSTALL_FAILED = "The update could not be installed. Try again."
        const val PERMISSION = "Allow KithMoot to install apps in Android settings, then try again."
    }
}
