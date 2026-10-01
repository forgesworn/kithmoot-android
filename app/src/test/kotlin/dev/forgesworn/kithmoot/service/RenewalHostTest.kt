package dev.forgesworn.kithmoot.service

import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.account.NostrAccount
import dev.forgesworn.kithmoot.account.ParticipantSigner
import dev.forgesworn.kithmoot.account.SignerException
import dev.forgesworn.kithmoot.account.SignerTimeoutException
import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.notifications.CallRingMode
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.createRoomInvitation
import dev.forgesworn.kithmoot.protocol.deriveRoom
import dev.forgesworn.kithmoot.protocol.encodeInvitationUrl
import dev.forgesworn.kithmoot.session.PrimaryIdentity
import dev.forgesworn.kithmoot.session.RoomIdentity
import dev.forgesworn.kithmoot.storage.RING_CREDENTIAL_TTL
import dev.forgesworn.kithmoot.storage.SAVED_CREDENTIAL_TTL
import dev.forgesworn.kithmoot.storage.SavedRoom
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The paths through quiet renewal that end without renewing anything must still say so. */
class RenewalHostTest {
    private val now = 1_800_000_000L
    private val relays = listOf("wss://relay.example")
    private val key = Entropy.bytes(32)
    /** Signs like a local key but is not one, so a room joined with it is saved as an account room. */
    private class AccountSigner(key: ByteArray) : ParticipantSigner {
        private val local = LocalSigner(key)
        override val pubkey = local.pubkey
        override val method = "nip55"
        override suspend fun sign(kind: Int, createdAt: Long, tags: List<List<String>>, content: String) = local.sign(kind, createdAt, tags, content)
        override suspend fun nip44Encrypt(peer: String, plaintext: String) = local.nip44Encrypt(peer, plaintext)
        override suspend fun nip44Decrypt(peer: String, payload: String) = local.nip44Decrypt(peer, payload)
    }
    private val me = AccountSigner(key)

    private class LockedSigner(key: ByteArray) : ParticipantSigner {
        private val local = LocalSigner(key)
        override val pubkey = local.pubkey
        override val method = "nip55"
        override suspend fun sign(kind: Int, createdAt: Long, tags: List<List<String>>, content: String): NostrEvent =
            throw SignerException("The signer is locked.")
        override suspend fun nip44Encrypt(peer: String, plaintext: String) = local.nip44Encrypt(peer, plaintext)
        override suspend fun nip44Decrypt(peer: String, payload: String) = local.nip44Decrypt(peer, payload)
    }

    private fun savedRoom(name: String = "Untitled test"): SavedRoom = kotlinx.coroutines.runBlocking {
        val secret = Entropy.bytes(32)
        val room = deriveRoom(secret)
        val who = PrimaryIdentity.createWith(me, room.roomId, now + SAVED_CREDENTIAL_TTL, now)
        val host = createRoomInvitation(true)
        SavedRoom.create(secret, who, encodeInvitationUrl("https://kithmoot.example/j/", host.invitation, relays), relays, name, now, host, null)
    }

    private inner class FakeHost(
        var account: Result<NostrAccount?>,
        private val quietSigner: ParticipantSigner?,
        var ringOn: Boolean = true,
        expiresIn: Long = 3_600,
        var unreadable: Boolean = false,
    ) : RenewalHost {
        val room = savedRoom()
        private var expiresAt = now + expiresIn
        var quietAsked = 0
        val kept = mutableListOf<String>()
        var published: Pair<List<AtRiskRoom>, Boolean>? = null
        val notified = mutableListOf<List<String>>()
        var cancelled = 0

        override fun account() = account
        override suspend fun runWithQuietSigner(account: NostrAccount, block: suspend (ParticipantSigner) -> Unit): Boolean {
            quietAsked++
            block(quietSigner ?: return false)
            return true
        }
        override fun rooms(now: Long): RoomsReading? = if (unreadable) null else RoomsReading(ringOn, mapOf(
            room.id to (room to RenewalCandidate(room.id, true, room.participant, false,
                if (ringOn) CallRingMode.RING else CallRingMode.NOTHING, expiresAt, chosenMode = CallRingMode.RING)),
        ))
        override fun keep(roomId: String, identity: RoomIdentity) { kept += roomId; expiresAt = now + RING_CREDENTIAL_TTL }
        override fun publish(atRisk: List<AtRiskRoom>, ringingOff: Boolean) { published = atRisk to ringingOff }
        override fun notify(names: List<String>) { notified += names }
        override fun cancelNotice() { cancelled++ }
    }

    private class TimedOutSigner(key: ByteArray) : ParticipantSigner {
        private val local = LocalSigner(key)
        override val pubkey = local.pubkey
        override val method = "nip55"
        override suspend fun sign(kind: Int, createdAt: Long, tags: List<List<String>>, content: String): NostrEvent =
            throw SignerTimeoutException("My Signet didn't answer. Open it, unlock it, then try again.")
        override suspend fun nip44Encrypt(peer: String, plaintext: String) = local.nip44Encrypt(peer, plaintext)
        override suspend fun nip44Decrypt(peer: String, payload: String) = local.nip44Decrypt(peer, payload)
    }

    private fun nip55() = NostrAccount(me.pubkey, "nip55", signerPackage = "com.example.signer")

    @Test fun `a signer that needs a screen still gets the notice again`() = runTest {
        val host = FakeHost(Result.success(nip55()), quietSigner = null)
        renewQuietlyVia(host, now)
        assertEquals(1, host.quietAsked)
        assertEquals(listOf(listOf("Untitled test")), host.notified)
        assertEquals(listOf("Untitled test"), host.published!!.first.map { it.name })
    }

    @Test fun `a locked signer ends in the notice, not in silence`() = runTest {
        val host = FakeHost(Result.success(nip55()), quietSigner = LockedSigner(key))
        renewQuietlyVia(host, now)
        assertEquals(emptyList(), host.kept)
        assertEquals(listOf(listOf("Untitled test")), host.notified)
    }

    @Test fun `a signer that times out tells the person who pressed the button, and settles first`() = runTest {
        val host = FakeHost(Result.success(nip55()), quietSigner = null)
        val error = kotlin.test.assertFailsWith<SignerTimeoutException> { renewRooms(host, TimedOutSigner(key), now) }
        assertEquals("My Signet didn't answer. Open it, unlock it, then try again.", error.message)
        assertEquals(emptyList(), host.kept)
        assertEquals(listOf(listOf("Untitled test")), host.notified, "the banner's answer and the notice are right either way")
    }

    @Test fun `a timeout in the quiet path ends the attempt without crashing the background loop`() = runTest {
        val host = FakeHost(Result.success(nip55()), quietSigner = TimedOutSigner(key))
        renewQuietlyVia(host, now)
        assertEquals(listOf(listOf("Untitled test")), host.notified)
    }

    @Test fun `a bunker is never asked quietly, and the notice follows the rooms`() = runTest {
        val host = FakeHost(Result.success(NostrAccount(me.pubkey, "bunker", bunkerUri = "bunker://x")), quietSigner = me)
        renewQuietlyVia(host, now)
        assertEquals(0, host.quietAsked)
        assertEquals(listOf(listOf("Untitled test")), host.notified)
    }

    @Test fun `an account that cannot be read still says which rooms cannot ring`() = runTest {
        val host = FakeHost(Result.failure(IllegalStateException("storage")), quietSigner = me)
        renewQuietlyVia(host, now)
        assertEquals(0, host.quietAsked)
        assertEquals(listOf(listOf("Untitled test")), host.notified)
        assertEquals(1, host.published!!.first.size)
    }

    @Test fun `nobody signed in means nothing is waiting on a signer`() = runTest {
        val host = FakeHost(Result.success(null), quietSigner = me)
        renewQuietlyVia(host, now)
        assertEquals(emptyList(), host.notified)
        assertEquals(emptyList(), host.published!!.first)
        assertEquals(1, host.cancelled)
    }

    @Test fun `a signer that answers renews the room and clears the notice`() = runTest {
        val host = FakeHost(Result.success(nip55()), quietSigner = me)
        renewQuietlyVia(host, now)
        assertTrue(host.room.viaAccount)
        assertEquals(listOf(host.room.id), host.kept)
        assertEquals(emptyList(), host.notified)
        assertEquals(emptyList(), host.published!!.first)
        assertEquals(1, host.cancelled)
    }

    @Test fun `the foreground looks again without bringing a dismissed notice back`() {
        val host = FakeHost(Result.success(nip55()), quietSigner = null)
        settle(host, now, post = false)
        assertEquals(emptyList(), host.notified)
        assertEquals(1, host.published!!.first.size, "the banner still has its answer")
        val fine = FakeHost(Result.success(nip55()), quietSigner = null, expiresIn = 3 * 86_400)
        settle(fine, now, post = false)
        assertEquals(1, fine.cancelled, "and the notice goes once nothing is wrong")
    }

    @Test fun `rooms that cannot be read change nothing`() {
        val host = FakeHost(Result.success(nip55()), quietSigner = null, unreadable = true)
        assertEquals(emptyList(), settle(host, now, post = true))
        assertEquals(null, host.published)
        assertEquals(0, host.cancelled)
    }

    @Test fun `ringing switched off is published, and is not a signer problem`() {
        val host = FakeHost(Result.success(nip55()), quietSigner = null, ringOn = false)
        settle(host, now, post = true)
        assertEquals(emptyList(), host.published!!.first)
        assertTrue(host.published!!.second)
        assertEquals(emptyList(), host.notified)
        val on = FakeHost(Result.success(nip55()), quietSigner = null, ringOn = true, expiresIn = 3 * 86_400)
        settle(on, now, post = true)
        assertFalse(on.published!!.second)
    }
}
