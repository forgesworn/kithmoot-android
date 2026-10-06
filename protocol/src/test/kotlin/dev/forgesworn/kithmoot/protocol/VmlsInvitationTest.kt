package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Schnorr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The VMLS invitation (P3-03b-3 decision 17), and that it never passes for
 * today's invitation, nor today's for it: an app without VMLS rooms refuses
 * every VMLS link, request and answer.
 */
class VmlsInvitationTest {
    private val now = 1_800_000_000L
    private val base = "https://kithmoot.app/join"
    private val box = "c".repeat(64)
    private val link = createRoomInvitation()
    private val personaKey = ByteArray(32) { (it + 3).toByte() }
    private val persona = Schnorr.publicKeyHex(personaKey)
    private val device = Schnorr.publicKeyHex(ByteArray(32) { (it + 60).toByte() })
    private val rendezvous = Schnorr.publicKeyHex(ByteArray(32) { (it + 90).toByte() })
    private val requesterKey = ByteArray(32) { (it + 120).toByte() }
    private val credential = createPersonCredential(personaKey, device, expiresAt = now + 7 * 86_400, createdAt = now)

    @Test fun `a link carries its box and relays, and today's decoder asks for a newer version`() {
        val url = encodeVmlsInvitationUrl(base, link.invitation, box, listOf("wss://relay.example"))
        val payload = decodeVmlsInvitationUrl(url)!!
        assertEquals(link.invitation, payload.invitation)
        assertEquals(box, payload.box)
        assertEquals(listOf("wss://relay.example"), payload.relays)
        val refused = assertThrows(JoinUrlException::class.java) { decodeInvitationUrl(url) }
        assertEquals("This invitation needs a newer version of KithMoot.", refused.message)
        // Today's link is no VMLS link.
        assertNull(decodeVmlsInvitationUrl(encodeInvitationUrl(base, link.invitation, listOf("wss://relay.example"))))
        assertNull(decodeVmlsInvitationUrl("$base#"))
    }

    @Test fun `a malformed VMLS link fails closed`() {
        val good = encodeVmlsInvitationUrl(base, link.invitation, box, emptyList())
        val json = String(base64UrlDecode(good.substringAfter('#')))
        for (bad in listOf(json.replace("\"vmls\"", "\"other\""), json.replace(box, "zz"), json.replace("\"r\":[]", "\"r\":[1]"))) {
            assertThrows(JoinUrlException::class.java) { decodeVmlsInvitationUrl("$base#${base64UrlEncode(bad.toByteArray())}") }
        }
    }

    @Test fun `a request names the guest by its person credential, and today's decoder ignores it`() {
        val event = encodeVmlsJoinRequest(link.invitation, requesterKey, credential, rendezvous, now)
        val request = decodeVmlsJoinRequest(event, link.invitation, now)!!
        assertEquals(persona, request.persona)
        assertEquals(device, request.device)
        assertEquals(rendezvous, request.rendezvous)
        assertEquals(event.id, request.requestId)
        assertEquals(Schnorr.publicKeyHex(requesterKey), request.requester)
        assertNull(decodeInvitationRequest(event, link.invitation, now))
        // Today's request is no VMLS request.
        assertNull(decodeVmlsJoinRequest(encodeInvitationRequest(link.invitation, requesterKey, now), link.invitation, now))
    }

    @Test fun `a request is refused stale, for another link, or with a credential that does not hold`() {
        val event = encodeVmlsJoinRequest(link.invitation, requesterKey, credential, rendezvous, now)
        assertNull(decodeVmlsJoinRequest(event, link.invitation, now + INVITATION_MAX_AGE_SECONDS + 1))
        assertNull(decodeVmlsJoinRequest(event, createRoomInvitation().invitation, now))
        val lapsed = createPersonCredential(personaKey, device, expiresAt = now - 1, createdAt = now - 3600)
        assertNull(decodeVmlsJoinRequest(encodeVmlsJoinRequest(link.invitation, requesterKey, lapsed, rendezvous, now), link.invitation, now))
        val forged = credential.copy(tags = credential.tags.map { if (it[0] == "device") listOf("device", rendezvous) else it })
        assertNull(decodeVmlsJoinRequest(encodeVmlsJoinRequest(link.invitation, requesterKey, forged, rendezvous, now), link.invitation, now))
        val notAPoint = "f".repeat(64)
        assertNull(decodeVmlsJoinRequest(encodeVmlsJoinRequest(link.invitation, requesterKey, credential, notAPoint, now), link.invitation, now))
    }

    @Test fun `an answer comes from the link's key to one request, and today's decoder ignores it`() {
        val request = decodeVmlsJoinRequest(encodeVmlsJoinRequest(link.invitation, requesterKey, credential, rendezvous, now), link.invitation, now)!!
        val admitted = VmlsJoinAnswer.Admitted(request.requestId, "a".repeat(64), rendezvous, 42, box, "Kitchen")
        val event = encodeVmlsJoinAnswer(link.invitation, link.inviterSecretKey, request.requester, admitted, now)
        assertEquals(admitted, decodeVmlsJoinAnswer(event, link.invitation, requesterKey, setOf(request.requestId), now))
        assertNull(decodeVmlsJoinAnswer(event, link.invitation, requesterKey, setOf("e".repeat(64)), now))
        assertNull(decodeVmlsJoinAnswer(event, link.invitation, requesterKey, setOf(request.requestId), now + INVITATION_MAX_AGE_SECONDS + 1))
        assertNull(decodeRoomAdmissionGrant(event, link.invitation, requesterKey, request.requestId, now))
        val declined = encodeVmlsJoinAnswer(link.invitation, link.inviterSecretKey, request.requester, VmlsJoinAnswer.Declined(request.requestId), now)
        assertEquals(VmlsJoinAnswer.Declined(request.requestId), decodeVmlsJoinAnswer(declined, link.invitation, requesterKey, setOf(request.requestId), now))
        // Only the link's key answers.
        val other = createRoomInvitation()
        assertThrows(IllegalArgumentException::class.java) { encodeVmlsJoinAnswer(link.invitation, other.inviterSecretKey, request.requester, admitted, now) }
        // Today's grant is no VMLS answer.
        val grant = encodeInvitationGrant(link, request.requester, request.requestId, ByteArray(32) { 7 }, now)
        assertNull(decodeVmlsJoinAnswer(grant, link.invitation, requesterKey, setOf(request.requestId), now))
        assertTrue(decodeRoomAdmissionGrant(grant, link.invitation, requesterKey, request.requestId, now) != null)
    }
}
