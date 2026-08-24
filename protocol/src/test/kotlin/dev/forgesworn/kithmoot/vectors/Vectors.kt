package dev.forgesworn.kithmoot.vectors

import dev.forgesworn.kithmoot.crypto.hexToBytes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * The published interop vectors, loaded verbatim from the TypeScript
 * implementation's `vectors/kithmoot-vectors.json`. This file is a copy, never
 * an edit: if a vector here cannot be satisfied, the two implementations
 * disagree and that is the finding.
 */
object Vectors {

    val root: JsonObject by lazy {
        val stream = requireNotNull(Vectors::class.java.getResourceAsStream("/kithmoot-vectors.json")) {
            "kithmoot-vectors.json is missing from the test resources"
        }
        Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
    }

    val groups: JsonObject get() = root.getValue("groups").jsonObject

    fun group(name: String): List<JsonObject> = groups.getValue(name).jsonArray.map { it.jsonObject }

    /** One JUnit parameter set per vector, named so a failure names the vector. */
    fun parameters(name: String): Collection<Array<Any>> =
        group(name).map { arrayOf(it.getValue("name").jsonPrimitive.content, it) }
}

fun JsonObject.child(key: String): JsonObject = getValue(key).jsonObject
fun JsonObject.childOrNull(key: String): JsonObject? = (this[key] as? JsonObject)
fun JsonObject.list(key: String): JsonArray = getValue(key).jsonArray
fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
fun JsonObject.textOrNull(key: String): String? =
    this[key]?.takeIf { it != JsonNull }?.jsonPrimitive?.content
fun JsonObject.number(key: String): Long = getValue(key).jsonPrimitive.long
fun JsonObject.decimal(key: String): Double = getValue(key).jsonPrimitive.double
fun JsonObject.flag(key: String): Boolean = getValue(key).jsonPrimitive.boolean
fun JsonObject.bytes(key: String): ByteArray = text(key).hexToBytes()
fun JsonObject.strings(key: String): List<String> = list(key).map { it.jsonPrimitive.content }
fun JsonObject.isNull(key: String): Boolean = this[key] == null || this[key] == JsonNull
