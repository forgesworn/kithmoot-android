package dev.forgesworn.kithmoot.update

import dev.forgesworn.kithmoot.protocol.BoundedDigest
import dev.forgesworn.kithmoot.protocol.UpdateManifest
import java.io.File
import java.io.FileOutputStream

/**
 * The part of an update that decides what to trust: fetch the manifest and its
 * signature, check the signature against the keys built into the app, decide
 * whether the manifest offers anything, and download the APK it pins. Nothing
 * Android here, so all of it runs in the JVM unit tests; [AppUpdates] wires it
 * to the network, the package manager and the installer.
 *
 * Mirrors `desktop/archive-updater.mjs` in the web repository: nothing the
 * download server says is taken on trust. The APK must match the size and
 * SHA-256 the signed manifest gives, counted as it streams in and cut off the
 * moment it runs long.
 */
class UpdateFlow(
    private val network: UpdateNetwork,
    private val applicationId: String,
    private val versionCode: Long,
    private val installedFrom: InstalledFrom,
    private val keys: List<String> = UpdateManifest.KEYS,
    private val origin: String = ORIGIN,
) {
    /** What one check found. */
    sealed interface Check {
        /** Nothing newer, or nothing at all. */
        data object Current : Check
        /** A newer release. Through Zapstore, the app never downloads it itself. */
        data class Offered(val update: UpdateManifest.AndroidUpdate, val viaZapstore: Boolean) : Check
    }

    /**
     * Fetches and checks the manifest. Throws [UpdateUnverified] when the
     * signature does not verify or the signed manifest is not one this app
     * acts on, and anything else (an [java.io.IOException], usually) when the
     * server could not be reached.
     */
    suspend fun check(): Check {
        val url = "$origin/${UpdateManifest.ANDROID}"
        val manifest = network.fetch(url, MAX_MANIFEST_BYTES)
        val signature = network.fetch("$url.sig", MAX_MANIFEST_BYTES)
        if (!UpdateManifest.verify(UpdateManifest.ANDROID, manifest, signature, keys)) {
            throw UpdateUnverified("update manifest signature did not verify")
        }
        val update = try {
            UpdateManifest.androidUpdate(manifest, applicationId, versionCode)
        } catch (e: UpdateManifest.MalformedManifest) {
            throw UpdateUnverified("signed manifest refused: ${e.message}")
        } ?: return Check.Current
        return Check.Offered(update, viaZapstore = installedFrom == InstalledFrom.ZAPSTORE)
    }

    /**
     * Streams the APK [update] pins into [dir], refusing anything that is not
     * exactly its size with its SHA-256. Deletes what it wrote on any failure;
     * a mismatch or an overrun throws [UpdateUnverified].
     */
    suspend fun download(update: UpdateManifest.AndroidUpdate, dir: File, onProgress: (Long) -> Unit = {}): File {
        check(installedFrom != InstalledFrom.ZAPSTORE) { "Zapstore installs update through Zapstore" }
        dir.mkdirs()
        val file = File(dir, update.downloadFilename)
        file.delete()
        val digest = BoundedDigest(update.apkBytes)
        try {
            FileOutputStream(file).use { out ->
                network.stream(apkUrl(update)) { chunk, length ->
                    digest.update(chunk, 0, length)
                    out.write(chunk, 0, length)
                    onProgress(digest.bytes)
                }
                out.fd.sync()
            }
            if (digest.bytes != update.apkBytes || digest.finish() != update.apkSha256) {
                throw UpdateUnverified("download does not match the signed manifest")
            }
            return file
        } catch (e: Throwable) {
            file.delete()
            if (e is BoundedDigest.TooLong) throw UpdateUnverified(e.message ?: "download is too large")
            throw e
        }
    }

    fun apkUrl(update: UpdateManifest.AndroidUpdate): String = "$origin/apk/${update.downloadFilename}"

    companion object {
        const val ORIGIN = "https://kithmoot.forgesworn.dev"
        const val MAX_MANIFEST_BYTES = 64 * 1024
    }
}

/** How this copy was installed, which decides who updates it. */
enum class InstalledFrom { ZAPSTORE, DIRECT }

/** The network, as the update flow needs it. */
interface UpdateNetwork {
    /** The body of [url], refusing a failed answer or one longer than [limit] bytes. */
    suspend fun fetch(url: String, limit: Int): ByteArray

    /** Streams [url]'s body to [sink] a chunk at a time, refusing a failed answer.
     *  [sink] may throw to stop the download. */
    suspend fun stream(url: String, sink: (ByteArray, Int) -> Unit)
}

/** Something offered as an update that the app could not verify, so did not install. */
class UpdateUnverified(message: String) : Exception(message)

/**
 * Whether a downloaded APK is the update it claims to be, before anybody is
 * asked to install it: our package, the manifest's version, signed by a
 * certificate this copy is already signed with (Android refuses anything else
 * on install anyway; refusing first never shows the person a hostile APK),
 * and, where the signed manifest names the release certificate, signed by
 * that one. Certificates are SHA-256 hex. Null when it is; otherwise why not.
 */
fun archiveRefusal(
    archive: ArchiveFacts?,
    applicationId: String,
    update: UpdateManifest.AndroidUpdate,
    runningCertificates: Set<String>,
): String? = when {
    archive == null -> "the download is not an APK Android can read"
    archive.packageName != applicationId -> "the download is another application"
    archive.versionCode != update.versionCode -> "the download is a different version"
    runningCertificates.isEmpty() || archive.certificates.none { it in runningCertificates } -> "the download is signed by a different key"
    update.certificateSha256 != null && update.certificateSha256 !in archive.signers -> "the download is not signed by the release key"
    else -> null
}

/** What the package manager reads from an APK file. */
data class ArchiveFacts(
    val packageName: String,
    val versionCode: Long,
    /** The certificates that signed this APK's contents. */
    val signers: Set<String>,
    /** [signers] and every certificate in their rotation history. */
    val certificates: Set<String>,
)
