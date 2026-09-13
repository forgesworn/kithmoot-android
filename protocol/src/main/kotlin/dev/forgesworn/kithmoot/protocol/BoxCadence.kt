package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.normaliseHex
import dev.forgesworn.kithmoot.crypto.toHex
import java.util.Base64
import java.net.URI
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
const val CADENCE_EPOCH_SECONDS = 3_600L
const val CADENCE_SLOT_SECONDS = 300L
const val CADENCE_BUCKET_BYTES = 4_096
const val CADENCE_DEVICE_SLOTS = 2
const val CADENCE_COUNTERS_PER_DEVICE = 8
const val CADENCE_MAX_LEASE_EPOCHS = 7 * 24L
const val CADENCE_MAX_FUTURE_START_EPOCHS = 7 * 24L

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

data class CadenceLeaseOptions(
    val scope: CadenceScope,
    val requestId: String,
    val leaseId: String,
    val generation: Long,
    val roomGeneration: Long,
    val deviceSlot: Int,
    val currentEpoch: Long,
    val startEpoch: Long,
    val endEpoch: Long,
    val roomKey: ByteArray,
    val publicRelays: List<String>,
    val circleBoxes: List<String>,
    val now: Long,
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

    fun leasePath(leaseId: String) = "/cadence/v1/leases/${exactId(leaseId, ID32, "lease id")}"
    fun queuePath(leaseId: String) = "${leasePath(leaseId)}/queue"
    fun leaseStatusPath(leaseId: String) = "${leasePath(leaseId)}/status"
    fun stopPath(leaseId: String) = "${leasePath(leaseId)}/stop"
    fun withdrawPath(leaseId: String, eventId: String) = "${queuePath(leaseId)}/${exactId(eventId, HEX64, "event id")}/withdraw"

    /** Build the fixed quiet-v1 profile while retaining only public drop keys. */
    fun leaseBody(options: CadenceLeaseOptions): JsonObject {
        val scope = options.scope
        val common = statusBody(scope, options.requestId, options.now)
        exactId(options.leaseId, ID32, "lease id")
        require(options.generation > 0 && options.roomGeneration > 0 && options.deviceSlot in 0 until CADENCE_DEVICE_SLOTS) {
            "invalid cadence generation or device slot"
        }
        require(options.roomKey.size == 32) { "invalid cadence room key" }
        require(options.currentEpoch >= 0 && options.startEpoch >= options.currentEpoch + 2 &&
            options.startEpoch <= options.currentEpoch + CADENCE_MAX_FUTURE_START_EPOCHS) { "invalid cadence future start" }
        require(options.endEpoch > options.startEpoch && options.endEpoch - options.startEpoch <= CADENCE_MAX_LEASE_EPOCHS) {
            "invalid cadence lease window"
        }
        val credentialExpiry = scope.credential.tags.singleOrNull { it.size == 2 && it[0] == "expiration" }
            ?.get(1)?.toLongOrNull() ?: throw IllegalArgumentException("invalid cadence credential")
        require(credentialExpiry >= Math.multiplyExact(options.endEpoch, CADENCE_EPOCH_SECONDS)) {
            "cadence credential expires before lease"
        }
        val publicRelays = destinations(options.publicRelays, local = false)
        val circleBoxes = destinations(options.circleBoxes, local = true)
        require(publicRelays.size >= 2 && "local" in circleBoxes && circleBoxes.none(publicRelays::contains)) {
            "invalid cadence destinations"
        }
        val counterLo = options.deviceSlot * CADENCE_COUNTERS_PER_DEVICE
        val ikm = DeadDrop.roomIkm(options.roomKey)
        val keys = buildJsonArray {
            for (epoch in options.startEpoch until options.endEpoch) {
                for (counter in counterLo until counterLo + CADENCE_COUNTERS_PER_DEVICE) {
                    val key = DeadDrop.deriveDropKey(ikm, epoch, scope.persona.normaliseHex(), counter)
                    try {
                        add(buildJsonObject {
                            put("epoch", epoch)
                            put("counter", counter)
                            put("pubkey", key.publicKey)
                        })
                    } finally {
                        key.privateKey.fill(0)
                    }
                }
            }
        }
        return buildJsonObject {
            put("v", 1); put("request_id", options.requestId); put("lease_id", options.leaseId)
            put("generation", options.generation); put("server", common.getValue("server")); put("room", common.getValue("room"))
            put("persona", common.getValue("persona")); put("device", common.getValue("device")); put("credential", common.getValue("credential"))
            put("grant_id", common.getValue("grant_id")); put("device_slot", options.deviceSlot); put("room_generation", options.roomGeneration)
            put("epoch_seconds", CADENCE_EPOCH_SECONDS); put("slot_seconds", CADENCE_SLOT_SECONDS); put("bucket_bytes", CADENCE_BUCKET_BYTES)
            put("allowed_inner_kinds", buildJsonArray { add(JsonPrimitive(1460)) }); put("device_slots", CADENCE_DEVICE_SLOTS)
            put("counter_lo", counterLo); put("counter_hi", counterLo + CADENCE_COUNTERS_PER_DEVICE)
            put("start_epoch", options.startEpoch); put("end_epoch", options.endEpoch)
            put("real_send_until_epoch", options.endEpoch); put("cover_until_epoch", options.endEpoch)
            put("drop_keys", keys)
            put("public_relays", buildJsonArray { publicRelays.forEach { add(JsonPrimitive(it)) } })
            put("circle_boxes", buildJsonArray { circleBoxes.forEach { add(JsonPrimitive(it)) } })
        }
    }

    fun queueBody(lease: JsonObject, requestId: String, event: NostrEvent): JsonObject {
        exactId(requestId, ID32, "request id")
        val room = leaseText(lease, "room", HEX64)
        val device = leaseText(lease, "device", HEX64)
        require(event.kind == 1460 && event.pubkey.normaliseHex() == device && Events.verify(event) &&
            event.tags.filter { it.firstOrNull() == "d" } == listOf(listOf("d", room))) { "invalid cadence queue event" }
        try { RoomDrops.plaintext(event, CADENCE_BUCKET_BYTES) { ByteArray(it) } } catch (_: RoomDrops.RumorTooLarge) {
            throw IllegalArgumentException("cadence queue event exceeds bucket")
        }
        return buildAuthorityBody(lease, requestId) { put("event", event.toRustWireJson()) }
    }

    fun mutationBody(lease: JsonObject, requestId: String, boundaryEpoch: Long?): JsonObject {
        exactId(requestId, ID32, "request id")
        require(boundaryEpoch == null || boundaryEpoch >= 0) { "invalid cadence boundary epoch" }
        return buildAuthorityBody(lease, requestId) {
            put("boundary_epoch", boundaryEpoch?.let(::JsonPrimitive) ?: JsonNull)
        }
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
        val code = root.getValue("code").jsonPrimitive.content
        val leaseId = root.getValue("lease_id").jsonPrimitive.content
        val generation = nonNegative(root, "generation")
        val state = root.getValue("state").jsonPrimitive.content
        val start = nonNegative(root, "start_epoch")
        val end = nonNegative(root, "end_epoch")
        val queue = nonNegative(root, "queue_count")
        require(code in setOf("staged", "queued", "status", "withdrawn", "stopping") &&
            ID32.matches(leaseId) && generation > 0 && state in setOf("staged", "active", "cover", "ended") && end > start && queue <= 256) {
            "invalid cadence receipt"
        }
        fun ids(key: String): List<String> = root.getValue(key).jsonArray.map { it.jsonPrimitive.content }.also {
            require(it.size <= 256 && it.distinct().size == it.size && it.all(HEX64::matches)) { "invalid cadence receipt item ids" }
        }
        val sent = ids("sent_item_ids")
        val failed = ids("failed_item_ids")
        require((sent.toSet() intersect failed.toSet()).isEmpty()) { "invalid cadence receipt item states" }
        return CadenceReceipt(
            code,
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

    private fun buildAuthorityBody(lease: JsonObject, requestId: String, tail: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject {
        require(lease.getValue("v").jsonPrimitive.long == 1L)
        put("v", 1); put("request_id", requestId)
        for (key in listOf("lease_id", "generation", "server", "room", "persona", "device", "credential", "grant_id")) {
            put(key, lease.getValue(key))
        }
        exactId(leaseText(lease, "lease_id", ID32), ID32, "lease id")
        require(lease.getValue("generation").jsonPrimitive.long > 0)
        tail()
    }

    private fun leaseText(value: JsonObject, key: String, pattern: Regex): String =
        value.getValue(key).jsonPrimitive.content.also { require(pattern.matches(it)) { "invalid cadence $key" } }

    private fun exactId(value: String, pattern: Regex, name: String): String =
        value.also { require(pattern.matches(it)) { "invalid cadence $name" } }

    private fun destinations(values: List<String>, local: Boolean): List<String> {
        require(values.isNotEmpty() && values.size <= 16 && values.distinct().size == values.size) { "invalid cadence destinations" }
        require(values.all { value -> local && value == "local" || canonicalWss(value) }) { "invalid cadence destination" }
        return values.toList()
    }

    private fun canonicalWss(value: String): Boolean = try {
        val uri = URI(value)
        uri.scheme == "wss" && uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null &&
            (uri.rawPath == null || uri.rawPath.isEmpty()) && uri.toASCIIString() == value
    } catch (_: Exception) { false }
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
