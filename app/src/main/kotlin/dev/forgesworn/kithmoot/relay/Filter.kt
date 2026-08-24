package dev.forgesworn.kithmoot.relay

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A NIP-01 subscription filter.
 *
 * Only the fields KithMoot actually uses are modelled. [tags] carries the
 * single-letter tag filters in their wire form, so `"#d" to listOf(roomId)`
 * rather than a room-shaped abstraction - the pool has no business knowing what
 * a room is.
 */
data class Filter(
    val ids: List<String>? = null,
    val authors: List<String>? = null,
    val kinds: List<Int>? = null,
    val tags: Map<String, List<String>> = emptyMap(),
    val since: Long? = null,
    val until: Long? = null,
    val limit: Int? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        ids?.let { put("ids", stringArray(it)) }
        authors?.let { put("authors", stringArray(it)) }
        kinds?.let { put("kinds", buildJsonArray { for (kind in it) add(JsonPrimitive(kind)) }) }
        for ((name, values) in tags) put(name, stringArray(values))
        since?.let { put("since", it) }
        until?.let { put("until", it) }
        limit?.let { put("limit", it) }
    }

    private fun stringArray(values: List<String>) =
        buildJsonArray { for (value in values) add(JsonPrimitive(value)) }
}
