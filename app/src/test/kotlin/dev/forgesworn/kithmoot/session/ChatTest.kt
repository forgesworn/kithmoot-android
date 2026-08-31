package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.KindredTier
import dev.forgesworn.kithmoot.protocol.RoomPolicy
import dev.forgesworn.kithmoot.protocol.deriveRoom
import dev.forgesworn.kithmoot.protocol.issueKindredProof
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.crypto.Schnorr
import kotlinx.serialization.json.Json
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
        assertEquals(1460, message("kind").kind)
    }

    @Test
    fun `oversized chat is refused at the encrypted boundary`() {
        assertNull(
            decodeChatEvent(
                message("x".repeat(MAX_CHAT_TEXT_LENGTH + 1)),
                room.roomId,
                room.roomKey,
                now = 200,
            ),
        )
    }

    @Test
    fun `a gated reader refuses unproved chat and accepts a valid proof`() {
        val hostKey = Fixtures.key(90)
        val policy = RoomPolicy(KindredTier.KITH, listOf(Schnorr.publicKeyHex(hostKey)))
        assertNull(decodeChatEvent(message("unproved"), room.roomId, room.roomKey, now = 200, policy = policy))

        val proof = issueKindredProof(
            issuerSecretKey = hostKey,
            participant = owner.participant,
            tier = KindredTier.KITH,
            roomId = room.roomId,
            expiresAt = 1_000,
        )
        val proved = encodeChatEvent(
            body = "proved",
            participant = owner.participant,
            credential = owner.credential,
            roomId = room.roomId,
            roomKey = room.roomKey,
            deviceSecretKey = owner.deviceSecretKey,
            sentAt = 100,
            proof = proof,
        )
        assertNotNull(decodeChatEvent(proved, room.roomId, room.roomKey, now = 200, policy = policy))
    }

    @Test
    fun `decodes the durable chat event emitted by TypeScript`() {
        val event = NostrEvent.fromJson(Json.parseToJsonElement(TYPE_SCRIPT_CHAT))
        val decoded = assertNotNull(decodeChatEvent(event, room.roomId, room.roomKey, now = 200))
        assertEquals("typescript-chat-vector", decoded.id)
        assertEquals("hello from TypeScript", decoded.body)
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
            put("id", "forged-device")
            put("participant", owner.participant)
            put("device", impostor.devicePubkey)
            put("credential", owner.credential.toJson())
            put("text", "I agree to everything")
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
            put("id", "forged-participant")
            put("participant", "ff".repeat(32))
            put("device", owner.devicePubkey)
            put("credential", owner.credential.toJson())
            put("text", "not mine")
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
    fun `history remains verifiable after its credential expires`() {
        // The credential is checked at the signed send time, not the reader's
        // clock. Otherwise every durable message would become unverifiable
        // when its sender's short-lived device credential lapses.
        assertNotNull(
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

    companion object {
        private const val TYPE_SCRIPT_CHAT =
            """{"kind":1460,"created_at":100,"tags":[["d","50d5fd09fdfaa905d4ce25c2239b67e5e3fe345566215dea73d0fb16adeed3a0"]],"content":"AkJmVMjqjqA+W1EhTDTIGMEd3O76CSNE3xDCk/6g/izwKdHOj0W6WPONMx7jrRmn8qI4qKcvw5r/lswLuHepu4v2hG4mq43tvT2TpqBHAskI3vVgJdMf8YuXB7Fsg61HCg8tBvc+ip7oelozzGuBqpmAMB5pHQ3Jea22Yzuwe4sL2DFS5mJr2KueqtamhS6zTDbyGMWipXC/GC9Jf5Efof4pP5eXipr/1zYG0tYygw2RSwFup3fu/wHCvIXcd6YWqZgrqXkU7VudJeYzaOE5eQDkC3FyQj/hSQ5lRjU3fCGRKIqfzO0hlmV0WoDXB44s81XfYZgEPvrotlpLS91dclG/iJ5yzja8mO1bbFuPb9OsrxmGnZr9fsvTT3LA1vaD0d4LztvXs4IiK7tRS3QTh29gUrYrs3agErI0XL+zaNMX6EkEh0f+qtmcQBFOwdsl2REl5KkmhhfxCPs2FY6x1odmekvda08NN0OTvKGFQ98NPp+bWxCFIeZyTvR1Ec3SntOcaNWZksXddEoRp4vv7pLiPS8IC5DpCBagDr7gPiVHqgccZ1JL4DOKactYT5KbyDitjrGeziG7OAhHrJR5+Ivbr89JpXRbTBM3ydzHlewtSLv7hbD7kjvuICFv7KhG8AMryhVoTyGkRAUePGUGP8izBIx1oXUowtDuOJRU55di9wQBb0hdmAtvek0C9XDpJ/3PkVRhYnSFK5/gOpZSmNUDlvyKoTT3dUP8ZvmB+uu3WYd6osg5YhgEK8IE4j4Xsgz6bFwk3q0HEr+wqDMwP/DwwCnLaS6iYIrQ0Az4DMxmrGvibvHBTCGoV2nEyaQeu32xUyI+zvejsctbeHz/MIzvjq56tGTstvEGwcr28E83xChjGFK2wbC4XH/bWAAQiTv5UgHcV1ts6uAGd9dVoWX5DNVoxn3YQYbvIIGooq0b8WP0+n8KK1RMTu2XdCZozn5S9ip+1xXEFYlVqPGg7Awi/ThbEnlNh/f/+TcA4Xw99HADzXLTgO1Zm/hdSgJeMBEOfBg1TZ2uz6+G0qy5IhyreNWvixHaXP6QU0xg8+pSJIQ4vy7KIoBx7Gl+ITfQT5sOQ1p3KusGNJ2GZyM8XCKZ0w==","pubkey":"460a7b966efffb36946f6dc3c17ff73ad789fc1c2df43c1258039ca2f9a1e3e6","id":"658a81969975f7407f9135d91cc3eb5372fd7e927da110ece4213206497ac885","sig":"786d2fb93094aafd50f2e26a486d64dc485905a21d316602f7c20f734482f64d7f567003f30f929ee553f114d9b055dbd002573e6bc8b070665a5eefca17d3b4"}"""
    }
}
