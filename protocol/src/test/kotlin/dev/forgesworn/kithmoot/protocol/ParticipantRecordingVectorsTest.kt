package dev.forgesworn.kithmoot.protocol

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Verbatim published TypeScript signatures, independently verified. Re-encode
 * complete JSON so omitted call or recorder fields cannot silently pass. */
@RunWith(Parameterized::class)
class ParticipantRecordingVectorsTest(private val name: String, private val raw: JsonObject,
    private val room: String, private val participant: String, private val device: String, private val call: String) {
    @Test fun verifiesCompletePublishedWarning() {
        val state = requireNotNull(LiveRecordingState.fromJson(raw))
        assertEquals("complete $name JSON", raw, state.toJson())
        assertTrue(verifyLiveRecordingState(room, state, participant, device, call, 4))
        assertFalse(verifyLiveRecordingState("cd".repeat(32), state, participant, device, call, 4))
        assertFalse(verifyLiveRecordingState(room, state, "aa".repeat(32), device, call, 4))
        assertFalse(verifyLiveRecordingState(room, state, participant, "bb".repeat(32), call, 4))
        for (profile in listOf(null, 2, 3, 5))
            assertFalse(verifyLiveRecordingState(room, state, participant, device, call, profile))
        // Context-free legacy verification may never adopt member fields.
        assertFalse(verifyLiveRecordingState(room, state, device))
        assertFalse(verifyLiveRecordingState(room, state.copy(recording = state.recording.copy(sig = "00".repeat(64))),
            participant, device, call, 4))
        state.capture?.let { capture ->
            assertFalse(verifyLiveRecordingState(room, state.copy(capture = capture.copy(sig = "00".repeat(64))),
                participant, device, call, 4))
        }
    }

    @Test fun warningCannotMoveCallsAndExactOffSurvivesLeavingTheOriginalCall() {
        val state = requireNotNull(LiveRecordingState.fromJson(raw))
        assertEquals(!state.recording.notice.on,
            verifyLiveRecordingState(room, state, participant, device, "34".repeat(16), 4))
        assertEquals(!state.recording.notice.on,
            verifyLiveRecordingState(room, state, participant, device, null, 4))
    }

    @Test fun partialMalformedAndMismatchedMemberBindingsAreRejected() {
        for (key in listOf("call", "recorder", "device")) {
            assertNull(LiveRecordingState.fromJson(JsonObject(raw - key)))
            for (value in listOf(JsonNull, JsonPrimitive(4), JsonPrimitive(true), JsonPrimitive("bad")))
                assertNull(LiveRecordingState.fromJson(JsonObject(raw + (key to value))))
        }
        assertNull(LiveRecordingState.fromJson(JsonObject(raw + ("extra" to JsonPrimitive(true)))))
        if ("capture" in raw) {
            assertNull(LiveRecordingState.fromJson(JsonObject(raw + ("recorder" to JsonPrimitive("aa".repeat(32))))))
            assertNull(LiveRecordingState.fromJson(JsonObject(raw + ("device" to JsonPrimitive("bb".repeat(32))))))
        }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun cases(): Collection<Array<Any>> {
            val bytes = requireNotNull(ParticipantRecordingVectorsTest::class.java.getResourceAsStream("/participant-recording.json"))
                .use { it.readBytes() }
            val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            assertEquals("kithmoot.participant-recording.v1", root.getValue("format").jsonPrimitive.content)
            val cases = root.getValue("cases").jsonArray
            assertEquals(setOf("audio", "gallery", "speaker", "screen-camera", "off"),
                cases.map { it.jsonObject.getValue("name").jsonPrimitive.content }.toSet())
            assertEquals(5, cases.size)
            fun text(key: String) = root.getValue(key).jsonPrimitive.content
            return cases.map { row ->
                val obj = row.jsonObject
                arrayOf<Any>(obj.getValue("name").jsonPrimitive.content, obj.getValue("state").jsonObject,
                    text("roomId"), text("participant"), text("device"), text("call"))
            }
        }
    }
}
