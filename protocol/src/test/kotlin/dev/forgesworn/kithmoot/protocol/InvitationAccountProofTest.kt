package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Verbatim fold-kit 0.11.0 corpus, including exact encrypted request bytes. */
class InvitationAccountProofTest {
    private val root by lazy { Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/invitation-account-vectors.json"))
        .bufferedReader().use { it.readText() }).jsonObject }
    private val input get() = root.getValue("input").jsonObject
    private val now get() = input.getValue("now").jsonPrimitive.long
    private val invitation get() = RoomInvitation(input.text("bearerHex").hexToBytes(), input.text("inviter"))
    private val requester get() = input.text("requesterSkHex").hexToBytes()
    private val proof get() = NostrEvent.fromJson(root.getValue("proof"))
    private val cases get() = root.getValue("cases").jsonArray.map { it.jsonObject }
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun case(name: String) = cases.single { it.text("name") == name }

    @Test fun `all published account-proof cases are exercised`() {
        assertEquals("kithmoot/v2/invitation-account-proof", root.text("protocolVersion"))
        assertTrue(root.getValue("syntheticOnly").jsonPrimitive.boolean)
        assertEquals(listOf("verified", "legacy", "forged-signature", "substituted-device"), cases.map { it.text("name") })
    }

    @Test fun `native account signer reproduces the published signed proof`() {
        val encoded = encodeInvitationAccountProof(invitation, input.text("device"), input.text("accountSkHex").hexToBytes(), now,
            input.text("proofAuxHex").hexToBytes())
        assertEquals(root.getValue("proof").jsonObject, encoded.toJson())
        assertTrue(verifyInvitationAccountProof(encoded, invitation, input.text("device"), input.text("participant"), now))
    }

    @Test fun `verified and legacy claims re-encode exact published encrypted events`() {
        for (name in listOf("verified", "legacy")) {
            val vector = case(name)
            val expected = NostrEvent.fromJson(vector.getValue("event"))
            val decoded = requireNotNull(decodeInvitationRequest(expected, invitation, now))
            val encoded = encodeInvitationRequest(invitation, requester, now,
                nonce = vector.text("nonceHex").hexToBytes(), auxRand = vector.text("auxHex").hexToBytes(),
                name = decoded.name, participant = decoded.participant, accountProof = if (name == "verified") proof else null)
            assertEquals("$name complete event", expected.toJson(), encoded.toJson())
            assertEquals("$name encrypted body", expected.content, encoded.content)
        }
    }

    @Test fun `invalid signed claims remain manual bearer requests and never verify an account`() {
        for (vector in cases) {
            val event = NostrEvent.fromJson(vector.getValue("event"))
            assertTrue(Events.verify(event))
            val decoded = requireNotNull(decodeInvitationRequest(event, invitation, now))
            assertEquals(input.text("device"), decoded.device)
            assertEquals(input.text("participant"), decoded.participant)
            val expected = vector.getValue("verifiedParticipant").jsonPrimitive.contentOrNull
            assertEquals(vector.text("name"), expected, decoded.verifiedParticipant)
            // Even malformed nested proofs preserve the original signed wire
            // bytes when re-encrypted. They are not passed to the safe writer.
            val key = Digests.hkdfSha256(invitation.bearer, null, INVITATION_REQUEST_KEY_INFO.toByteArray(), 32)
            try {
                val body = Nip44.decrypt(event.content, key)
                val reencoded = Events.sign(requester, event.kind, event.createdAt, event.tags,
                    Nip44.encrypt(body, key, vector.text("nonceHex").hexToBytes()), vector.text("auxHex").hexToBytes())
                assertEquals(event.toJson(), reencoded.toJson())
            } finally { key.fill(0) }
        }
    }

    @Test fun `proof cannot be moved to another device account invitation or request time`() {
        val otherDevice = Schnorr.publicKeyHex(ByteArray(32) { 71 })
        val otherAccount = Schnorr.publicKeyHex(ByteArray(32) { 72 })
        assertFalse(verifyInvitationAccountProof(proof, invitation, otherDevice, input.text("participant"), now))
        assertFalse(verifyInvitationAccountProof(proof, invitation, input.text("device"), otherAccount, now))
        assertFalse(verifyInvitationAccountProof(proof, RoomInvitation(ByteArray(32) { 73 }, invitation.inviter), input.text("device"), input.text("participant"), now))
        assertFalse(verifyInvitationAccountProof(proof, invitation, input.text("device"), input.text("participant"), now + 1))
        assertFalse(verifyInvitationAccountProof(proof.copy(tags = proof.tags + listOf(listOf("t", INVITATION_ACCOUNT_PROOF))), invitation,
            input.text("device"), input.text("participant"), now))
    }

    @Test fun `verification is fresh after a previously valid proof is tampered with`() {
        assertTrue(verifyInvitationAccountProof(proof, invitation, input.text("device"), input.text("participant"), now))
        val forged = proof.copy(sig = "00".repeat(64))
        assertFalse(verifyInvitationAccountProof(forged, invitation, input.text("device"), input.text("participant"), now))
        assertThrows(IllegalArgumentException::class.java) {
            encodeInvitationRequest(invitation, requester, now, participant = input.text("participant"), accountProof = forged)
        }
    }

    @Test fun `guest names are bounded and account claims without a proof stay unverified`() {
        val request = encodeInvitationRequest(invitation, requester, now, name = "  " + "x".repeat(100) + "  ", participant = input.text("participant"))
        val decoded = requireNotNull(decodeInvitationRequest(request, invitation, now))
        assertEquals("x".repeat(64), decoded.name)
        assertEquals(input.text("participant"), decoded.participant)
        assertNull(decoded.verifiedParticipant)
    }

    @Test fun `stale negative and overflowing timestamps do not admit a guest`() {
        val event = NostrEvent.fromJson(case("verified").getValue("event"))
        assertNotNull(decodeInvitationRequest(event, invitation, now + 90))
        assertNull(decodeInvitationRequest(event, invitation, now + 91))
        assertNull(decodeInvitationRequest(event, invitation, -1))
        assertNull(decodeInvitationRequest(event, invitation, Long.MAX_VALUE))
        val negative = Events.sign(requester, event.kind, Long.MIN_VALUE, event.tags, event.content)
        assertNull(decodeInvitationRequest(negative, invitation, now))
    }

    @Test fun `admission owns its delegated key after the temporary request key is wiped`() {
        val creator = createRoomInvitation()
        val requestKey = requester
        val request = encodeInvitationRequest(creator.invitation, requestKey, now)
        val grant = encodeInvitationGrant(creator, Schnorr.publicKeyHex(requestKey), request.id, ByteArray(32) { 82 }, now)
        val admission = requireNotNull(decodeRoomAdmissionGrant(grant, creator.invitation, requestKey, request.id, now))
        val delegate = requireNotNull(admission.delegate)
        val pubkey = Schnorr.publicKeyHex(delegate.inviterSecretKey)
        assertNotSame(requestKey, delegate.inviterSecretKey)
        requestKey.fill(0)
        assertEquals(pubkey, Schnorr.publicKeyHex(delegate.inviterSecretKey))
        assertTrue(Events.verify(encodeInvitationGrant(delegate, Schnorr.publicKeyHex(ByteArray(32) { 83 }), "ab".repeat(32), admission.secret, now)))
    }
}
