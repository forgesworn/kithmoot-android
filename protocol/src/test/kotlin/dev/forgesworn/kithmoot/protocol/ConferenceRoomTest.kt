package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.Schnorr
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ConferenceRoomTest {
    private val now = 1_790_000_000L
    private val ends = now + 3 * 24 * 60 * 60
    private val host = createRoomInvitation(persistent = true)
    private val secret = ByteArray(32) { 9 }
    private val d = listOf("d", "room")

    @Test fun `no end leaves tags untouched`() {
        val tags = listOf(d, listOf("expiration", "5"))
        assertSame(tags, withRoomExpiration(tags, null))
    }

    @Test fun `an end is added when there is no expiration`() {
        assertEquals(listOf(d, listOf("expiration", "$ends")), withRoomExpiration(listOf(d), ends))
    }

    @Test fun `an earlier expiration is kept and a later one is lowered`() {
        val earlier = listOf(listOf("p", "x"), listOf("expiration", "${now + 60}"))
        assertEquals(earlier, withRoomExpiration(earlier, ends))
        val later = listOf(listOf("expiration", "${ends + 1}"), d)
        assertEquals(listOf(listOf("expiration", "$ends"), d), withRoomExpiration(later, ends))
        assertEquals(listOf(listOf("expiration", "$ends")), withRoomExpiration(listOf(listOf("expiration", "$ends")), ends))
    }

    @Test fun `unreadable or repeated expirations collapse to one no later than the end`() {
        assertEquals(listOf(d, listOf("expiration", "$ends")), withRoomExpiration(listOf(d, listOf("expiration", "soon")), ends))
        val two = listOf(listOf("expiration", "${ends + 9}"), d, listOf("expiration", "${now + 5}"))
        assertEquals(listOf(listOf("expiration", "${now + 5}"), d), withRoomExpiration(two, ends))
    }

    @Test fun `a new end must be in the future and within thirty days`() {
        requireConferenceEnds(now + 1, now)
        requireConferenceEnds(now + MAX_CONFERENCE_SECONDS, now)
        assertThrows(IllegalArgumentException::class.java) { requireConferenceEnds(now, now) }
        assertThrows(IllegalArgumentException::class.java) { requireConferenceEnds(now + MAX_CONFERENCE_SECONDS + 1, now) }
        assertFalse(conferenceEnded(null, now))
        assertFalse(conferenceEnded(ends, ends - 1))
        assertTrue(conferenceEnded(ends, ends))
    }

    @Test fun `an invitation without an end is unchanged and decodes without one`() {
        val event = encodePersistentInvitation(host, secret, now)
        assertEquals(listOf(listOf("d", deriveInvitationId(host.invitation))), event.tags)
        val admission = decodePersistentInvitation(event, host.invitation)!!
        assertNull(admission.endsAt)
        assertFalse(body(event).containsKey("ends"))
    }

    @Test fun `an invitation with an end carries it in the body and as its expiration`() {
        val event = encodePersistentInvitation(host, secret, now, ends = ends)
        assertEquals(listOf(listOf("d", deriveInvitationId(host.invitation)), listOf("expiration", "$ends")), event.tags)
        assertEquals(listOf("v", "room", "secret", "ends"), body(event).keys.toList())
        assertEquals(ends, body(event).getValue("ends").jsonPrimitive.long)
        val admission = decodePersistentInvitation(event, host.invitation)!!
        assertArrayEquals(secret, admission.secret)
        assertEquals(ends, admission.endsAt)
    }

    @Test fun `an ended room cannot sign its invitation`() {
        assertThrows(IllegalArgumentException::class.java) { encodePersistentInvitation(host, secret, ends, ends = ends) }
    }

    @Test fun `a body end without a tag is accepted`() {
        val event = signed(bodyWith(JsonPrimitive(ends)), listOf(dTag()))
        assertEquals(ends, decodePersistentInvitation(event, host.invitation)!!.endsAt)
    }

    @Test fun `a tag that does not match the body refuses the invitation`() {
        assertNull(decodePersistentInvitation(signed(bodyWith(JsonPrimitive(ends)), listOf(dTag(), listOf("expiration", "${ends - 1}"))), host.invitation))
        assertNull(decodePersistentInvitation(signed(bodyWith(null), listOf(dTag(), listOf("expiration", "$ends"))), host.invitation))
        assertNull(decodePersistentInvitation(signed(bodyWith(JsonPrimitive(ends)),
            listOf(dTag(), listOf("expiration", "$ends"), listOf("expiration", "$ends"))), host.invitation))
    }

    @Test fun `an end that is not a positive safe integer refuses the invitation`() {
        for (bad in listOf(JsonPrimitive("$ends"), JsonPrimitive(0), JsonPrimitive(-5), JsonPrimitive(1.5), JsonPrimitive(1L shl 53), JsonNull)) {
            assertNull(bad.toString(), decodePersistentInvitation(signed(bodyWith(bad), listOf(dTag())), host.invitation))
        }
    }

    @Test fun `a conference retirement lapses with the room`() {
        val retirement = encodeInvitationRetirement(host.invitation, host.inviterSecretKey, now, ends = ends)
        assertEquals(listOf("expiration", "$ends"), retirement.tags.last())
        assertTrue(decodeInvitationRetirement(retirement, host.invitation))
        assertEquals(1, encodeInvitationRetirement(host.invitation, host.inviterSecretKey, now).tags.size)
    }

    @Test fun `room traffic carries the end`() {
        val device = ByteArray(32) { 3 }
        val roomKey = ByteArray(32) { 4 }
        val wrap = wrapSignal(SignalBody(type = "offer", sdp = "v=0", roomId = "ab".repeat(32)), device, Schnorr.publicKeyHex(ByteArray(32) { 5 }),
            createdAt = ends - 10, roomEnds = ends).wrap
        assertEquals("$ends", wrap.tagValue("expiration"))
        assertEquals(1, wrap.tags.count { it[0] == "expiration" })
        val bell = encodeCallBellEvent(EncodeCallBellOptions("ab".repeat(32), roomKey, device, CallBellState.START,
            CallMembership("cd".repeat(16), now), now, roomEnds = now + 60))
        assertEquals("${now + 60}", bell.tagValue("expiration"))
        assertNotNull(decodeCallBellEvent(bell, "ab".repeat(32), roomKey, now))
    }

    private fun dTag() = listOf("d", deriveInvitationId(host.invitation))

    private fun bodyWith(ends: JsonElement?) = buildJsonObject {
        put("v", 3)
        put("room", deriveRoom(secret).roomId)
        put("secret", base64UrlEncode(secret))
        if (ends != null) put("ends", ends)
    }

    private fun key() = Digests.hkdfSha256(host.invitation.bearer, null, "kithmoot/v3/group-invitation-key".toByteArray(), 32)

    private fun signed(body: JsonObject, tags: List<List<String>>) =
        Events.sign(host.inviterSecretKey, KIND_GROUP_INVITATION, now, tags, Nip44.encrypt(body.toString(), key()))

    private fun body(event: NostrEvent) = Json.parseToJsonElement(Nip44.decrypt(event.content, key())).jsonObject
}
