package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.JoinPayload
import dev.forgesworn.kithmoot.protocol.InvitationPayload
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.RoomInvitation
import dev.forgesworn.kithmoot.protocol.RoomPolicy
import dev.forgesworn.kithmoot.protocol.decodeInvitationUrl
import dev.forgesworn.kithmoot.protocol.decodeJoinUrl
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64

/** The base every link this client mints is built on. Matches the reference client. */
const val KITHMOOT_JOIN_BASE: String = "https://kithmoot.forgesworn.dev/j/"

/**
 * A pairing link: a join URL that also carries a device key and the credential
 * that authorises it.
 *
 * A second device cannot be enrolled the obvious way round. `PrimaryIdentity.enrol`
 * signs a credential naming a device pubkey, so the participant would have to
 * know that pubkey before the second device had said anything - which needs a
 * rendezvous the protocol does not have. So the first device mints the whole
 * identity: a fresh device key, and a credential for it. The link carries both.
 *
 * That is why the interface labels it the way it does. This link is not an
 * invitation - anyone holding it **is** you, in that room, until the credential
 * expires. It is encoded as a superset of a join URL rather than a separate
 * format so that a client which does not understand pairing still reads it as a
 * perfectly good join URL and joins as a stranger, rather than failing shut.
 *
 * As with a join URL, everything rides in the fragment, which is never sent to a
 * server.
 */
class PairingPayload(
    val join: JoinPayload,
    val deviceSecretKey: ByteArray,
    val credential: NostrEvent,
)

/** The same credential-bearing extension over a v2 invitation link. */
class InvitationPairingPayload(
    val join: InvitationPayload,
    val deviceSecretKey: ByteArray,
    val credential: NostrEvent,
)

fun encodePairingLink(
    base: String = KITHMOOT_JOIN_BASE,
    secret: ByteArray,
    relays: List<String>,
    policy: RoomPolicy? = null,
    deviceSecretKey: ByteArray,
    credential: NostrEvent,
): String {
    require(secret.size == 32) { "a room secret is 32 bytes" }
    require(deviceSecretKey.size == 32) { "a device secret key is 32 bytes" }
    val payload = buildJsonObject {
        put("s", encode(secret))
        put("r", buildJsonArray { for (relay in relays) add(JsonPrimitive(relay)) })
        if (policy != null) put("a", policy.toJson())
        put("k", encode(deviceSecretKey))
        put("c", credential.toJson())
    }
    return "$base#${encode(payload.toString().toByteArray(Charsets.UTF_8))}"
}

/**
 * Reads the pairing half of a link, or returns null when there is none.
 *
 * Null is the answer for an ordinary join URL as well as for a malformed one:
 * the caller falls back to joining as a new participant either way, and there is
 * nothing it would do differently between the two.
 */
fun decodePairingLink(url: String): PairingPayload? = try {
    val join = decodeJoinUrl(url)
    val fragment = url.substringAfter('#', "")
    val payload = Json.parseToJsonElement(String(decode(fragment), Charsets.UTF_8)).jsonObject
    val key = decode(payload.getValue("k").jsonPrimitive.content)
    val credential = NostrEvent.fromJson(payload.getValue("c").jsonObject)
    if (key.size != 32) null else PairingPayload(join, key, credential)
} catch (_: Exception) {
    null
}

fun encodeInvitationPairingLink(
    base: String = KITHMOOT_JOIN_BASE,
    invitation: RoomInvitation,
    relays: List<String>,
    policy: RoomPolicy? = null,
    deviceSecretKey: ByteArray,
    credential: NostrEvent,
): String {
    require(deviceSecretKey.size == 32) { "a device secret key is 32 bytes" }
    val payload = buildJsonObject {
        put("v", 2)
        put("j", encode(invitation.bearer))
        put("h", invitation.canonicalInviter)
        put("r", buildJsonArray { for (relay in relays) add(JsonPrimitive(relay)) })
        if (policy != null) put("a", policy.toJson())
        put("k", encode(deviceSecretKey))
        // `x` avoids colliding with the web client's one-off pairing-code
        // field `c`. A client that does not know this Android extension still
        // sees a valid ordinary invitation and joins as a separate person.
        put("x", credential.toJson())
    }
    return "$base#${encode(payload.toString().toByteArray(Charsets.UTF_8))}"
}

fun decodeInvitationPairingLink(url: String): InvitationPairingPayload? = try {
    val join = requireNotNull(decodeInvitationUrl(url))
    val fragment = url.substringAfter('#', "")
    val payload = Json.parseToJsonElement(String(decode(fragment), Charsets.UTF_8)).jsonObject
    val key = decode(payload.getValue("k").jsonPrimitive.content)
    val credential = NostrEvent.fromJson(payload.getValue("x").jsonObject)
    if (key.size != 32) null else InvitationPairingPayload(join, key, credential)
} catch (_: Exception) {
    null
}

private fun encode(bytes: ByteArray): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

private fun decode(text: String): ByteArray = Base64.getUrlDecoder().decode(text)
