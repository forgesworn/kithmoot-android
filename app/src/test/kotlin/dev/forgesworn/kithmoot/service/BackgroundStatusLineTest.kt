package dev.forgesworn.kithmoot.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the notification settings say about background delivery, and when a switch may read on. */
class BackgroundStatusLineTest {
    @Test fun `switch off says nothing, whatever else is true`() {
        for (allowed in listOf(true, false)) for (rooms in listOf(0, 3)) for (state in DeliveryState.entries) {
            assertNull(backgroundStatusLine(false, allowed, rooms, state))
        }
    }

    @Test fun `blocked notifications come before everything else`() {
        for (rooms in listOf(0, 3)) for (state in DeliveryState.entries) {
            assertEquals("Paused until notifications are allowed", backgroundStatusLine(true, false, rooms, state))
        }
    }

    @Test fun `no saved rooms is nothing to receive, not off`() {
        for (state in DeliveryState.entries) {
            assertEquals("Nothing to receive yet: you have no saved rooms", backgroundStatusLine(true, true, 0, state))
        }
    }

    @Test fun `otherwise the delivery state's own label follows Now`() {
        for (state in DeliveryState.entries) {
            assertEquals("Now: ${state.label}", backgroundStatusLine(true, true, 2, state))
        }
        assertEquals("Now: Receiving", backgroundStatusLine(true, true, 1, DeliveryState.LIVE))
        assertEquals("Now: Restricted by Android", backgroundStatusLine(true, true, 1, DeliveryState.RESTRICTED))
    }

    @Test fun `a switch reads on only when saved and allowed`() {
        assertTrue(effectivelyOn(saved = true, notificationsAllowed = true))
        assertFalse(effectivelyOn(saved = true, notificationsAllowed = false))
        assertFalse(effectivelyOn(saved = false, notificationsAllowed = true))
        assertFalse(effectivelyOn(saved = false, notificationsAllowed = false))
    }
}
