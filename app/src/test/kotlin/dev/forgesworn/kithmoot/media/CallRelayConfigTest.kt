package dev.forgesworn.kithmoot.media

import kotlin.test.*

class CallRelayConfigTest {
    @Test fun `relay login round trips without entering diagnostic output`() {
        val config = CallRelayConfig.parse("turn:relay.example:3478, turns:relay.example:5349", "friend", "test-secret")!!
        val restored = CallRelayConfig.decode(config.encode())
        assertEquals(config.urls, restored.urls)
        assertEquals("friend", restored.username)
        assertEquals("test-secret", restored.credential)
        assertFalse(config.toString().contains("test-secret"))
    }
    @Test fun `accepts IPv6 TCP and TLS`() {
        assertEquals(2, CallRelayConfig.parse("turn:[2001:db8::1]:3478?transport=tcp,turns:relay.example:443?transport=tcp", "u", "p")!!.urls.size)
    }
    @Test fun `normalises the relay scheme and hostname`() {
        assertEquals(listOf("turn:relay.example:3478?transport=tcp"), CallRelayConfig.parse("TURN:Relay.Example:3478?transport=TCP", "u", "p")!!.urls)
    }
    @Test fun `refuses partial or malformed settings with no credential in the error`() {
        for (url in listOf("https://relay.example", "turn:user:password@relay.example:3478", "turn:relay.example:0", "turn:relay.example:65536",
            "turn:relay.example/path", "turn:relay.example#fragment", "turns:relay.example?transport=udp", "stun:relay.example:3478")) {
            val error = assertFailsWith<IllegalArgumentException> { CallRelayConfig.parse(url, "u", "test-secret") }
            assertFalse(error.toString().contains("test-secret"))
        }
        assertFailsWith<IllegalArgumentException> { CallRelayConfig.parse("turn:relay.example", "", "p") }
        assertFailsWith<IllegalArgumentException> { CallRelayConfig.parse("turn:relay.example", "u", "p\n") }
        assertNull(CallRelayConfig.parse("", "", ""))
    }
    @Test fun `corrupt storage refuses and does not disclose the parser input`() {
        for (text in listOf("{test-secret", """{"urls":[],"username":"u","credential":"test-secret"}""", "x".repeat(16_385))) {
            val error = assertFailsWith<IllegalArgumentException> { CallRelayConfig.decode(text.toByteArray()) }
            assertFalse(error.toString().contains("test-secret"))
            assertNull(error.cause)
        }
    }
}
