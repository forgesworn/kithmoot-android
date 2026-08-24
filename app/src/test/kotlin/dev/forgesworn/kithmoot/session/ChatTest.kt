package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.deriveRoom
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ChatTest {

    private val room = Fixtures.room()
    private val owner = Fixtures.primary(room, 1, 2)

    private fun message(body: String) = encodeChatEvent(
        body = body,
        participant = owner.participant,
        credential = owner.credential,
        roomId = room.roomId,
        roomKey = room.roomKey,
        deviceSecretKey = owner.deviceSecretKey,
        sentAt = 100,
    )

    @Test
    fun `a chat line round-trips`() {
        val decoded = decodeChatEvent(message("Start a room."), room.roomId, room.roomKey, now = 200)

        assertNotNull(decoded)
        assertEquals("Start a room.", decoded.body)
        assertEquals(owner.participant, decoded.participant)
        assertEquals(owner.devicePubkey, decoded.device)
    }

    @Test
    fun `a line for another room is refused`() {
        val elsewhere = deriveRoom(ByteArray(32) { 99 })
        assertNull(decodeChatEvent(message("hello"), elsewhere.roomId, elsewhere.roomKey, now = 200))
    }

    @Test
    fun `a line encrypted to another key is refused`() {
        val elsewhere = deriveRoom(ByteArray(32) { 99 })
        assertNull(decodeChatEvent(message("hello"), room.roomId, elsewhere.roomKey, now = 200))
    }

    @Test
    fun `a line signed by a device the credential does not name is refused`() {
        // Someone in the room re-encrypting a credential they legitimately hold
        // and signing with their own key, to put words in its owner's mouth.
        val impostor = Fixtures.primary(room, 40, 41)
        val plaintext = buildJsonObject {
            put("participant", owner.participant)
            put("credential", owner.credential.toJson())
            put("body", "I agree to everything")
            put("sentAt", 100)
        }
        val forged = Events.sign(
            secretKey = impostor.deviceSecretKey,
            kind = KIND_CHAT,
            createdAt = 100,
            tags = listOf(listOf("d", room.roomId)),
            content = Nip44.encrypt(plaintext.toString(), room.roomKey),
        )

        assertNull(decodeChatEvent(forged, room.roomId, room.roomKey, now = 200))
    }

    @Test
    fun `a line claiming a participant the credential does not belong to is refused`() {
        val plaintext = buildJsonObject {
            put("participant", "ff".repeat(32))
            put("credential", owner.credential.toJson())
            put("body", "not mine")
            put("sentAt", 100)
        }
        val mismatched = Events.sign(
            secretKey = owner.deviceSecretKey,
            kind = KIND_CHAT,
            createdAt = 100,
            tags = listOf(listOf("d", room.roomId)),
            content = Nip44.encrypt(plaintext.toString(), room.roomKey),
        )

        assertNull(decodeChatEvent(mismatched, room.roomId, room.roomKey, now = 200))
    }

    @Test
    fun `an expired credential is refused`() {
        assertNull(
            decodeChatEvent(message("hello"), room.roomId, room.roomKey, now = Fixtures.CREDENTIAL_EXPIRY + 1),
        )
    }

    @Test
    fun `rubbish never throws`() {
        val rubbish = Events.sign(
            secretKey = owner.deviceSecretKey,
            kind = KIND_CHAT,
            createdAt = 100,
            tags = listOf(listOf("d", room.roomId)),
            content = "not a NIP-44 payload at all",
        )
        assertNull(decodeChatEvent(rubbish, room.roomId, room.roomKey, now = 200))
    }
}
