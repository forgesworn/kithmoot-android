package dev.forgesworn.kithmoot.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RetirementResendTest {

    @Test fun `each retirement goes once to each relay, however often relays reconnect`() {
        val resend = RetirementResend()
        val none: (String) -> Set<String> = { emptySet() }
        assertEquals(mapOf("r1" to setOf("wss://a", "wss://b")), resend.due(setOf("wss://a", "wss://b"), listOf("r1"), none))
        // b bounces: nothing goes again, to it or to a.
        assertTrue(resend.due(setOf("wss://a"), listOf("r1"), none).isEmpty())
        assertTrue(resend.due(setOf("wss://a", "wss://b"), listOf("r1"), none).isEmpty())
        // A relay seen for the first time gets it once.
        assertEquals(mapOf("r1" to setOf("wss://c")), resend.due(setOf("wss://a", "wss://b", "wss://c"), listOf("r1"), none))
    }

    @Test fun `a relay that already said OK is not offered it`() {
        val resend = RetirementResend()
        val due = resend.due(setOf("wss://a", "wss://b"), listOf("r1", "r2")) { id -> if (id == "r1") setOf("wss://a") else emptySet() }
        assertEquals(mapOf("r1" to setOf("wss://b"), "r2" to setOf("wss://a", "wss://b")), due)
    }

    @Test fun `a retirement made during the session goes to the relays already up`() {
        val resend = RetirementResend()
        resend.due(setOf("wss://a"), listOf("r1")) { emptySet() }
        assertEquals(mapOf("r2" to setOf("wss://a")), resend.due(setOf("wss://a"), listOf("r1", "r2")) { emptySet() })
    }
}
