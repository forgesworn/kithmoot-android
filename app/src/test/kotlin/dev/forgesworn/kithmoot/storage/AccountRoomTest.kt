package dev.forgesworn.kithmoot.storage

import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.account.ParticipantSigner
import dev.forgesworn.kithmoot.account.shortNpub
import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.createRoomInvitation
import dev.forgesworn.kithmoot.protocol.deriveRoom
import dev.forgesworn.kithmoot.protocol.encodeInvitationUrl
import dev.forgesworn.kithmoot.session.PrimaryIdentity
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** A signer whose key is somewhere else: signs like a local one, but is not one. */
private class ElsewhereSigner(key: ByteArray) : ParticipantSigner {
    private val local = LocalSigner(key)
    override val pubkey = local.pubkey
    override val method = "nip55"
    var signatures = 0
    override suspend fun sign(kind: Int, createdAt: Long, tags: List<List<String>>, content: String): NostrEvent { signatures++; return local.sign(kind, createdAt, tags, content) }
    override suspend fun nip44Encrypt(peer: String, plaintext: String) = local.nip44Encrypt(peer, plaintext)
    override suspend fun nip44Decrypt(peer: String, payload: String) = local.nip44Decrypt(peer, payload)
}

class AccountRoomTest {
    private val now = 1_800_000_000L
    private val relays = listOf("wss://relay.example")

    @Test fun `a room joined as an account saves no participant key and reopens only with that account`() = runTest {
        val secret = Entropy.bytes(32)
        val room = deriveRoom(secret)
        val signer = ElsewhereSigner(Entropy.bytes(32))
        val who = PrimaryIdentity.createWith(signer, room.roomId, now + 3600, now)
        val host = createRoomInvitation(true)
        val saved = SavedRoom.create(secret, who, encodeInvitationUrl("https://kithmoot.example/j/", host.invitation, relays), relays, "Workshop", now, host, host.invitation.canonicalInviter)
        val disk = MemoryStorage()
        RoomRepository(disk).save(saved)
        val stored = disk.read()!!.decodeToString()
        assertFalse("participantKey" in stored, "the account's key never touches the room store")
        assertTrue(signer.pubkey in stored)

        val restored = RoomRepository(disk).get(saved.id)!!
        assertTrue(restored.viaAccount)
        assertEquals(signer.pubkey, restored.participant)
        assertEquals(signer.pubkey, restored.summary().account)

        // The same account signs one fresh credential and is the same person on the same device.
        val again = restored.identity(now + 86_400 * 3, signer)
        assertEquals(who.participant, again.participant)
        assertEquals(who.devicePubkey, again.devicePubkey)
        assertEquals(2, signer.signatures)

        // Another account, or none, is told whose room this is instead of joining as a stranger.
        val other = ElsewhereSigner(Entropy.bytes(32))
        val refusal = assertFailsWith<RoomRecoveryException> { restored.identity(now, other) }
        assertTrue(shortNpub(signer.pubkey) in refusal.message!!)
        assertFailsWith<RoomRecoveryException> { restored.identity(now, null) }
        assertFailsWith<RoomRecoveryException> { restored.identity(now) }
    }

    @Test fun `a room with its own key still opens without any account`() = runTest {
        val secret = Entropy.bytes(32)
        val room = deriveRoom(secret)
        val who = PrimaryIdentity.create(room.roomId, now + 3600, now)
        val host = createRoomInvitation(false)
        val saved = SavedRoom.create(secret, who, encodeInvitationUrl("https://kithmoot.example/j/", host.invitation, relays), relays, "Quick", now, host, null)
        assertFalse(saved.viaAccount)
        assertNull(saved.summary().account)
        val signedIn = ElsewhereSigner(Entropy.bytes(32))
        assertEquals(who.participant, saved.identity(now + 10, signedIn).participant, "a signed-in account does not take over a room this device already owns")
        assertEquals(who.participant, saved.identity(now + 10).participant)
    }
}
