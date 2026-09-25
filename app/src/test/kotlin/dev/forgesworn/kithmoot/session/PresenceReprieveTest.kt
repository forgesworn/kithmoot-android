package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.support.FakeRelay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A relay going quiet must not end a call that is still carrying media.
 *
 * Presence is restated through relays; a call runs peer to peer. When one of
 * the relays hangs, heartbeats can stop arriving while the call itself is
 * fine, and sweeping the device out of the roster closes that working call.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PresenceReprieveTest {

    private val timing = SessionTiming(heartbeatIntervalMs = 600_000, sweepIntervalMs = 1_000, presenceTtlSeconds = 10)

    @Test
    fun `a device with a working call stays through a silent relay`() = runTest {
        val room = Fixtures.room()
        val relay = FakeRelay()
        val alice = session(room, Fixtures.primary(room, 1, 2), relay, timing)
        val bobIdentity = Fixtures.primary(room, 3, 4)
        val bob = session(room, bobIdentity, relay, timing, seed = 11)
        alice.mediaConnected = { it == bobIdentity.devicePubkey }
        alice.join()
        advanceTimeBy(100)
        runCurrent()
        bob.join()
        advanceTimeBy(5_000)
        runCurrent()

        // Bob's heartbeats stop arriving, but the call to him is up.
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(setOf(bobIdentity.devicePubkey), alice.remoteDevices.value, "a working call must keep bob")

        // The call goes too: now the ordinary timeout applies.
        alice.mediaConnected = { false }
        advanceTimeBy(15_000)
        runCurrent()
        assertEquals(emptySet(), alice.remoteDevices.value, "bob should lapse once his call is gone")
    }

    @Test
    fun `presence is timed from arrival, not from the sender's clock`() = runTest {
        val room = Fixtures.room()
        val relay = FakeRelay()
        val alice = session(room, Fixtures.primary(room, 1, 2), relay, timing)
        alice.join()
        advanceTimeBy(20_000)
        runCurrent()

        // Bob's clock runs eight seconds behind alice's. His announce arrives
        // just inside the window by his stamp, and must still count for the
        // whole window from when alice heard it.
        val bobIdentity = Fixtures.primary(room, 3, 4)
        val bob = RoomSession(
            room = room,
            identity = bobIdentity,
            transport = relay.transport(),
            scope = backgroundScope,
            timing = timing,
            now = { currentTime / 1000 - 8 },
        )
        bob.join()
        advanceTimeBy(500)
        runCurrent()
        assertEquals(setOf(bobIdentity.devicePubkey), alice.remoteDevices.value)

        advanceTimeBy(6_000)
        runCurrent()
        assertEquals(setOf(bobIdentity.devicePubkey), alice.remoteDevices.value, "bob was heard six seconds ago")
    }
}
