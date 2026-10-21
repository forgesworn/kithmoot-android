package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.KindredProof
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.Room
import dev.forgesworn.kithmoot.protocol.withRoomExpiration
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Chat events built the way a writer would, for tests of readers and of deletion. Android writes
 * envelope 1 only, so envelope 2 is built here from the primitives: the outer key by HKDF, the
 * digest by hand, the device's signature inside.
 */
object ChatEvents {
    /** A place chat is written to: a root (the room, or an epoch) and the channel under it, whose id is the `d` tag. */
    class Stream(val rootId: String, val rootKey: ByteArray, val channel: String? = null) {
        private val derived = deriveChatChannel(rootId, rootKey, channel)
        val id: String get() = derived.id
        val key: ByteArray get() = derived.key
    }

    /** The outer key of one envelope-2 message, derived independently of the code under test. */
    fun outerKey(deviceSecretKey: ByteArray, d: String, messageId: String): ByteArray =
        Digests.hkdfSha256(deviceSecretKey, null, "kithmoot/v1/chat-outer:${d.lowercase()}:$messageId".toByteArray(Charsets.UTF_8), 32)

    /** The JSON of an envelope-1 line from [identity], exactly as the encoder writes it. */
    fun body(room: Room, identity: RoomIdentity, stream: Stream, id: String, sentAt: Long = 100, text: String = "hello", proof: KindredProof? = null): String =
        Nip44.decrypt(v1(room, identity, stream, id, sentAt, text, proof).content, stream.key)

    /** An envelope-1 line: signed by the device. */
    fun v1(
        room: Room, identity: RoomIdentity, stream: Stream, id: String, sentAt: Long = 100, text: String = "hello",
        proof: KindredProof? = null, roomEnds: Long? = null,
    ): NostrEvent =
        encodeChatEvent(
            body = text, participant = identity.participant, credential = identity.credential, roomId = stream.rootId,
            roomKey = stream.rootKey, deviceSecretKey = identity.deviceSecretKey, sentAt = sentAt, id = id,
            channel = stream.channel, credentialRoomId = room.roomId, proof = proof, roomEnds = roomEnds,
        )

    /**
     * An envelope-2 line on [stream]: [body] signed by [signer] inside, the event signed by [outerSk].
     * By default [outerSk] is the outer key the writer derives from the device's own key.
     */
    fun v2(
        room: Room,
        identity: RoomIdentity,
        stream: Stream,
        id: String,
        sentAt: Long = 100,
        text: String = "hello",
        signer: ByteArray = identity.deviceSecretKey,
        outerSk: ByteArray = outerKey(identity.deviceSecretKey, stream.id, id),
        proof: KindredProof? = null,
        roomEnds: Long? = null,
        body: String = body(room, identity, stream, id, sentAt, text, proof),
    ): NostrEvent {
        val outer = Schnorr.publicKeyHex(outerSk)
        val digest = Digests.sha256("kithmoot/v1/chat-envelope-2:${room.roomId}:${stream.id}:$outer:$sentAt:$body".toByteArray(Charsets.UTF_8))
        val envelope = buildJsonObject { put("v", 2); put("body", body); put("sig", Schnorr.sign(digest, signer).toHex()) }
        return Events.sign(outerSk, KIND_CHAT, sentAt, withRoomExpiration(listOf(listOf("d", stream.id)), roomEnds), Nip44.encrypt(envelope.toString(), stream.key))
    }
}
