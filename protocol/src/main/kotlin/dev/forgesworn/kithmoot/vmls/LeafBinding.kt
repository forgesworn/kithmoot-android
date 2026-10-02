package dev.forgesworn.kithmoot.vmls

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.NostrEvent
import fr.acinq.secp256k1.Secp256k1
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * The unsigned body of a VMLS/1 `leaf-binding/1`, read the way the MLS device
 * vault must read it before it signs (Vennel MLS contract §6.2, review packet
 * S1). A port of the binding parts of Vennel's independent reader
 * (`vectors/vmls-reader.mjs`) and of KithMoot web's `src/vmls/binding.ts`,
 * which follow `vmls-core`'s `binding.rs` check for check: the same order and
 * the same stable error codes.
 *
 * Only the seven-key unsigned body is accepted. A signed eight-key binding, a
 * digest without its body, or a body with extra bytes is refused, so the
 * vault can never be asked to sign something it has not read.
 *
 * These are not `Credential.kt`'s rules: that file matches tags first-found
 * and hex case-insensitively, while the engine requires exactly one of each
 * tag, lower-case hex, and bounds on issue time and remaining lifetime.
 */

/** The stable `vmls_core::ErrorCode` names this reader can return. */
enum class BindingErrorCode {
    Malformed, NonCanonical, UnsupportedVersion, TooLarge,
    BadLeafIdentity, BadDeviceKey, BindingExpired, BindingOutlivesCredential,
    CredentialMalformed, CredentialSignatureInvalid, CredentialNotPersonScoped,
    CredentialWrongIdentity, CredentialWrongDevice, CredentialExpired,
    CredentialLifetimeTooLong, CredentialRevoked, CredentialNotYetValid,
}

class BindingException(val code: BindingErrorCode) : Exception(code.name)

private fun fail(code: BindingErrorCode): Nothing = throw BindingException(code)

object LeafBinding {
    /** A completed binding (body, key 8 and a 64-byte signature) fits in this. */
    const val MAX_BINDING_BYTES = 8192
    /** Key 8's header and the signature's: `08 58 40` and 64 bytes. */
    private const val SIGNATURE_FIELD_BYTES = 3 + 64
    const val MAX_UNSIGNED_BODY_BYTES = MAX_BINDING_BYTES - SIGNATURE_FIELD_BYTES
    const val SIGNATURE_TAG = "VMLS/1 leaf-binding"
    const val DEVICE_CREDENTIAL_KIND = 20460
    const val MAX_PERSON_CREDENTIAL_SECONDS = 30L * 86_400
    const val MAX_CREATED_AT_SKEW_SECONDS = 600L
    /** JavaScript's `Number.MAX_SAFE_INTEGER`: every reader caps timestamps here. */
    const val MAX_SAFE = 9_007_199_254_740_991L
    private val IDENTITY_PREFIX = "vmls1".toByteArray(Charsets.US_ASCII)
    private val HEX64 = Regex("^[0-9a-f]{64}$")
    private val EXPIRATION = Regex("^(0|[1-9][0-9]{0,15})$")

    /** Reads a seven-key unsigned `leaf-binding/1` body, and nothing else. */
    fun readUnsigned(body: ByteArray): UnsignedBinding {
        if (body.size > MAX_UNSIGNED_BODY_BYTES) fail(BindingErrorCode.TooLarge)
        val r = CborReader(body)
        r.map(7)
        r.key(1); if (r.head(0) != 1UL) fail(BindingErrorCode.UnsupportedVersion)
        r.key(2); val identity = r.fixed(37)
        if (!identity.copyOfRange(0, 5).contentEquals(IDENTITY_PREFIX)) fail(BindingErrorCode.BadLeafIdentity)
        r.key(3); val signatureKey = r.fixed(32)
        r.key(4); r.map(5)
        r.key(1); val pubkey = r.fixed(32).toHex()
        r.key(2); val createdAt = r.timestamp()
        r.key(3)
        val tags = ArrayList<List<String>>()
        repeat(r.arrayLen(16)) {
            val n = r.arrayLen(8)
            if (n == 0) fail(BindingErrorCode.Malformed)
            tags.add(List(n) { r.text(512) })
        }
        r.key(4); val content = r.text(1024)
        r.key(5); val sig = r.fixed(64).toHex()
        r.key(5); val device = r.fixed(32)
        r.key(6); val expiresAt = r.timestamp()
        r.key(7); val homeBox = r.fixed(32)
        r.finish()
        val id = Events.eventId(pubkey, createdAt, DEVICE_CREDENTIAL_KIND, tags, content)
        val event = NostrEvent(DEVICE_CREDENTIAL_KIND, createdAt, tags, content, pubkey, id, sig)
        return UnsignedBinding(body.copyOf(), identity, signatureKey, event, device, expiresAt, homeBox)
    }

    /** `SHA-256(UTF8("VMLS/1 leaf-binding") || body)`: what the device signs. */
    fun digest(body: ByteArray): ByteArray = Digests.sha256(SIGNATURE_TAG.toByteArray(Charsets.UTF_8) + body)

    /**
     * The person-credential rules of `DeviceCredential::verify_person`: a
     * valid NIP-01 signature, `d` equal to the signer, `scope=person`, one
     * `device`, one canonical `expiration` in the future, not issued more than
     * ten minutes ahead, at most 30 days long, and not revoked. The id is
     * recomputed from the event's fields; a carried id is never trusted.
     */
    fun verifyPersonCredential(
        event: NostrEvent,
        now: Long,
        expectedIdentity: String? = null,
        revoked: Set<String> = emptySet(),
    ): PersonCredential {
        if (event.kind != DEVICE_CREDENTIAL_KIND) fail(BindingErrorCode.CredentialMalformed)
        val id = Events.eventId(event.pubkey, event.createdAt, event.kind, event.tags, event.content)
        if (!Events.verify(event.copy(id = id))) fail(BindingErrorCode.CredentialSignatureInvalid)
        fun only(name: String): String? {
            val found = event.tags.filter { it.firstOrNull() == name }
            if (found.size > 1) fail(BindingErrorCode.CredentialMalformed)
            return found.firstOrNull()?.let { it.getOrNull(1) ?: "" }
        }
        val d = only("d")
        val scope = only("scope")
        if (d != event.pubkey || scope != "person") fail(BindingErrorCode.CredentialNotPersonScoped)
        if (expectedIdentity != null && expectedIdentity != event.pubkey) fail(BindingErrorCode.CredentialWrongIdentity)
        val device = only("device")
        if (device == null || !HEX64.matches(device)) fail(BindingErrorCode.CredentialMalformed)
        val expiration = only("expiration")
        if (expiration == null || !EXPIRATION.matches(expiration) || expiration.toLong() > MAX_SAFE) fail(BindingErrorCode.CredentialMalformed)
        val expiresAt = expiration.toLong()
        if (expiresAt <= now) fail(BindingErrorCode.CredentialExpired)
        if (event.createdAt > now + MAX_CREATED_AT_SKEW_SECONDS) fail(BindingErrorCode.CredentialNotYetValid)
        if (expiresAt - event.createdAt > MAX_PERSON_CREDENTIAL_SECONDS || expiresAt - now > MAX_PERSON_CREDENTIAL_SECONDS) {
            fail(BindingErrorCode.CredentialLifetimeTooLong)
        }
        if (id in revoked) fail(BindingErrorCode.CredentialRevoked)
        return PersonCredential(id, event.pubkey, device, expiresAt)
    }

    /**
     * Everything the vault checks of an unsigned body before signing it with
     * its device key: the device key is a curve point, the credential is a
     * valid person credential for [expectedIdentity] naming that device, and
     * the binding is unexpired and does not outlive the credential.
     */
    fun checkUnsigned(
        binding: UnsignedBinding,
        now: Long,
        expectedIdentity: String,
        revoked: Set<String> = emptySet(),
    ): PersonCredential {
        if (!isPoint(binding.device)) fail(BindingErrorCode.BadDeviceKey)
        val credential = verifyPersonCredential(binding.event, now, expectedIdentity, revoked)
        if (credential.device != binding.device.toHex()) fail(BindingErrorCode.CredentialWrongDevice)
        if (binding.expiresAt <= now) fail(BindingErrorCode.BindingExpired)
        if (binding.expiresAt > credential.expiresAt) fail(BindingErrorCode.BindingOutlivesCredential)
        return credential
    }

    /** Whether 32 bytes are the x coordinate of a secp256k1 point. */
    fun isPoint(x: ByteArray): Boolean = x.size == 32 && try {
        Secp256k1.get().pubkeyParse(byteArrayOf(0x02) + x)
        true
    } catch (_: Exception) {
        false
    }
}

/** What the vault signs over, read from the unsigned body. Arrays are copies. */
class UnsignedBinding internal constructor(
    /** The exact body bytes; the digest is computed over these. */
    val body: ByteArray,
    /** `vmls1` followed by the 32-byte leaf id. */
    val identity: ByteArray,
    val signatureKey: ByteArray,
    /** The embedded kind-20460 person credential, its id recomputed. */
    val event: NostrEvent,
    val device: ByteArray,
    val expiresAt: Long,
    val homeBox: ByteArray,
) {
    val leafId: ByteArray get() = identity.copyOfRange(5, 37)
}

data class PersonCredential(
    /** The credential's event id. */
    val id: String,
    val identity: String,
    val device: String,
    val expiresAt: Long,
)

/** A streaming reader: the same checks, in the same order, as `cbor.rs`. */
private class CborReader(private val input: ByteArray) {
    private var at = 0

    fun take(n: Int): ByteArray {
        if (n < 0 || at + n > input.size) fail(BindingErrorCode.Malformed)
        return input.copyOfRange(at, at + n).also { at += n }
    }

    /** A head of [major]: the value, compared unsigned, refusing non-minimal forms. */
    fun head(major: Int): ULong {
        val initial = take(1)[0].toInt() and 0xff
        if (initial ushr 5 != major) fail(BindingErrorCode.NonCanonical)
        val info = initial and 0x1f
        if (info < 24) return info.toULong()
        val width = when (info) { 24 -> 1; 25 -> 2; 26 -> 4; 27 -> 8; else -> fail(BindingErrorCode.NonCanonical) }
        var value = 0UL
        for (byte in take(width)) value = (value shl 8) or (byte.toInt() and 0xff).toULong()
        val minimum = when (width) { 1 -> 24UL; 2 -> 0x100UL; 4 -> 0x10000UL; else -> 0x100000000UL }
        if (value < minimum) fail(BindingErrorCode.NonCanonical)
        return value
    }

    fun bounded(major: Int, max: Int): Int {
        val n = head(major)
        if (n > max.toULong()) fail(BindingErrorCode.TooLarge)
        return n.toInt()
    }

    fun map(n: Int) { if (head(5) != n.toULong()) fail(BindingErrorCode.NonCanonical) }
    fun key(k: Int) { if (head(0) != k.toULong()) fail(BindingErrorCode.NonCanonical) }

    fun timestamp(): Long {
        val value = head(0)
        if (value > LeafBinding.MAX_SAFE.toULong()) fail(BindingErrorCode.Malformed)
        return value.toLong()
    }

    fun fixed(n: Int): ByteArray {
        if (head(2) != n.toULong()) fail(BindingErrorCode.Malformed)
        return take(n)
    }

    fun text(max: Int): String {
        val raw = take(bounded(3, max))
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(raw))
                .toString()
        } catch (_: CharacterCodingException) {
            fail(BindingErrorCode.Malformed)
        }
        if (raw.any { val b = it.toInt() and 0xff; b < 0x20 && b !in ALLOWED_CONTROLS }) fail(BindingErrorCode.Malformed)
        return text
    }

    fun arrayLen(max: Int): Int = bounded(4, max)

    fun finish() { if (at != input.size) fail(BindingErrorCode.NonCanonical) }

    private companion object {
        val ALLOWED_CONTROLS = setOf(0x08, 0x09, 0x0a, 0x0c, 0x0d)
    }
}
