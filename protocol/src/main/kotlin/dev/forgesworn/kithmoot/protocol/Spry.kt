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
            // A private mesh node's peer card, or a standard node's card (a
            // member's built-in relay), each checked fresh as it is read: a
            // lapsed card is dropped, so its offer is not redeemed until its
            // host publishes it again.
            SpryOffer(endpoint, token, expiresAt, SpryLink.card(raw["node"], now) ?: SpryLink.standardCard(raw["node"], now))
        }.getOrNull()
    }
}

fun spryEndpoint(value: String): String {
    require(value.length <= 2048)
    if (value.startsWith("spry:")) { SpryControlEndpoint.parse(value); return value }
    val uri = URI(value)
    val host = requireNotNull(uri.host).lowercase()
    require(uri.scheme == "https" && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.path in listOf("", "/") &&
        host.contains('.') && !host.endsWith('.') && !Regex("^[0-9.]+$").matches(host) && !host.startsWith('[') &&
        !listOf("local", "localhost", "internal", "invalid", "test").any { host == it || host.endsWith(".$it") } && (uri.port == -1 || uri.port in 1..65535))
    return "https://$host" + if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"
}

/**
 * Whether an offer is a standard-profile node reached over `spry:` control,
 * such as a member's built-in relay: its card is a `spry-standard-v1` card
 * signed by the endpoint's own key. The card's ten-minute expiry was checked
 * when the offer was read; here it is checked as of the card's own signing
 * time, so a guest that read a fresh offer keeps using it. Only such an offer
 * from another member is ever redeemed: a private-node (`spry-peer-v1`) offer
 * belongs to its own pairing alone. The web's `isSpryStandardOffer`.
 */
fun isSpryStandardOffer(endpoint: String, node: NostrEvent?): Boolean = runCatching {
    endpoint.startsWith("spry:") && node != null && SpryLink.standardCard(node.toJson(), node.createdAt) != null &&
        node.pubkey == SpryControlEndpoint.parse(endpoint).publicKey
}.getOrDefault(false)

val SpryOffer.standard: Boolean get() = isSpryStandardOffer(endpoint, node)

private val OCTET = "(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])"
private val IPV4 = Regex("^$OCTET\\.$OCTET\\.$OCTET\\.$OCTET$")

/**
 * Public unicast IPv4, as Spry's `public_v4` decides it: not private,
 * loopback, link-local, broadcast, documentation, `0/8`, multicast or
 * reserved (`224/3`), carrier-grade NAT, benchmarking or `192.0.0/24`. The
 * web's `isPublicIpv4`, range for range; deliberately not
 * `PublicAddress.isPublic`, which also refuses 192.88.99/24.
 */
fun isPublicIpv4(text: String): Boolean {
    val match = IPV4.matchEntire(text) ?: return false
    val (a, b, c) = match.groupValues.drop(1).take(3).map(String::toInt)
    return !(a == 10 || a == 172 && b in 16..31 || a == 192 && b == 168 || a == 127 || a == 169 && b == 254 ||
        text == "255.255.255.255" || a == 192 && b == 0 && c == 2 || a == 198 && b == 51 && c == 100 || a == 203 && b == 0 && c == 113 ||
        a == 0 || a >= 224 || a == 100 && b in 64..127 || a == 198 && (b == 18 || b == 19) || a == 192 && b == 0 && c == 0)
}

private val STANDARD_URL = Regex("^turn:([0-9.]{7,15}):([1-9][0-9]{3,4})\\?transport=(udp|tcp)$")

/**
 * The TURN URLs a standard-profile node may hand a guest (built-in relay
 * design, section 3.3): one or two `turn:` URLs on one public IPv4 literal,
 * ports 1024 to 65535, UDP or TCP, no repeats. No `turns:`, names, IPv6 or
 * private addresses, so another member's offer cannot point a guest's TURN
 * client into its own network. The web's `isSpryStandardTurnUrls`, without
 * its loopback seam for the web's local acceptance build.
 */
fun isSpryStandardTurnUrls(urls: List<String>): Boolean {
    if (urls.isEmpty() || urls.size > 2 || urls.toSet().size != urls.size) return false
    val hosts = mutableSetOf<String>()
    for (url in urls) {
        val match = STANDARD_URL.matchEntire(url) ?: return false
        val port = match.groupValues[2].toInt()
        if (port !in 1024..65535 || !isPublicIpv4(match.groupValues[1])) return false
        hosts += match.groupValues[1]
    }
    return hosts.size == 1
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
