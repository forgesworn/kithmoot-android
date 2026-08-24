package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.KIND_ROSTER
import dev.forgesworn.kithmoot.support.FakeRelay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The behaviour the whole session exists for.
 *
 * Roster events are kind 20461, in the ephemeral range: relays do not store
 * them and will not replay them to a new subscriber. So a device that joins and
 * only listens hears nothing at all, and the second person into a room sees an
 * empty room forever. The fix is that everyone already present answers an
 * arrival by re-announcing - and the fix's own failure mode is an announce storm,
 * because an answer looks exactly like an arrival.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnnounceAndRespondTest {

    @Test
    fun `the second person into the room sees the first`() = runTest {
        val room = Fixtures.room()
        val relay = FakeRelay()

        val alice = session(room, Fixtures.primary(room, 1, 2), relay)
        alice.join()
        advanceTimeBy(100)
        runCurrent()
        assertEquals(1, alice.participants.value.size, "alice should see only herself")

        val bob = session(room, Fixtures.primary(room, 3, 4), relay, seed = 11)
        bob.join()
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(2, bob.participants.value.size, "bob must learn that alice was already here")
        assertEquals(2, alice.participants.value.size, "alice must learn bob arrived")
    }

    @Test
    fun `the exchange settles instead of running away`() = runTest {
        val room = Fixtures.room()
        val relay = FakeRelay()

        val alice = session(room, Fixtures.primary(room, 1, 2), relay)
        alice.join()
        advanceTimeBy(100)
        runCurrent()

        val bob = session(room, Fixtures.primary(room, 3, 4), relay, seed = 11)
        bob.join()
        advanceTimeBy(5_000)
        runCurrent()

        // Alice announces on join and answers bob once; bob announces on join and
        // answers alice once. Four roster events, and then silence: alice has
        // already answered bob, so bob's answer provokes nothing.
        val settled = relay.countOfKind(KIND_ROSTER)
        assertEquals(4, settled, "two devices should exchange exactly four roster events")

        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(settled, relay.countOfKind(KIND_ROSTER), "the exchange must not keep going")
    }

    @Test
    fun `a room of three settles too`() = runTest {
        val room = Fixtures.room()
        val relay = FakeRelay()

        val sessions = listOf(
            session(room, Fixtures.primary(room, 1, 2), relay, seed = 3),
            session(room, Fixtures.primary(room, 20, 21), relay, seed = 5),
            session(room, Fixtures.primary(room, 40, 41), relay, seed = 9),
        )
        for (each in sessions) {
            each.join()
            advanceTimeBy(2_000)
            runCurrent()
        }
        advanceTimeBy(5_000)
        runCurrent()

        for (each in sessions) {
            assertEquals(3, each.participants.value.size, "every device should see all three")
        }
        val settled = relay.countOfKind(KIND_ROSTER)
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(settled, relay.countOfKind(KIND_ROSTER))
        assertTrue(settled < 20, "a three-device room should not need $settled roster events")
    }

    @Test
    fun `a burst of arrivals draws one answer, not one per arrival`() = runTest {
        val room = Fixtures.room()
        val relay = FakeRelay()

        val aliceIdentity = Fixtures.primary(room, 1, 2)
        val alice = session(room, aliceIdentity, relay)
        alice.join()
        advanceTimeBy(100)
        runCurrent()

        // Three devices arriving at once is what a relay coming back up looks
        // like. Alice must answer the burst once, not three times.
        val arrivals = listOf(
            session(room, Fixtures.primary(room, 20, 21), relay, seed = 5),
            session(room, Fixtures.primary(room, 40, 41), relay, seed = 9),
            session(room, Fixtures.primary(room, 60, 61), relay, seed = 13),
        )
        for (each in arrivals) each.join()
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(
            2,
            relay.countFrom(aliceIdentity.devicePubkey, KIND_ROSTER),
            "alice should announce once on joining and answer the whole burst once",
        )
        for (each in arrivals + alice) {
            assertEquals(4, each.participants.value.size, "every device should end up seeing all four")
        }

        val settled = relay.countOfKind(KIND_ROSTER)
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(settled, relay.countOfKind(KIND_ROSTER), "the burst must not keep echoing")
        assertTrue(settled < 20, "a four-device room should not need $settled roster events")
    }

    @Test
    fun `a heartbeat from a device already known draws no answer`() = runTest {
        val room = Fixtures.room()
        val relay = FakeRelay()

        val alice = session(room, Fixtures.primary(room, 1, 2), relay)
        val bob = session(room, Fixtures.primary(room, 3, 4), relay, seed = 11)
        alice.join()
        advanceTimeBy(100)
        runCurrent()
        bob.join()
        advanceTimeBy(5_000)
        runCurrent()

        val settled = relay.countOfKind(KIND_ROSTER)
        // A heartbeat is byte-for-byte an announce. If it provoked an answer,
        // every heartbeat in the room would provoke one from everybody, forever.
        bob.announce()
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(settled + 1, relay.countOfKind(KIND_ROSTER), "only bob's heartbeat should appear")
    }

    @Test
    fun `a device that lapses out of the roster is answered again when it returns`() = runTest {
        val room = Fixtures.room()
        val relay = FakeRelay()
        val timing = SessionTiming(heartbeatIntervalMs = 600_000, sweepIntervalMs = 1_000, presenceTtlSeconds = 10)

        val aliceIdentity = Fixtures.primary(room, 1, 2)
        val alice = session(room, aliceIdentity, relay, timing)
        val bob = session(room, Fixtures.primary(room, 3, 4), relay, timing, seed = 11)
        alice.join()
        advanceTimeBy(100)
        runCurrent()
        bob.join()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(2, alice.participants.value.size)

        // Bob stops heartbeating. Alice sweeps him out, and forgets that she
        // ever answered him.
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(1, alice.participants.value.size, "bob should have lapsed")

        val aliceBefore = relay.countFrom(aliceIdentity.devicePubkey, KIND_ROSTER)
        bob.announce()
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(2, alice.participants.value.size, "bob should be back")
        assertEquals(
            aliceBefore + 1,
            relay.countFrom(aliceIdentity.devicePubkey, KIND_ROSTER),
            "a genuine rejoin is answered once, not ignored and not answered repeatedly",
        )
    }
}
