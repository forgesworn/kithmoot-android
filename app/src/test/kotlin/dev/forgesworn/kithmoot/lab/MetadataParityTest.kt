package dev.forgesworn.kithmoot.lab

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.security.MessageDigest

/** Exact synthetic Node oracle vectors. No Android runtime, keys or transport needed. */
class MetadataParityTest {
    private val fixtureBytes = requireNotNull(javaClass.getResourceAsStream("/lab/metadata-vectors.json")).use { it.readBytes() }
    private val fixture = Json.parseToJsonElement(fixtureBytes.toString(Charsets.UTF_8)).jsonObject
    private val input = fixture.getValue("manifestInput").jsonObject
    private val terms = input.getValue("terms").jsonObject.mapValues { it.value.jsonPrimitive.content }
    private val salt = hex(input.getValue("saltHex").jsonPrimitive.content)
    private val actions = input.getValue("actionSequence").jsonArray.map { it.jsonPrimitive.content }
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun clock(values: List<Long>): () -> Long {
        var index = 0
        return { values.getOrElse(index++) { values.last() } }
    }
    private fun session(times: List<Long> = listOf(1000, 1010, 2000), maxFrames: Int = 10000,
                        maxBytes: Long = 8000000, selectedTerms: Map<String, String> = terms) = MetadataSession(
        "1".repeat(64), "synthetic-parity", selectedTerms, salt, "bob", input.getValue("durationMs").jsonPrimitive.long,
        actions, input.getValue("signerOrigin").jsonPrimitive.content, clock(times), 1000, maxFrames, maxBytes)
    private fun observe(s: MetadataSession, wire: ByteArray, source: String = "app-page", endpoint: String = "ordinary-relay",
                        origin: String = "wss://ordinary.invalid", url: String? = null, transport: String = "websocket",
                        direction: String = "out") = s.observe(direction, source, endpoint, origin, url, transport, wire)
    private inline fun rejected(block: () -> Unit) {
        var thrown = false
        try { block() } catch (_: RuntimeException) { thrown = true }
        assertTrue("capture must reject unsupported input", thrown)
    }
    private fun records(snapshot: JsonObject) = snapshot.getValue("records").jsonArray.map { it.jsonObject }
    private fun subset(expected: JsonElement, actual: JsonElement, path: String) {
        if (expected is JsonObject) {
            assertTrue("$path must be object", actual is JsonObject)
            for ((key, value) in expected) {
                val found = actual.jsonObject[key]
                assertNotNull("$path.$key missing", found)
                if (key in setOf("matches", "endpointUrlMatches", "requiredRootMatches")) {
                    val roles = if (key == "requiredRootMatches") listOf("aliceRootHex", "bobRootHex") else MetadataPrimitives.roles
                    for (role in roles) assertEquals("$path.$key.$role", value.jsonObject[role]?.jsonPrimitive?.int ?: 0,
                        found!!.jsonObject.getValue(role).jsonPrimitive.int)
                } else subset(value, found!!, "$path.$key")
            }
        } else if (expected is JsonArray) {
            assertTrue("$path must be array", actual is JsonArray)
            assertEquals("$path length", expected.size, actual.jsonArray.size)
            expected.forEachIndexed { i, value -> subset(value, actual.jsonArray[i], "$path[$i]") }
        } else assertEquals(path, expected, actual)
    }

    @Test fun fixtureIsByteIdenticalAndManifestUsesTheNodeDomainAndRoleOrder() {
        val digest = MessageDigest.getInstance("SHA-256").digest(fixtureBytes)
        assertEquals("0bacbb1e8e945845a4b82af0093c46c3bf7b6f41f98fdf599ea75719e0faf7a1", MetadataPrimitives.hex(digest))
        assertEquals(fixture.getValue("roleOrder").jsonArray.map { it.jsonPrimitive.content }, MetadataPrimitives.roles)
        val s = session()
        val expected = fixture.getValue("expectedManifest").jsonObject
        assertEquals(expected.getValue("manifestDigest").jsonPrimitive.content, s.manifestDigest)
        assertEquals(expected.getValue("roleHashes").jsonObject.mapValues { it.value.jsonPrimitive.content }, s.roleHashes)
        s.close()
    }

    @Test fun everyNodeRecordVectorMatchesIncludingCanonicalEventsUrlsBinaryErrorsAndBudgets() {
        for (value in fixture.getValue("cases").jsonArray) {
            val case = value.jsonObject; val name = case.getValue("name").jsonPrimitive.content
            val options = case.getValue("options").jsonObject
            val s = session(case.getValue("clock").jsonArray.map { it.jsonPrimitive.long },
                options.getValue("maxFrames").jsonPrimitive.int, options.getValue("maxBytes").jsonPrimitive.long)
            var failure: RuntimeException? = null
            try {
                val request = case["httpRequest"] as? JsonObject
                if (request != null) {
                    val url = request.getValue("url").jsonPrimitive.content
                    val values = mutableListOf(url)
                    for ((header, content) in request.getValue("headers").jsonObject) { values += header; values += content.jsonPrimitive.content }
                    request["postData"]?.let { values += it.jsonPrimitive.content }
                    for (wire in values) observe(s, wire.toByteArray(Charsets.UTF_8), endpoint = "app-origin", origin = "https://app.invalid", transport = "http-value")
                } else for (record in case.getValue("inputs").jsonArray) {
                    val r = record.jsonObject
                    fun text(key: String, fallback: String) = r[key]?.jsonPrimitive?.content ?: fallback
                    val wire = r["wireBase64"]?.let { Base64.getDecoder().decode(it.jsonPrimitive.content) }
                        ?: r.getValue("wire").jsonPrimitive.content.toByteArray(Charsets.UTF_8)
                    observe(s, wire, text("sourceLabel", "app-page"), text("endpointLabel", "ordinary-relay"),
                        text("endpointOrigin", "wss://ordinary.invalid"), r["endpointUrl"]?.jsonPrimitive?.content,
                        text("transport", "websocket"), text("direction", "out"))
                }
            } catch (e: RuntimeException) { failure = e }
            val expected = case.getValue("expected").jsonObject
            assertEquals("$name rejection", expected.getValue("failure") !is JsonNull, failure != null)
            val actual = s.close(); val actualRecords = records(actual)
            subset(expected.getValue("records"), actual.getValue("records"), name)
            assertEquals("$name payload bytes", expected.getValue("capturedValueBytes").jsonPrimitive.long, actual.getValue("capturedValueBytes").jsonPrimitive.long)
            assertEquals("$name errors", expected.getValue("errors").jsonPrimitive.int, actual.getValue("errors").jsonPrimitive.int)
            assertEquals("$name overflow", expected.getValue("overflow"), actual.getValue("overflow"))
            assertEquals("$name clock", expected.getValue("clockRegression"), actual.getValue("clockRegression"))
            assertEquals("$name duration", expected.getValue("elapsedMs").jsonPrimitive.long, actual.getValue("durationMs").jsonPrimitive.long)
            assertEquals("$name frames", expected.getValue("rawFrameCount").jsonPrimitive.int, actualRecords.size)
            val events = actualRecords.mapNotNull { (it["canonicalEventDigest"] as? JsonPrimitive)?.takeIf { v -> v !is JsonNull }?.content }
            assertEquals("$name event frames", expected.getValue("rawEventFrameCount").jsonPrimitive.int, events.size)
            assertEquals("$name unique events", expected.getValue("uniqueCanonicalEventCount").jsonPrimitive.int, events.toSet().size)
            assertEquals(JsonPrimitive(false), actual.getValue("absenceClaim"))
            assertEquals(JsonPrimitive(false), actual.getValue("privacyReductionClaim"))
        }
    }

    @Test fun streamingUtf8AcrossEveryByteBoundaryProducesOneCompleteLogicalBody() {
        val chunks = fixture.getValue("chunkAcceptance").jsonObject.getValue("chunksUtf8Hex").jsonArray
        val s = session(); val body = requireNotNull(s.stream("out", "https://app.invalid/body", "external-http", "request-body"))
        for (chunk in chunks) body.write(hex(chunk.jsonPrimitive.content))
        body.finish(true); body.finish(true)
        val result = s.close(); val record = records(result).single()
        assertEquals(40, record.getValue("capturedValueBytes").jsonPrimitive.int)
        assertEquals("2b16e32394e28e841388e8c1c73412fab940318a413d40cf588c087cf89052db", record.getValue("wireDigest").jsonPrimitive.content)
        assertEquals(1, record.getValue("matches").jsonObject.getValue("messageAlice").jsonPrimitive.int)
        assertEquals(JsonPrimitive(true), record.getValue("bodyComplete"))
        assertFalse(record.containsKey("endpointUrlMatches"))
        assertEquals(0, result.getValue("coverageGaps").jsonPrimitive.int)
        assertEquals(0, result.getValue("inFlight").jsonPrimitive.int)
        assertEquals(0, result.getValue("endpointUrlReferenceBytes").jsonPrimitive.int)
    }

    @Test fun earlyCloseAndUnfinishedResponseAreExplicitGapsNeverWholeBodyAbsence() {
        val partial = session(); val body = requireNotNull(partial.stream("in", "https://app.invalid/body", "external-http", "response-body"))
        body.write("part".toByteArray()); body.finish(false)
        val result = partial.close()
        assertEquals(1, result.getValue("coverageGaps").jsonPrimitive.int)
        assertEquals(JsonPrimitive(false), records(result).single().getValue("bodyComplete"))
        assertEquals("partial-actual-consumption", records(result).single().getValue("bodyCoverage").jsonPrimitive.content)
        val unfinished = session(); requireNotNull(unfinished.stream("in", "https://app.invalid/unused", "external-http", "response-body"))
        val open = unfinished.close()
        assertTrue(records(open).isEmpty()); assertEquals(1, open.getValue("inFlight").jsonPrimitive.int)
        assertEquals(1, open.getValue("missingRecords").jsonPrimitive.int)
        assertEquals(JsonPrimitive(false), open.getValue("absenceClaim"))
    }

    @Test fun frameAndStreamingOverflowInvalidateWithoutInflatingFramesOrBytes() {
        val frames = session(maxFrames = 1); observe(frames, "[]".toByteArray())
        rejected { observe(frames, "[]".toByteArray()) }
        val first = frames.close(); assertEquals(1, records(first).size); assertTrue(first.getValue("overflow").jsonPrimitive.boolean)
        val bytes = session(maxBytes = 2); val body = requireNotNull(bytes.stream("out", "https://app.invalid/body", "external-http", "request-body"))
        body.write(byteArrayOf(1, 2, 3)); body.finish(true)
        val second = bytes.close(); assertTrue(records(second).isEmpty()); assertTrue(second.getValue("overflow").jsonPrimitive.boolean)
        assertTrue(second.getValue("errors").jsonPrimitive.int > 0); assertEquals(0, second.getValue("inFlight").jsonPrimitive.int)
    }

    @Test fun invalidLabelsUrlsAndUnsafeNumbersRejectAndPoisonTheCapture() {
        val cases: List<(MetadataSession) -> Unit> = listOf(
            { observe(it, "[]".toByteArray(), direction = "sideways") },
            { observe(it, "[]".toByteArray(), source = "invented-source") },
            { observe(it, "[]".toByteArray(), endpoint = "invented-endpoint") },
            { observe(it, "[]".toByteArray(), url = "wss://wrong.invalid/") },
            { observe(it, "[]".toByteArray(), endpoint = "signer-public-relay", origin = "wss://wrong.invalid") },
            { observe(it, "[\"NOTICE\",9007199254740992]".toByteArray()) }
        )
        for ((index, action) in cases.withIndex()) {
            val s = session(); rejected { action(s) }; val result = s.close()
            assertTrue("case $index must not look healthy after rejection", result.getValue("errors").jsonPrimitive.int > 0)
        }
        for (time in listOf(-1L, 9007199254740992L)) rejected { session(times = listOf(time)) }
        rejected { session(selectedTerms = terms + ("messageAlice" to "\uD800")) }
        rejected { MetadataPrimitives.stringify(JsonPrimitive("\uD800")) }
    }

    @Test fun invalidStreamingSliceCannotSubtractBytesOrRemainHealthy() {
        val s = session(); val body = requireNotNull(s.stream("out", "https://app.invalid/body", "external-http", "request-body"))
        rejected { body.write(byteArrayOf(1), 0, -1) }
        val result = s.close()
        assertTrue(result.getValue("capturedValueBytes").jsonPrimitive.long >= 0)
        assertTrue(result.getValue("errors").jsonPrimitive.int > 0)
    }
}
