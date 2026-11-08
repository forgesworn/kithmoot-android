package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.toHex
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import kotlinx.serialization.json.*

const val NODE_MIGRATION_FRAME_CHARS = 1024
private const val MAX_MIGRATION_WIRE_BYTES = 131072
private const val MAX_MIGRATION_FRAME_CHARS = 174763
private const val MAX_MIGRATION_FRAMES = 171
data class NodeMigrationFrame(val id: String, val part: Int, val total: Int, val data: String) {
    fun toJson() = buildJsonObject { put("op", "node-migration"); put("v", 1); put("id", id); put("part", part); put("total", total); put("data", data) }
}
/** Only carriers inside the room's authenticated encrypted control channel. */
object NodeMigrationFrames {
    private val dataPattern = Regex("^[A-Za-z0-9_-]+$")
    fun read(raw: JsonElement?): NodeMigrationFrame? = runCatching {
        val f = NodeWire.shape(raw, setOf("op", "v", "id", "part", "total", "data"))
        require(NodeWire.string(f["op"]) == "node-migration" && NodeWire.number(f["v"]) == 1L)
        val part = NodeWire.number(f["part"]); val total = NodeWire.number(f["total"])
        require(total in 1..MAX_MIGRATION_FRAMES.toLong() && part < total)
        val id = NodeWire.string(f["id"]); val data = NodeWire.string(f["data"])
        require(NodeWire.key.matches(id) && data.length in 1..NODE_MIGRATION_FRAME_CHARS && dataPattern.matches(data)
            && (part == total - 1 || data.length == NODE_MIGRATION_FRAME_CHARS))
        NodeMigrationFrame(id, part.toInt(), total.toInt(), data)
    }.getOrNull()
    internal fun signedWire(raw: JsonElement?): JsonObject {
        val event = NodeWire.event(raw, MAX_MIGRATION_WIRE_BYTES)
        require(event.kind == NODE_MIGRATION_KIND || event.kind == NODE_MIGRATION_READY_KIND || event.kind == NODE_RECORD_KIND)
        return raw as JsonObject
    }
    fun encode(raw: JsonObject): List<NodeMigrationFrame> {
        signedWire(raw)
        val wire = raw.toString().toByteArray(Charsets.UTF_8)
        require(wire.size <= MAX_MIGRATION_WIRE_BYTES)
        val data = Base64.getUrlEncoder().withoutPadding().encodeToString(wire)
        val id = Digests.sha256(wire).toHex(); val parts = data.chunked(NODE_MIGRATION_FRAME_CHARS)
        return parts.mapIndexed { index, part -> NodeMigrationFrame(id, index, parts.size, part) }
    }
}
/** One receiver per room. Source is the verified outer sender; never accept a
 * sender claimed by a fragment. Same bounds and expiry as the JS receiver. */
class NodeMigrationFrameReceiver {
    private data class Pending(val source: String, val at: Long, val total: Int, val parts: MutableMap<Int, String>)
    private val pending = linkedMapOf<String, Pending>()
    private val completed = linkedMapOf<String, Long>()
    @Synchronized fun push(raw: JsonElement?, source: String, now: Long): JsonObject? {
        if (!NodeWire.key.matches(source) || !NodeWire.integer(now)) return null
        pending.entries.removeAll { now < it.value.at || it.value.at + 300 <= now }
        completed.entries.removeAll { now < it.value || it.value + 300 <= now }
        val frame = NodeMigrationFrames.read(raw) ?: return null
        val key = "$source:${frame.id}"
        if (key in completed) return null
        var state = pending[key]
        if (state == null) {
            if (pending.size >= 8 || pending.values.count { it.source == source } >= 2) return null
            state = Pending(source, now, frame.total, mutableMapOf()); pending[key] = state
        }
        if (state.total != frame.total || state.parts.containsKey(frame.part) && state.parts[frame.part] != frame.data) {
            pending.remove(key); return null
        }
        state.parts[frame.part] = frame.data
        if (state.parts.size != state.total) return null
        pending.remove(key)
        return runCatching {
            val data = (0 until state.total).joinToString("") { state.parts.getValue(it) }
            require(data.length <= MAX_MIGRATION_FRAME_CHARS)
            val wire = Base64.getUrlDecoder().decode(data)
            require(wire.size <= MAX_MIGRATION_WIRE_BYTES && Base64.getUrlEncoder().withoutPadding().encodeToString(wire) == data
                && Digests.sha256(wire).toHex() == frame.id)
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(wire)).toString()
            val event = NodeMigrationFrames.signedWire(Json.parseToJsonElement(text))
            if (completed.size >= 64) completed.remove(completed.keys.first())
            completed[key] = now
            event
        }.getOrNull()
    }
    @Synchronized fun clear() { pending.clear(); completed.clear() }
}
