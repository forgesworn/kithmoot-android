package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import java.util.Base64

/**
 * Signed update manifests. Mirrors `desktop/update-manifest.mjs` in the web
 * repository, which keeps the same keys and the same message construction;
 * `update-manifest.json`, copied verbatim from its `vectors/`, keeps the two
 * in step.
 *
 * The download server is not trusted: a checksum served beside the file it
 * describes proves nothing to somebody who can change both. What the app
 * trusts instead is an Ed25519 signature, made at release time on a machine
 * that never holds the server's keys, over the exact bytes of the manifest it
 * fetched, checked against a key compiled into the app. The manifest then pins
 * the APK's SHA-256 and size.
 */
object UpdateManifest {
    /**
     * Raw Ed25519 public keys, hex. The first signs releases. The second is a
     * recovery key kept offline, so a lost or leaked release key can be
     * replaced by a release the recovery key signs, without stranding anybody.
     */
    val KEYS: List<String> = listOf(
        "eada5891ff88e81b80a1ea7ffca7bfa31ac66b33aa03c862d6e62d397ca0216a",
        "be13e8bf3e634b044bbd11575ebe488e33240ecb4c6fdfd8ca08dd9e7cfa424e",
    )

    /** What each signed file is for: its path on the site. A signature over one never verifies as the other. */
    const val ANDROID = "android-release.json"
    const val DESKTOP = "downloads/release.json"

    /** At most this many bytes of APK, whatever a manifest says. */
    const val MAX_APK_BYTES: Long = 512L * 1024 * 1024

    private const val DOMAIN = "kithmoot/v1/update-manifest"
    private val SIGNATURE = Regex("^[A-Za-z0-9+/]{86}==\\s*$")
    private val KEY = Regex("^[0-9a-f]{64}$")
    private val SHA256 = Regex("^[0-9a-f]{64}$")
    private val FILENAME = Regex("^[a-zA-Z0-9][a-zA-Z0-9._-]*\\.apk$")
    private val INTEGER = Regex("^(0|[1-9][0-9]*)$")

    /** The bytes a manifest's signature covers: a domain tag, the manifest's
     *  path on the site, then the file exactly as served. */
    fun signedMessage(label: String, bytes: ByteArray): ByteArray {
        require(label == ANDROID || label == DESKTOP) { "unknown manifest $label" }
        return "$DOMAIN\n$label\n".toByteArray(Charsets.UTF_8) + bytes
    }

    /** Whether [signature] (the `.sig` file: base64 of 64 bytes) is a signature
     *  by one of [keys] over [bytes] as manifest [label]. Never throws. */
    fun verify(label: String, bytes: ByteArray, signature: ByteArray, keys: List<String> = KEYS): Boolean = try {
        val text = String(signature, Charsets.UTF_8)
        if (!SIGNATURE.matches(text)) false
        else {
            val sig = Base64.getDecoder().decode(text.trim())
            val message = signedMessage(label, bytes)
            sig.size == 64 && keys.any { hex ->
                KEY.matches(hex) && Ed25519Signer().run {
                    init(false, Ed25519PublicKeyParameters(hex.hexToBytes(), 0))
                    update(message, 0, message.size)
                    verifySignature(sig)
                }
            }
        }
    } catch (e: Exception) {
        false
    }

    /** An update the signed manifest offers. */
    data class AndroidUpdate(
        val versionName: String,
        val versionCode: Long,
        val apkBytes: Long,
        val apkSha256: String,
        val downloadFilename: String,
        /** The release certificate's SHA-256, lower-case hex, when the manifest names one. */
        val certificateSha256: String?,
    )

    /** A verified manifest that is not one this app will act on. */
    class MalformedManifest(message: String) : Exception(message)

    /**
     * Reads a verified Android manifest and returns the update this copy should
     * take, or null when there is none. Throws [MalformedManifest] when the
     * manifest is not for this app or is not well formed. Only call it on bytes
     * [verify] accepted.
     */
    fun androidUpdate(bytes: ByteArray, applicationId: String, currentVersionCode: Long): AndroidUpdate? {
        val manifest = try {
            Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject
        } catch (e: Exception) {
            throw MalformedManifest("not a JSON object")
        }
        if (manifest.integer("schemaVersion") != 1L) throw MalformedManifest("unknown schemaVersion")
        if (manifest.text("applicationId") != applicationId) throw MalformedManifest("for another application")
        val versionCode = manifest.integer("versionCode")?.takeIf { it > 0 } ?: throw MalformedManifest("bad versionCode")
        // Never sideways or backwards: an old signed manifest replayed by the
        // server can at worst keep this copy where it is.
        if (versionCode <= currentVersionCode) return null
        val versionName = manifest.text("versionName")?.takeIf { it.isNotBlank() && it.length <= 64 } ?: throw MalformedManifest("bad versionName")
        val filename = manifest.text("downloadFilename")?.takeIf { FILENAME.matches(it) } ?: throw MalformedManifest("bad downloadFilename")
        val sha256 = manifest.text("apkSha256")?.takeIf { SHA256.matches(it) } ?: throw MalformedManifest("bad apkSha256")
        val size = manifest.integer("apkBytes")?.takeIf { it in 1..MAX_APK_BYTES } ?: throw MalformedManifest("bad apkBytes")
        val certificate = if (manifest["currentCertificateSha256"] == null) null
            else manifest.text("currentCertificateSha256")?.takeIf { SHA256.matches(it) } ?: throw MalformedManifest("bad currentCertificateSha256")
        return AndroidUpdate(versionName, versionCode, size, sha256, filename, certificate)
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** A JSON number written as a plain non-negative integer: not a string, not `1.0`, not `1e3`. */
    private fun JsonObject.integer(key: String): Long? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString && INTEGER.matches(it.content) }?.content?.toLongOrNull()
}

/** A running SHA-256 and byte count that refuses to go past [limit]. */
class BoundedDigest(private val limit: Long) {
    private val hash = MessageDigest.getInstance("SHA-256")
    var bytes: Long = 0
        private set

    /** Throws [TooLong] the moment the total passes [limit], before hashing the excess. */
    fun update(chunk: ByteArray, offset: Int = 0, length: Int = chunk.size) {
        bytes += length
        if (bytes > limit) throw TooLong()
        hash.update(chunk, offset, length)
    }

    /** Lower-case hex SHA-256 of everything so far. Call once. */
    fun finish(): String = hash.digest().toHex()

    class TooLong : Exception("download is larger than the manifest says")
}
