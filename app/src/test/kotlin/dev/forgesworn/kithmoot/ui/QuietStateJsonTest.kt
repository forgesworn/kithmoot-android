package dev.forgesworn.kithmoot.ui

import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.session.QuietTransport
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QuietStateJsonTest {
    private val event = NostrEvent(
        kind = 1460,
        createdAt = 1,
        tags = emptyList(),
        content = "cipher",
        pubkey = "1".repeat(64),
        id = "2".repeat(64),
        sig = "3".repeat(128),
    )

    @Test
    fun `box pending ownership survives saved room round trip`() {
        val restored = quietStateFromJson(quietStateToJson(QuietTransport.QuietState(
            used = emptyMap(),
            queued = listOf(event),
            boxPending = setOf(event.id),
            keyFingerprint = "4".repeat(64),
        )))

        assertEquals(listOf(event), restored?.queued)
        assertEquals(setOf(event.id), restored?.boxPending)
        assertEquals("4".repeat(64), restored?.keyFingerprint)
    }

    @Test
    fun `old quiet state has no box pending ownership`() {
        val old = quietStateToJson(QuietTransport.QuietState(emptyMap(), listOf(event))).let {
            buildJsonObject { for ((key, value) in it) if (key != "boxPending") put(key, value) }
        }

        assertEquals(emptySet(), quietStateFromJson(old)?.boxPending)
    }

    @Test
    fun `saved box pending ownership must name a retained event exactly once`() {
        val valid = quietStateToJson(QuietTransport.QuietState(emptyMap(), listOf(event)))
        val invalid = buildJsonObject {
            for ((key, value) in valid) put(key, value)
            put("boxPending", buildJsonArray {
                add(JsonPrimitive("4".repeat(64)))
                add(JsonPrimitive("4".repeat(64)))
            })
        }

        assertNull(quietStateFromJson(invalid))
    }
}
