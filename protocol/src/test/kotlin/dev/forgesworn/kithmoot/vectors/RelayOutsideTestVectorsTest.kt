package dev.forgesworn.kithmoot.vectors

import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.RELAY_TEST_STAGES
import dev.forgesworn.kithmoot.protocol.RelayTestObservations
import dev.forgesworn.kithmoot.protocol.RelayTestVerdict
import dev.forgesworn.kithmoot.protocol.isValidRelayTestRequest
import dev.forgesworn.kithmoot.protocol.isValidRelayTestResult
import dev.forgesworn.kithmoot.protocol.relayTestFinding
import dev.forgesworn.kithmoot.protocol.relayTestVerdict
import dev.forgesworn.kithmoot.protocol.unwrapSignal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared `relay-outside-test-v1.json` vectors (docs/protocol.md, "Relay
 * outside test"): which request and result bodies a reader accepts, the
 * verdict a tester sends for what its two peers saw, what a host may say
 * about a result, and whole signal wraps carrying each message. A verbatim
 * copy of the web project's file, kept apart from `kithmoot-vectors.json`.
 */
class RelayOutsideTestVectorsTest {
    private val root: JsonObject = Json.parseToJsonElement(
        requireNotNull(RelayOutsideTestVectorsTest::class.java.getResourceAsStream("/relay-outside-test-v1.json")) {
            "relay-outside-test-v1.json is missing from the test resources"
        }.bufferedReader().use { it.readText() },
    ).jsonObject

    private fun cases(group: String) = root.list(group).map { it.jsonObject }

    @Test fun namesTheSameStagesAsTheCodec() {
        assertEquals(RELAY_TEST_STAGES, root.strings("stages"))
    }

    @Test fun everyRequestIsJudgedAsTheWebJudgesIt() {
        for (v in cases("requests")) assertEquals("request: ${v.text("name")}", v.flag("accept"), isValidRelayTestRequest(v["relayTest"]))
    }

    @Test fun everyResultIsJudgedAsTheWebJudgesIt() {
        for (v in cases("results")) assertEquals("result: ${v.text("name")}", v.flag("accept"), isValidRelayTestResult(v["relayTest"]))
    }

    @Test fun everyVerdictAndFindingMatches() {
        for (v in cases("verdicts")) {
            val name = v.text("name")
            val seen = v.child("observations")
            val verdict = relayTestVerdict(RelayTestObservations(
                turnHost = seen.text("turnHost"),
                relayed = seen.flag("relayed"),
                reflexive = seen.strings("reflexive"),
                hosts = seen.strings("hosts"),
                local = seen.textOrNull("local"),
                echoed = seen.flag("echoed"),
                pathA = seen.flag("pathA"),
                pathB = seen.flag("pathB"),
            ))
            val expected = v.child("verdict")
            assertEquals("verdict: $name", RelayTestVerdict(
                ok = expected.flag("ok"),
                stage = expected.textOrNull("stage"),
                sameNetwork = expected["sameNetwork"]?.jsonPrimitive?.booleanOrNull == true,
            ), verdict)
            // What the tester sends is exactly the vector's verdict, and a reader accepts it.
            val body = buildJsonObject {
                put("id", "0123456789abcdef"); put("endpoint", root.text("endpoint")); put("ok", verdict.ok)
                verdict.stage?.let { put("stage", it) }
                if (verdict.sameNetwork) put("sameNetwork", true)
            }
            assertEquals("verdict body: $name", JsonObject(mapOf("id" to JsonPrimitive("0123456789abcdef"), "endpoint" to JsonPrimitive(root.text("endpoint"))) + expected), body)
            assertTrue("verdict accepted: $name", isValidRelayTestResult(body))
            assertEquals("finding: $name", v.text("finding"), relayTestFinding(verdict))
        }
    }

    @Test fun aTestThatDidNotRunIsNeverAFinding() {
        for (v in cases("notRun")) {
            val result = v.child("result")
            assertEquals("finding: ${v.text("name")}", v.text("finding"),
                relayTestFinding(result.flag("ok"), result.textOrNull("stage"), result["sameNetwork"]?.jsonPrimitive?.boolean == true))
        }
    }

    @Test fun everyWrapOpensAsTheWebOpensIt() {
        for (v in cases("wraps")) {
            val name = v.text("name")
            val key = ByteArray(32) { v.getValue("recipientKeyRepeatedByte").jsonPrimitive.int.toByte() }
            val opened = unwrapSignal(NostrEvent.fromJson(v.child("event")), key, root.text("roomId"), now = v.number("now"))
            val expect = v["expect"]
            if (expect == null || expect == JsonNull) { assertNull("wrap: $name", opened); continue }
            val want = expect.jsonObject
            assertNotNull("wrap: $name", opened)
            assertEquals("from: $name", want.text("from"), opened!!.from)
            assertEquals("createdAt: $name", want.number("createdAt"), opened.createdAt)
            assertEquals("body: $name", want.child("body"), opened.body.toJson())
        }
    }
}
