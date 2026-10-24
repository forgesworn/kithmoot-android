package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.hexEquals
import kotlinx.serialization.json.*
import java.net.URI

const val KIND_ROOM_DESCRIPTOR = 20465
const val SPRY_GRANT_SECONDS = 30L * 24 * 60 * 60

/** A short-lived call capability, carried only inside the encrypted room descriptor. */
data class SpryOffer(val endpoint: String, val token: String, val expiresAt: Long, val node: NostrEvent? = null) {
    override fun toString() = "SpryOffer(redacted)"
    fun toJson() = buildJsonObject { put("v", 1); put("endpoint", endpoint); put("token", token); put("expiresAt", expiresAt); if (node != null) put("node", node.toJson()) }
    companion object {
        fun parse(value: JsonElement?, now: Long): SpryOffer? = runCatching {
            val raw = value?.jsonObject ?: return null
            require(raw.getValue("v").jsonPrimitive.int == 1)
            val endpoint = spryEndpoint(raw.getValue("endpoint").jsonPrimitive.content)
            val token = raw.getValue("token").jsonPrimitive.content
            val expiresAt = raw.getValue("expiresAt").jsonPrimitive.long
            require(Regex("^[A-Za-z0-9_-]{43}$").matches(token) && expiresAt > now && expiresAt <= now + SPRY_GRANT_SECONDS + 60)
            SpryOffer(endpoint, token, expiresAt, SpryLink.card(raw["node"], now))
        }.getOrNull()
    }
}

fun spryEndpoint(value: String): String {
    require(value.length <= 2048)
    if (value.startsWith("spry:")) { SpryControlEndpoint.parse(value); return value }
    val uri = URI(value)
    val host = requireNotNull(uri.host).lowercase()
    require(uri.scheme == "https" && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.path in listOf("", "/") &&
        host.contains('.') && !Regex("^[0-9.]+$").matches(host) && !host.startsWith('[') &&
        !listOf("local", "localhost", "internal", "invalid", "test").any { host == it || host.endsWith(".$it") } && (uri.port == -1 || uri.port in 1..65535))
    return "https://$host" + if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"
}

data class SpryDescriptor(val offer: SpryOffer, val participant: String, val updatedAt: Long,
    val forwarders: JsonArray, val iceServers: JsonArray, val device: String = "")

fun decodeSpryDescriptor(event: NostrEvent, epoch: EpochKeys, stableRoom: String, now: Long, policy: RoomPolicy?): SpryDescriptor? = runCatching {
    if (event.kind != KIND_ROOM_DESCRIPTOR || event.content.length > 32768 || event.tagValue("d")?.hexEquals(epoch.id) != true || !Events.verify(event)) return null
    val raw = Json.parseToJsonElement(Nip44.decrypt(event.content, epoch.key)).jsonObject
    val device = raw.getValue("device").jsonPrimitive.content
    val participant = raw.getValue("participant").jsonPrimitive.content
    val credential = NostrEvent.fromJson(raw.getValue("credential"))
    val check = verifyDeviceCredential(credential, stableRoom, now)
    val updatedAt = raw.getValue("updatedAt").jsonPrimitive.long
    if (!device.hexEquals(event.pubkey) || check !is CredentialCheck.Valid || !check.device.hexEquals(device) || !check.participant.hexEquals(participant) || updatedAt > now + 60) return null
    val proof = (raw["proof"] as? JsonObject)?.let(KindredProof::fromJson)
    if (policy != null && !evaluateAccess(policy, participant, proof, now, stableRoom).admitted) return null
    val offer = SpryOffer.parse(raw["spry"], now) ?: return null
    SpryDescriptor(offer, participant.lowercase(), updatedAt, raw["forwarders"] as? JsonArray ?: JsonArray(emptyList()), raw["iceServers"] as? JsonArray ?: JsonArray(emptyList()), device.lowercase())
}.getOrNull()

fun encodeSpryDescriptor(offer: SpryOffer, epoch: EpochKeys, participant: String, credential: NostrEvent, deviceKey: ByteArray,
    now: Long, proof: KindredProof? = null, previous: SpryDescriptor? = null, ends: Long? = null): NostrEvent {
    val payload = buildJsonObject {
        put("device", credential.tagValue("device") ?: error("Missing device")); put("participant", participant); put("credential", credential.toJson())
        if (proof != null) put("proof", proof.toJson())
        put("forwarders", previous?.forwarders ?: JsonArray(emptyList())); put("iceServers", previous?.iceServers ?: JsonArray(emptyList()))
        put("spry", offer.toJson()); put("updatedAt", now)
    }
    return Events.sign(deviceKey, KIND_ROOM_DESCRIPTOR, now, withRoomExpiration(listOf(listOf("d", epoch.id)), ends), Nip44.encrypt(payload.toString(), epoch.key))
}
