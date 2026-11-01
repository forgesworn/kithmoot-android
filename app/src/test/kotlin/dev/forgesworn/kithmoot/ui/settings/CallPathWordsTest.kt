package dev.forgesworn.kithmoot.ui.settings

import dev.forgesworn.kithmoot.media.PublicCallRelayMode
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins every row of design-connections-calls sections 6.1 and 6.2 to its 9.x string, for the phone.
 * The web's call-path-words.ts carries the same table; the tests there and here use the same inputs.
 */
class CallPathWordsTest {
    private val zone = ZoneId.of("Europe/London")
    private val noon = ZonedDateTime.of(2026, 10, 31, 15, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val at1402 = ZonedDateTime.of(2026, 10, 31, 14, 2, 0, 0, zone).toInstant().toEpochMilli()
    private fun view(f: CallPathFacts) = CallPathWords.view(f.copy(nowMs = noon, zone = zone))

    private val direct = "Calls connect device to device. Some networks block that, so a call may not connect. Set up a relay below to carry those calls."

    @Test fun `CS1 nothing set up is direct only`() {
        val v = view(CallPathFacts())
        assertEquals(CallWinner.NONE, v.winner)
        assertEquals("Direct only", v.head)
        assertEquals(direct, v.body)
        assertEquals("Direct only", v.rootSub)
        assertNull(v.spryFailed)
        assertEquals("Not paired", v.spryRow)
        assertEquals("Not set up", v.serverRow)
        assertEquals("Off", v.publicRow)
        assertEquals("STUN server: Google, Cloudflare", v.advancedRow)
    }

    @Test fun `CS2 names the first ready relay of your own`() {
        fun check(f: CallPathFacts, name: String) {
            val v = view(f)
            assertEquals("Direct, or through $name", v.head)
            assertEquals("Calls connect device to device when they can. When they can't, they go through $name.", v.body)
            assertEquals(v.head, v.rootSub)
        }
        check(CallPathFacts(builtInRunning = true), "this phone's relay")
        check(CallPathFacts(spry = StoredKind.SAVED), "your Spry node")
        check(CallPathFacts(server = StoredKind.SAVED, serverHost = "relay.example.org"), "your relay server")
    }

    @Test fun `the order is relay server, then Spry, then this phone's relay, then public`() {
        val everything = CallPathFacts(server = StoredKind.SAVED, spry = StoredKind.SAVED, builtInRunning = true,
            publicMode = PublicCallRelayMode.ON, publicChosen = true)
        assertEquals(CallWinner.SERVER, view(everything).winner)
        assertEquals(CallWinner.SPRY, view(everything.copy(server = StoredKind.NONE)).winner)
        assertEquals(CallWinner.BUILT_IN, view(everything.copy(server = StoredKind.NONE, spry = StoredKind.NONE)).winner)
        assertEquals(CallWinner.PUBLIC, view(everything.copy(server = StoredKind.NONE, spry = StoredKind.NONE, builtInRunning = false)).winner)
    }

    @Test fun `an unreadable server or pairing is not a winner`() {
        assertEquals(CallWinner.NONE, view(CallPathFacts(server = StoredKind.UNREADABLE)).winner)
        assertEquals(CallWinner.NONE, view(CallPathFacts(spry = StoredKind.UNREADABLE)).winner)
    }

    @Test fun `CS3 a chosen public relay`() {
        val v = view(CallPathFacts(publicMode = PublicCallRelayMode.ON, publicChosen = true))
        assertEquals(CallWinner.PUBLIC, v.winner)
        assertEquals("Direct, or through a public relay", v.head)
        assertEquals("Calls connect device to device when they can. When they can't, they go through the public call relay you chose, unless the room brings its own.", v.body)
        assertEquals("Direct, or through a public relay", v.rootSub)
    }

    @Test fun `public relays on but none chosen, or not allowed, are not a winner`() {
        assertEquals(CallWinner.NONE, view(CallPathFacts(publicMode = PublicCallRelayMode.ON)).winner)
        assertEquals(CallWinner.NONE, view(CallPathFacts(publicMode = PublicCallRelayMode.ON, publicChosen = true, publicAnonymous = true)).winner)
        assertEquals(CallWinner.NONE, view(CallPathFacts(publicMode = PublicCallRelayMode.ON, publicChosen = true, publicOutranked = true)).winner)
    }

    @Test fun `CS4 Hide my IP with a relay`() {
        val v = view(CallPathFacts(builtInRunning = true, hideIp = true))
        assertEquals("Always through this phone's relay", v.head)
        assertEquals("Hide my IP address is on, so calls never connect directly. People on the call don't see your IP address.", v.body)
        assertEquals("Always through this phone's relay", v.rootSub)
        assertEquals("Always through your relay server", view(CallPathFacts(server = StoredKind.SAVED, hideIp = true)).head)
    }

    @Test fun `CS5 Hide my IP with no relay`() {
        val v = view(CallPathFacts(hideIp = true))
        assertEquals("No relay for calls", v.head)
        assertEquals("Hide my IP address is on, so a call can only start in a room that brings its own relay. Set up a relay below, or turn off Hide my IP address.", v.body)
        assertEquals("Hide my IP is on · no relay", v.rootSub)
    }

    @Test fun `CS6 adds a third line only when Spry is the winner and failed`() {
        val failed = CallPathFacts(spry = StoredKind.SAVED, spryFailedAt = at1402)
        val v = view(failed)
        assertEquals("Your Spry node didn't answer at 14:02, so that call had no relay. Check that Spry is running on your node.", v.spryFailed)
        assertEquals("Direct, or through your Spry node", v.head)
        assertEquals("Paired · no answer at 14:02", v.spryRow)
        assertEquals("Your Spry node didn't answer at 14:02, so that call couldn't start. Check that Spry is running on your node.",
            view(failed.copy(hideIp = true)).spryFailed)
        assertNull(view(failed.copy(server = StoredKind.SAVED)).spryFailed)
        assertNull(view(CallPathFacts(spry = StoredKind.SAVED)).spryFailed)
    }

    @Test fun `a failure from yesterday says so`() {
        val yesterday = ZonedDateTime.of(2026, 10, 30, 23, 58, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("Paired · no answer yesterday at 23:58", view(CallPathFacts(spry = StoredKind.SAVED, spryFailedAt = yesterday)).spryRow)
    }

    @Test fun `Spry row second lines`() {
        assertEquals("Not paired", view(CallPathFacts()).spryRow)
        assertEquals("Paired", view(CallPathFacts(spry = StoredKind.SAVED)).spryRow)
        assertEquals("Paired · your relay server comes first", view(CallPathFacts(spry = StoredKind.SAVED, server = StoredKind.SAVED)).spryRow)
        assertEquals("Can't be read · pair again", view(CallPathFacts(spry = StoredKind.UNREADABLE)).spryRow)
    }

    @Test fun `Relay server row second lines`() {
        assertEquals("Not set up", view(CallPathFacts()).serverRow)
        assertEquals("Set up · relay.example.org", view(CallPathFacts(server = StoredKind.SAVED, serverHost = "relay.example.org")).serverRow)
        assertEquals("Can't be read · save it again", view(CallPathFacts(server = StoredKind.UNREADABLE)).serverRow)
    }

    @Test fun `Public call relays row second lines`() {
        val on = CallPathFacts(publicMode = PublicCallRelayMode.ON)
        assertEquals("Off", view(CallPathFacts()).publicRow)
        assertEquals("On · not used: you have your own relay", view(on.copy(publicOutranked = true)).publicRow)
        assertEquals("On · none chosen", view(on).publicRow)
        assertEquals("On · one chosen", view(on.copy(publicChosen = true)).publicRow)
        assertEquals("Not available in anonymous mode", view(on.copy(publicAnonymous = true)).publicRow)
        assertEquals("Can't be read", view(CallPathFacts(publicMode = PublicCallRelayMode.UNAVAILABLE)).publicRow)
    }

    @Test fun `Advanced row second lines`() {
        assertEquals("STUN server: Google, Cloudflare", view(CallPathFacts()).advancedRow)
        assertEquals("STUN server: stun.example.org", view(CallPathFacts(stunHost = "stun.example.org")).advancedRow)
        assertEquals("Can't be read · save it again", view(CallPathFacts(stunUnreadable = true)).advancedRow)
    }

    @Test fun `the host of an address`() {
        assertEquals("relay.example.org", CallPathWords.hostOf("turn:relay.example.org:3478"))
        assertEquals("relay.example.org", CallPathWords.hostOf("turns:relay.example.org?transport=tcp"))
        assertEquals("stun.example.org", CallPathWords.hostOf("stun:stun.example.org:3478"))
        assertEquals("[2001:db8::1]", CallPathWords.hostOf("turn:[2001:db8::1]:3478"))
        assertNull(CallPathWords.hostOf("nonsense"))
    }

    @Test fun `no string in the Calls pages uses an em dash or a curly apostrophe`() {
        val seen = listOf(
            CallPathWords.HIDE_IP_NOTE, CallPathWords.ROOMS_NOTE, CallPathWords.SPRY_LEAD, CallPathWords.SPRY_ACCT_IN, CallPathWords.SPRY_ACCT_OUT,
            CallPathWords.SPRY_EXCL, CallPathWords.SPRY_ON, CallPathWords.SRV_LEAD, CallPathWords.SRV_ADDR_HINT, CallPathWords.SRV_PRIVACY,
            CallPathWords.SRV_RM_MSG, CallPathWords.OWN_CALLS, CallPathWords.ADV_STUN_NOTE, CallPathWords.ADV_USING_DEFAULT,
        )
        for (text in seen) {
            assert('—' !in text && '’' !in text) { text }
        }
    }
}
