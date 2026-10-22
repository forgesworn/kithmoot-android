package dev.forgesworn.kithmoot.media

import dev.forgesworn.kithmoot.ui.HIDE_IP_NO_RELAY
import dev.forgesworn.kithmoot.ui.room.RELAY_MISSING_NOTICE
import dev.forgesworn.kithmoot.ui.room.relayMissingNote
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CallIceServersTest {
    @Test fun `a call asks for no relay credential and names STUN only`() {
        assertEquals(listOf("stun:kithmoot.forgesworn.dev:3478"), CallIceServers.STUN_URLS)
        assertTrue(CallIceServers.STUN_URLS.none { it.startsWith("turn", true) })
        assertNull(CallIceServers.resolveRelayOnly())
        // Nothing in the app asks ForgeSworn's host for a TURN credential any more.
        val source = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { file -> file.readText().contains("/turn\"") || file.readText().contains("/turn\$") }.map { it.name }.toList()
        assertEquals(emptyList(), source)
    }

    @Test fun `hiding the IP address is off unless chosen`() {
        assertEquals(false, dev.forgesworn.kithmoot.ui.StartState().hideIpOnCalls)
    }

    @Test fun `the words match the web's switch-off notices`() {
        assertEquals("Some calls need a relay. Your group has none, so this call may not connect on some networks.", RELAY_MISSING_NOTICE)
        assertTrue(HIDE_IP_NO_RELAY.startsWith("This room has no usable TURN relay."))
        assertTrue("Hide my IP address on calls" in HIDE_IP_NO_RELAY && "Settings › Privacy" in HIDE_IP_NO_RELAY)
        // Android cannot read a room's own call servers from its link yet, so it promises no relay-enabled link.
        assertFalse("relay-enabled link" in HIDE_IP_NO_RELAY)
    }

    @Test fun `a call that cannot connect directly and has no relay says so, and only then`() {
        assertNull(relayMissingNote(onCall = true, hasRelay = false, connections = mapOf("a" to "connected")))
        assertEquals(RELAY_MISSING_NOTICE, relayMissingNote(onCall = true, hasRelay = false, connections = mapOf("a" to "connected", "b" to "failed")))
        assertEquals(RELAY_MISSING_NOTICE, relayMissingNote(onCall = true, hasRelay = false, connections = mapOf("b" to "disconnected")))
        assertNull(relayMissingNote(onCall = true, hasRelay = true, connections = mapOf("b" to "failed")))
        assertNull(relayMissingNote(onCall = false, hasRelay = false, connections = mapOf("b" to "failed")))
    }
}
