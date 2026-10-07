package dev.forgesworn.kithmoot.storage

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RoomWipeTest {
    private val target = RoomWipeTarget("a".repeat(64), "b".repeat(64), "c".repeat(64))

    @Test fun `every store forgetRoom misses is a step, and the saved room goes last`() {
        // What forgetting a room by hand never cleared (the background service's
        // stores, the tray, the NIP-77 archives, how a call rings) is now named.
        assertTrue(RoomWipeStep.entries.containsAll(listOf(
            RoomWipeStep.BACKGROUND_INBOX, RoomWipeStep.PARTICIPANT_CACHE, RoomWipeStep.NOTIFICATIONS,
            RoomWipeStep.NIP77_OFFERS, RoomWipeStep.NIP77_INDEX, RoomWipeStep.CALL_RING_SETTING,
        )))
        // And what it always cleared.
        assertTrue(RoomWipeStep.entries.containsAll(listOf(
            RoomWipeStep.PENDING_OUTBOX, RoomWipeStep.ASSIGNMENTS, RoomWipeStep.EPOCHS, RoomWipeStep.MEMBERS, RoomWipeStep.SAVED_ROOM,
        )))
        assertEquals(RoomWipeStep.SAVED_ROOM, RoomWipeStep.entries.last())
    }

    @Test fun `a wipe that does not clear every store refuses to be built`() {
        val noop: suspend (RoomWipeTarget) -> Unit = {}
        val all = RoomWipeStep.entries.associateWith { noop }
        RoomWipe(all)
        for (step in RoomWipeStep.entries) {
            val error = assertFailsWith<IllegalArgumentException> { RoomWipe(all - step) }
            assertTrue(error.message!!.contains(step.name))
        }
    }

    @Test fun `every step runs in order, for the room named, and one failing stops none of the others`() = runTest {
        val ran = mutableListOf<RoomWipeStep>()
        val wipe = RoomWipe(RoomWipeStep.entries.associateWith { step ->
            val clear: suspend (RoomWipeTarget) -> Unit = { t ->
                assertEquals(target.roomId, t.roomId)
                ran += step
                if (step == RoomWipeStep.NIP77_OFFERS || step == RoomWipeStep.EPOCHS) error("store unreadable")
            }
            clear
        })
        assertEquals(listOf(RoomWipeStep.NIP77_OFFERS, RoomWipeStep.EPOCHS), wipe.run(target))
        assertEquals(RoomWipeStep.entries.toList(), ran.toList())
        ran.clear()
        assertEquals(emptyList<RoomWipeStep>(), wipe.run(target, only = setOf(RoomWipeStep.NOTIFICATIONS, RoomWipeStep.BACKGROUND_INBOX)))
        assertEquals(listOf(RoomWipeStep.BACKGROUND_INBOX, RoomWipeStep.NOTIFICATIONS), ran)
    }
}
