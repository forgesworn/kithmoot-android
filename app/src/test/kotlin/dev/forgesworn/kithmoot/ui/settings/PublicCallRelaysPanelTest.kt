package dev.forgesworn.kithmoot.ui.settings

import dev.forgesworn.kithmoot.media.Adverts
import dev.forgesworn.kithmoot.media.MemoryStorage
import dev.forgesworn.kithmoot.media.PublicCallRelayMode
import dev.forgesworn.kithmoot.media.PublicCallRelayStore
import dev.forgesworn.kithmoot.media.PublicCallRelays
import dev.forgesworn.kithmoot.media.PublicRelayLoginResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PublicCallRelaysPanelTest {
    private class Harness(scope: TestScope, var anonymous: Boolean = false, var outranked: Boolean = false, var inCall: Boolean = false,
                          var checkPasses: Boolean = true) {
        val storage = MemoryStorage()
        var searches = 0
        var fetches = 0
        var checks = 0
        val relays = PublicCallRelays(PublicCallRelayStore(storage), { fetches++; Adverts.issued(it, Adverts.BASE + 3700) },
            { Adverts.BASE + 60 }, StandardTestDispatcher(scope.testScheduler))
        val panel = PublicCallRelaysPanel(relays, scope, { searches++; listOf(Adverts.signed(1), Adverts.signed(2)) },
            outranked = { outranked }, anonymous = { anonymous }, inCall = { inCall }, check = { checks++; checkPasses })
    }

    @Test fun theSwitchIsOffByDefaultAndNothingRuns() = runTest {
        val h = Harness(this)
        h.panel.opened(); advanceUntilIdle()
        assertFalse(h.panel.view.value.switchOn)
        assertTrue(h.panel.view.value.switchEnabled)
        assertEquals(0, h.searches)
    }

    @Test fun turningItOnAsksOnceAndListsWhatWasFound() = runTest {
        val h = Harness(this)
        h.panel.setEnabled(true); advanceUntilIdle()
        assertEquals(1, h.searches)
        assertEquals(2, h.panel.view.value.listing.size)
        assertEquals(PublicCallRelaysCopy.found(2), h.panel.view.value.status)
        // Reopening within a minute does not ask again.
        h.panel.opened(); advanceUntilIdle()
        assertEquals(1, h.searches)
    }

    @Test fun anonymousModeShowsTheSwitchOffAndDisabledAndRunsNothing() = runTest {
        val h = Harness(this)
        h.relays.setEnabled(true)
        h.anonymous = true
        h.panel.opened(); advanceUntilIdle()
        val view = h.panel.view.value
        assertFalse(view.switchOn)
        assertFalse(view.switchEnabled)
        assertFalse(view.pickerShown)
        h.panel.lookAgain(); h.panel.checkSelected(); h.panel.setEnabled(true); advanceUntilIdle()
        assertEquals(0, h.searches)
        assertEquals(0, h.fetches)
        assertEquals(0, h.checks)
    }

    @Test fun aPairingOrHandEnteredRelayMeansNoQueryOrLogin() = runTest {
        val h = Harness(this, outranked = true)
        h.panel.setEnabled(true); advanceUntilIdle()
        assertTrue(h.panel.view.value.switchOn)
        assertFalse(h.panel.view.value.pickerShown)
        h.panel.lookAgain(); h.panel.checkSelected(); advanceUntilIdle()
        assertEquals(0, h.searches)
        assertEquals(0, h.fetches)
    }

    @Test fun noQueryOrCheckWhileOnACall() = runTest {
        val h = Harness(this)
        h.panel.setEnabled(true); advanceUntilIdle()
        h.panel.select(h.panel.view.value.listing.first().key)
        h.inCall = true
        h.panel.lookAgain(); h.panel.checkSelected(); advanceUntilIdle()
        assertEquals(1, h.searches)
        assertEquals(0, h.fetches)
        assertFalse(h.panel.view.value.canCheck)
    }

    @Test fun aPassedCheckRemembersTheRelayAndAFailedOneSaysNoRawError() = runTest {
        val h = Harness(this)
        h.panel.setEnabled(true); advanceUntilIdle()
        val key = h.panel.view.value.listing.first().key
        h.panel.select(key)
        h.checkPasses = false
        h.panel.checkSelected(); advanceUntilIdle()
        assertEquals(PublicCallRelaysCopy.FAILED, h.panel.view.value.status)
        assertNull(h.relays.lastGood)
        h.checkPasses = true
        h.panel.checkSelected(); advanceUntilIdle()
        assertEquals(PublicCallRelaysCopy.PASSED, h.panel.view.value.status)
        assertEquals(key, h.relays.lastGood?.key)
        // The second check reused the first check's login.
        assertEquals(1, h.fetches)
    }

    @Test fun turningItOffClearsTheFile() = runTest {
        val h = Harness(this)
        h.panel.setEnabled(true); advanceUntilIdle()
        assertTrue(h.storage.bytes != null)
        h.panel.setEnabled(false); advanceUntilIdle()
        assertNull(h.storage.bytes)
        assertEquals(PublicCallRelayMode.OFF, h.panel.view.value.mode)
        assertEquals(PublicCallRelaysCopy.TURNED_OFF, h.panel.view.value.status)
    }

    @Test fun theOperatorsWordsAreLabelledAndIsolated() {
        val entry = dev.forgesworn.kithmoot.media.PublicRelayListing("a".repeat(64), "aaaaaaaa…aaaa", "relay.example.org", "GB", "Hi", true, false)
        assertEquals("The operator says: ⁨region GB · Hi⁩", PublicCallRelaysCopy.claim(entry))
        assertEquals("Key aaaaaaaa…aaaa · last worked on this device", PublicCallRelaysCopy.key(entry))
        assertNull(PublicCallRelaysCopy.claim(entry.copy(region = null, note = null)))
    }
}
