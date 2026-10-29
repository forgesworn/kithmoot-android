package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.KIND_ROSTER
import dev.forgesworn.kithmoot.protocol.SpryKey
import dev.forgesworn.kithmoot.protocol.decodeRosterEvent
import dev.forgesworn.kithmoot.support.FakeRelay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** What a paired member's Spry registrar reads from a room. */
@OptIn(ExperimentalCoroutinesApi::class)
class SpryRosterSessionTest {

    @Test
    fun `every announcement carries this device's room Spry key`() = runTest {
        val room = Fixtures.room()
        val relay = FakeRelay()
        val identity = Fixtures.primary(room, 1, 2)
        val alice = session(room, identity, relay)
        alice.join()
        advanceTimeBy(100); runCurrent()
        val entries = relay.published.filter { it.kind == KIND_ROSTER }.mapNotNull { decodeRosterEvent(it, room.roomId, room.roomKey, now = 10_000) }
        val expected = SpryKey.publicKeyHex(Fixtures.key(2))
        assertEquals(expected, alice.spryPublicKey)
        assertEquals(setOf(expected), entries.map { it.spryKey }.toSet())
    }

    @Test
    fun `the Spry roster names every present device with its key, this one included`() = runTest {
        // Anybody this phone blocked is here too: a block lives in the view
        // model, never in the room's roster, so it cannot cost them the relay.
        val room = Fixtures.room()
        val relay = FakeRelay()
        val alice = session(room, Fixtures.primary(room, 1, 2), relay)
        val bob = session(room, Fixtures.primary(room, 3, 4), relay)
        alice.join(); bob.join()
        advanceTimeBy(5_000); runCurrent()
        val rows = alice.spryRoster().associateBy { it.participant }
        assertEquals(setOf(alice.identity.participant, bob.identity.participant), rows.keys)
        assertEquals(SpryKey.publicKeyHex(Fixtures.key(4)), rows.getValue(bob.identity.participant).spryKey)
        assertEquals(alice.spryPublicKey, rows.getValue(alice.identity.participant).spryKey)
        assertEquals(false, rows.getValue(bob.identity.participant).agent)
        assertEquals(emptySet(), alice.removedParticipants())
        alice.leave(); bob.leave()
        advanceTimeBy(1_000); runCurrent()
    }
}
