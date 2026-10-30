package dev.forgesworn.kithmoot.ui.settings

import dev.forgesworn.kithmoot.callrelay.CANNOT_HOST_TEXT
import dev.forgesworn.kithmoot.callrelay.RelayCaps
import dev.forgesworn.kithmoot.callrelay.RelayFailure
import dev.forgesworn.kithmoot.callrelay.RelayGate
import dev.forgesworn.kithmoot.callrelay.RelayMapping
import dev.forgesworn.kithmoot.callrelay.RelayNodeLine
import dev.forgesworn.kithmoot.callrelay.RelayPhase
import dev.forgesworn.kithmoot.callrelay.RelayRefusal
import dev.forgesworn.kithmoot.callrelay.RelayState
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins every sentence of the call relay page to the settings spec (sections 6 and 9). */
class CallRelayWordsTest {
    private val zone = ZoneId.of("Europe/London")
    private val noon = ZonedDateTime.of(2026, 10, 30, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
    private fun facts(state: RelayState?, refusal: RelayRefusal? = null, handPaired: Boolean = false, lastUsedAt: Long? = null) =
        RelayFacts(state, refusal, handPaired, lastUsedAt, nowMs = noon, zone = zone)
    private fun page(state: RelayState?, refusal: RelayRefusal? = null, handPaired: Boolean = false, lastUsedAt: Long? = null) =
        CallRelayWords.page(facts(state, refusal, handPaired, lastUsedAt))
    private val running = RelayState(phase = RelayPhase.RUNNING, wanted = true, controlConnected = true, todayBytes = 300_000_000, bytesRelayed = 1_200_000_000,
        mapping = RelayMapping("upnp", 10, 10, permanent = false, restored = 0), endpoint = "spry://${"f".repeat(64)}?relay=wss%3A%2F%2Fr.example.com%2F",
        pairing = "P".repeat(43), bind = "192.168.1.20", listenPort = 23456)

    @Test fun `the turn on notice is the approved wording with phone and 2 GB`() {
        assertEquals(
            "People in rooms you made, and rooms where you share it, can send their encrypted calls through this phone when they can't connect directly. " +
                "They'll see your internet address. It uses your upload bandwidth, up to 2 GB a day. " +
                "KithMoot asks your router to open what the relay needs and closes it again when the relay stops. " +
                "You run this relay and can stop it at any time. " +
                "Check that the laws that apply to you and your internet provider's terms allow it.",
            CallRelayWords.onNotice())
        assertEquals("It runs only on Wi-Fi that isn't metered, while this phone is charging and has at least 50% battery, and it uses some battery.", CallRelayWords.ON_PHONE)
        assertEquals("Hide my IP address doesn't hide your address from people who use your relay.", CallRelayWords.ON_HIDE_IP)
        assertEquals("Turn on the call relay on this phone?", CallRelayWords.ON_TITLE)
    }

    @Test fun `bytes are decimal, en-GB, and never overclaim`() {
        assertEquals("0 MB", CallRelayWords.bytes(0))
        assertEquals("under 1 MB", CallRelayWords.bytes(1))
        assertEquals("under 1 MB", CallRelayWords.bytes(999_999))
        assertEquals("1 MB", CallRelayWords.bytes(1_000_000))
        assertEquals("300 MB", CallRelayWords.bytes(300_000_000))
        assertEquals("999 MB", CallRelayWords.bytes(999_999_999))
        assertEquals("1.0 GB", CallRelayWords.bytes(1_000_000_000))
        assertEquals("1.2 GB", CallRelayWords.bytes(1_299_999_999))
        assertEquals("2 GB", CallRelayWords.limit(RelayCaps.DAILY_LIMIT_BYTES))
    }

    @Test fun `a time is the clock, or yesterday at the clock after midnight`() {
        val at = { hour: Int, minute: Int, day: Int -> ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli() }
        assertEquals("at 14:02", CallRelayWords.atTime(at(14, 2, 30), at(15, 0, 30), zone))
        assertEquals("yesterday at 23:58", CallRelayWords.atTime(at(23, 58, 29), at(0, 5, 30), zone))
    }

    @Test fun `the enum sentences and the page agree`() {
        assertEquals(CallRelayWords.CRASHED + ". " + CallRelayWords.CRASHED_2, RelayFailure.CRASHED.text)
        assertTrue(RelayGate.DAILY_LIMIT.text.contains(CallRelayWords.limit(RelayCaps.DAILY_LIMIT_BYTES)))
        assertEquals("Today's limit of 2 GB is used up.", RelayGate.DAILY_LIMIT.text)
        assertEquals(RelayNodeLine.PORTMAP_REASONS, CANNOT_HOST_TEXT.keys)
        assertTrue(CANNOT_HOST_TEXT.values.none { "computer" in it || Regex("\\bports?\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) })
    }

    @Test fun `off is the intro and the tester note`() {
        for (state in listOf(null, RelayState())) {
            val p = page(state)
            assertEquals("Off", p.headline)
            assertEquals(CallRelayWords.INTRO, p.line2)
            assertEquals(CallRelayWords.TESTER, p.line3)
            assertEquals("Off", p.rowSummary)
            assertEquals(RelaySwitch.OFF, p.switch)
            assertTrue(p.off)
        }
        assertTrue(CallRelayWords.INTRO.startsWith("When two people can't connect directly, a call relay passes their encrypted audio and video along."))
        assertTrue(CallRelayWords.INTRO.contains("use this phone as their relay"))
    }

    @Test fun `starting stopping and waiting`() {
        assertEquals(RelayPage("Starting…", "Setting up your router. This can take up to a minute.", rowSummary = "Starting…", switch = RelaySwitch.ON),
            page(RelayState(phase = RelayPhase.STARTING, wanted = true)))
        assertEquals(RelayPage("Stopping…", rowSummary = "Stopping…", switch = RelaySwitch.STOPPING), page(RelayState(phase = RelayPhase.STOPPING, wanted = true)))
        assertEquals(RelayPage("Waiting for a network", "It starts when this phone is back online.", rowSummary = "Waiting for a network", switch = RelaySwitch.ON),
            page(RelayState(phase = RelayPhase.OFF, wanted = true)))
    }

    @Test fun `running idle, in use, last used and waiting for Nostr relays`() {
        val idle = page(running)
        assertEquals("Running on this phone", idle.headline)
        assertNull(idle.line2)
        assertEquals("Not used yet · Relayed today: 300 MB of 2 GB", idle.line3)
        assertEquals("On", idle.rowSummary)
        assertEquals(RelaySwitch.ON, idle.switch)
        assertTrue(idle.running)

        val busy = page(running.copy(allocations = 3))
        assertEquals("In use now · Relayed today: 300 MB of 2 GB", busy.line3)
        assertEquals("On · in use now", busy.rowSummary)
        assertEquals("In use now", busy.useTitle)
        assertEquals("Relayed today: 300 MB of 2 GB", busy.useSummary)

        val last = page(running, lastUsedAt = ZonedDateTime.of(2026, 10, 30, 11, 41, 0, 0, zone).toInstant().toEpochMilli())
        assertEquals("Last used at 11:41 · Relayed today: 300 MB of 2 GB", last.line3)

        val waiting = page(running.copy(controlConnected = false))
        assertEquals("Running, waiting for your Nostr relays", waiting.headline)
        assertEquals("Nobody can use it until KithMoot reaches at least one of your Nostr relays.", waiting.line2)
        assertEquals("On · waiting for Nostr relays", waiting.rowSummary)
    }

    @Test fun `every reason a network cannot host has its own page`() {
        for ((code, text) in CANNOT_HOST_TEXT) {
            val wanted = page(RelayState(phase = RelayPhase.CANNOT_HOST, wanted = true, reason = code))
            assertEquals("Can't run on this network", wanted.headline)
            assertEquals(text, wanted.line2)
            assertEquals("It tries again when this phone joins another network.", wanted.line3)
            assertEquals(RelaySwitch.ON, wanted.switch)
            val gaveUp = page(RelayState(phase = RelayPhase.CANNOT_HOST, wanted = false, reason = code))
            assertEquals(text, gaveUp.line2)
            assertNull(gaveUp.line3)
            assertEquals(RelaySwitch.OFF, gaveUp.switch)
            assertEquals("Can't run on this network", gaveUp.rowSummary)
        }
        assertEquals("This network can't host a call relay.", page(RelayState(phase = RelayPhase.CANNOT_HOST, wanted = true, reason = "new-word")).line2)
    }

    @Test fun `every gate pauses with its words and the daily limit has its own`() {
        val rows = mapOf(RelayGate.NO_WIFI to "Paused · not on Wi-Fi", RelayGate.METERED to "Paused · metered Wi-Fi", RelayGate.VPN to "Paused · VPN on",
            RelayGate.NO_ADDRESS to "Paused · Wi-Fi connecting", RelayGate.NOT_CHARGING to "Paused · not charging",
            RelayGate.LOW_BATTERY to "Paused · battery under 50%", RelayGate.HOT to "Paused · phone is warm")
        val lines = mapOf(RelayGate.NO_WIFI to "It runs only on Wi-Fi.", RelayGate.METERED to "This Wi-Fi is set as metered, so the relay stays off.",
            RelayGate.VPN to "A VPN is on. People can't reach a relay behind it.", RelayGate.NO_ADDRESS to "Waiting for this Wi-Fi to finish connecting.",
            RelayGate.NOT_CHARGING to "It runs only while this phone is charging.", RelayGate.LOW_BATTERY to "It waits until the battery is at least 50%.",
            RelayGate.HOT to "This phone is warm. It tries again after 10 minutes cool.")
        for (gate in RelayGate.entries) {
            val p = page(RelayState(phase = RelayPhase.PAUSED, wanted = true, gate = gate))
            assertEquals(RelaySwitch.ON, p.switch)
            if (gate == RelayGate.DAILY_LIMIT) {
                assertEquals(RelayPage("Paused for today", "Today's limit of 2 GB is used up.", "It starts again after midnight.", "Paused until midnight", RelaySwitch.ON), p)
            } else {
                assertEquals(RelayPage("Paused", lines.getValue(gate), "It starts again by itself when that changes.", rows.getValue(gate), RelaySwitch.ON), p)
            }
        }
    }

    @Test fun `every failure says what happened and what to do`() {
        val crashed = page(RelayState(phase = RelayPhase.FAILED, wanted = true, failure = RelayFailure.CRASHED))
        assertEquals(RelayPage("The relay stopped unexpectedly", "Starting it again…", rowSummary = "Restarting…", switch = RelaySwitch.ON), crashed)
        assertEquals(RelayPage("Stopped", "The relay stopped unexpectedly twice, so it was left off.", "Turn it on again to try once more. If it keeps stopping, restart KithMoot.", "Stopped", RelaySwitch.OFF),
            page(RelayState(phase = RelayPhase.FAILED, failure = RelayFailure.CRASHED_TWICE)))
        assertEquals(RelayPage("Stopped", "The relay couldn't start.", "Try turning it on again.", "Stopped", RelaySwitch.OFF), page(RelayState(phase = RelayPhase.FAILED, failure = RelayFailure.START_FAILED)))
        assertEquals(RelayPage("Stopped", "The relay didn't get ready in time.", "Try turning it on again.", "Stopped", RelaySwitch.OFF), page(RelayState(phase = RelayPhase.FAILED, failure = RelayFailure.START_TIMEOUT)))
        assertEquals(RelayPage("Stopped", "Part of KithMoot that runs the relay is missing or damaged.", "Reinstall KithMoot from kithmoot.app, then try again.", "Stopped", RelaySwitch.OFF),
            page(RelayState(phase = RelayPhase.FAILED, failure = RelayFailure.LIBRARY)))
        assertEquals(RelayPage("Still stopping", "The relay hasn't stopped yet. Press Stop again.", rowSummary = "Stopping…", switch = RelaySwitch.ON),
            page(RelayState(phase = RelayPhase.FAILED, failure = RelayFailure.STOP_TIMEOUT)))
        // A failure with a wanted relay still reads as stopped when it will not try again by itself.
        assertEquals("Stopped", page(RelayState(phase = RelayPhase.FAILED, wanted = true, failure = RelayFailure.START_FAILED)).headline)
        // A failure this build has no name for.
        assertEquals("The relay couldn't start.", page(RelayState(phase = RelayPhase.FAILED)).line2)
        // The node's own refusal line is never shown.
        assertFalse(page(RelayState(phase = RelayPhase.FAILED, failure = RelayFailure.START_FAILED, refusal = "needs --listen-port")).toString().contains("listen-port"))
    }

    @Test fun `a turn on that never reached the relay is worded from its code`() {
        assertEquals(RelayPage("You already use your own Spry relay", CallRelayWords.SPRY_2, rowSummary = "Not used: you have Spry", switch = RelaySwitch.UNAVAILABLE), page(null, handPaired = true))
        assertEquals(page(null, handPaired = true), page(null, RelayRefusal.HAND_PAIRED))
        assertTrue(CallRelayWords.SPRY_2.contains("unpair Spry on the Connections page"))
        assertEquals(RelayPage("Stopped", CallRelayWords.NO_ACCOUNT, rowSummary = "Off", switch = RelaySwitch.OFF), page(null, RelayRefusal.NO_ACCOUNT))
        assertEquals(RelayPage("Stopped", "This phone's relay is set up for another account you use here. Switch to that account to use it.", rowSummary = "Off", switch = RelaySwitch.OFF),
            page(null, RelayRefusal.OTHER_ACCOUNT))
        assertEquals("None of your Nostr relays can carry requests for the call relay. Add one whose address starts with wss:// and uses a domain name, in Nostr relays.",
            page(null, RelayRefusal.NO_CONTROL_RELAYS).line2)
        val keys = page(null, RelayRefusal.KEYS_UNREADABLE)
        assertEquals("The relay's saved keys couldn't be read. This happens after switching between builds of KithMoot.", keys.line2)
        assertTrue(keys.offerRemoveKeys)
        assertEquals("Stopped", keys.rowSummary)
        assertEquals(RelayPage("Stopped", "The relay couldn't start.", "Try turning it on again.", "Stopped", RelaySwitch.OFF), page(null, RelayRefusal.START_FAILED))
        // A relay that is on is not contradicted by an old refusal.
        assertEquals("Running on this phone", page(running, RelayRefusal.OTHER_ACCOUNT).headline)
    }

    @Test fun `details name the router method and counts but no address`() {
        val state = running.copy(allocations = 3, bytesRelayed = 1_200_000_000, mapping = RelayMapping("natpmp", 8, 10, permanent = true, restored = 2))
        assertEquals(listOf(
            "Router: opened automatically using NAT-PMP",
            "Openings on your router: 8 of 10",
            "Your router only offers permanent openings. If KithMoot crashes they stay open until it next starts, when it removes them.",
            "Your router dropped 2 openings; the relay put them back.",
            "Nostr relays: connected",
            "Connections now: 3",
            "Relayed since it started: 1.2 GB",
            "Daily limit: 2 GB. When it's reached, the relay pauses until midnight.",
        ), CallRelayWords.details(facts(state)))
        assertEquals("Nostr relays: not connected yet", CallRelayWords.details(facts(running.copy(controlConnected = false, mapping = null))).first())
        assertTrue(CallRelayWords.details(facts(null)).isEmpty())
    }

    @Test fun `no output holds an address, endpoint, pairing, bind, refusal or a count of people`() {
        val states = buildList {
            add(null)
            for (phase in RelayPhase.entries) for (gate in listOf(null) + RelayGate.entries) for (failure in listOf(null) + RelayFailure.entries) for (wanted in listOf(true, false)) {
                add(running.copy(phase = phase, gate = gate, failure = failure, wanted = wanted, allocations = 3, reason = "double-nat",
                    refusal = "needs a listen port", controlConnected = true))
            }
        }
        val forbidden = listOf("endpoint", "pairing", "bind", "refusal", "spry:", "turn:", "listen", "192.168")
        val peopleCount = Regex("\\d+\\s+(people|users|members)", RegexOption.IGNORE_CASE)
        val ipv4 = Regex("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b")
        val seen = mutableListOf<String>()
        for (state in states) for (refusal in listOf(null) + RelayRefusal.entries) {
            val f = facts(state, refusal, lastUsedAt = noon - 60_000)
            val p = CallRelayWords.page(f)
            seen += listOfNotNull(p.headline, p.line2, p.line3, p.rowSummary, p.useTitle, p.useSummary) + CallRelayWords.details(f)
        }
        seen += listOf(CallRelayWords.INTRO, CallRelayWords.TESTER, CallRelayWords.NOTE_ON, CallRelayWords.NO_NOTIFICATIONS, CallRelayWords.onNotice(), CallRelayWords.ON_PHONE)
        for (line in seen) {
            for (word in forbidden) assertFalse("$word in: $line", line.contains(word, ignoreCase = true))
            assertFalse(line, peopleCount.containsMatchIn(line))
            assertFalse(line, ipv4.containsMatchIn(line))
            assertFalse("em dash in $line", line.contains('—'))
        }
    }

    @Test fun `no switch is on while the relay will not run by itself`() {
        for (failure in listOf(RelayFailure.CRASHED_TWICE, RelayFailure.START_FAILED, RelayFailure.START_TIMEOUT, RelayFailure.LIBRARY))
            assertEquals(RelaySwitch.OFF, page(RelayState(phase = RelayPhase.FAILED, wanted = true, failure = failure)).switch)
    }
}
