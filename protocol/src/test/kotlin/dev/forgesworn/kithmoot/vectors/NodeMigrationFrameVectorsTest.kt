package dev.forgesworn.kithmoot.vectors

import dev.forgesworn.kithmoot.protocol.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NodeMigrationFrameVectorsTest {
    private val v = Json.parseToJsonElement(requireNotNull(javaClass.getResource("/node-migration-vectors.json")).readText()).jsonObject
    private val now = v.getValue("now").jsonPrimitive.long
    private val source = v.getValue("preparation").jsonObject.getValue("pubkey").jsonPrimitive.content
    private val frames = v.getValue("frames").jsonArray
    @Test fun reproducesAndReassemblesTheExactTypeScriptFragments() {
        assertEquals(frames, JsonArray(NodeMigrationFrames.encode(v.getValue("commit").jsonObject).map { it.toJson() }))
        assertTrue(frames.size > 1)
        assertTrue(frames.all { it.toString().length <= 2000 })
        val receiver = NodeMigrationFrameReceiver()
        val result = frames.reversed().mapNotNull { receiver.push(it, source, now) }
        assertEquals(listOf(v.getValue("commit")), result)
        assertTrue(frames.all { receiver.push(it, source, now) == null })
    }
    @Test fun refusesChangedBytesUnknownFieldsAndAssembliesFromDifferentSenders() {
        val receiver = NodeMigrationFrameReceiver()
        frames.forEachIndexed { index, frame -> assertNull(receiver.push(frame, if (index == 0) "00".repeat(32) else source, now)) }
        receiver.clear()
        frames.drop(1).forEach { assertNull(receiver.push(it, source, now)) }
        val first = frames[0].jsonObject
        val data = first.getValue("data").jsonPrimitive.content
        val altered = JsonObject(first + ("data" to JsonPrimitive(data.dropLast(1) + if (data.endsWith("a")) "b" else "a")))
        assertNull(receiver.push(altered, source, now))
        assertNull(NodeMigrationFrames.read(JsonObject(first + ("extra" to JsonPrimitive(true)))))
        assertNull(NodeMigrationFrames.read(JsonObject(first + ("total" to JsonPrimitive(1000000)))))
    }
    @Test fun expiresPartialAssembliesAndBoundsPendingCopies() {
        val receiver = NodeMigrationFrameReceiver()
        receiver.push(frames[0], source, now)
        frames.drop(1).forEach { assertNull(receiver.push(it, source, now + 300)) }
        receiver.clear()
        val first = frames[0].jsonObject
        repeat(100) { receiver.push(JsonObject(first + ("id" to JsonPrimitive(it.toString(16).padStart(64, '0')))), source, now) }
        assertEquals(listOf(v.getValue("commit")), frames.mapNotNull { receiver.push(it, source, now + 300) })
    }
}
