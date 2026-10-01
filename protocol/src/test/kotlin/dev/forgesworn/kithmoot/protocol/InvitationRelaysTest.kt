package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.*
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.Assert.*

/** The room's own relays in the group invitation body, against fold-kit 0.4.0's own output. */
class InvitationRelaysTest {
    private val fixture = Json.parseToJsonElement(javaClass.getResource("/persistent-group-relays-web.json")!!.readText()).jsonObject
    private val invitation = decodeInvitationUrl(fixture.getValue("url").jsonPrimitive.content)!!.invitation
    private val key = fixture.getValue("inviterKey").jsonPrimitive.content.hexToBytes()
    private val secret = fixture.getValue("secret").jsonPrimitive.content.hexToBytes()
    private val nonce = fixture.getValue("nonce").jsonPrimitive.content.hexToBytes()
    private val relays = fixture.getValue("relays").jsonArray.map { it.jsonPrimitive.content }
    private val welcomeKey = Digests.hkdfSha256(invitation.bearer, null, "kithmoot/v3/group-invitation-key".toByteArray(), 32)

    private fun case(name: String) = fixture.getValue(name).jsonObject
    private fun sealed(body: String, now: Long = 1_800_000_000) =
        Events.sign(key, KIND_GROUP_INVITATION, now, listOf(listOf("d", deriveInvitationId(invitation))), Nip44.encrypt(body, welcomeKey))
    private fun body(extra: String) =
        """{"v":3,"room":"${deriveRoom(secret).roomId}","secret":"${base64UrlEncode(secret)}"$extra}"""

    @Test fun `web invitations with room relays decode, with and without an end`() {
        val open = decodePersistentInvitation(NostrEvent.fromJson(case("open").getValue("event")), invitation)!!
        assertArrayEquals(secret, open.secret)
        assertEquals(relays, open.relays)
        assertNull(open.endsAt)
        val conference = decodePersistentInvitation(NostrEvent.fromJson(case("conference").getValue("event")), invitation)!!
        assertEquals(relays, conference.relays)
        assertEquals(case("conference").getValue("ends").jsonPrimitive.long, conference.endsAt)
    }

    @Test fun `native encoding reproduces the web plaintext, content and id, keys in the order v room secret ends relays`() {
        for (name in listOf("open", "conference")) {
            val web = NostrEvent.fromJson(case(name).getValue("event"))
            val ends = case(name)["ends"]?.jsonPrimitive?.long
            val native = encodePersistentInvitation(RoomInvitationHost(invitation, key), secret, web.createdAt, nonce, ByteArray(32), ends, relays)
            assertEquals(case(name).getValue("plaintext").jsonPrimitive.content, Nip44.decrypt(native.content, welcomeKey))
            assertEquals(web.content, native.content)
            assertEquals(web.id, native.id)
        }
        val plain = case("conference").getValue("plaintext").jsonPrimitive.content
        assertTrue(plain.indexOf("\"ends\"") < plain.indexOf("\"relays\""))
    }

    @Test fun `with no relays the body is what it always was`() {
        val native = encodePersistentInvitation(RoomInvitationHost(invitation, key), secret, 1_800_000_000, nonce, ByteArray(32))
        assertEquals(body(""), Nip44.decrypt(native.content, welcomeKey))
        assertNull(decodePersistentInvitation(native, invitation)!!.relays)
    }

    @Test fun `a malformed relay list refuses the whole envelope and cannot be written`() {
        val bad = listOf(
            "[]",
            (0..8).joinToString(",", "[", "]") { "\"wss://relay$it.example.com/\"" },
            """["wss://relay.example.com/","wss://relay.example.com/"]""",
            """["wss://relay.example.com/",7]""",
            """["ws://relay.example.com/"]""",
            """["https://relay.example.com/"]""",
            """["wss://relay.example.com"]""",
            """["wss://Relay.Example.com/"]""",
            """["wss://relay.example.com:443/"]""",
            """["wss://relay.example.com/nostr/"]""",
            """["wss://user:pw@relay.example.com/"]""",
            """["wss://relay.example.com/#x"]""",
            "\"wss://relay.example.com/\"",
            "null",
        )
        for (relays in bad) {
            assertNull(relays, decodePersistentInvitation(sealed(body(",\"relays\":$relays")), invitation))
            val list = runCatching { Json.parseToJsonElement(relays).jsonArray.map { it.jsonPrimitive.content } }.getOrNull() ?: continue
            assertThrows(relays, IllegalArgumentException::class.java) {
                encodePersistentInvitation(RoomInvitationHost(invitation, key), secret, 1_800_000_000, relays = list)
            }
        }
        val eight = (0 until 8).map { "wss://relay$it.example.com/" }
        assertEquals(eight, decodePersistentInvitation(encodePersistentInvitation(RoomInvitationHost(invitation, key), secret, 1_800_000_000, relays = eight), invitation)!!.relays)
    }

    @Test fun `a key this reader does not know is ignored`() {
        val admission = decodePersistentInvitation(sealed(body(""","relays":["wss://relay.example.com/"],"later":{"x":1}""")), invitation)!!
        assertEquals(listOf("wss://relay.example.com/"), admission.relays)
    }
}
