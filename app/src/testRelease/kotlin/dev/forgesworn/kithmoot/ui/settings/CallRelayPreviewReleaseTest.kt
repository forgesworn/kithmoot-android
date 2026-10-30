package dev.forgesworn.kithmoot.ui.settings

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A release build has no call relay screen preview, whatever it is asked (settings spec, acceptance 33). */
class CallRelayPreviewReleaseTest {
    @Test fun `no preview is listed or pinned`() {
        assertTrue(CallRelayPreviews.entries(debug = true).isEmpty())
        assertNull(CallRelayPreviews.facts("S4", 0))
    }
}
