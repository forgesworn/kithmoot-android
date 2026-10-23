package dev.forgesworn.kithmoot.ui.room

import dev.forgesworn.kithmoot.storage.AdmissionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The invite sheet said "can walk in" for every link. A Mac asking to join
 * with a link this phone could no longer answer for waited for ever, and the
 * sheet had promised it would just walk in.
 */
class InviteNoteTest {
    @Test fun `a persistent link walks in`() {
        assertEquals(INVITE_NOTE_WALK_IN, inviteNote(AdmissionStatus.Own, persistent = true))
    }

    @Test fun `a temporary link this phone answers for asks, and says to keep the room open`() {
        assertEquals(INVITE_NOTE_ASK, inviteNote(AdmissionStatus.Own, persistent = false))
        assertEquals(INVITE_NOTE_ASK, inviteNote(AdmissionStatus.Delegated(until = 2_000_000_000), persistent = false))
        assertTrue("Keep the room open here" in INVITE_NOTE_ASK && "walk in" !in INVITE_NOTE_ASK)
    }

    @Test fun `a link this phone can no longer answer for says so, and who has to`() {
        val lapsed = inviteNote(AdmissionStatus.Unavailable("delegation expired at 1"), persistent = false)
        assertTrue("can no longer let them in" in lapsed && "has run out" in lapsed && "Only the device that made it can answer" in lapsed)
        val elsewhere = inviteNote(AdmissionStatus.Unavailable("no invitation key on this device"), persistent = false)
        assertTrue("made on another device" in elsewhere)
    }
}
