package dev.forgesworn.kithmoot.protocol

import kotlinx.serialization.json.*
import kotlin.random.Random
import org.junit.Test
import org.junit.Assert.*

class VmlsRevocationRequestTest {
    private val at = 1_900_000_000L
    private val sender = "11".repeat(32)
    private val keeper = "22".repeat(32)
    private val request = VmlsRevocationRequest(sender, keeper, "33".repeat(32), listOf("44".repeat(32)), listOf("55".repeat(32)), at, at + 86400)
    private inline fun <reified T : Throwable> assertRefuses(block: () -> Unit) {
        try { block(); fail("Expected ${T::class}") } catch (e: Exception) { assertTrue(e is T) }
    }
    private fun parse(text: String) = VmlsRevocationRequest.parse(text, sender, keeper, at)

    @Test fun `hostile bytes truncations oversized nesting and duplicate authority tags refuse without escaping parser errors`() {
        val text = request.rumor().toString()
        for (cut in 0 until text.length) assertTrue("truncation $cut", runCatching { parse(text.take(cut)) }.isFailure)
        val random = Random(21350)
        repeat(1000) {
            val bytes = random.nextBytes(random.nextInt(0, 2048)).decodeToString()
            try { parse(bytes); fail("random bytes accepted") } catch (_: Exception) { }
        }
        // Recomputed hashes do not make ambiguous or excessive framing legitimate.
        for (tags in listOf(request.tags() + listOf(listOf("p", keeper)), request.tags() + listOf(listOf("reason", "urgent")), request.tags() + listOf(listOf("box", "55".repeat(32))))) {
            val o = JsonObject(request.rumor() + mapOf("tags" to JsonArray(tags.map { JsonArray(it.map(::JsonPrimitive)) }),
                "id" to JsonPrimitive(Events.eventId(sender, at, KIND_VMLS_REVOCATION_REQUEST, tags, ""))))
            assertRefuses<IllegalArgumentException> { parse(o.toString()) }
        }
        assertRefuses<IllegalArgumentException> { parse(" ".repeat(16_385)) }
        try { parse("[".repeat(2000) + "0" + "]".repeat(2000)); fail("nested array accepted") } catch (_: Exception) { }
    }

    @Test fun `a valid inner rumor is unsigned and has no free form content`() {
        assertEquals(request, parse(request.rumor().toString()))
        assertEquals("", request.rumor().getValue("content").jsonPrimitive.content)
        assertFalse("sig" in request.rumor())
        assertRefuses<IllegalArgumentException> { request.copy(sender = keeper) }
        assertRefuses<IllegalArgumentException> { request.copy(boxes = emptyList()) }
    }
}
