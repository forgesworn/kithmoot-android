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
    @Test fun `custom login is attached only to its own relay with separate STUN`() {
        val relay = CallRelayConfig.parse("turn:private.example:3478,turns:private.example:5349", "friend", "test-secret")!!
        val servers = CallIceServers.resolve(relay)
        assertEquals(listOf("stun:private.example:3478"), servers.first().urls)
        assertEquals("", servers.first().password)
        assertEquals(relay.urls, servers.last().urls)
        assertEquals("friend", servers.last().username)
        assertEquals("test-secret", servers.last().password)
        assertTrue(CallIceServers.hasRelay(servers))
    }

    @Test fun `hiding the IP uses only the selected relay and never STUN`() {
        val relay = CallRelayConfig.parse("turn:private.example:3478,turns:private.example:5349", "friend", "test-secret")!!
        val servers = requireNotNull(CallIceServers.resolveRelayOnly(relay))
        assertEquals(1, servers.size)
        assertTrue(servers.all { it.urls.all { url -> url.startsWith("turn") } })
        assertEquals(relay.urls, servers.single().urls)
        assertEquals("friend", servers.single().username)
        assertEquals("test-secret", servers.single().password)
        assertNull(CallIceServers.resolveRelayOnly())
    }
    @Test fun `a call asks for no relay credential and names Cloudflare's STUN only`() {
        assertEquals(listOf("stun:stun.cloudflare.com:3478"), CallIceServers.DEFAULT_STUN_URLS)
        assertEquals(listOf(listOf("stun:stun.cloudflare.com:3478")), CallIceServers.resolve().map { it.urls })
        assertNull(CallIceServers.resolveRelayOnly())
        // ForgeSworn runs no STUN for the public any more (23 October 2026).
        val stunHosts = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { file -> file.readText().contains("forgesworn.dev:3478") || file.readText().contains("\$HOST:3478") }.map { it.name }.toList()
        assertEquals(emptyList(), stunHosts)
        // Nothing in the app asks ForgeSworn's host for a TURN credential any more.
        val source = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { file -> file.readText().contains("/turn\"") || file.readText().contains("/turn\$") }.map { it.name }.toList()
        assertEquals(emptyList(), source)
    }

    @Test fun `your own STUN server comes before your relay's and the default`() {
        val relay = CallRelayConfig.parse("turn:private.example:3478", "friend", "test-secret")!!
        val own = CallStunConfig.parse("stun:mine.example:3478, stun:mine.example:3479")!!
        val servers = CallIceServers.resolve(relay, own)
        assertEquals(listOf(listOf("stun:mine.example:3478"), listOf("stun:mine.example:3479"), relay.urls), servers.map { it.urls })
        assertEquals("test-secret", servers.last().password)
        assertEquals(own.urls, CallIceServers.stunUrls(null, own))
        assertTrue(CallIceServers.resolve(null, own).none { it.urls.any { url -> "cloudflare" in url } })
    }

    @Test fun `a relay without a plain turn address and nothing of your own asks no STUN server`() {
        val relay = CallRelayConfig.parse("turns:private.example:5349", "friend", "test-secret")!!
        assertEquals(emptyList(), CallIceServers.stunUrls(relay))
        assertEquals(listOf(relay.urls), CallIceServers.resolve(relay).map { it.urls })
    }

    @Test fun `a build can name other STUN servers or none`() {
        assertEquals(emptyList(), CallIceServers.defaultStunUrls(""))
        assertEquals(listOf("stun:a.example:3478", "stun:b.example"), CallIceServers.defaultStunUrls("stun:a.example:3478, nonsense, stun:b.example"))
        assertEquals(emptyList(), CallIceServers.resolve(defaults = emptyList()))
        assertEquals("Cloudflare’s unless you set your own", CallIceServers.defaultStunWords())
        assertEquals("No STUN server unless you set one", CallIceServers.defaultStunWords(emptyList()))
        assertEquals("a.example, b.example unless you set your own", CallIceServers.defaultStunWords(listOf("stun:a.example:3478", "stun:b.example")))
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
