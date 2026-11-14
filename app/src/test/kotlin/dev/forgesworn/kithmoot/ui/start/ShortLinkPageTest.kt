package dev.forgesworn.kithmoot.ui.start

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortLinkPageTest {
    @Test fun aShortLinkPageLinkIsKeptOnTheJoinPageBesideIt() {
        assertEquals("https://kithmoot.app/j/#BwABAg", onJoinPage("https://kithmoot.app/n/#BwABAg"))
        assertEquals("https://kithmoot.app/j/#BwABAg", onJoinPage("https://kithmoot.app/n#BwABAg"))
        assertEquals("https://host.example/sub/j/?relay=1#BwABAg", onJoinPage("https://host.example/sub/n/?relay=1#BwABAg"))
    }

    @Test fun anyOtherLinkComesBackUnchanged() {
        for (link in listOf(
            "https://kithmoot.app/j/#BwABAg",
            "https://kithmoot.app/news/#BwABAg",
            "http://kithmoot.app/n/#BwABAg",
            "kithmoot://join#BwABAg",
            "not a link",
        )) assertEquals(link, onJoinPage(link))
    }

    @Test fun theScannerTakesTheJoinPageAndTheShortLinkPage() {
        assertTrue(looksLikeKithMootInvitation("https://kithmoot.app/j/#BwABAg"))
        assertTrue(looksLikeKithMootInvitation("https://kithmoot.app/n/#BwABAg"))
        assertTrue(looksLikeKithMootInvitation("kithmoot://join#BwABAg"))
        assertFalse(looksLikeKithMootInvitation("https://kithmoot.app/news/#BwABAg"))
        assertFalse(looksLikeKithMootInvitation("https://example.com/"))
    }
}
