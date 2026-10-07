package dev.forgesworn.kithmoot.ui.settings

import dev.forgesworn.kithmoot.notifications.notificationsAttention
import dev.forgesworn.kithmoot.relay.RelayHealth
import dev.forgesworn.kithmoot.ui.AccountView
import dev.forgesworn.kithmoot.update.AppUpdates.State
import dev.forgesworn.kithmoot.update.InstalledFrom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The words on Settings' root list, and how its pages nest. */
class SettingsSummariesTest {
    private fun account(method: String, signer: String? = null) = AccountView("p", "npub1p", "npub1…p", method, signer)

    @Test fun `back from a page goes to its parent and the list leaves`() {
        assertNull(SettingsPage.ROOT.parent)
        assertEquals(SettingsPage.ACCOUNT, SettingsPage.PROFILE.parent)
        assertEquals(SettingsPage.CONNECTIONS, SettingsPage.RELAYS.parent)
        assertEquals(SettingsPage.CONNECTIONS, SettingsPage.DM_RELAYS.parent)
        for (page in listOf(SettingsPage.ACCOUNT, SettingsPage.NOTIFICATIONS, SettingsPage.DISPLAY, SettingsPage.PRIVACY,
            SettingsPage.CONNECTIONS, SettingsPage.UPDATES, SettingsPage.DEVELOPER)) assertEquals(SettingsPage.ROOT, page.parent)
    }

    @Test fun `a third level page is selected under its root row`() {
        assertEquals(SettingsPage.ACCOUNT, SettingsPage.PROFILE.section)
        assertEquals(SettingsPage.CONNECTIONS, SettingsPage.RELAYS.section)
        assertEquals(SettingsPage.DISPLAY, SettingsPage.DISPLAY.section)
    }

    @Test fun `account row says the sync problem first, then waiting changes, then who signs`() {
        val me = account("nip55", "Amber")
        assertEquals("Room sync failed. Open to retry", accountSummary(me, "no relay", 3))
        assertEquals("1 change waiting to sync", accountSummary(me, null, 1))
        assertEquals("2 changes waiting to sync", accountSummary(me, null, 2))
        assertEquals("Signed in with Amber (signer app)", accountSummary(me, null, 0))
        assertEquals("Signed in through your remote signer (bunker)", accountSummary(account("bunker"), null, 0))
        assertEquals("Signed in with a key kept on this phone (local)", accountSummary(account("local"), null, 0))
    }

    @Test fun `relay problems are counted and worded for one and many`() {
        val health = listOf(RelayHealth("Reconnecting"), RelayHealth("Connected", read = "History read confirmed"),
            RelayHealth("Connected", write = "No acknowledgement"), RelayHealth("Connected", read = "Authentication required"))
        assertEquals(3, relayIssueCount(health))
        assertEquals("1 relay needs attention", relaysNeedAttention(1))
        assertEquals("3 relays need attention", relaysNeedAttention(3))
        assertEquals("Relays and site address", connectionsSummary(0))
        assertEquals("2 relays need attention", connectionsSummary(2))
    }

    @Test fun `privacy row reflects the switch`() {
        assertEquals("Profile names and pictures shown", privacySummary(true))
        assertEquals("Profile names and pictures hidden", privacySummary(false))
    }

    @Test fun `updates row follows the last check`() {
        val direct = InstalledFrom.DIRECT
        assertEquals("Version 0.6.62", updatesSummary("0.6.62", State.Idle, direct))
        assertEquals("Version 0.6.62", updatesSummary("0.6.62", State.Checking, direct))
        assertEquals("Version 0.6.62 · Up to date", updatesSummary("0.6.62", State.Current, direct))
        assertEquals("Version 0.6.62 · 0.7.0 available", updatesSummary("0.6.62", State.Available("0.7.0", false), direct))
        assertEquals("Version 0.6.62 · Downloading 0.7.0", updatesSummary("0.6.62", State.Downloading("0.7.0", 0.4f), direct))
        assertEquals("Version 0.6.62 · 0.7.0 ready to install", updatesSummary("0.6.62", State.Ready("0.7.0"), direct))
        assertEquals("Version 0.6.62 · Update failed", updatesSummary("0.6.62", State.Failed("no"), direct))
        assertEquals("Version 0.6.62 · Updates come through Zapstore", updatesSummary("0.6.62", State.Idle, InstalledFrom.ZAPSTORE))
        assertEquals("Version 0.6.62 · Up to date", updatesSummary("0.6.62", State.Current, InstalledFrom.ZAPSTORE))
    }

    @Test fun `notifications row names the worst problem first`() {
        assertEquals("Needs attention: notifications are blocked", notificationsAttention(false, true, true, true))
        assertEquals("Needs attention: calls won't ring while KithMoot is closed", notificationsAttention(true, true, true, true))
        assertEquals("Needs attention: you can't answer calls yet", notificationsAttention(true, false, true, true))
        assertEquals("Needs attention: Android may stop calls ringing", notificationsAttention(true, false, false, true))
        assertNull(notificationsAttention(true, false, false, false))
    }
}
