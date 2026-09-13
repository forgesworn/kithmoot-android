package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.normaliseHex
import dev.forgesworn.kithmoot.crypto.toHex
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

const val CADENCE_AUTH_KIND = 27_235
const val CADENCE_MAX_BODY_BYTES = 256 * 1024

data class CadenceScope(
    val nodeId: String,
    val room: String,
    val persona: String,
    val device: String,
    val credential: NostrEvent,
    val grantId: String,
)

data class CadenceSignedRequest(
    val method: String,
    val path: String,
    val body: ByteArray,
    val payloadSha256: String,
    val authorizationEvent: NostrEvent,
    val authorization: String,
) {
    override fun toString() = "CadenceSignedRequest(method=$method, body=${body.size} bytes)"
}

data class CadenceStatusAnswer(
    val code: String,
    val ready: Boolean,
    val serverTime: Long,
    val currentEpoch: Long,
    val earliestStartEpoch: Long,
    val missing: List<String>,
)

data class CadenceReceipt(
    val code: String,
    val leaseId: String,
    val generation: Long,
    val state: String,
    val serverTime: Long,
    val startEpoch: Long,
    val endEpoch: Long,
    val queueCount: Int,
    val sentItemIds: List<String>,
    val failedItemIds: List<String>,
)

/** Byte-exact cadence v1 bodies and NIP-98 authorization for a pinned Bothy. */
object BoxCadence {
    private val HEX64 = Regex("[0-9a-f]{64}")
    private val ID32 = Regex("[0-9a-f]{32}")
    private val NODE52 = Regex("[a-z2-7]{52}")
    private val ALLOWED_CREDENTIAL_TAGS = setOf("d", "device", "expiration", "scope", "label")

    fun server(nodeId: String): String {
        require(NODE52.matches(nodeId)) { "invalid cadence node id" }
        return "ws://$nodeId/events"
    }

    fun statusBody(scope: CadenceScope, requestId: String, now: Long, leaseId: String? = null, generation: Long? = null): JsonObject {
        require(ID32.matches(requestId)) { "invalid cadence request id" }
        require(now >= 0) { "invalid cadence time" }
        require((leaseId == null) == (generation == null)) { "incomplete cadence lease scope" }
        if (leaseId != null) require(ID32.matches(leaseId) && requireNotNull(generation) > 0) { "invalid cadence lease scope" }
        val room = scope.room.normaliseHex()
        val persona = scope.persona.normaliseHex()
        val device = scope.device.normaliseHex()
        require(HEX64.matches(room) && HEX64.matches(persona) && HEX64.matches(device)) { "invalid cadence identity" }
        require(ID32.matches(scope.grantId)) { "invalid cadence grant id" }
        val credential = verifyDeviceCredential(scope.credential, room, now, acceptPerson = true)
        require(credential is CredentialCheck.Valid && credential.participant == persona && credential.device == device) {
            "invalid cadence credential"
        }
        require(scope.credential.createdAt <= now + 300 && scope.credential.tags.all {
            it.isNotEmpty() && it.first() in ALLOWED_CREDENTIAL_TAGS
        }) { "invalid cadence credential" }
        return buildJsonObject {
            put("v", 1)
            put("request_id", requestId)
            put("server", server(scope.nodeId))
            put("room", room)
            put("persona", persona)
            put("device", device)
            put("credential", scope.credential.toRustWireJson())
            put("grant_id", scope.grantId)
            put("lease_id", leaseId?.let(::JsonPrimitive) ?: JsonNull)
            put("generation", generation?.let(::JsonPrimitive) ?: JsonNull)
        }
    }

    fun sign(
        nodeId: String,
        method: String,
        path: String,
        value: JsonObject,
        deviceSecretKey: ByteArray,
        now: Long,
        auxRand: ByteArray? = null,
    ): CadenceSignedRequest {
        server(nodeId)
        require(method == "POST" || method == "PUT") { "invalid cadence method" }
        require(path.length <= 2048 && path.startsWith("/cadence/v1/") && '?' !in path && '#' !in path && path.all { it.code in 0x21..0x7e }) {
            "invalid cadence path"
        }
        require(now >= 0 && deviceSecretKey.size == 32) { "invalid cadence signer" }
        val device = Schnorr.publicKeyHex(deviceSecretKey)
        value["device"]?.jsonPrimitive?.content?.let {
            require(it.normaliseHex() == device) { "cadence body does not belong to signer" }
        }
        val body = value.toString().toByteArray(Charsets.UTF_8)
        require(body.size <= CADENCE_MAX_BODY_BYTES) { "cadence request body is too large" }
        val hash = Digests.sha256(body).toHex()
        val tags = listOf(
            listOf("u", "http://$nodeId$path"),
            listOf("method", method),
            listOf("payload", hash),
        )
        val event = if (auxRand == null) {
            Events.sign(deviceSecretKey, CADENCE_AUTH_KIND, now, tags, "")
        } else {
            Events.sign(deviceSecretKey, CADENCE_AUTH_KIND, now, tags, "", auxRand)
        }
        check(event.pubkey == device && Events.verify(event)) { "invalid cadence authorization" }
        val authorizationBytes = event.toRustWireJson().toString().toByteArray(Charsets.UTF_8)
        return CadenceSignedRequest(
            method,
            path,
            body,
            hash,
            event,
            "Nostr " + Base64.getEncoder().encodeToString(authorizationBytes),
        )
    }

    fun parseStatus(httpStatus: Int, body: ByteArray): CadenceStatusAnswer {
        require(body.size <= CADENCE_MAX_BODY_BYTES) { "cadence response body is too large" }
        val root = try {
            Json.parseToJsonElement(body.toString(Charsets.UTF_8)).jsonObject
        } catch (error: Exception) {
            throw IllegalArgumentException("invalid cadence response", error)
        }
        if (httpStatus != 200) {
            val code = root["code"]?.jsonPrimitive?.content ?: "http-$httpStatus"
            throw IllegalStateException("Bothy refused cadence request: $code")
        }
        require(root.keys == setOf("v", "code", "ready", "server_time", "current_epoch", "earliest_start_epoch", "missing")) {
            "invalid cadence status fields"
        }
        require(root.getValue("v").jsonPrimitive.long == 1L) { "unsupported cadence status version" }
        val code = root.getValue("code").jsonPrimitive.content
        val ready = root.getValue("ready").jsonPrimitive.boolean
        require((ready && code == "ok") || (!ready && code == "not-ready")) { "invalid cadence readiness" }
        val serverTime = nonNegative(root, "server_time")
        val currentEpoch = nonNegative(root, "current_epoch")
        val earliest = nonNegative(root, "earliest_start_epoch")
        require(earliest >= currentEpoch) { "invalid cadence start epoch" }
        val missing = root.getValue("missing").jsonArray.map { item ->
            item.jsonPrimitive.content.also { require(it.isNotEmpty() && it.length <= 128) { "invalid cadence missing capability" } }
        }
        require(missing.size <= 32 && missing.distinct().size == missing.size && (ready == missing.isEmpty())) { "invalid cadence missing capabilities" }
        return CadenceStatusAnswer(code, ready, serverTime, currentEpoch, earliest, missing)
    }

    fun parseReceipt(httpStatus: Int, body: ByteArray): CadenceReceipt {
        require(body.size <= CADENCE_MAX_BODY_BYTES) { "cadence response body is too large" }
        val root = try {
            Json.parseToJsonElement(body.toString(Charsets.UTF_8)).jsonObject
        } catch (error: Exception) {
            throw IllegalArgumentException("invalid cadence response", error)
        }
        if (httpStatus !in 200..299) {
            val code = root["code"]?.jsonPrimitive?.content ?: "http-$httpStatus"
            throw IllegalStateException("Bothy refused cadence request: $code")
        }
        require(root.keys == setOf("v", "code", "lease_id", "generation", "state", "server_time", "start_epoch", "end_epoch", "queue_count", "sent_item_ids", "failed_item_ids")) {
            "invalid cadence receipt fields"
        }
        require(root.getValue("v").jsonPrimitive.long == 1L) { "unsupported cadence receipt version" }
        val leaseId = root.getValue("lease_id").jsonPrimitive.content
        val generation = nonNegative(root, "generation")
        val state = root.getValue("state").jsonPrimitive.content
        val start = nonNegative(root, "start_epoch")
        val end = nonNegative(root, "end_epoch")
        val queue = nonNegative(root, "queue_count")
        require(ID32.matches(leaseId) && generation > 0 && state in setOf("staged", "active", "cover", "ended") && end > start && queue <= 256) {
            "invalid cadence receipt"
        }
        fun ids(key: String): List<String> = root.getValue(key).jsonArray.map { it.jsonPrimitive.content }.also {
            require(it.size <= 256 && it.distinct().size == it.size && it.all(HEX64::matches)) { "invalid cadence receipt item ids" }
        }
        val sent = ids("sent_item_ids")
        val failed = ids("failed_item_ids")
        require((sent.toSet() intersect failed.toSet()).isEmpty()) { "invalid cadence receipt item states" }
        return CadenceReceipt(
            root.getValue("code").jsonPrimitive.content,
            leaseId,
            generation,
            state,
            nonNegative(root, "server_time"),
            start,
            end,
            queue.toInt(),
            sent,
            failed,
        )
    }

    private fun nonNegative(root: JsonObject, key: String): Long = root.getValue(key).jsonPrimitive.long.also {
        require(it >= 0) { "invalid cadence $key" }
    }
}

/** Rust's serde Event order is frozen for byte-identical Authorization retries. */
fun NostrEvent.toRustWireJson(): JsonObject = buildJsonObject {
    put("id", id)
    put("pubkey", pubkey)
    put("created_at", createdAt)
    put("kind", kind)
    put("tags", buildJsonArray {
        for (tag in tags) add(buildJsonArray { for (value in tag) add(JsonPrimitive(value)) })
    })
    put("content", content)
    put("sig", sig)
}
