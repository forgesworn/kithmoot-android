package dev.forgesworn.kithmoot.vectors

import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.protocol.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.*
import org.junit.Test

/** Events produced by Fold Kit's v8 implementation from synthetic keys. */
class OwnerApprovedRoomAdmissionVectorsTest {
    private val fixture = Json.parseToJsonElement(
        requireNotNull(javaClass.getResourceAsStream("/owner-approved-room-admission.json"))
            .bufferedReader().use { it.readText() },
    ).jsonObject
    private fun text(name: String) = fixture.getValue(name).jsonPrimitive.content
    private fun secret(name: String) = text(name).hexToBytes()
    private val now = fixture.getValue("now").jsonPrimitive.long
    private val payload = requireNotNull(decodeInvitationUrl(text("link")))
    private val invitation = payload.invitation
    private val request = NostrEvent.fromJson(fixture.getValue("request").jsonObject)
    private val grant = NostrEvent.fromJson(fixture.getValue("grant").jsonObject)
    private val requester = secret("requester")

    @Test fun webLinkAndRequestKeepTheOriginalRoomAndScopedRendezvous() {
        assertEquals(text("roomId"), invitation.ownerApprovedRoom)
        assertEquals(text("invitationId"), deriveInvitationId(invitation))
        assertEquals("Synthetic room", payload.name)
        val ask = requireNotNull(decodeInvitationRequest(request, invitation, now))
        assertEquals(request.id, ask.requestId)
        assertEquals(Schnorr.publicKeyHex(requester), ask.device)
        assertEquals(invitation, decodeInvitationUrl(encodeInvitationUrl("https://example.invalid/j/", invitation, payload.relays))?.invitation)
        val differentRoom = RoomInvitation(invitation.bearer, invitation.inviter, ownerApprovedRoom = "ab".repeat(32))
        assertNotEquals(deriveInvitationId(invitation), deriveInvitationId(differentRoom))
        assertNull(decodeInvitationRequest(request, differentRoom, now))
        assertThrows(JoinUrlException::class.java) { decodeJoinUrl(text("link")) }
    }

    @Test fun webAndAndroidGrantsCarryTheFreshEpochOnlyForTheNamedRequest() {
        val native = encodeInvitationGrant(
            RoomInvitationHost(invitation, secret("owner")),
            Schnorr.publicKeyHex(requester), request.id, secret("original"), now,
            approvedEpoch = OwnerApprovedRoomEpoch(RoomEpoch(1, secret("fresh")), fixture.getValue("rotatedAt").jsonPrimitive.long),
        )
        for (event in listOf(grant, native)) {
            val admitted = requireNotNull(decodeOwnerApprovedRoomAdmissionGrant(event, invitation, requester, request.id, now))
            assertArrayEquals(secret("original"), admitted.secret)
            assertEquals(1, admitted.epoch.key.epoch)
            assertArrayEquals(secret("fresh"), admitted.epoch.key.secret)
            assertEquals(fixture.getValue("rotatedAt").jsonPrimitive.long, admitted.epoch.rotatedAt)
            assertNull(decodeRoomAdmissionGrant(event, invitation, requester, request.id, now))
            assertNull(decodeOwnerApprovedRoomAdmissionGrant(event, invitation, requester, "ab".repeat(32), now))
            assertNull(decodeOwnerApprovedRoomAdmissionGrant(event, invitation, secret("owner"), request.id, now))
            assertNull(decodeOwnerApprovedRoomAdmissionGrant(event, invitation, requester, request.id, now + 91))
        }
        val otherOwner = RoomInvitation(invitation.bearer, Schnorr.publicKeyHex(requester), ownerApprovedRoom = invitation.ownerApprovedRoom)
        assertNull(decodeOwnerApprovedRoomAdmissionGrant(grant, otherOwner, requester, request.id, now))
        assertThrows(IllegalArgumentException::class.java) {
            encodeInvitationGrant(RoomInvitationHost(invitation, secret("owner")), Schnorr.publicKeyHex(requester),
                request.id, secret("original"), now, approvedEpoch = OwnerApprovedRoomEpoch(RoomEpoch(1, secret("original")), now))
        }
    }
}
