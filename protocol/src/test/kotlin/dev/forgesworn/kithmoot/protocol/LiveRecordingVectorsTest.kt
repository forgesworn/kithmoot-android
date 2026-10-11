package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.hexToBytes
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Verbatim generated TypeScript events, independently decrypted and verified.
 * JSON re-encoding prevents a field missing from both models passing silently. */
@RunWith(Parameterized::class)
class LiveRecordingVectorsTest(private val name: String, private val row: JsonObject) {
    @Test fun verifiesPublishedWireCase() {
        val input = row.getValue("input").jsonObject
        val expected = row.getValue("expected").jsonObject
        fun text(key: String) = input.getValue(key).jsonPrimitive.content
        val raw = input.getValue("entry").jsonObject
        val state = LiveRecordingState.fromJson(raw["liveRecording"])
        assertEquals("state for $name", expected.getValue("state"), state?.toJson() ?: JsonNull)
        assertEquals("authority for $name", expected.getValue("authorised").jsonPrimitive.boolean,
            state?.let { verifyLiveRecordingState(text("verificationRoom"), it, text("authority")) } == true)
        val event = NostrEvent.fromJson(row.getValue("event").jsonObject)
        val decoded = requireNotNull(decodeRosterEvent(event, text("roomId"), text("roomKeyHex").hexToBytes(), raw.getValue("updatedAt").jsonPrimitive.long))
        assertEquals("complete decoded JSON for $name", expected.getValue("entry"), decoded.toJson())
        assertEquals("live authority for $name", expected.getValue("liveAuthorised").jsonPrimitive.boolean,
            decoded.liveRecording?.let { verifyLiveRecordingState(text("verificationRoom"), it, text("authority")) } == true)
        val model = RosterEntry.fromJson(raw)
        if (model.toJson() == raw) {
            val rebuilt = encodeRosterEvent(model, text("roomId"), text("roomKeyHex").hexToBytes(),
                text("deviceSkHex").hexToBytes(), text("nonceHex").hexToBytes(), text("auxRandHex").hexToBytes())
            assertEquals("exact encrypted event for $name", event, rebuilt)
        }
    }
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun cases(): Collection<Array<Any>> {
            val bytes = requireNotNull(LiveRecordingVectorsTest::class.java.getResourceAsStream("/recording-live.json")).use { it.readBytes() }
            val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            assertEquals("kithmoot/v1", root.getValue("protocolVersion").jsonPrimitive.content)
            val rows = root.getValue("cases").jsonArray
            assertEquals(29, rows.size)
            assertEquals(29, rows.map { it.jsonObject.getValue("name").jsonPrimitive.content }.toSet().size)
            return rows.map { val row = it.jsonObject; arrayOf<Any>(row.getValue("name").jsonPrimitive.content, row) }
        }
    }
}
