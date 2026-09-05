package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.protocol.KindredTier
import dev.forgesworn.kithmoot.protocol.RoomPolicy
import dev.forgesworn.kithmoot.protocol.createRoomInvitation
import dev.forgesworn.kithmoot.protocol.decodeInvitationUrl
import dev.forgesworn.kithmoot.protocol.decodeJoinUrl
import dev.forgesworn.kithmoot.protocol.deriveRoom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingLinkTest {

    @Test fun `group pairing keeps v3 membership and the original bounded device credential`() {
        val invitation = createRoomInvitation(true).invitation
        val deviceKey = Fixtures.key(41)
        val credential = owner.enrol(Schnorr.publicKeyHex(deviceKey), room.roomId, Fixtures.CREDENTIAL_EXPIRY, 0)
        val url = encodeInvitationPairingLink(invitation = invitation, relays = relays,
            deviceSecretKey = deviceKey, credential = credential)
        val decoded = assertNotNull(decodeInvitationPairingLink(url))
        assertTrue(decoded.join.invitation.persistent)
        assertEquals(credential, decoded.credential)
        assertContentEquals(deviceKey, decoded.deviceSecretKey)
        assertNull(SecondaryIdentity.adopt(decoded.credential, decoded.deviceSecretKey, room.roomId, Fixtures.CREDENTIAL_EXPIRY))
    }

    private val room = deriveRoom(ByteArray(32) { 7 })
    private val secret = ByteArray(32) { 7 }
    private val relays = listOf("wss://relay.damus.io", "wss://nos.lol")
    private val owner = Fixtures.primary(room, participantSeed = 1, deviceSeed = 2)

    private fun link(deviceKey: ByteArray = Fixtures.key(40), policy: RoomPolicy? = null): String =
        encodePairingLink(
            secret = secret,
            relays = relays,
            policy = policy,
            deviceSecretKey = deviceKey,
            credential = owner.enrol(
                devicePubkey = Schnorr.publicKeyHex(deviceKey),
                roomId = room.roomId,
                expiresAt = Fixtures.CREDENTIAL_EXPIRY,
                createdAt = 0,
            ),
        )

    @Test
    fun `a pairing link round trips`() {
        val decoded = assertNotNull(decodePairingLink(link()))

        assertContentEquals(secret, decoded.join.secret)
        assertEquals(relays, decoded.join.relays)
        assertContentEquals(Fixtures.key(40), decoded.deviceSecretKey)
    }

    @Test
    fun `a pairing link is also a perfectly good join URL`() {
        // Deliberate: a client that knows nothing about pairing reads this as a
        // join link and joins as a stranger, rather than failing shut.
        val payload = decodeJoinUrl(link())

        assertContentEquals(secret, payload.secret)
        assertEquals(relays, payload.relays)
    }

    @Test
    fun `the secret rides in the fragment, never the path`() {
        val url = link()

        assertEquals("https://kithmoot.forgesworn.dev/j/", KITHMOOT_JOIN_BASE)
        assertTrue(url.startsWith("$KITHMOOT_JOIN_BASE#"))
        assertEquals(1, url.count { it == '#' })
    }

    @Test
    fun `an access policy survives the round trip`() {
        val policy = RoomPolicy(KindredTier.KITH, admitted = listOf("00".repeat(32)))

        val decoded = assertNotNull(decodePairingLink(link(policy = policy)))

        assertEquals(policy, decoded.join.policy)
    }

    @Test
    fun `the credential in the link actually adopts`() {
        val decoded = assertNotNull(decodePairingLink(link()))

        val identity = SecondaryIdentity.adopt(
            credential = decoded.credential,
            deviceSecretKey = decoded.deviceSecretKey,
            roomId = room.roomId,
            now = 0,
        )

        assertNotNull(identity)
        // The second device speaks as the same person, from its own key.
        assertEquals(owner.participant, identity.participant)
        assertTrue(identity.devicePubkey != owner.devicePubkey)
    }

    @Test
    fun `a credential minted for another room does not adopt`() {
        val elsewhere = deriveRoom(ByteArray(32) { 9 })
        val decoded = assertNotNull(decodePairingLink(link()))

        assertNull(
            SecondaryIdentity.adopt(
                credential = decoded.credential,
                deviceSecretKey = decoded.deviceSecretKey,
                roomId = elsewhere.roomId,
                now = 0,
            ),
        )
    }

    @Test
    fun `an ordinary join URL carries no pairing material`() {
        val plain = dev.forgesworn.kithmoot.protocol.encodeJoinUrl(KITHMOOT_JOIN_BASE, secret, relays)

        assertNull(decodePairingLink(plain))
    }

    @Test
    fun `rubbish is not a pairing link`() {
        assertNull(decodePairingLink("https://kithmoot.com/j#not-valid-base64url!!!"))
        assertNull(decodePairingLink("https://kithmoot.com/j"))
        assertNull(decodePairingLink(""))
    }

    @Test
    fun `a v2 pairing link keeps the traffic secret out of the URL`() {
        val invitation = createRoomInvitation().invitation
        val deviceKey = Fixtures.key(41)
        val url = encodeInvitationPairingLink(
            invitation = invitation,
            relays = relays,
            deviceSecretKey = deviceKey,
            credential = owner.enrol(
                devicePubkey = Schnorr.publicKeyHex(deviceKey),
                roomId = room.roomId,
                expiresAt = Fixtures.CREDENTIAL_EXPIRY,
                createdAt = 0,
            ),
        )

        val decoded = assertNotNull(decodeInvitationPairingLink(url))
        assertEquals(invitation, decoded.join.invitation)
        assertContentEquals(deviceKey, decoded.deviceSecretKey)
        assertEquals(invitation, assertNotNull(decodeInvitationUrl(url)).invitation)
        val fragmentJson = String(java.util.Base64.getUrlDecoder().decode(url.substringAfter('#')))
        assertTrue("\"s\"" !in fragmentJson)
    }
}
