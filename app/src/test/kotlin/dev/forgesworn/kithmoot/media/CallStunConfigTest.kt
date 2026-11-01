package dev.forgesworn.kithmoot.media

import dev.forgesworn.kithmoot.ui.settings.stunServerExplanation
import dev.forgesworn.kithmoot.ui.settings.stunServerSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CallStunConfigTest {
    @Test fun `an empty field means the default`() {
        assertNull(CallStunConfig.parse(""))
        assertNull(CallStunConfig.parse(" , \n "))
    }

    @Test fun `STUN addresses are read, lowercased and kept once`() {
        val value = CallStunConfig.parse(" STUN:Stun.Example:3478,stun:stun.example:5349\nstun:[2001:db8::1]:3478, stun:stun.example:3478, stun:plain.example")!!
        assertEquals(listOf("stun:stun.example:3478", "stun:stun.example:5349", "stun:[2001:db8::1]:3478", "stun:plain.example"), value.urls)
        assertEquals(value.urls, CallStunConfig.decode(value.encode()).urls)
    }

    @Test fun `anything that is not a STUN address is refused with the format`() {
        for (bad in listOf("stuns:stun.example:5349", "turn:relay.example:3478", "https://stun.example", "stun:host:0", "stun:host:70000",
            "stun:host?transport=udp", "stun:user@host", "stun:-host", "stun:host:3478/path", "stun: host")) {
            assertFailsWith<IllegalArgumentException>(bad) { CallStunConfig.parse(bad) }.also {
                assertEquals(CallStunConfig.FORMAT_HELP, it.message)
            }
        }
        val five = (1..5).joinToString(",") { "stun:s$it.example" }
        assertFailsWith<IllegalArgumentException> { CallStunConfig.parse(five) }
        assertFalse(CallStunConfig.isStunUrl("stun:" + "a".repeat(260)))
    }

    @Test fun `an unreadable saved value says where to fix it`() {
        val failure = assertFailsWith<IllegalArgumentException> { CallStunConfig.decode("turn:x".toByteArray()) }
        assertEquals(CallStunConfig.UNREADABLE, failure.message)
        assertTrue("Settings › Calls › Advanced" in CallStunConfig.UNREADABLE)
    }

    @Test fun `the dialog names who answers by default, and that it sees the IP address`() {
        val words = stunServerExplanation()
        assertTrue("Without one, calls ask Google’s and Cloudflare’s." in words)
        assertTrue("It sees your IP address when a call starts, never the call itself." in words)
        assertTrue("never added to room links" in words)
        assertTrue("Without one, calls only connect on the same network or through your call relay." in stunServerExplanation(emptyList()))
    }

    @Test fun `the Connections row shows your own servers once set`() {
        assertEquals("Google’s and Cloudflare’s unless you set your own", stunServerSummary())
        val own = CallStunConfig.parse("stun:stun.example.org:3478, stun:[2001:db8::7]:3478, stun:192.0.2.7")!!
        assertEquals("Your own: stun.example.org, [2001:db8::7], 192.0.2.7", stunServerSummary(own.urls))
    }
}
