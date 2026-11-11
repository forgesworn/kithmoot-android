package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.CALL_BELL_TTL_SECONDS
import dev.forgesworn.kithmoot.protocol.CallBell
import dev.forgesworn.kithmoot.protocol.CallBellState
import dev.forgesworn.kithmoot.protocol.CallMembership
import dev.forgesworn.kithmoot.protocol.KIND_CALL_BELL
import dev.forgesworn.kithmoot.protocol.Room
import dev.forgesworn.kithmoot.protocol.decodeCallBellEvent
import dev.forgesworn.kithmoot.support.FakeRelay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The bells a device rings as it goes on a call, read back the way a phone
 * with the app closed reads them. The complaint behind `answer`: once a
 * person had picked up on one device, the rest of their devices kept ringing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallBellSessionTest {

    private val CALL = "c0ffeec0ffeec0ffeec0ffeec0ffeec0"

    private fun TestScope.bellsFrom(relay: FakeRelay, room: Room, device: String): List<CallBell> =
        relay.published.filter { it.kind == KIND_CALL_BELL }
            .mapNotNull { decodeCallBellEvent(it, room.roomId, room.roomKey, currentTime / 1000) }
            .filter { it.device == device }

    private fun TestScope.seconds(): Long = currentTime / 1000

    @Test
    fun `the first device on a call rings a start naming its own person`() = runTest {
        val room = Fixtures.room()
        val relay = FakeRelay()
        val laptop = session(room, Fixtures.primary(room, 1, 2), relay)
        laptop.join()
        advanceTimeBy(2_000); runCurrent()

        laptop.setCall(CallMembership(CALL, seconds()))
        advanceTimeBy(100); runCurrent()

        val bell = bellsFrom(relay, room, laptop.identity.devicePubkey).single()
        assertEquals(CallBellState.START, bell.state)
        assertEquals(laptop.identity.participant, bell.participant)
    }

    @Test
    fun `picking up on a second device rings an answer its own person can be told apart by`() = runTest {
        val room = Fixtures.room()
        val relay = FakeRelay()
        val bob = session(room, Fixtures.primary(room, 3, 4), relay, seed = 11)
        val aliceLaptop = Fixtures.primary(room, 1, 2)
        val laptop = session(room, aliceLaptop, relay)
        val phone = session(room, Fixtures.secondary(room, aliceLaptop, 9), relay, seed = 13)
        bob.join(); laptop.join(); phone.join()
        advanceTimeBy(2_000); runCurrent()

        bob.setCall(CallMembership(CALL, seconds()))
        advanceTimeBy(500); runCurrent()
        phone.setCall(CallMembership(CALL, seconds()))
        advanceTimeBy(100); runCurrent()

        val answer = bellsFrom(relay, room, phone.identity.devicePubkey).single()
        assertEquals(CallBellState.ANSWER, answer.state)
        assertEquals(CALL, answer.call.id)
        // The laptop, waiting with the app closed, stops ringing on this.
        assertEquals(aliceLaptop.participant, answer.participant)
    }

    @Test
    fun `joining a call long past ringing rings nothing`() = runTest {
        val room = Fixtures.room()
        val relay = FakeRelay()
        val bob = session(room, Fixtures.primary(room, 3, 4), relay, seed = 11)
        val alice = session(room, Fixtures.primary(room, 1, 2), relay)
        bob.join(); alice.join()
        advanceTimeBy(200_000); runCurrent()

        bob.setCall(CallMembership(CALL, seconds() - CALL_BELL_TTL_SECONDS - 1))
        advanceTimeBy(500); runCurrent()
        alice.setCall(CallMembership(CALL, seconds()))
        advanceTimeBy(100); runCurrent()

        assertEquals(emptyList(), bellsFrom(relay, room, alice.identity.devicePubkey))
    }

    @Test
    fun `joining a call that began just inside the ring time still answers`() = runTest {
        val room = Fixtures.room()
        val relay = FakeRelay()
        val bob = session(room, Fixtures.primary(room, 3, 4), relay, seed = 11)
        val alice = session(room, Fixtures.primary(room, 1, 2), relay)
        bob.join(); alice.join()
        advanceTimeBy(200_000); runCurrent()

        // A second to spare, which the half second below cannot use up.
        bob.setCall(CallMembership(CALL, seconds() - CALL_BELL_TTL_SECONDS + 1))
        advanceTimeBy(500); runCurrent()
        alice.setCall(CallMembership(CALL, seconds()))
        advanceTimeBy(100); runCurrent()

        assertEquals(listOf(CallBellState.ANSWER), bellsFrom(relay, room, alice.identity.devicePubkey).map { it.state })
    }

    @Test
    fun `recovering membership of a call somebody else is on answers nothing`() = runTest {
        val room = Fixtures.room()
        val relay = FakeRelay()
        val bob = session(room, Fixtures.primary(room, 3, 4), relay, seed = 11)
        val alice = session(room, Fixtures.primary(room, 1, 2), relay)
        bob.join(); alice.join()
        advanceTimeBy(2_000); runCurrent()

        bob.setCall(CallMembership(CALL, seconds()))
        advanceTimeBy(500); runCurrent()
        // Already on it before the rebuild; nobody is ringing for this.
        alice.restoreCall(CallMembership(CALL, seconds()))
        advanceTimeBy(100); runCurrent()

        assertEquals(emptyList(), bellsFrom(relay, room, alice.identity.devicePubkey))
    }
}
