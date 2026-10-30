package dev.forgesworn.kithmoot.ui.settings

import dev.forgesworn.kithmoot.callrelay.CANNOT_HOST_TEXT
import dev.forgesworn.kithmoot.callrelay.RelayGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Developer page's previews never ship (settings spec, 11.5 and acceptance 33), and cover every state. */
class CallRelayPreviewTest {
    @Test fun `a build that is not a debug build lists no preview`() {
        assertTrue(CallRelayPreviews.entries(debug = false).isEmpty())
    }

    @Test fun `a debug build lists every state of the spec once`() {
        val ids = CallRelayPreviews.entries(debug = true).map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (id in listOf("S1", "S3", "S4", "S5", "S7x", "S8a", "S8b", "S8c", "S8d", "S8e", "S8h", "S9", "S10a", "S10b", "S10c", "S10d", "S11", "S12", "S14"))
            assertTrue(id, id in ids)
        for (code in CANNOT_HOST_TEXT.keys) assertTrue("S7:$code" in ids)
        for (gate in RelayGate.entries) if (gate != RelayGate.DAILY_LIMIT) assertTrue("AP:${gate.name}" in ids)
    }

    @Test fun `each preview reads as the state it is named for`() {
        val now = 1_700_000_000_000L
        val pages = CallRelayPreviews.entries(debug = true).associate { it.id to CallRelayWords.page(it.facts(now)) }
        assertEquals("Off", pages.getValue("S1").headline)
        assertEquals("Starting…", pages.getValue("S3").headline)
        assertEquals("Running on this phone", pages.getValue("S4").headline)
        assertEquals("On · in use now", pages.getValue("S5").rowSummary)
        assertEquals("Running, waiting for your Nostr relays", pages.getValue("S14").headline)
        assertEquals("Paused until midnight", pages.getValue("S9").rowSummary)
        assertEquals("Paused · not charging", pages.getValue("AP:NOT_CHARGING").rowSummary)
        assertEquals("Not used: you have Spry", pages.getValue("S10a").rowSummary)
        assertTrue(pages.getValue("S8h").offerRemoveKeys)
        assertTrue(pages.getValue("S4L").useTitle!!.startsWith("Last used "))
    }
}
