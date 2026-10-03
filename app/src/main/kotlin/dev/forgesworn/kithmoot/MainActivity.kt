package dev.forgesworn.kithmoot

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.forgesworn.kithmoot.ui.theme.LocalTextSizeSetting
import dev.forgesworn.kithmoot.ui.theme.TextSize
import dev.forgesworn.kithmoot.ui.theme.TextSizeSetting
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.runtime.saveable.rememberSaveable
import dev.forgesworn.kithmoot.ui.Stage
import dev.forgesworn.kithmoot.ui.KithMootApp
import dev.forgesworn.kithmoot.ui.RoomViewModel
import dev.forgesworn.kithmoot.ui.theme.KithMootTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.activity.result.contract.ActivityResultContracts
import dev.forgesworn.kithmoot.account.Nip55Bridge
import dev.forgesworn.kithmoot.account.SignetSignIn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.withLock

/**
 * The only activity.
 *
 * It is `singleTask` in the manifest, so a join link tapped while a room is
 * already open arrives at [onNewIntent] on the running instance rather than
 * standing up a second copy of the application on top of a live session.
 */
class MainActivity : ComponentActivity() {

    /** A link that has arrived and not yet been acted on. */
    private val incoming = MutableStateFlow<String?>(null)
    /** The browser coming back from Signet with a sign-in. */
    private val signetReturn = MutableStateFlow<String?>(null)
    private val pictureInPicture = MutableStateFlow(false)
    /**
     * Whether the person can see this application.
     *
     * Only the camera background pipeline reads it, and it reads it to stop a
     * segmentation model running on a phone that is in somebody's pocket. See
     * RoomViewModel.setAppVisible.
     */
    private val visible = MutableStateFlow(true)
    private val notificationRoom = MutableStateFlow<String?>(null)
    /** A room to open and join the call in, off an Answer press - the
     *  notification's action or [dev.forgesworn.kithmoot.ui.incoming.IncomingCallActivity]. */
    private val answerCallRoom = MutableStateFlow<String?>(null)
    /** True from Answer on the lock screen until that call is over: see [showOverLock]. */
    private val answeredOverLock = MutableStateFlow(false)
    /** An answer is opening its room and joining the call: no other prompt may cover it. */
    private val answering = MutableStateFlow(false)
    private val renewRequested = MutableStateFlow(false)

    /**
     * Signer intents. A NIP-55 signer app is another activity started for a
     * result, and only an activity can do that; the view model asks through
     * the application's [dev.forgesworn.kithmoot.account.SignerRelay], which keeps
     * the request and its timeout, so this activity being recreated while the
     * signer is up neither loses the answer nor leaves the request waiting.
     */
    private val signerRelay get() = (application as KithMootApplication).signerRelay
    private val signerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        signerRelay.deliver(result.resultCode == RESULT_OK, result.data)
    }
    private val startSigner: (Intent) -> Unit = { intent -> signerLauncher.launch(intent) }

    /**
     * RECORD_AUDIO for a call answered straight in, like Signal or WhatsApp -
     * asked for here rather than through [dev.forgesworn.kithmoot.ui.Permissions],
     * since answering runs before the room composable's own asker exists for
     * this room. A refusal joins muted rather than failing the answer.
     */
    private var micPermissionAnswer: CompletableDeferred<Boolean>? = null
    private val micPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micPermissionAnswer?.complete(granted)
        micPermissionAnswer = null
    }

    private suspend fun ensureMicForAnswer(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return true
        val answer = CompletableDeferred<Boolean>()
        micPermissionAnswer = answer
        return try {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            answer.await()
        } catch (e: Exception) {
            micPermissionAnswer = null
            false
        }
    }
    override fun onDestroy() {
        signerRelay.detach(startSigner)
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        signerRelay.attach(startSigner)
        enableEdgeToEdge()
        if (intent.action == dev.forgesworn.kithmoot.notifications.ChatNotifications.OPEN) notificationRoom.value = intent.getStringExtra(dev.forgesworn.kithmoot.notifications.ChatNotifications.ROOM)
        else if (intent.action == dev.forgesworn.kithmoot.service.CredentialRenewal.ACTION_RENEW) renewRequested.value = true
        else if (intent.action == dev.forgesworn.kithmoot.notifications.IncomingCallActionReceiver.ACTION_ANSWER) {
            answered(intent)
            answerCallRoom.value = intent.getStringExtra(dev.forgesworn.kithmoot.notifications.IncomingCallRinger.EXTRA_ROOM_ID)
        } else signetFrom(intent)?.let { signetReturn.value = it } ?: run { incoming.value = linkFrom(intent) }

        setContent {
            var textSize by remember { mutableStateOf(TextSize.load(this)) }
            val textSetting = remember(textSize) { TextSizeSetting(textSize) { chosen -> TextSize.save(this, chosen); textSize = chosen } }
            KithMootTheme(textScale = textSize.scale) {
              CompositionLocalProvider(LocalTextSizeSetting provides textSetting) {
                val model: RoomViewModel = viewModel()
                model.signerBridge = signerRelay
                // Beside a call, another room opens in a second, chat-only
                // instance, so the call's session, engine and notifications
                // carry on untouched. Nothing a person navigates to ends a call.
                val visitor: RoomViewModel = viewModel(key = "chat-only", factory = viewModelFactory {
                    initializer { RoomViewModel(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!, chatOnly = true) }
                })
                var visiting by rememberSaveable { mutableStateOf(false) }
                val callStage by model.stage.collectAsState()
                val callRoom by model.room.collectAsState()
                val visitorStage by visitor.stage.collectAsState()
                // With the call gone and the visited room closed, there is only
                // one instance left worth showing.
                LaunchedEffect(visiting, callStage, visitorStage) {
                    if (visiting && callStage != Stage.ROOM && visitorStage == Stage.START) visiting = false
                }
                val backToCall = { if (visitor.stage.value == Stage.ROOM) visitor.leave(); visiting = false }
                // Collected rather than keyed on the value: clearing the
                // request would otherwise change the key and cancel the very
                // effect that is still opening the room.
                LaunchedEffect(Unit) { notificationRoom.filterNotNull().collect { id ->
                    notificationRoom.value = null
                    val callRoom = model.room.value
                    if (visiting && id == callRoom.roomId) { backToCall(); return@collect }
                    // A message from another room while on a call: open it
                    // beside the call, as the rooms list would, never instead of it.
                    if (!visiting && model.stage.value == Stage.ROOM && callRoom.onCall && id != callRoom.roomId) {
                        visitor.borrowAccount(model)
                        visitor.callRoomId = callRoom.roomId
                        visitor.refreshSavedRooms()
                        visiting = true
                    }
                    val target = if (visiting) visitor else model
                    target.start.first { state -> !state.loadingRooms }
                    model.awaitAccountRestored()
                    target.openNotificationRoom(id)
                } }
                // Collected for the same reason: an answer must run to the join.
                LaunchedEffect(Unit) { answerCallRoom.filterNotNull().collect { id ->
                    answerCallRoom.value = null
                    answering.value = true
                    try {
                    if (visiting) backToCall()
                    model.start.first { state -> !state.loadingRooms }
                    model.awaitAccountRestored()
                    model.openNotificationRoom(id)
                    // joinCall() is a no-op until the room actually reaches
                    // Stage.ROOM, which this waits for below.
                    kotlinx.coroutines.flow.combine(model.stage, model.room) { s, r -> s to r.roomId }
                        .first { (s, roomId) -> s == Stage.ROOM && roomId == id }
                    // Answer means straight in, talking, like a phone call -
                    // the microphone goes live with the join. Camera stays off.
                    // Android's permission prompt cannot show over the lock
                    // screen, so a phone still locked joins without the mic
                    // rather than waiting on a prompt nobody can see.
                    val locked = getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
                    val micAllowed = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    model.joinCall(micOn = if (micAllowed || !locked) ensureMicForAnswer() else false)
                    // Straight to the call, not the chat, now and after unlocking.
                    model.showCallView()
                    } finally { answering.value = false }
                } }
                val overLock by answeredOverLock.collectAsState()
                val answeringNow by answering.collectAsState()
                LaunchedEffect(overLock) {
                    if (!overLock) return@LaunchedEffect
                    // Over the lock screen for the answered call only: once it
                    // ends, or the person goes anywhere but the call, the lock
                    // screen covers KithMoot again. A call that never joins
                    // gives up the lock screen too.
                    val joined = kotlinx.coroutines.withTimeoutOrNull(OVER_LOCK_JOIN_MS) {
                        kotlinx.coroutines.flow.combine(model.stage, model.room) { s, r -> s == Stage.ROOM && r.onCall }.first { it }
                    }
                    if (joined != null) kotlinx.coroutines.flow.combine(model.stage, model.room, androidx.compose.runtime.snapshotFlow { visiting }) { s, r, v ->
                        s != Stage.ROOM || !r.onCall || v
                    }.first { it }
                    hideUnderLock()
                }
                val renew by renewRequested.collectAsState()
                LaunchedEffect(renew) {
                    if (!renew) return@LaunchedEffect
                    renewRequested.value = false
                    model.renewCallCredentials()
                }
                // Coming to the front is a chance the signer is still unlocked.
                LaunchedEffect(Unit) {
                    visible.collect { shown ->
                        if (shown) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            dev.forgesworn.kithmoot.service.CredentialRenewal.renewQuietly(applicationContext)
                        }
                    }
                }
                val link by incoming.collectAsState()
                LaunchedEffect(link) {
                    val url = link ?: return@LaunchedEffect
                    incoming.value = null
                    val target = if (visiting) visitor else model
                    target.onJoinUrlChanged(url)
                    target.joinFromUrl(url)
                }
                val signet by signetReturn.collectAsState()
                LaunchedEffect(signet) {
                    val callback = signet ?: return@LaunchedEffect
                    signetReturn.value = null
                    model.completeSignetSignIn(callback)
                }
                LaunchedEffect(model) {
                    model.browser.collect { url ->
                        try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
                        catch (e: Exception) { model.cancelSignIn(); model.showNotice("No browser could open the Signet sign-in.") }
                    }
                }
                val onScreen by visible.collectAsState()
                LaunchedEffect(onScreen) { model.setAppVisible(onScreen) }
                // Ring when KithMoot is closed is on by default (see
                // `service/BackgroundRingSettings.kt`), so most installs
                // reach the service through here rather than the Settings
                // switch or a reboot: reconciled each time the app comes to
                // the front, not once per start, so notifications allowed in
                // Android's settings meanwhile start it on the way back.
                // The running service keeps itself current.
                LaunchedEffect(onScreen) {
                    if (!onScreen) return@LaunchedEffect
                    val delivery = dev.forgesworn.kithmoot.service.BackgroundDeliverySettings(this@MainActivity)
                    // A run that ended without the service saying so (force-stop, crash)
                    // is shown as such until the restarted service reports again.
                    if (delivery.wasRunning() && !dev.forgesworn.kithmoot.service.BackgroundCallListenerService.alive) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            delivery.report(dev.forgesworn.kithmoot.service.DeliveryState.STOPPED, running = false)
                        }
                    }
                    dev.forgesworn.kithmoot.service.startBackgroundServiceIfWanted(this@MainActivity)
                }
                val inPip by pictureInPicture.collectAsState()
                // Still locked: the call alone, never the rest of the room.
                var locked by remember { mutableStateOf(false) }
                LaunchedEffect(overLock) {
                    val keyguard = getSystemService(android.app.KeyguardManager::class.java)
                    while (overLock) {
                        locked = keyguard.isKeyguardLocked
                        kotlinx.coroutines.delay(500)
                    }
                    locked = false
                }
                if (visiting) {
                    KithMootApp(
                        visitor,
                        accountModel = model,
                        dock = if (callStage == Stage.ROOM) ({
                            dev.forgesworn.kithmoot.ui.room.CallDock(callRoom, onToggleMic = model::toggleMicrophone, onBack = backToCall, onLeave = model::leave)
                        }) else null,
                        callRoomId = callRoom.roomId.takeIf { callStage == Stage.ROOM },
                        onBackToCall = backToCall,
                    )
                } else KithMootApp(model, inPip, if (packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) ({
                    val opened = runCatching { enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().setAspectRatio(android.util.Rational(16, 9)).build()) }.getOrDefault(false)
                    if (!opened) model.showNotice("Picture-in-picture could not open. You can still zoom in fullscreen.")
                }) else null, onRoomsKeepingCall = {
                    visitor.borrowAccount(model)
                    visitor.callRoomId = callRoom.roomId
                    visitor.refreshSavedRooms()
                    visiting = true
                }, lockedCallOnly = overLock && locked, callAnswering = answeringNow || overLock, onUnlock = {
                    getSystemService(android.app.KeyguardManager::class.java).requestDismissKeyguard(this@MainActivity, null)
                })
              }
            }
        }
    }

    /**
     * Answer on the lock screen goes straight to the call, the way a phone
     * call does, rather than joining unseen behind the lock screen until the
     * person unlocks. Only for that call: see the `overLock` effect.
     */
    /** Answer, from the notification or the full-screen call: stop ringing, never ring for this call again, show over the lock screen. */
    private fun answered(intent: Intent) {
        val roomId = intent.getStringExtra(dev.forgesworn.kithmoot.notifications.IncomingCallRinger.EXTRA_ROOM_ID).orEmpty()
        val callId = intent.getStringExtra(dev.forgesworn.kithmoot.notifications.IncomingCallRinger.EXTRA_CALL_ID).orEmpty()
        dev.forgesworn.kithmoot.notifications.HandledCalls.add(roomId, callId)
        // Before the stop: a ringing Telecom call becomes the answered one rather than a missed one.
        if (roomId.isNotEmpty()) dev.forgesworn.kithmoot.telecom.CallTelecom.answeredInApp(roomId)
        if (roomId.isNotEmpty()) dev.forgesworn.kithmoot.notifications.IncomingCallRinger.stop(this, roomId)
        // The same Answer joins from the notice for this person's own call on another device.
        if (roomId.isNotEmpty()) dev.forgesworn.kithmoot.notifications.OwnCallElsewhereNotice.cancel(this, roomId)
        showOverLock()
    }

    private fun showOverLock() {
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        answeredOverLock.value = true
    }

    private fun hideUnderLock() {
        setShowWhenLocked(false)
        setTurnScreenOn(false)
        answeredOverLock.value = false
    }

    override fun onStart() {
        super.onStart()
        visible.value = true
    }

    override fun onStop() {
        super.onStop()
        visible.value = false
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pictureInPicture.value = isInPictureInPictureMode
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == dev.forgesworn.kithmoot.notifications.ChatNotifications.OPEN) {
            notificationRoom.value = intent.getStringExtra(dev.forgesworn.kithmoot.notifications.ChatNotifications.ROOM); return
        }
        if (intent.action == dev.forgesworn.kithmoot.service.CredentialRenewal.ACTION_RENEW) { renewRequested.value = true; return }
        if (intent.action == dev.forgesworn.kithmoot.notifications.IncomingCallActionReceiver.ACTION_ANSWER) {
            answered(intent)
            answerCallRoom.value = intent.getStringExtra(dev.forgesworn.kithmoot.notifications.IncomingCallRinger.EXTRA_ROOM_ID); return
        }
        signetFrom(intent)?.let { signetReturn.value = it; return }
        linkFrom(intent)?.let { incoming.value = it }
    }

    /** `kithmoot://signet?…`: Signet sending the browser back here with a sign-in. */
    private fun signetFrom(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        val raw = intent.dataString ?: return null
        return raw.takeIf { SignetSignIn.parse(it) != null }
    }

    /**
     * The link off an incoming VIEW intent.
     *
     * `dataString` is used rather than rebuilding from the `Uri`, because the
     * payload lives entirely in the fragment and a round trip through `Uri`
     * parts is a good way to lose it. A URL with no fragment carries no room and
     * is ignored.
     */
    private fun linkFrom(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        val raw = intent.dataString ?: return null
        return raw.takeIf { it.contains('#') }
    }
}

/** How long Answer waits for the call to join before giving the lock screen back. */
private const val OVER_LOCK_JOIN_MS = 30_000L
