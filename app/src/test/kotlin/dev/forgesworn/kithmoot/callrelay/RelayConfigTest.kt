package dev.forgesworn.kithmoot.callrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayConfigTest {
    private val key = "b".repeat(64)
    private val network = RelayNetwork(bind = "192.168.1.20", gateway = "192.168.1.1")

    @Test fun `the node's options are the desktop's, with the router named and no stdin switch`() {
        val args = RelayConfig.args(key, "/data/state/node-state.json", listOf("wss://relay.example.com/", "wss://nos.example.org/"), network, 23456)
        assertEquals(listOf("--map-ports", "--gateway", "192.168.1.1", "--pairing-key", key, "--state-file", "/data/state/node-state.json",
            "--control-relay", "wss://relay.example.com/", "--control-relay", "wss://nos.example.org/",
            "--bind", "192.168.1.20", "--listen-port", "23456",
            "--max-allocations", "8", "--max-per-user", "2", "--max-tcp-connections", "16", "--bytes-per-second", "125000"), args)
        assertFalse("the library refuses it", "--exit-on-stdin-close" in args)
        assertFalse("the library's options follow `supervised`", "supervised" in args)
    }

    @Test fun `bad values never reach the node`() {
        val relays = listOf("wss://relay.example.com/")
        assertThrows(IllegalArgumentException::class.java) { RelayConfig.args("B".repeat(64), "/s", relays, network, 23456) }
        assertThrows(IllegalArgumentException::class.java) { RelayConfig.args(key, "relative", relays, network, 23456) }
        assertThrows(IllegalArgumentException::class.java) { RelayConfig.args(key, "/s", relays, network, 19999) }
        assertThrows(IllegalArgumentException::class.java) { RelayConfig.args(key, "/s", relays, network, 40000) }
        assertThrows(IllegalArgumentException::class.java) { RelayConfig.args(key, "/s", emptyList(), network, 23456) }
        assertThrows(IllegalArgumentException::class.java) { RelayConfig.args(key, "/s", List(5) { "wss://r$it.example.com/" }, network, 23456) }
        for (bad in listOf("127.0.0.1", "0.0.0.0", "169.254.1.2", "224.0.0.1", "255.255.255.255", "192.168.1", "192.168.01.2", "fe80::1")) {
            assertThrows(bad, IllegalArgumentException::class.java) { RelayConfig.args(key, "/s", relays, network.copy(bind = bad), 23456) }
            assertThrows(bad, IllegalArgumentException::class.java) { RelayConfig.args(key, "/s", relays, network.copy(gateway = bad), 23456) }
        }
    }

    @Test fun `the environment is the TURN secret alone`() {
        val secret = "c".repeat(64)
        assertEquals(listOf("SPRY_TURN_SECRET=$secret"), RelayConfig.environment(secret))
        assertThrows(IllegalArgumentException::class.java) { RelayConfig.environment("short") }
    }

    @Test fun `control relays are wss on DNS names, normalised`() {
        assertEquals("wss://relay.example.com/", RelayConfig.controlRelay("wss://Relay.Example.com"))
        assertEquals("wss://relay.example.com/", RelayConfig.controlRelay("wss://relay.example.com:443/"))
        assertEquals("wss://relay.example.com:7777/nostr", RelayConfig.controlRelay("wss://relay.example.com:7777/nostr"))
        for (bad in listOf("ws://relay.example.com/", "https://relay.example.com/", "wss://203.0.113.9/", "wss://[2001:db8::1]/",
            "wss://localhost/", "wss://relay.local/", "wss://box.internal/", "wss://user@relay.example.com/", "wss://relay.example.com/?a=b",
            "wss://relay.example.com/#x", "wss://relay/", "wss://relay.example.com./", "not a url")) {
            assertNull(bad, RelayConfig.controlRelay(bad))
        }
    }

    @Test fun `up to four of the account's usable relays are picked, once each`() {
        val picked = RelayConfig.pickControlRelays(listOf("ws://plain.example.com", "wss://a.example.com", "wss://A.example.com/",
            "wss://b.example.com", "wss://localhost", "wss://c.example.com", "wss://d.example.com", "wss://e.example.com"))
        assertEquals(listOf("wss://a.example.com/", "wss://b.example.com/", "wss://c.example.com/", "wss://d.example.com/"), picked)
        assertEquals(emptyList<String>(), RelayConfig.pickControlRelays(listOf("ws://x.example.com", "wss://127.0.0.1")))
        assertThrows(IllegalArgumentException::class.java) { RelayConfig.controlRelayList(listOf("wss://a.example.com", "wss://a.example.com/")) }
    }

    @Test fun `this device's own relay is reached on its local listener`() {
        assertEquals(listOf("turn:192.168.1.20:23456?transport=udp", "turn:192.168.1.20:23456?transport=tcp"),
            RelayConfig.localTurnUrls(LocalTurn("192.168.1.20", 23456)))
        assertThrows(IllegalArgumentException::class.java) { RelayConfig.localTurnUrls(LocalTurn("127.0.0.1", 23456)) }
        assertThrows(IllegalArgumentException::class.java) { RelayConfig.localTurnUrls(LocalTurn("192.168.1.20", 80)) }
    }

    @Test fun `every reason a network cannot host has plain words`() {
        assertEquals(RelayNodeLine.PORTMAP_REASONS, CANNOT_HOST_TEXT.keys)
        assertTrue(CANNOT_HOST_TEXT.values.none { "computer" in it })
    }
}
