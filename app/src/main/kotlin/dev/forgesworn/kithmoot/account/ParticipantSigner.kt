package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.NostrEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Whoever holds the participant key.
 *
 * A room needs one signature from the person per join, on the device
 * credential, and the person's key need not be on this phone to give it. It
 * can be in a signer app on the same phone (NIP-55), in a bunker reached over
 * a relay (NIP-46: My Signet, a Heartwood), or, as a last resort, pasted in.
 * `sign` is a round trip in every case but the last, and can take as long as a
 * person takes to tap Approve.
 */
interface ParticipantSigner {
    val pubkey: String

    /** `local`, `nip55` or `bunker`. What the saved account says it is. */
    val method: String

    /** Whether NIP-44 is offered: what keeps room bookmarks and read positions private to the person. */
    val canEncrypt: Boolean get() = true

    suspend fun sign(kind: Int, createdAt: Long, tags: List<List<String>>, content: String): NostrEvent
    suspend fun nip44Encrypt(peer: String, plaintext: String): String
    suspend fun nip44Decrypt(peer: String, payload: String): String

    fun close() {}
}

class SignerException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A key held in memory on this device. Rooms started with no account use one per room. */
class LocalSigner(private val secretKey: ByteArray) : ParticipantSigner {
    override val pubkey: String = Schnorr.publicKeyHex(secretKey)
    override val method: String get() = "local"

    fun sign(kind: Int, createdAt: Long, tags: List<List<String>>, content: String, auxRand: ByteArray): NostrEvent =
        Events.sign(secretKey, kind, createdAt, tags, content, auxRand)

    override suspend fun sign(kind: Int, createdAt: Long, tags: List<List<String>>, content: String): NostrEvent =
        Events.sign(secretKey, kind, createdAt, tags, content)

    override suspend fun nip44Encrypt(peer: String, plaintext: String): String =
        Nip44.encrypt(plaintext, Nip44.conversationKey(secretKey, peer.hexToBytes()))

    override suspend fun nip44Decrypt(peer: String, payload: String): String =
        Nip44.decrypt(payload, Nip44.conversationKey(secretKey, peer.hexToBytes()))

    /** Only the encrypted local store, and the account store, need a copy. */
    fun secretKeyForStorage(): ByteArray = secretKey.copyOf()
}

/**
 * The unsigned event a remote signer is handed, in the shape NIP-01 gives it
 * and NIP-46 and NIP-55 both expect: the `pubkey` filled in and the `id`
 * computed, so the signer has nothing to invent and a strict one has nothing
 * to refuse.
 */
fun unsignedEventJson(pubkey: String, kind: Int, createdAt: Long, tags: List<List<String>>, content: String): String =
    buildJsonObject {
        put("id", Events.eventId(pubkey, createdAt, kind, tags, content))
        put("pubkey", pubkey)
        put("created_at", createdAt)
        put("kind", kind)
        put("tags", JsonArray(tags.map { tag -> JsonArray(tag.map(::JsonPrimitive)) }))
        put("content", content)
    }.toString()

/**
 * What a remote signer sent back, checked. A signer is somebody else's code
 * on the far side of a round trip; the one thing that binds its answer to the
 * request is that the event verifies under the key we asked for, with the
 * fields we asked it to sign.
 */
fun checkedSignedEvent(
    signed: NostrEvent, pubkey: String, kind: Int, createdAt: Long, tags: List<List<String>>, content: String,
): NostrEvent {
    if (signed.pubkey != pubkey) throw SignerException("The signer answered for a different key.")
    if (signed.kind != kind || signed.createdAt != createdAt || signed.tags != tags || signed.content != content) {
        throw SignerException("The signer changed the event before signing it.")
    }
    if (!Events.verify(signed)) throw SignerException("The signature from the signer does not verify.")
    return signed
}
