package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.Schnorr
import kotlinx.serialization.json.*

const val KIND_GROUP_INVITATION = 1463
private const val GROUP_INVITATION_KEY_INFO = "kithmoot/v3/group-invitation-key"

private fun groupInvitationKey(invitation: RoomInvitation): ByteArray {
    require(invitation.persistent)
    return Digests.hkdfSha256(invitation.bearer, null, GROUP_INVITATION_KEY_INFO.toByteArray(Charsets.UTF_8), 32)
}

/** At most this many room relays in a group invitation: the same bound as a link's relay hints. */
const val MAX_INVITATION_RELAYS: Int = 8

/**
 * The room relays a group invitation may carry, as fold-kit 0.4.0's
 * `isInvitationRelays` accepts them: one to [MAX_INVITATION_RELAYS] distinct
 * URLs, each already in the canonical form [canonicalRoomRelayUrl] gives
 * (nostr-tools' `normalizeURL`, as `wss://relay.example/`). Null for anything
 * else, which refuses the whole envelope: a list is never trimmed.
 */
fun invitationRelaysOf(element: JsonElement?): List<String>? {
    val array = element as? JsonArray ?: return null
    if (array.isEmpty() || array.size > MAX_INVITATION_RELAYS) return null
    val urls = array.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return null }
    if (urls.toSet().size != urls.size) return null
    if (urls.any { url -> runCatching { canonicalRoomRelayUrl(url) }.getOrNull() != url }) return null
    return urls
}

/** The room relays a list of relay hints can supply: each that is a safe
 *  relay URL, in canonical form, without repeats, at most
 *  [MAX_INVITATION_RELAYS]. Empty when none qualifies. For a room that
 *  learns its relays from a link, or a creator choosing them. */
fun invitationRelaysFrom(urls: List<String>): List<String> =
    urls.mapNotNull { runCatching { canonicalRoomRelayUrl(it) }.getOrNull() }.distinct().take(MAX_INVITATION_RELAYS)

/**
 * A durable bearer envelope, signed by the link's pinned inviter. No delegation is granted.
 *
 * A conference room's [ends] rides in the body and, as a NIP-40 `expiration`,
 * on the event, so relays drop the way in when the room ends. The room's own
 * [relays] - fixed when it was made, used by every member - ride in the body
 * after it (see [invitationRelaysOf]). A room that self-destructs carries
 * `"destruct": true` between the two (fold-kit 0.9.0), inside the encryption
 * and never as a tag. Without any of them the envelope is exactly what it
 * always was.
 */
fun encodePersistentInvitation(
    host: RoomInvitationHost,
    roomSecret: ByteArray,
    now: Long,
    nonce: ByteArray = Entropy.bytes(32),
    auxRand: ByteArray = Entropy.bytes(32),
    ends: Long? = null,
    relays: List<String>? = null,
    destruct: Boolean = false,
): NostrEvent {
    require(Schnorr.publicKeyHex(host.inviterSecretKey) == host.invitation.canonicalInviter)
    ends?.let { require(it > now) { "this conference room has ended" } }
    relays?.let { require(invitationRelaysOf(JsonArray(it.map(::JsonPrimitive))) != null) { "room relays must be one to $MAX_INVITATION_RELAYS distinct canonical relay URLs" } }
    val room = deriveRoom(roomSecret)
    val body = buildJsonObject {
        put("v", 3)
        put("room", room.roomId)
        put("secret", base64UrlEncode(roomSecret))
        ends?.let { put("ends", it) }
        if (destruct) put("destruct", true)
        relays?.let { put("relays", JsonArray(it.map(::JsonPrimitive))) }
    }
    return Events.sign(host.inviterSecretKey, KIND_GROUP_INVITATION, now,
        withRoomExpiration(listOf(listOf("d", deriveInvitationId(host.invitation))), ends),
        Nip44.encrypt(body.toString(), groupInvitationKey(host.invitation), nonce), auxRand)
}

/**
 * Untrusted relay data must never escape as an exception or provide a responder key.
 *
 * A conference room's end comes back as [RoomAdmission.endsAt]. An `ends`
 * that is not a positive safe integer, a second `expiration` tag, or an
 * `expiration` that is not exactly the body's `ends` refuses the whole
 * envelope: the tag is what relays act on, so it must say what the room says.
 * The room's relays come back as [RoomAdmission.relays]; a malformed list
 * refuses the envelope too. So does a `destruct` that is present and not
 * exactly `true` (`false`, `"true"`, `1`, `null`), as fold-kit 0.9.0 reads it:
 * the same strictness as `ends`. Exactly `true` is [RoomAdmission.destruct].
 */
fun decodePersistentInvitation(event: NostrEvent, invitation: RoomInvitation): RoomAdmission? = try {
    if (!invitation.persistent || event.kind != KIND_GROUP_INVITATION || event.pubkey != invitation.canonicalInviter || !Events.verify(event)) null
    else if (event.tags.count { it.firstOrNull() == "d" } != 1 || event.tagValue("d") != deriveInvitationId(invitation)) null
    else {
        val body = Json.parseToJsonElement(Nip44.decrypt(event.content, groupInvitationKey(invitation))).jsonObject
        require(body.getValue("secret").jsonPrimitive.isString)
        require(!body.getValue("v").jsonPrimitive.isString)
        val secret = base64UrlDecode(body.getValue("secret").jsonPrimitive.content)
        val ends = if ("ends" in body) requireNotNull(conferenceEndsOf(body["ends"])) else null
        val expirations = event.tags.filter { it.firstOrNull() == "expiration" }
        require(expirations.size <= 1)
        expirations.singleOrNull()?.let { require(ends != null && it.getOrNull(1) == ends.toString()) }
        val relays = if ("relays" in body) requireNotNull(invitationRelaysOf(body["relays"])) else null
        val destruct = "destruct" in body
        if (destruct) require(body["destruct"].let { it is JsonPrimitive && it !is JsonNull && !it.isString && it.booleanOrNull == true })
        if (body["v"]?.jsonPrimitive?.longOrNull != 3L || deriveRoom(secret).roomId != body["room"]?.jsonPrimitive?.content) null
        // A group invitation always opens epoch 0, as fold-kit's says.
        else RoomAdmission(secret, null, ends, relays, epoch = 0, destruct = destruct)
    }
} catch (_: Exception) { null }
