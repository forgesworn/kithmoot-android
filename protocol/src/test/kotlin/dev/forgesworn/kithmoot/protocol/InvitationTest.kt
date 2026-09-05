package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Schnorr
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InvitationTest {
    private val now = 1_800_000_000L
    private val inviterKey = ByteArray(32) { 11 }
    private val invitation = RoomInvitation(ByteArray(32) { 22 }, Schnorr.publicKeyHex(inviterKey))
    private val host = RoomInvitationHost(invitation, inviterKey)
    private val requesterKey = ByteArray(32) { 33 }

    @Test
    fun `v2 URL round trips an invitation without a room traffic secret`() {
        val url = encodeInvitationUrl(
            "https://kithmoot.com/j",
            invitation,
            listOf("wss://one.example", "wss://two.example"),
        )
        val decoded = decodeInvitationUrl(url)
        assertNotNull(decoded)

        assertEquals(invitation, decoded!!.invitation)
        assertEquals(listOf("wss://one.example", "wss://two.example"), decoded.relays)
        val json = String(base64UrlDecode(url.substringAfter('#')), Charsets.UTF_8)
        assertTrue("\"v\":2" in json)
        assertTrue("v2 URL must not contain the room traffic-secret field", "\"s\"" !in json)
    }

    @Test
    fun `legacy links are not mistaken for invitations`() {
        val legacy = encodeJoinUrl("https://kithmoot.com/j", ByteArray(32) { 1 }, listOf("wss://one.example"))
        assertNull(decodeInvitationUrl(legacy))
    }

    @Test
    fun `malformed v2 invitation fails rather than becoming an open room`() {
        val malformed = "https://kithmoot.com/j#" + base64UrlEncode("""{"v":2,"j":"AA","h":"bad","r":[]}""".toByteArray())
        assertThrows(JoinUrlException::class.java) { decodeInvitationUrl(malformed) }
    }

    @Test
    fun `request proves possession of the bearer and is bound to its sender`() {
        val request = encodeInvitationRequest(
            invitation,
            requesterKey,
            now,
            nonce = ByteArray(32) { 44 },
            auxRand = ByteArray(32) { 55 },
        )
        val decoded = decodeInvitationRequest(request, invitation, now)
        assertNotNull(decoded)

        assertEquals(Schnorr.publicKeyHex(requesterKey), decoded!!.device)
        assertEquals(request.id, decoded.requestId)
        val wrongBearer = RoomInvitation(ByteArray(32) { 99.toByte() }, invitation.inviter)
        assertNull(decodeInvitationRequest(request, wrongBearer, now))
        assertNull(decodeInvitationRequest(request, invitation, now + 91))
    }

    @Test
    fun `grant comes only from the pinned inviter and only for this request`() {
        val request = encodeInvitationRequest(
            invitation,
            requesterKey,
            now,
            nonce = ByteArray(32) { 1 },
            auxRand = ByteArray(32) { 2 },
        )
        val roomSecret = ByteArray(32) { 77 }
        val grant = encodeInvitationGrant(
            host,
            Schnorr.publicKeyHex(requesterKey),
            request.id,
            roomSecret,
            now,
            nonce = ByteArray(32) { 3 },
            auxRand = ByteArray(32) { 4 },
        )

        assertArrayEquals(
            roomSecret,
            decodeInvitationGrant(grant, invitation, requesterKey, request.id, now),
        )
        assertNull(decodeInvitationGrant(grant, invitation, requesterKey, "f".repeat(64), now))
        assertNull(decodeInvitationGrant(grant, invitation, ByteArray(32) { 88.toByte() }, request.id, now))
    }

    @Test
    fun `fresh invitations are unlinkable`() {
        val first = createRoomInvitation()
        val second = createRoomInvitation()
        assertNotEquals(deriveInvitationId(first.invitation), deriveInvitationId(second.invitation))
    }

    @Test
    fun `an admitted member can grant the same invitation after the creator leaves`() {
        val firstRequest = encodeInvitationRequest(invitation, requesterKey, now)
        val roomSecret = ByteArray(32) { 78 }
        val firstGrant = encodeInvitationGrant(
            host,
            Schnorr.publicKeyHex(requesterKey),
            firstRequest.id,
            roomSecret,
            now,
        )
        val first = decodeRoomAdmissionGrant(firstGrant, invitation, requesterKey, firstRequest.id, now)
        assertNotNull(first)
        assertEquals(
            Schnorr.publicKeyHex(requesterKey),
            verifyInvitationDelegation(invitation, first!!.delegate!!.delegation, now),
        )

        val nextKey = ByteArray(32) { 44 }
        val nextRequest = encodeInvitationRequest(invitation, nextKey, now)
        val delegatedGrant = encodeInvitationGrant(
            first.delegate!!,
            Schnorr.publicKeyHex(nextKey),
            nextRequest.id,
            roomSecret,
            now,
        )
        assertArrayEquals(
            roomSecret,
            decodeInvitationGrant(delegatedGrant, invitation, nextKey, nextRequest.id, now),
        )
    }

    @Test
    fun `tampering with a delegation or its invitation binding is rejected`() {
        val request = encodeInvitationRequest(invitation, requesterKey, now)
        val grant = encodeInvitationGrant(
            host,
            Schnorr.publicKeyHex(requesterKey),
            request.id,
            ByteArray(32) { 79 },
            now,
        )
        val admitted = decodeRoomAdmissionGrant(grant, invitation, requesterKey, request.id, now)!!
        val tampered = admitted.delegate!!.delegation.map { it.copy(delegate = "a".repeat(64)) }
        assertNull(verifyInvitationDelegation(invitation, tampered, now))
        assertThrows(IllegalArgumentException::class.java) {
            RoomInvitationHost(invitation, requesterKey, tampered)
        }
        val nextKey = ByteArray(32) { 45 }
        assertThrows(IllegalArgumentException::class.java) {
            encodeInvitationGrant(
                admitted.delegate!!,
                Schnorr.publicKeyHex(nextKey),
                "a".repeat(64),
                ByteArray(32) { 99.toByte() },
                now,
            )
        }
    }

    @Test
    fun `only the creator can issue a permanent retirement tombstone`() {
        val event = encodeInvitationRetirement(invitation, inviterKey, now)
        assertTrue(decodeInvitationRetirement(event, invitation))

        val another = RoomInvitation(ByteArray(32) { 23 }, invitation.inviter)
        assertFalse(decodeInvitationRetirement(event, another))
        assertThrows(IllegalArgumentException::class.java) {
            encodeInvitationRetirement(invitation, requesterKey, now)
        }
    }

    @Test
    fun `decodes request and grant emitted by the TypeScript client`() {
        val request = NostrEvent.fromJson(Json.parseToJsonElement(TYPE_SCRIPT_REQUEST))
        val decodedRequest = decodeInvitationRequest(request, invitation, now)
        assertNotNull(decodedRequest)
        assertEquals(Schnorr.publicKeyHex(requesterKey), decodedRequest!!.device)

        val grant = NostrEvent.fromJson(Json.parseToJsonElement(TYPE_SCRIPT_GRANT))
        assertArrayEquals(
            ByteArray(32) { 77 },
            decodeInvitationGrant(grant, invitation, requesterKey, request.id, now),
        )
    }

    companion object {
        /** Static events generated by src/invitation.ts from this test's fixed keys. */
        private const val TYPE_SCRIPT_REQUEST =
            """{"kind":20466,"created_at":1800000000,"tags":[["d","e4ab3deb8236620cc308b7abc3fef757621408fc89cc743d3b635c3509ee7c99"],["p","552c630b64b54bf50210c9e253d38bd4949c72e22873500f6285c2bede312a84"]],"content":"Ar+R8BKJplpnBn1sL551Et63xzYQX2Nmv91MwuiiWIkASK4+67GkJJ8gTlczaXwLUR4U0DxRpiX6snWkesaS6JSbadhXlWQ6cynXgg6C+zFwHkjue9nKTX+2ejizY4Rgko5eUoZ9VjtHvTH3OusQYMmOq5OTXTRDvaMJMWVorItZgLkpmKDPd58ansDfi7mQL1qCNXWgx2sRtq5ynJ7PLrJ0+A==","pubkey":"8d7500dd4c12685d1f568b4c2b5048e8534b873319f3a8daa612b469132ec7f7","id":"63aedc7bb1456ef73ca1f9afccb7c00195ecfd05aa7fab21ef7792b3bb3c45da","sig":"3215c7fb8f7ffbd64d57bbef6f0739be2781f3be3db17710fcc0d93a8eb11fb59da6ca7711d600bb889b15bcafc2b4725b54fb59c24fcae27176a233c54503f6"}"""

        private const val TYPE_SCRIPT_GRANT =
            """{"kind":20467,"created_at":1800000000,"tags":[["d","e4ab3deb8236620cc308b7abc3fef757621408fc89cc743d3b635c3509ee7c99"],["p","8d7500dd4c12685d1f568b4c2b5048e8534b873319f3a8daa612b469132ec7f7"]],"content":"AiJsSo8VZg5W5Nh4+N5NLr7XL4BzT71GpFxKdXhL7VHFnbQn7oaV2YlPy+z/oqFwZEp0OXYpeDlvj4gCi01C6KUwsjmrSYrJK/VSZWx1JEPbH5plhzoKOtnVIXW8TN/mFzTuMXumUTkChQM+zbSH30DyYvY4Tv757ma7JiAjY7aIzrFJwzIHeSIXopiC2kp1/IudkPGtxdo/rybnofS6vLahGyOVjz76Di0Tksup+wmgaueiuFNYlaqFziOKsLZPkN4QD2Rg2TV5Ae5Z/yeFdeHbL1WStP0al+WSU+4v9ARwqezKYlGuoNOFzyf/R039n0VHAoK2gEgLwdhV0B3Dyx3DoG975xH809utpccrE5KYYSU35ddPtIFj0tWsE0sGjc3yNPUe1fYUDtWvQJcSoRYAVQqB3mbT3QgG3PC033St1NUXLIe/RS9JLm+TOY10vRMGbw4xpFoa5OvH0aYkBP7qB1AwnPiYMkXGTe5O7lHSJz5fEIJUOFdLmoBsnyXanOr51mA7sKE5XKnZ6LQ6zX7otxcjRcDFFeXJvLApITuLlu84VzEDBL5swdyn/D5dVt8gVaoLK6tKSL306xvt5GpMBSZkTvnj/40+l9LWal1XJl8suK6zWaJNVbQDuOMOqUzVRt3TKYuxN4oedipJ+Ctbp+yfVXF0i5E0yoN8t7l28Tw8+JG/R2OWzkxN2khBMoPFyg0REvJxfnmEQLH1GgAJV9JEj5caWN+Nj1MZ4z3yTee0Fh1p4S0vQ6i1o+rVHJ+I1NI3P7be9VR5ng1ldgM8z4pkccoXSbTomvtu7mxAo2ElfelQdRTxu90XYgeRB7PV0jefsZc/tUV6KkxD6S18SB3Bdd/0xOsUJBqn6YG/krbUVu92zAh3RLx69gIL4z8M0AHrjBRhBKPtjTDuCGZw/Xbusgub0e632NrVKYJD7lc=","pubkey":"552c630b64b54bf50210c9e253d38bd4949c72e22873500f6285c2bede312a84","id":"775351502c8f40c21232c3417ae98244348331659249ca10a6fb745ecfb272ae","sig":"1f5665cba5261308b085da517fb576c15362d5305c110bd75de88fda224bf1d51e6a663047a410cac045f2441634252b55624fbd82b32c0444372d57778ed09e"}"""
    }
}
