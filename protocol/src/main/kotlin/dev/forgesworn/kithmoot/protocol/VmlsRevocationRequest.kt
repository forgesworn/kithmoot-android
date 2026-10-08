package dev.forgesworn.kithmoot.protocol

import kotlinx.serialization.json.*

/** Experimental, inner-only kind; never published without a NIP-59 seal and gift wrap. */
const val KIND_VMLS_REVOCATION_REQUEST = 21350
const val VMLS_REQUEST_SECONDS = 7 * 24 * 60 * 60L

/** Identity is authenticated by the seal, not by a claimed sending device. */
data class VmlsRevocationRequest(
    val sender: String, val keeper: String, val device: String,
    val sessions: List<String>, val boxes: List<String>, val createdAt: Long, val expiration: Long,
) {
    init {
        require(listOf(sender, keeper, device).all(HEX::matches) && sender != keeper)
        require(sessions.size in 1..64 && boxes.size in 1..64)
        require((sessions + boxes).all(HEX::matches))
        require(sessions.distinct().size == sessions.size && boxes.distinct().size == boxes.size)
        require(createdAt >= 0 && expiration > createdAt && expiration - createdAt <= VMLS_REQUEST_SECONDS)
    }
    fun tags(): List<List<String>> = listOf(
        listOf("p", keeper), listOf("t", "vmls-revocation-request/1"), listOf("device", device),
        listOf("expiration", expiration.toString()),
    ) + sessions.map { listOf("session", it) } + boxes.map { listOf("box", it) }

    fun rumor(): JsonObject = buildJsonObject {
        put("id", Events.eventId(sender, createdAt, KIND_VMLS_REVOCATION_REQUEST, tags(), ""))
        put("pubkey", sender); put("created_at", createdAt); put("kind", KIND_VMLS_REVOCATION_REQUEST)
        put("tags", JsonArray(tags().map { JsonArray(it.map(::JsonPrimitive)) })); put("content", "")
    }
    companion object {
        private val HEX = Regex("[0-9a-f]{64}")
        /** Strict framing, unsigned rumor, exact identity binding, bounded authentic timestamp. */
        fun parse(text: String, sealAuthor: String, recipient: String, now: Long): VmlsRevocationRequest {
            require(text.length <= 16_384)
            val o = Json.parseToJsonElement(text).jsonObject
            require(o.keys == setOf("id", "pubkey", "created_at", "kind", "tags", "content"))
            require(o.getValue("kind").jsonPrimitive.int == KIND_VMLS_REVOCATION_REQUEST)
            require(o.getValue("pubkey").jsonPrimitive.content == sealAuthor && o.getValue("content").jsonPrimitive.content == "")
            val tags = o.getValue("tags").jsonArray.map { t -> t.jsonArray.map { it.jsonPrimitive.also { v -> require(v.isString) }.content } }
            require(tags.size <= 132 && tags.all { it.size == 2 && it[0] in setOf("p", "t", "device", "expiration", "session", "box") })
            fun one(name: String) = tags.single { it[0] == name }[1]
            require(one("p") == recipient && one("t") == "vmls-revocation-request/1")
            val request = VmlsRevocationRequest(sealAuthor, recipient, one("device"),
                tags.filter { it[0] == "session" }.map { it[1] }, tags.filter { it[0] == "box" }.map { it[1] },
                o.getValue("created_at").jsonPrimitive.long, one("expiration").toLong())
            require(request.createdAt <= now + 600 && request.expiration > now)
            require(o.getValue("id").jsonPrimitive.content == Events.eventId(sealAuthor, request.createdAt, KIND_VMLS_REVOCATION_REQUEST, tags, ""))
            return request
        }
    }
}
