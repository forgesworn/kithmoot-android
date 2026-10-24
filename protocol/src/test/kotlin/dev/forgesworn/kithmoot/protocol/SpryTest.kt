package dev.forgesworn.kithmoot.protocol

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SpryTest {
    @Test fun requiresPublicHttpsAndBoundsAccessLifetime() {
        for (url in listOf("http://relay.example", "https://127.0.0.1", "https://10.0.0.1", "https://[::1]", "https://node.local", "https://user:secret@relay.example", "https://relay.example/path")) {
            assertTrue(runCatching { spryEndpoint(url) }.isFailure)
        }
        val now = 1_800_000_000L
        val value = buildJsonObject { put("v", 1); put("endpoint", "https://relay.example:3744"); put("token", "a".repeat(43)); put("expiresAt", now + 100); put("roomKey", "must not be retained") }
        val offer = requireNotNull(SpryOffer.parse(value, now))
        assertFalse(offer.toString().contains("a".repeat(43)))
        assertFalse(offer.toJson().toString().contains("roomKey"))
        assertNull(SpryOffer.parse(value, now + 100))
        assertNull(SpryOffer.parse(value, now - SPRY_GRANT_SECONDS - 1000))
    }
}
