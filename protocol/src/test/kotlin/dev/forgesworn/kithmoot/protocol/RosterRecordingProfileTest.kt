package dev.forgesworn.kithmoot.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RosterRecordingProfileTest {
    private val credential = NostrEvent(20460, 0, emptyList(), "", "00".repeat(32), "00".repeat(32), "00".repeat(64))
    private val entry = RosterEntry("aa".repeat(32), "bb".repeat(32), credential, updatedAt = 1_800_000_000)

    @Test fun `only numeric capability two survives and legacy JSON remains unchanged`() {
        val baseline = entry.toJson()
        assertNull(baseline["recordingProfile"])
        for (text in listOf("null", "true", "\"2\"", "1", "3", "{}", "[]")) {
            val fields = baseline.toMutableMap()
            fields["recordingProfile"] = Json.parseToJsonElement(text)
            val decoded = RosterEntry.fromJson(JsonObject(fields))
            assertNull(decoded.recordingProfile)
            assertEquals(baseline, decoded.toJson())
        }
        for (text in listOf("2", "2.0", "2e0")) {
            val fields = baseline.toMutableMap()
            fields["recordingProfile"] = Json.parseToJsonElement(text)
            val decoded = RosterEntry.fromJson(JsonObject(fields))
            assertEquals(2, decoded.recordingProfile)
            assertEquals(entry.copy(recordingProfile = 2).toJson(), decoded.toJson())
        }
        assertEquals(baseline, entry.copy(recordingProfile = 99).toJson())
    }
}
