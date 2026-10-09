package dev.forgesworn.kithmoot.ui.room

import dev.forgesworn.kithmoot.session.PendingChatState
import kotlin.test.*

class NearbyPendingStatusTest {
    @Test fun `nearby handoff never promises delivery or a relay acknowledgement`() {
        val status = pendingStatus(PendingChatState.UNKNOWN, internetAllowed = false)
        assertTrue(status.contains("Delivery is not confirmed"))
        assertTrue(status.contains("same message"))
        assertFalse(status.contains("relay", ignoreCase = true))
        assertFalse(pendingStatus(PendingChatState.REFUSED, internetAllowed = false).contains("relay"))
    }
}
