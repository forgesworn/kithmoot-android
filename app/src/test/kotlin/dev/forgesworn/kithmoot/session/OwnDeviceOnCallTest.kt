package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.CallMembership
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.RosterEntry
import dev.forgesworn.kithmoot.support.FakeRelay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A person with a laptop on a call who picks it up on their phone as well.
 * The complaint: the phone took the microphone and the sound off the laptop,
 * and every other device of theirs kept ringing. The phone now joins quietly,
 * and none of their devices rings for a call one of them is on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OwnDeviceOnCallTest {

    private val credential = NostrEvent(20460, 0, emptyList(), "", "00".repeat(32), "00".repeat(32), "00".repeat(64))
    private val CALL = "c0ffeec0ffeec0ffeec0ffeec0ffeec0"
    private val OTHER = "1234567890abcdef1234567890abcdef"

    private fun entry(participant: String, device: String, updatedAt: Long, call: CallMembership? = null) =
        RosterEntry(participant, device, credential, updatedAt = updatedAt, call = call)

    private fun people(vararg entries: RosterEntry) = groupByParticipant(entries.toList())

    @Test
    fun `joining a call your laptop is already on is joining beside it`() {
        val room = people(entry("alice", "laptop", 10, CallMembership(CALL, 100)), entry("alice", "phone", 20))
        assertTrue(ownDeviceElsewhereOnCall(room, "alice", "phone", CALL))
    }

    @Test
    fun `it stays beside once the phone has said it is on the call too`() {
        val room = people(
            entry("alice", "laptop", 10, CallMembership(CALL, 100)),
            entry("alice", "phone", 20, CallMembership(CALL, 200)),
        )
        assertTrue(ownDeviceElsewhereOnCall(room, "alice", "phone", CALL))
        // And from the laptop's side, so its ring logic agrees.
        assertTrue(ownDeviceElsewhereOnCall(room, "alice", "laptop", CALL))
    }

    @Test
    fun `somebody else on the call is not one of your devices`() {
        val room = people(entry("bob", "bobs-laptop", 10, CallMembership(CALL, 100)), entry("alice", "phone", 20))
        assertFalse(ownDeviceElsewhereOnCall(room, "alice", "phone", CALL))
    }

    @Test
    fun `your laptop on a different call does not make this one quiet`() {
        val room = people(entry("alice", "laptop", 10, CallMembership(OTHER, 100)), entry("alice", "phone", 20))
        assertFalse(ownDeviceElsewhereOnCall(room, "alice", "phone", CALL))
    }

    @Test
    fun `alone on a call, or on none, is not beside anything`() {
        val alone = people(entry("alice", "phone", 20, CallMembership(CALL, 200)), entry("alice", "laptop", 10))
        assertFalse(ownDeviceElsewhereOnCall(alone, "alice", "phone", CALL))
        assertFalse(ownDeviceElsewhereOnCall(alone, "alice", "laptop", null))
    }

    @Test
    fun `a weak speaker claim never takes the sound from your device that holds it`() = runTest {
        val room = Fixtures.room()
        val relay = FakeRelay()
        val aliceLaptop = Fixtures.primary(room, 1, 2)
        val laptop = session(room, aliceLaptop, relay)
        val phone = session(room, Fixtures.secondary(room, aliceLaptop, 9), relay, seed = 13)
        laptop.join(); phone.join()
        advanceTimeBy(2_000); runCurrent()
        laptop.claim(Roles.MONITOR)
        advanceTimeBy(1_000); runCurrent()

        // Later than the laptop's claim, and still loses to it.
        phone.claimWeakly(Roles.MONITOR)
        advanceTimeBy(500); runCurrent()
        assertEquals(laptop.identity.devicePubkey, phone.localRoles.value.monitorDevice)
        assertTrue(laptop.localRoles.value.holdsMonitor)

        // Only once the laptop lets go does the sound come to the phone.
        laptop.release(Roles.MONITOR)
        advanceTimeBy(500); runCurrent()
        assertTrue(phone.localRoles.value.holdsMonitor)
    }

    @Test
    fun `a weak claim does not undo a real one this device already holds`() = runTest {
        val room = Fixtures.room()
        val relay = FakeRelay()
        val aliceLaptop = Fixtures.primary(room, 1, 2)
        val laptop = session(room, aliceLaptop, relay)
        val phone = session(room, Fixtures.secondary(room, aliceLaptop, 9), relay, seed = 13)
        laptop.join(); phone.join()
        advanceTimeBy(2_000); runCurrent()
        laptop.claim(Roles.MONITOR)
        advanceTimeBy(1_000); runCurrent()
        // Listen here, then a join path asking only weakly.
        phone.claim(Roles.MONITOR)
        phone.claimWeakly(Roles.MONITOR)
        advanceTimeBy(500); runCurrent()

        assertTrue(phone.localRoles.value.holdsMonitor)
        assertEquals(phone.identity.devicePubkey, laptop.localRoles.value.monitorDevice)
    }
}
