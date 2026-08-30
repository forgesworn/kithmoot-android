package dev.forgesworn.kithmoot.session

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
import kotlin.test.assertTrue

/**
 * Leaving is a stated fact. The last entry a device publishes carries
 * `left: true`, and everybody else drops it at once rather than after the
 * presence timeout - during which, otherwise, every peer would be walking its
 * route ladder chasing a device that had gone. Mirrors the farewell cases in
 * `src/session.test.ts`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LeaveTest {

    @Test
    fun `a device that says goodbye is gone from everybody else at once`() = runTest {
        val room = Fixtures.room()
        val relay = FakeRelay()
        val alice = session(room, Fixtures.primary(room, 1, 2), relay)
        alice.join()
        advanceTimeBy(100)
        runCurrent()
        val bob = session(room, Fixtures.primary(room, 3, 4), relay, seed = 11)
        bob.join()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(2, bob.participants.value.size, "bob should see alice before she leaves")

        alice.leave()
        advanceTimeBy(100)
        runCurrent()

        assertEquals(1, bob.participants.value.size, "bob must drop alice the moment she says goodbye, not on the presence timeout")
    }

    @Test
    fun `a farewell is marked left, and is an answer rather than an arrival`() = runTest {
        val room = Fixtures.room()
        val relay = FakeRelay()
        val alice = session(room, Fixtures.primary(room, 1, 2), relay)
        alice.join()
        advanceTimeBy(100)
        runCurrent()
        alice.leave()
        runCurrent()

        val farewell = relay.published.last { it.kind == KIND_ROSTER && it.pubkey == alice.identity.devicePubkey }
        val decoded = decodeRosterEvent(farewell, room.roomId, room.roomKey, now = 0)
        assertNotNull(decoded)
        assertTrue(decoded.left, "the last entry must say the device has left")
        assertTrue(decoded.reply, "a farewell is not an arrival, so nobody should answer it")
        assertTrue(decoded.tracks.isEmpty())
        assertTrue(decoded.claims.isEmpty())
    }

    @Test
    fun `an entry delivered late, from before the goodbye, cannot bring a departed device back`() = runTest {
        val room = Fixtures.room()
        val relay = FakeRelay()
        val alice = session(room, Fixtures.primary(room, 1, 2), relay)
        alice.join()
        advanceTimeBy(100)
        runCurrent()
        val bob = session(room, Fixtures.primary(room, 3, 4), relay, seed = 11)
        bob.join()
        advanceTimeBy(2_000)
        runCurrent()
        val earlier = relay.published.filter { it.kind == KIND_ROSTER && it.pubkey == alice.identity.devicePubkey }
        assertTrue(earlier.isNotEmpty())

        alice.leave()
        advanceTimeBy(100)
        runCurrent()
        assertEquals(1, bob.participants.value.size)

        // Three relays deliver in three orders; a slower one hands bob what
        // alice said before she left.
        for (stale in earlier) relay.publish(stale)
        runCurrent()
        assertEquals(1, bob.participants.value.size, "a stale entry must not resurrect a device that said goodbye")
    }

    @Test
    fun `a device that left and comes back is an arrival again, and is answered`() = runTest {
        val room = Fixtures.room()
        val relay = FakeRelay()
        val first = session(room, Fixtures.primary(room, 1, 2), relay)
        first.join()
        advanceTimeBy(100)
        runCurrent()
        val bob = session(room, Fixtures.primary(room, 3, 4), relay, seed = 11)
        bob.join()
        advanceTimeBy(2_000)
        runCurrent()
        first.leave()
        advanceTimeBy(100)
        runCurrent()
        assertEquals(1, bob.participants.value.size)

        // Later, the same device on the same key comes back.
        advanceTimeBy(30_000)
        val answersBefore = relay.countFrom(bob.identity.devicePubkey, KIND_ROSTER)
        val again = session(room, Fixtures.primary(room, 1, 2), relay, seed = 5)
        again.join()
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(2, bob.participants.value.size, "bob must see the device back")
        assertEquals(answersBefore + 1, relay.countFrom(bob.identity.devicePubkey, KIND_ROSTER), "bob must answer the arrival")
        assertEquals(2, again.participants.value.size, "the returning device must learn bob is here")
    }
}
