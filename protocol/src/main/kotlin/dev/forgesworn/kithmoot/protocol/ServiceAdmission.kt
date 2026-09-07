package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.toHex
import java.net.URI
import kotlinx.serialization.json.*

/** M2 codecs only. Decoding a valid statement does not authorise a request. */
const val KIND_MEMBER_PASS = 20470
const val KIND_SERVICE_POLICY = 30460
private val serviceHex = Regex("[0-9a-f]{64}")
private val servicePermissions = setOf("relay", "credentials", "upload", "notify")

data class ServiceAudience(val type: String, val id: String) {
    fun toJson() = buildJsonObject { put("type", type); put("id", id) }
}
private fun JsonObject.serviceString(key: String): String {
    val value = getValue(key).jsonPrimitive
    require(value.isString)
    return value.content
}
private fun JsonObject.serviceInteger(key: String): Long {
    val value = getValue(key).jsonPrimitive
    require(!value.isString)
    val number = value.double
    require(number.isFinite() && number in 0.0..9_007_199_254_740_991.0 && number % 1.0 == 0.0)
    return number.toLong()
}
fun normaliseServiceAudience(raw: JsonObject): ServiceAudience? = try {
    val type = raw.serviceString("type")
    val id = raw.serviceString("id")
    require(type in setOf("forwarder", "turn", "blossom", "nudger") && id.length <= 2048)
    if (type == "forwarder") require(serviceHex.matches(id))
    else {
        val uri = URI(id)
        require(uri.scheme == "https" && uri.host != null && uri.host == uri.host.lowercase())
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.port != 443)
        require(uri.port in -1..65535 && id.all { it.code in 33..126 })
        val host = canonicalServiceHost(uri.host)
        require(uri.rawAuthority == host + if (uri.port == -1) "" else ":${uri.port}")
        require(uri.rawPath.split('/').none { it.replace(Regex("%2e", RegexOption.IGNORE_CASE), ".") in setOf(".", "..") })
        require(if (type == "nudger") uri.rawPath.startsWith("/") else uri.rawPath.isEmpty())
    }
    ServiceAudience(type, id)
} catch (_: Exception) { null }

/** Canonical numeric addresses without DNS lookups; match WHATWG serialization. */
private fun canonicalServiceHost(host: String): String {
    if (host.startsWith("[")) {
        val raw = host.removePrefix("[").removeSuffix("]")
        val sides = raw.split("::")
        require(sides.size <= 2)
        fun words(s: String) = if (s.isEmpty()) emptyList() else s.split(':').map {
            require(Regex("[0-9a-f]{1,4}").matches(it)); it.toInt(16)
        }
        val left = words(sides[0]); val right = if (sides.size == 2) words(sides[1]) else emptyList()
        val zeroes = 8 - left.size - right.size
        require(if (sides.size == 2) zeroes >= 1 else zeroes == 0)
        val words = left + List(zeroes) { 0 } + right
        var start = -1; var length = 1; var i = 0
        while (i < words.size) {
            if (words[i] != 0) { i++; continue }
            val first = i
            while (i < words.size && words[i] == 0) i++
            if (i - first > length) { start = first; length = i - first }
        }
        fun text(part: List<Int>) = part.joinToString(":") { it.toString(16) }
        return "[" + if (start < 0) text(words) + "]" else text(words.take(start)) + "::" + text(words.drop(start + length)) + "]"
    }
    require(Regex("(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)*[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.?").matches(host))
    val last = host.trimEnd('.').substringAfterLast('.')
    if (last.all { it.isDigit() } || last.startsWith("0x")) {
        val parts = host.split('.')
        require(parts.size == 4 && parts.all { it.toIntOrNull()?.let { n -> n in 0..255 && n.toString() == it } == true })
    }
    return host
}

data class MemberPass(val audience: ServiceAudience, val room: String, val device: String, val epoch: Long, val expiresAt: Long, val permissions: List<String>) {
    fun toJson() = buildJsonObject {
        put("v", 1); put("audience", audience.toJson()); put("room", room); put("device", device)
        put("epoch", epoch); put("expiresAt", expiresAt)
        put("permissions", buildJsonArray { permissions.forEach { add(it) } })
    }
}
data class ServicePolicy(val audience: ServiceAudience, val room: String, val enforcementEpoch: Long, val activateAt: Long, val graceEnd: Long) {
    fun toJson() = buildJsonObject {
        put("v", 1); put("audience", audience.toJson()); put("room", room)
        put("enforcementEpoch", enforcementEpoch); put("activateAt", activateAt); put("graceEnd", graceEnd)
    }
}
private fun servicePayload(event: NostrEvent, kind: Int): JsonObject {
    require(event.kind == kind && event.content.length <= 4096 && event.tags.size <= 8)
    require(event.createdAt in 0..9_007_199_254_740_991L && Events.verify(event))
    return Json.parseToJsonElement(event.content).jsonObject.also { require(it.serviceInteger("v") == 1L) }
}
private fun serviceTag(event: NostrEvent, name: String, value: String): Boolean =
    event.tags.filter { it.firstOrNull() == name } == listOf(listOf(name, value))

fun decodeMemberPass(event: NostrEvent): MemberPass? = try {
    val raw = servicePayload(event, KIND_MEMBER_PASS)
    val audience = requireNotNull(normaliseServiceAudience(raw.getValue("audience").jsonObject))
    val room = raw.serviceString("room").also { require(serviceHex.matches(it)) }
    val device = raw.serviceString("device").also { require(serviceHex.matches(it)) }
    val epoch = raw.serviceInteger("epoch")
    val expiry = raw.serviceInteger("expiresAt").also { require(it > event.createdAt) }
    val permissions = raw.getValue("permissions").jsonArray.map {
        require(it.jsonPrimitive.isString); it.jsonPrimitive.content.also { p -> require(p in servicePermissions) }
    }.sorted()
    require(permissions.size in 1..4 && permissions.distinct().size == permissions.size)
    require(serviceTag(event, "d", room) && serviceTag(event, "p", device) && serviceTag(event, "expiration", expiry.toString()))
    MemberPass(audience, room, device, epoch, expiry, permissions)
} catch (_: Exception) { null }

fun decodeServicePolicy(event: NostrEvent): ServicePolicy? = try {
    val raw = servicePayload(event, KIND_SERVICE_POLICY)
    val audience = requireNotNull(normaliseServiceAudience(raw.getValue("audience").jsonObject))
    val room = raw.serviceString("room").also { require(serviceHex.matches(it)) }
    val epoch = raw.serviceInteger("enforcementEpoch")
    val activate = raw.serviceInteger("activateAt")
    val grace = raw.serviceInteger("graceEnd").also { require(it >= activate) }
    require(serviceTag(event, "d", room))
    ServicePolicy(audience, room, epoch, activate, grace)
} catch (_: Exception) { null }

fun deriveServiceKey(secret: ByteArray, roomId: String, audience: ServiceAudience, role: String): ByteArray {
    require(secret.size == 32 && serviceHex.matches(roomId) && normaliseServiceAudience(audience.toJson()) != null)
    require(role in setOf("authority", "device"))
    if (role == "device" && audience.type == "forwarder") { Schnorr.publicKeyHex(secret); return secret.copyOf() }
    for (counter in 0..255) {
        val info = buildJsonArray { add("kithmoot.service.v1"); add(role); add(roomId); add(audience.type); add(audience.id); add(counter) }
        val key = Digests.hkdfSha256(secret, null, info.toString().toByteArray(Charsets.UTF_8), 32)
        try { Schnorr.publicKeyHex(key); return key } catch (_: Exception) { /* Deterministically reject invalid scalars. */ }
    }
    error("Could not derive a service key")
}
fun deriveServiceRoom(secret: ByteArray, roomId: String, audience: ServiceAudience): String {
    require(secret.size == 32 && serviceHex.matches(roomId) && normaliseServiceAudience(audience.toJson()) != null)
    if (audience.type in setOf("forwarder", "nudger")) return roomId
    val info = buildJsonArray { add("kithmoot.service.v1"); add("room"); add(roomId); add(audience.type); add(audience.id) }
    return Digests.hkdfSha256(secret, null, info.toString().toByteArray(Charsets.UTF_8), 32).toHex()
}
