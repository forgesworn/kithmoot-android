package dev.forgesworn.kithmoot.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CircleGrantTest {
    private val terms = CircleGrantTerms(
        "ws://${"a".repeat(52)}/events",
        "1".repeat(64),
        "2".repeat(64),
        "3".repeat(64),
        "4".repeat(32),
        1_900_000_000,
    )

    @Test fun `active grant has the frozen exact scope and order`() {
        assertEquals(listOf(
            listOf("t", "event-grant"),
            listOf("server", terms.server),
            listOf("d", terms.room),
            listOf("p", terms.persona),
            listOf("device", terms.device),
            listOf("grant", terms.grantId),
            listOf("read", "1460"),
            listOf("read", "1462"),
            listOf("read", "20461"),
            listOf("write", "1460"),
            listOf("write", "20461"),
            listOf("expiration", "1900000000"),
            listOf("status", "active"),
        ), terms.tags(CircleGrantStatus.ACTIVE))
        assertEquals("revoked", terms.tags(CircleGrantStatus.REVOKED).last()[1])
    }

    @Test fun `malformed authority coordinates fail before signing`() {
        assertThrows(IllegalArgumentException::class.java) { terms.copy(server = "wss://example.com/events") }
        assertThrows(IllegalArgumentException::class.java) { terms.copy(room = "A".repeat(64)) }
        assertThrows(IllegalArgumentException::class.java) { terms.copy(grantId = "4".repeat(64)) }
        assertThrows(IllegalArgumentException::class.java) { terms.copy(expiration = 0) }
    }
}
