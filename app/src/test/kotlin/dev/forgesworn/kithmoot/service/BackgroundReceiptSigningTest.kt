package dev.forgesworn.kithmoot.service

import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.account.ParticipantSigner
import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.createRoomInvitation
import dev.forgesworn.kithmoot.protocol.deriveRoom
import dev.forgesworn.kithmoot.protocol.encodeInvitationUrl
import dev.forgesworn.kithmoot.relay.RoomRoute
import dev.forgesworn.kithmoot.session.PrimaryIdentity
import dev.forgesworn.kithmoot.session.RoomIdentity
import dev.forgesworn.kithmoot.storage.SAVED_CREDENTIAL_TTL
import dev.forgesworn.kithmoot.storage.SavedRoom
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A signer app: its key is never stored with the room, so the room is joined as an account. */
private class AccountSigner(key: ByteArray) : ParticipantSigner {
    private val local = LocalSigner(key)
    override val pubkey = local.pubkey
    override val method = "nip55"
    var signatures = 0
    override suspend fun sign(kind: Int, createdAt: Long, tags: List<List<String>>, content: String): NostrEvent { signatures++; return local.sign(kind, createdAt, tags, content) }
    override suspend fun nip44Encrypt(peer: String, plaintext: String) = local.nip44Encrypt(peer, plaintext)
    override suspend fun nip44Decrypt(peer: String, payload: String) = local.nip44Decrypt(peer, payload)
}

/** What the closed app signs a delivery receipt with, room by room: never what only a signer, or another account, could. */
class BackgroundReceiptSigningTest {
    private val now = 1_800_000_000L
    private val relays = listOf("wss://relay.example")
    private val base = "https://kithmoot.example/j/"

    private fun saved(secret: ByteArray, identity: RoomIdentity): SavedRoom {
        val host = createRoomInvitation(false)
        return SavedRoom.create(secret, identity, encodeInvitationUrl(base, host.invitation, relays), relays, "Workshop", now, host, null)
    }

    @Test fun `a room joined as an account is signed for only while that account is signed in`() = runTest {
        val secret = Entropy.bytes(32)
        val signer = AccountSigner(Entropy.bytes(32))
        val room = saved(secret, PrimaryIdentity.createWith(signer, deriveRoom(secret).roomId, now + SAVED_CREDENTIAL_TTL, now))
        assertTrue(room.viaAccount)
        val signed = assertNotNull(receiptSigning(room, { signer.pubkey }, now + 3_600))
        assertEquals(signer.pubkey, signed.participant)
        assertEquals(room.devicePubkey, dev.forgesworn.kithmoot.crypto.Schnorr.publicKeyHex(signed.deviceSecretKey))
        assertEquals(1, signer.signatures, "the kept credential is used, and the signer is never asked for another")
        // Signed out, an account that cannot be read, or somebody else: the kept credential would still verify, and is not used.
        assertNull(receiptSigning(room, { null }, now + 3_600))
        assertNull(receiptSigning(room, { LocalSigner(Entropy.bytes(32)).pubkey }, now + 3_600))
        assertEquals(1, signer.signatures)
    }

    @Test fun `an account room's kept credential lapses with the day, and only the signer could make another`() = runTest {
        val secret = Entropy.bytes(32)
        val signer = AccountSigner(Entropy.bytes(32))
        val room = saved(secret, PrimaryIdentity.createWith(signer, deriveRoom(secret).roomId, now + SAVED_CREDENTIAL_TTL, now))
        assertNotNull(receiptSigning(room, { signer.pubkey }, now + SAVED_CREDENTIAL_TTL - 1))
        assertNull(receiptSigning(room, { signer.pubkey }, now + SAVED_CREDENTIAL_TTL))
    }

    @Test fun `a key held here signs without asking who is signed in`() {
        val secret = Entropy.bytes(32)
        val room = saved(secret, PrimaryIdentity.create(deriveRoom(secret).roomId, now + 3_600, now))
        assertTrue(!room.viaAccount)
        assertNotNull(receiptSigning(room, { error("never asked for a room whose key is on this phone") }, now + 86_400 * 30))
    }

    @Test fun `no saved room, a room off the Internet and nothing to sign with all give nothing`() = runTest {
        assertNull(receiptSigning(null, { null }, now))
        val secret = Entropy.bytes(32)
        val room = saved(secret, PrimaryIdentity.create(deriveRoom(secret).roomId, now + 3_600, now))
        assertNotNull(receiptSigning(room, { null }, now))
        assertNull(receiptSigning(room.withRoute(RoomRoute.NEARBY), { null }, now))
    }
}
