package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.account.ParticipantSigner
import dev.forgesworn.kithmoot.account.SignerException
import dev.forgesworn.kithmoot.account.SignerTimeoutException
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.protocol.KIND_ROSTER
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.Room
import dev.forgesworn.kithmoot.protocol.createDeviceCredential
import dev.forgesworn.kithmoot.protocol.decodeRosterEvent
import dev.forgesworn.kithmoot.relay.RoomTransport
import dev.forgesworn.kithmoot.storage.RoomStorage
import dev.forgesworn.kithmoot.support.FakeRelay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * An open room renews this device's credential by the wall clock. The session's
 * clock here is a plain variable moved by hand, while the coroutine scheduler's
 * virtual time stands nearly still: a phone in deep sleep, whose uptime timers
 * stop while the wall clock runs on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CredentialRenewalSessionTest {
    private class Store : RoomStorage {
        private var bytes: ByteArray? = null
        override fun read() = bytes?.clone()
        override fun write(value: ByteArray) { bytes = value.clone() }
        override fun reset() { bytes = null }
    }

    /** A signer app on this phone: the same key, but not one the room holds itself. */
    private class AppSigner(private val inner: LocalSigner) : ParticipantSigner by inner {
        override val method: String get() = "nip55"
    }

    private val start = 1_000_000L
    private val day = 24L * 60 * 60
    private val hour = 60L * 60

    private fun localOwner(room: Room) = PrimaryIdentity.create(room.roomId, start + day, start,
        participantSecretKey = Fixtures.key(1), deviceSecretKey = Fixtures.key(2))

    private fun signerOwner(room: Room): PrimaryIdentity {
        val device = Fixtures.key(2)
        val credential = createDeviceCredential(Fixtures.key(1), Schnorr.publicKeyHex(device), room.roomId, start + day, start)
        return PrimaryIdentity(AppSigner(LocalSigner(Fixtures.key(1))), device, credential)
    }

    private class Harness(val live: RoomSession, val relay: FakeRelay, val outbox: PendingChatOutbox)

    private suspend fun TestScope.open(room: Room, owner: RoomIdentity, clock: () -> Long, renewer: RoomCredentialRenewer?,
        timing: SessionTiming = Fixtures.QUIET, transport: (FakeRelay) -> RoomTransport = { it.transport() }): Harness {
        val relay = FakeRelay()
        val outbox = PendingChatOutbox(Store(), room.roomId, owner.participant, owner.devicePubkey)
        val live = RoomSession(room, owner, transport(relay), backgroundScope, timing = timing, now = clock,
            nowMs = { clock() * 1000 }, chatOutbox = outbox, credentialRenewer = renewer)
        live.join(); advanceTimeBy(1_000); runCurrent()
        return Harness(live, relay, outbox)
    }

    private fun Harness.ownRosterCredentials(room: Room, at: Long): List<NostrEvent> =
        relay.published.filter { it.kind == KIND_ROSTER }.mapNotNull { decodeRosterEvent(it, room.roomId, room.roomKey, at)?.credential }

    @Test fun `a room open for 25 hours renews before it sends, and the message goes`() = runTest {
        val room = Fixtures.room(); val owner = localOwner(room)
        var clock = start
        val asks = mutableListOf<Boolean>()
        val h = open(room, owner, { clock }, RoomCredentialRenewer { visible ->
            asks += visible
            owner.enrol(owner.devicePubkey, room.roomId, clock + day, clock)
        })
        clock += 25 * hour

        assertTrue(h.live.sendChatDurable("Still here"))

        assertEquals(1, asks.size)
        assertEquals(clock + day, credentialExpiresAt(owner.credential))
        val chat = h.relay.published.single { it.kind == KIND_CHAT }
        assertNotNull(decodeChatEvent(chat, room.roomId, room.roomKey, clock), "readers accept the message")
        assertTrue(h.outbox.items().isEmpty())
        // The roster was restated under the new credential, before the message.
        assertTrue(h.ownRosterCredentials(room, clock).any { it == owner.credential })
        h.live.leave()
    }

    @Test fun `a key held here renews silently on the first heartbeat after a long sleep`() = runTest {
        val room = Fixtures.room(); val owner = localOwner(room)
        var clock = start
        val asks = mutableListOf<Boolean>()
        val h = open(room, owner, { clock }, RoomCredentialRenewer { visible ->
            asks += visible
            owner.enrol(owner.devicePubkey, room.roomId, clock + day, clock)
        }, timing = SessionTiming(heartbeatIntervalMs = 20_000, sweepIntervalMs = 600_000))
        val before = owner.credential
        // Thirteen hours of wall clock pass while the scheduler's uptime barely moves.
        clock += 13 * hour
        advanceTimeBy(20_001); runCurrent()

        assertEquals(listOf(false), asks, "a heartbeat never shows a signer")
        assertTrue(credentialExpiresAt(owner.credential) > credentialExpiresAt(before))
        assertTrue(h.ownRosterCredentials(room, clock).contains(owner.credential))
        h.live.leave()
    }

    @Test fun `coming back to the room after a long gap renews, showing the signer only near the end`() = runTest {
        val room = Fixtures.room(); val owner = signerOwner(room)
        var clock = start
        val asks = mutableListOf<Boolean>()
        val h = open(room, owner, { clock }, RoomCredentialRenewer { visible ->
            asks += visible
            if (!visible) null else owner.enrol(owner.devicePubkey, room.roomId, clock + day, clock)
        })
        val original = owner.credential

        // Past halfway, hours left: the signer is asked quietly, and says it would have to show itself.
        clock += 13 * hour
        h.live.checkCredential(CredentialMoment.RESUME); runCurrent()
        assertEquals(listOf(false), asks)
        assertEquals(original, owner.credential)
        // A second return soon after does not ask again.
        h.live.checkCredential(CredentialMoment.RESUME); runCurrent()
        assertEquals(listOf(false), asks)

        // Back after the night, an hour left: the person is here, so the signer may show itself.
        clock += 10 * hour
        h.live.checkCredential(CredentialMoment.RESUME); runCurrent()
        assertEquals(listOf(false, true), asks)
        assertEquals(clock + day, credentialExpiresAt(owner.credential))
        assertTrue(h.ownRosterCredentials(room, clock).contains(owner.credential))
        h.live.leave()
    }

    @Test fun `a heartbeat or a reconnection never shows the signer, even with the credential gone`() = runTest {
        val room = Fixtures.room(); val owner = signerOwner(room)
        var clock = start
        val asks = mutableListOf<Boolean>()
        val h = open(room, owner, { clock }, RoomCredentialRenewer { visible -> asks += visible; null })
        clock += 25 * hour
        h.live.checkCredential(CredentialMoment.TICK); runCurrent()
        assertEquals(listOf(false), asks)
        h.live.leave()
    }

    @Test fun `a signer that refuses leaves nothing signed and says why`() = runTest {
        val room = Fixtures.room(); val owner = signerOwner(room)
        var clock = start
        var asks = 0
        val h = open(room, owner, { clock }, RoomCredentialRenewer { asks++; throw SignerException("Amber declined to sign.") })
        clock += 25 * hour
        val rosterBefore = h.relay.countOfKind(KIND_ROSTER)

        val refused = assertFailsWith<CredentialRenewalException> { h.live.sendChatDurable("Will not go") }

        assertEquals(CredentialRenewalReason.SIGNER_REFUSED, refused.reason)
        assertEquals(CredentialWords.failure(CredentialRenewalReason.SIGNER_REFUSED), refused.message)
        assertFalse(refused.message!!.contains("rejoin", ignoreCase = true))
        assertEquals(1, asks)
        assertEquals(0, h.relay.countOfKind(KIND_CHAT))
        assertTrue(h.outbox.items().isEmpty(), "nothing is kept that readers would refuse")
        assertEquals(rosterBefore, h.relay.countOfKind(KIND_ROSTER))
        // Send again is the retry: the person is asked again at once.
        assertFailsWith<CredentialRenewalException> { h.live.sendChatDurable("Will not go") }
        assertEquals(2, asks)
        h.live.leave()
    }

    @Test fun `a signer that does not answer is told apart from one that refuses`() = runTest {
        val room = Fixtures.room(); val owner = signerOwner(room)
        var clock = start
        val h = open(room, owner, { clock }, RoomCredentialRenewer { throw SignerTimeoutException("Amber didn't answer.") })
        clock += 25 * hour
        val silent = assertFailsWith<CredentialRenewalException> { h.live.sendChatDurable("Waiting") }
        assertEquals(CredentialRenewalReason.SIGNER_NO_ANSWER, silent.reason)
        assertEquals(0, h.relay.countOfKind(KIND_CHAT))
        h.live.leave()
    }

    @Test fun `a slow signer is asked once and every waiting message goes when it answers`() = runTest {
        val room = Fixtures.room(); val owner = signerOwner(room)
        var clock = start
        val answer = CompletableDeferred<Unit>()
        val asks = mutableListOf<Boolean>()
        val h = open(room, owner, { clock }, RoomCredentialRenewer { visible ->
            asks += visible
            answer.await()
            owner.enrol(owner.devicePubkey, room.roomId, clock + day, clock)
        })
        clock += 25 * hour

        val first = backgroundScope.async { h.live.sendChatDurable("First") }
        runCurrent()
        val second = backgroundScope.async { h.live.sendChatDurable("Second") }
        runCurrent()
        h.live.checkCredential(CredentialMoment.RESUME); runCurrent()

        assertEquals(listOf(true), asks, "one request, shared")
        assertTrue(h.live.credentialRenewalPending.value)
        assertEquals(0, h.relay.countOfKind(KIND_CHAT))
        assertTrue(h.outbox.items().isEmpty())

        answer.complete(Unit)
        assertTrue(first.await())
        assertTrue(second.await())
        assertEquals(2, h.relay.countOfKind(KIND_CHAT))
        assertFalse(h.live.credentialRenewalPending.value)
        assertEquals(listOf(true), asks)
        h.live.leave()
    }

    @Test fun `nothing is published under an expired credential`() = runTest {
        val room = Fixtures.room(); val owner = localOwner(room)
        var clock = start
        val h = open(room, owner, { clock }, renewer = null,
            timing = SessionTiming(heartbeatIntervalMs = 20_000, sweepIntervalMs = 600_000))
        clock += 25 * hour
        val rosterBefore = h.relay.countOfKind(KIND_ROSTER)

        advanceTimeBy(60_001); runCurrent()
        assertEquals(rosterBefore, h.relay.countOfKind(KIND_ROSTER), "no heartbeat under an expired credential")
        assertFailsWith<CredentialRenewalException> { h.live.sendChat("Plain send") }
        assertFailsWith<CredentialRenewalException> { h.live.prepareChatForSend("Prepared send") }
        assertFailsWith<CredentialRenewalException> { h.live.sendChatDurable("Durable send") }
        assertFailsWith<CredentialRenewalException> { h.live.sendChatConfirmed("Confirmed send") }
        assertEquals(0, h.relay.countOfKind(KIND_CHAT))
        assertTrue(h.outbox.items().isEmpty())
        h.live.leave()
    }

    @Test fun `a credential in its last minutes is renewed before a message is signed under it`() = runTest {
        val room = Fixtures.room(); val owner = localOwner(room)
        var clock = start
        var asks = 0
        val h = open(room, owner, { clock }, RoomCredentialRenewer {
            asks++
            owner.enrol(owner.devicePubkey, room.roomId, clock + day, clock)
        })
        clock = start + day - 60
        assertTrue(h.live.sendChatDurable("Just in time"))
        assertEquals(1, asks)
        val chat = h.relay.published.single { it.kind == KIND_CHAT }
        assertNotNull(decodeChatEvent(chat, room.roomId, room.roomKey, start + day + 60), "still readable after the old credential lapsed")
        h.live.leave()
    }

    @Test fun `a paired device cannot renew and is told to pair again`() = runTest {
        val room = Fixtures.room(); val main = localOwner(room)
        val device = Fixtures.key(30)
        val credential = main.enrolNow(Schnorr.publicKeyHex(device), room.roomId, start + day, start)
        val paired = assertNotNull(SecondaryIdentity.adopt(credential, device, room.roomId, start))
        var clock = start
        val h = open(room, paired, { clock }, renewer = null)
        clock += 25 * hour
        val refused = assertFailsWith<CredentialRenewalException> { h.live.sendChatDurable("From the paired phone") }
        assertEquals(CredentialRenewalReason.CANNOT_RENEW, refused.reason)
        assertEquals(0, h.relay.countOfKind(KIND_CHAT))
        h.live.leave()
    }
}
