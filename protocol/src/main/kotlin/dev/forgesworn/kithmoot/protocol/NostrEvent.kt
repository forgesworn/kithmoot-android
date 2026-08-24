package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * A signed Nostr event, in the field order NIP-01 serialises and the KithMoot
 * wire format nests: kind, created_at, tags, content, pubkey, id, sig.
 */
data class NostrEvent(
    val kind: Int,
    val createdAt: Long,
    val tags: List<List<String>>,
    val content: String,
    val pubkey: String,
    val id: String,
    val sig: String,
) {
    /** The first value of the first tag with this name, or null. */
    fun tagValue(name: String): String? =
        tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)

    fun toJson(): JsonObject = buildJsonObject {
        put("kind", kind)
        put("created_at", createdAt)
        put("tags", tagsToJson(tags))
        put("content", content)
        put("pubkey", pubkey)
        put("id", id)
        put("sig", sig)
    }

    /** Compact JSON, byte-identical to what the TypeScript client publishes. */
    fun toCompactJson(): String = toJson().toString()

    companion object {
        fun fromJson(json: JsonObject): NostrEvent = NostrEvent(
            kind = json.getValue("kind").jsonPrimitive.int,
            createdAt = json.getValue("created_at").jsonPrimitive.long,
            tags = tagsFromJson(json.getValue("tags").jsonArray),
            content = json.getValue("content").jsonPrimitive.content,
            pubkey = json.getValue("pubkey").jsonPrimitive.content,
            id = json.getValue("id").jsonPrimitive.content,
            sig = json.getValue("sig").jsonPrimitive.content,
        )

        fun fromJson(json: kotlinx.serialization.json.JsonElement): NostrEvent = fromJson(json.jsonObject)
    }
}

internal fun tagsToJson(tags: List<List<String>>): JsonArray = buildJsonArray {
    for (tag in tags) {
        add(buildJsonArray { for (value in tag) add(JsonPrimitive(value)) })
    }
}

internal fun tagsFromJson(tags: JsonArray): List<List<String>> =
    tags.map { tag -> tag.jsonArray.map { it.jsonPrimitive.content } }

/**
 * Event identity and signing. The id is sha256 over NIP-01's canonical
 * serialisation; the signature is BIP-340 over that id.
 */
object Events {

    fun canonicalSerialisation(
        pubkey: String,
        createdAt: Long,
        kind: Int,
        tags: List<List<String>>,
        content: String,
    ): String = buildJsonArray {
        add(0)
        add(pubkey)
        add(createdAt)
        add(kind)
        add(tagsToJson(tags))
        add(content)
    }.toString()

    fun eventId(
        pubkey: String,
        createdAt: Long,
        kind: Int,
        tags: List<List<String>>,
        content: String,
    ): String = Digests.sha256(
        canonicalSerialisation(pubkey, createdAt, kind, tags, content).toByteArray(Charsets.UTF_8),
    ).toHex()

    fun sign(
        secretKey: ByteArray,
        kind: Int,
        createdAt: Long,
        tags: List<List<String>>,
        content: String,
        auxRand: ByteArray = Entropy.bytes(32),
    ): NostrEvent {
        val pubkey = Schnorr.publicKeyHex(secretKey)
        val id = eventId(pubkey, createdAt, kind, tags, content)
        val sig = Schnorr.sign(id.hexToBytes(), secretKey, auxRand).toHex()
        return NostrEvent(kind, createdAt, tags, content, pubkey, id, sig)
    }

    /** Recomputes the id and checks the signature. Never throws. */
    fun verify(event: NostrEvent): Boolean = try {
        val id = eventId(event.pubkey, event.createdAt, event.kind, event.tags, event.content)
        id == event.id && Schnorr.verify(event.sig.hexToBytes(), id.hexToBytes(), event.pubkey.hexToBytes())
    } catch (_: Exception) {
        false
    }
}
