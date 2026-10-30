package dev.forgesworn.kithmoot.callrelay

import dev.forgesworn.kithmoot.ui.settings.builtInRelaySummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RelayNoticeTest {
    private val running = RelayState(phase = RelayPhase.RUNNING, wanted = true, endpoint = "spry://${"f".repeat(64)}?relay=wss%3A%2F%2Frelay.example.com%2F",
        pairing = "P".repeat(43), bind = "192.168.1.20", listenPort = 23456, allocations = 2)

    @Test fun `the notification says the relay is on and how many connections use it`() {
        assertEquals("Call relay on" to "Relaying 2 connections for people in your rooms.", RelayNotice.words(running))
        assertEquals("Call relay on" to "Nobody is using it right now.", RelayNotice.words(running.copy(allocations = 0)))
        assertEquals("Call relay on" to "Relaying 1 connection for people in your rooms.", RelayNotice.words(running.copy(allocations = 1)))
    }

    @Test fun `a stop says why in plain words`() {
        assertEquals("Call relay paused" to RelayGate.NOT_CHARGING.text, RelayNotice.words(RelayState(phase = RelayPhase.PAUSED, wanted = true, gate = RelayGate.NOT_CHARGING)))
        assertEquals("Call relay can't run on this network" to CANNOT_HOST_TEXT.getValue("double-nat") + " It tries again on another network.",
            RelayNotice.words(RelayState(phase = RelayPhase.CANNOT_HOST, wanted = true, reason = "double-nat")))
        assertEquals("Call relay stopped" to RelayFailure.CRASHED_TWICE.text, RelayNotice.words(RelayState(phase = RelayPhase.FAILED, failure = RelayFailure.CRASHED_TWICE)))
        assertEquals("Call relay restarting" to RelayFailure.CRASHED.text, RelayNotice.words(RelayState(phase = RelayPhase.FAILED, wanted = true, failure = RelayFailure.CRASHED)))
    }

    @Test fun `no address, port or key is ever shown`() {
        for (state in listOf(running, running.copy(phase = RelayPhase.STARTING), running.copy(phase = RelayPhase.STOPPING))) {
            val (title, text) = RelayNotice.words(state)
            val summary = builtInRelaySummary(state)
            for (shown in listOf(title, text, summary)) {
                assertFalse(shown, shown.contains("192.168.1.20") || shown.contains("23456") || shown.contains("f".repeat(64)) || shown.contains("P".repeat(43)))
            }
        }
    }
}
