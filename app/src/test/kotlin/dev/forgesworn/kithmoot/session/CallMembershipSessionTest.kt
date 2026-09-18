package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.CallMembership
import dev.forgesworn.kithmoot.protocol.KIND_ROSTER
import dev.forgesworn.kithmoot.protocol.decodeRosterEvent
import dev.forgesworn.kithmoot.support.FakeRelay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A call is a claim on presence, and this is the claim.
 *
 * The complaint that brought it here: a Mac showed "Start call" while an
 * Android phone was streaming to it, and pressing Start minted a second call
 * id, because Android never wrote the field and never read it. Nobody could
 * see who was on a call except by looking for tracks, which is not the same
 * question.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallMembershipSessionTest {

    private val CALL = "c0ffeec0ffeec0ffeec0ffeec0ffeec0"
    private val OTHER = "1234567890abcdef1234567890abcdef"

    @Test
    fun `a device on a call says so on every announcement`() = runTest {
        val room = Fixtures.room()
        val relay = FakeRelay()
        val alice = session(room, Fixtures.primary(room, 1, 2), relay)
        alice.join()
        advanceTimeBy(100)
        runCurrent()

        alice.setCall(CallMembership(CALL, since = 1_000))
        advanceTimeBy(100)
        runCurrent()

        val published = relay.published.filter { it.kind == KIND_ROSTER }
            .mapNotNull { decodeRosterEvent(it, room.roomId, room.roomKey, now = 10_000) }
        assertEquals(CallMembership(CALL, 1_000), published.last().call)
        assertEquals(CallMembership(CALL, 1_000), alice.currentCall())
    }

    @Test
    fun `an entry that is not on a call carries nothing at all`() = runTest {
        val room = Fixtures.room()
        val relay = FakeRelay()
        val alice = session(room, Fixtures.primary(room, 1, 2), relay)
        alice.join()
        advanceTimeBy(100)
        runCurrent()

        // The wire has to stay byte-identical for a client that has never
        // heard of calls, which is what lets this ride presence at all.
        val entry = relay.published.filter { it.kind == KIND_ROSTER }
            .mapNotNull { decodeRosterEvent(it, room.roomId, room.roomKey, now = 10_000) }
            .last()
        assertNull(entry.call)
        assertTrue(!entry.toJson().containsKey("call"))
    }

    @Test
    fun `dropping off a call is stated, not guessed`() = runTest {
        val room = Fixtures.room()
        val relay = FakeRelay()
        val alice = session(room, Fixtures.primary(room, 1, 2), relay)
        val bob = session(room, Fixtures.primary(room, 3, 4), relay, seed = 11)
        alice.join(); bob.join()
        advanceTimeBy(2_000); runCurrent()

        alice.setCall(CallMembership(CALL, since = 1_000))
        advanceTimeBy(500); runCurrent()
        assertEquals(1, bob.calls().size, "bob should see the call alice started")

        alice.setCall(null)
        advanceTimeBy(500); runCurrent()
        assertTrue(bob.calls().isEmpty(), "a call ends when the last device stops saying it is on one")
    }

    @Test
    fun `a farewell is never on a call`() = runTest {
        val room = Fixtures.room()
        val relay = FakeRelay()
        val alice = session(room, Fixtures.primary(room, 1, 2), relay)
        alice.join()
        advanceTimeBy(100); runCurrent()
        alice.setCall(CallMembership(CALL, since = 1_000))
        advanceTimeBy(100); runCurrent()

        alice.leave()
        advanceTimeBy(100); runCurrent()

        val farewell = relay.published.filter { it.kind == KIND_ROSTER }
            .mapNotNull { decodeRosterEvent(it, room.roomId, room.roomKey, now = 10_000) }
            .last { it.left }
        assertNotNull(farewell)
        assertNull(farewell.call, "the last thing a device publishes must not claim it is on a call")
    }

    @Test
    fun `a call with somebody on it is visible to everybody else in the room`() = runTest {
        val room = Fixtures.room()
        val relay = FakeRelay()
        val alice = session(room, Fixtures.primary(room, 1, 2), relay)
        val bob = session(room, Fixtures.primary(room, 3, 4), relay, seed = 11)
        alice.join(); bob.join()
        advanceTimeBy(2_000); runCurrent()

        // Nothing switched on: alice is on the call from a train with her
        // camera and microphone off, which used to be indistinguishable from
        // not being on it.
        alice.setCall(CallMembership(CALL, since = 1_000))
        advanceTimeBy(500); runCurrent()

        val seen = bob.calls().single()
        assertEquals(CALL, seen.id)
        assertEquals(listOf(alice.identity.participant), seen.participants)
    }

    @Test
    fun `two calls started at once are visibly two, and the bigger one is offered first`() = runTest {
        val room = Fixtures.room()
        val relay = FakeRelay()
        val alice = session(room, Fixtures.primary(room, 1, 2), relay)
        val bob = session(room, Fixtures.primary(room, 3, 4), relay, seed = 11)
        val carol = session(room, Fixtures.primary(room, 5, 6), relay, seed = 13)
        alice.join(); bob.join(); carol.join()
        advanceTimeBy(3_000); runCurrent()

        // Alice and Bob both pressed Start. Carol joined Alice's.
        alice.setCall(CallMembership(OTHER, since = 1_000))
        bob.setCall(CallMembership(CALL, since = 900))
        carol.setCall(CallMembership(OTHER, since = 1_100))
        advanceTimeBy(1_000); runCurrent()

        val calls = alice.calls()
        assertEquals(2, calls.size, "two people pressing Start at once is two calls, and saying so is the point")
        // Most people first, so a fourth person joining piles onto the call
        // the room is actually on and the other withers.
        assertEquals(OTHER, calls.first().id)
        assertEquals(2, calls.first().participants.size)
        assertEquals(1_000, calls.first().since, "since is the earliest join, not the latest")
    }
}
