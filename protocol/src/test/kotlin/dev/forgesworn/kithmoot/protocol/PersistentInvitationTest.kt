package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.*
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.Assert.*

class PersistentInvitationTest {
    private val fixture = Json.parseToJsonElement(javaClass.getResource("/persistent-group-web.json")!!.readText()).jsonObject
    private val url = fixture.getValue("url").jsonPrimitive.content
    private val invitation = decodeInvitationUrl(url)!!.invitation
    private val event = NostrEvent.fromJson(fixture.getValue("event"))
    private val key = fixture.getValue("inviterKey").jsonPrimitive.content.hexToBytes()
    private val secret = fixture.getValue("secret").jsonPrimitive.content.hexToBytes()

    @Test fun `web v3 envelope decrypts and grants membership without signing authority`() {
        assertTrue(invitation.persistent)
        val admission = decodePersistentInvitation(event, invitation)!!
        assertArrayEquals(secret, admission.secret)
        assertNull(admission.delegate)
        assertEquals(fixture.getValue("room").jsonPrimitive.content, deriveRoom(admission.secret).roomId)
        val encoded = encodeInvitationUrl("https://kithmoot.forgesworn.dev/j/", invitation, listOf("ws://10.0.2.2:18777"))
        assertEquals(url, encoded)
        assertFalse(String(base64UrlDecode(encoded.substringAfter('#'))).contains("secret"))
    }

    @Test fun `native encoding reproduces the web event content and id with the fixture nonce`() {
        val native = encodePersistentInvitation(RoomInvitationHost(invitation, key), secret, event.createdAt,
            fixture.getValue("nonce").jsonPrimitive.content.hexToBytes(), ByteArray(32))
        assertEquals(event.content, native.content)
        assertEquals(event.id, native.id)
        assertTrue(Events.verify(native))
    }

    @Test fun `bad signatures bearer signer duplicate tags and wrong room are refused`() {
        assertNull(decodePersistentInvitation(event.copy(sig = "00".repeat(64)), invitation))
        assertNull(decodePersistentInvitation(event, RoomInvitation(ByteArray(32), invitation.inviter, true)))
        assertNull(decodePersistentInvitation(event, RoomInvitation(invitation.bearer, Schnorr.publicKeyHex(ByteArray(32) { 7 }), true)))
        val duplicate = Events.sign(key, event.kind, event.createdAt, event.tags + event.tags, event.content)
        assertNull(decodePersistentInvitation(duplicate, invitation))
        val wrongBody = """{"v":3,"room":"${"00".repeat(32)}","secret":"${base64UrlEncode(secret)}"}"""
        val welcomeKey = Digests.hkdfSha256(invitation.bearer, null, "kithmoot/v3/group-invitation-key".toByteArray(), 32)
        val wrong = Events.sign(key, event.kind, event.createdAt, event.tags, Nip44.encrypt(wrongBody, welcomeKey))
        assertNull(decodePersistentInvitation(wrong, invitation))
        assertNull(decodePersistentInvitation(event, RoomInvitation(invitation.bearer, invitation.inviter)))
    }

    @Test fun `web tombstone is compatible and unknown link versions cannot fall back to a room secret`() {
        assertTrue(decodeInvitationRetirement(NostrEvent.fromJson(fixture.getValue("retirement")), invitation))
        val payload = """{"v":4,"s":"${base64UrlEncode(secret)}","r":[]}"""
        assertThrows(JoinUrlException::class.java) { decodeInvitationUrl("https://example/#${base64UrlEncode(payload.toByteArray())}") }
    }
}
