package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.vmls.LeafBinding
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/*
 * A VMLS room's invitation (P3-03b-3 decision 17): a share link of today's
 * shape, answered over the same request and answer kinds (20466, 20467)
 * with payloads of their own. Each version is one today's decoders refuse,
 * so an app without VMLS rooms (a release build among them) never takes a
 * VMLS link, request or answer for an ordinary one: the link says "needs a
 * newer version", and the request and answer decode to nothing.
 *
 * The keeper answers only after consenting and granting the guest's device
 * at the box: nothing here answers on its own.
 */

/** The link's version: today's decoder refuses it as needing a newer version. */
const val VMLS_INVITATION_URL_VERSION: Long = 4

/** The request's body version: today's decoder answers only version 1. */
const val VMLS_JOIN_REQUEST_VERSION: Long = 2

/** The answer's body version: today's decoder answers only version 2. */
const val VMLS_JOIN_ANSWER_VERSION: Long = 3

/** How long a guest waits for the keeper's answer (decision 17), and a keeper's prompt stays open. */
const val VMLS_JOIN_WAIT_SECONDS: Long = 10 * 60

private const val MARKER = "vmls"
private const val MAX_NAME = 80
private val HEX64 = Regex("^[0-9a-f]{64}$")

/**
 * A VMLS link: the bearer and the link's own key, as today's invitation,
 * and the box the room lives on (decision 15: the guest checks that its
 * pairing reaches that box). No room name: the answer carries it.
 */
class VmlsInvitationPayload(val invitation: RoomInvitation, val box: String, val relays: List<String>)

fun encodeVmlsInvitationUrl(base: String, invitation: RoomInvitation, box: String, relays: List<String>): String {
    require(!invitation.persistent) { "a VMLS link is no group invitation" }
    require(HEX64.matches(box)) { "a box is a 32-byte hex Link node id" }
    val payload = buildJsonObject {
        put("v", VMLS_INVITATION_URL_VERSION)
        put("m", MARKER)
        put("j", base64UrlEncode(invitation.bearer))
        put("h", invitation.canonicalInviter)
        put("b", box)
        put("r", buildJsonArray { for (relay in relays) add(JsonPrimitive(relay)) })
    }
    return "$base#${base64UrlEncode(payload.toString().toByteArray(Charsets.UTF_8))}"
}

/** Null for a link that is not a VMLS link; a malformed VMLS link fails closed. */
fun decodeVmlsInvitationUrl(url: String): VmlsInvitationPayload? {
    val fragment = url.substringAfter('#', "")
    if (fragment.isEmpty()) return null
    val payload = try {
        Json.parseToJsonElement(String(base64UrlDecode(fragment), Charsets.UTF_8)).jsonObject
    } catch (_: Exception) {
        return null
    }
    val version = (payload["v"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
    if (version != VMLS_INVITATION_URL_VERSION) return null
    fun malformed(): Nothing = throw JoinUrlException("join URL carries a malformed VMLS invitation")
    try {
        if (payload.getValue("m").jsonPrimitive.content != MARKER) malformed()
        val bearer = base64UrlDecode(payload.getValue("j").jsonPrimitive.content)
        val inviter = payload.getValue("h").jsonPrimitive.content
        val box = payload.getValue("b").jsonPrimitive.content
        if (!HEX64.matches(box)) malformed()
        val relays = (payload.getValue("r") as JsonArray).map { it.jsonPrimitive.also { p -> require(p.isString) }.content }
        return VmlsInvitationPayload(RoomInvitation(bearer, inviter), box, relays)
    } catch (error: JoinUrlException) {
        throw error
    } catch (_: Exception) {
        malformed()
    }
}

/**
 * A guest's request to join, as the keeper reads it: the guest's person
 * credential (signed by its persona, naming its MLS device: the only proof
 * of whose device it is) and its rendezvous key. [requestId] is the event
 * id and [requester] the request's throwaway key, which the answer goes to.
 */
class VmlsJoinRequest(
    val requestId: String,
    val requester: String,
    val credential: NostrEvent,
    val persona: String,
    val device: String,
    val rendezvous: String,
)

fun encodeVmlsJoinRequest(
    invitation: RoomInvitation,
    requesterSecretKey: ByteArray,
    credential: NostrEvent,
    rendezvous: String,
    now: Long,
    nonce: ByteArray = Entropy.bytes(32),
    auxRand: ByteArray = Entropy.bytes(32),
): NostrEvent {
    require(HEX64.matches(rendezvous)) { "a rendezvous key is 32-byte hex" }
    val body = buildJsonObject {
        put("v", VMLS_JOIN_REQUEST_VERSION)
        put("m", MARKER)
        put("credential", credential.toJson())
        put("rz", rendezvous)
    }
    return Events.sign(
        secretKey = requesterSecretKey,
        kind = KIND_INVITATION_REQUEST,
        createdAt = now,
        tags = listOf(
            listOf("d", deriveInvitationId(invitation)),
            listOf("p", invitation.canonicalInviter),
        ),
        content = Nip44.encrypt(body.toString(), invitationRequestKey(invitation), nonce),
        auxRand = auxRand,
    )
}

/** Null for anything but a fresh, well-formed VMLS request over [invitation] with a valid person credential. */
fun decodeVmlsJoinRequest(
    event: NostrEvent,
    invitation: RoomInvitation,
    now: Long,
    maxAgeSeconds: Long = INVITATION_MAX_AGE_SECONDS,
): VmlsJoinRequest? = try {
    if (event.kind != KIND_INVITATION_REQUEST || !Events.verify(event)) null
    else if (kotlin.math.abs(now - event.createdAt) > maxAgeSeconds) null
    else if (event.tagValue("d") != deriveInvitationId(invitation)) null
    else if (!event.tagValue("p").equals(invitation.canonicalInviter, ignoreCase = true)) null
    else {
        val body = Json.parseToJsonElement(Nip44.decrypt(event.content, invitationRequestKey(invitation))).jsonObject
        val version = (body["v"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        val rendezvous = body.getValue("rz").jsonPrimitive.content
        if (version != VMLS_JOIN_REQUEST_VERSION || body.getValue("m").jsonPrimitive.content != MARKER || !HEX64.matches(rendezvous)) null
        else {
            val credential = NostrEvent.fromJson(body.getValue("credential"))
            val checked = LeafBinding.verifyPersonCredential(credential, now)
            Schnorr.publicKey(rendezvous.hexToBytes())
            VmlsJoinRequest(event.id.lowercase(), event.pubkey.lowercase(), credential, checked.identity, checked.device, rendezvous)
        }
    }
} catch (_: Exception) {
    null
}

/** The keeper's answer to one request. */
sealed class VmlsJoinAnswer {
    abstract val requestId: String

    /**
     * Admitted: the guest's device is granted at [box]. The guest makes its
     * capability for the keeper's [rendezvous] key at [counter] and deposits
     * it there; the keeper adds it and the room's Welcome follows.
     */
    data class Admitted(
        override val requestId: String,
        val keeper: String,
        val rendezvous: String,
        val counter: Long,
        val box: String,
        val name: String,
    ) : VmlsJoinAnswer() {
        init {
            require(HEX64.matches(requestId) && HEX64.matches(keeper) && HEX64.matches(rendezvous) && HEX64.matches(box))
            require(counter in 0..MAX_COUNTER)
            require(name.isNotBlank() && name.length <= MAX_NAME && name.none { it.isISOControl() })
        }
    }

    /** The keeper said no: the guest stops waiting. */
    data class Declined(override val requestId: String) : VmlsJoinAnswer() {
        init { require(HEX64.matches(requestId)) }
    }

    companion object {
        /** JSON's exact integers. */
        const val MAX_COUNTER: Long = (1L shl 53) - 1
    }
}

/** Signed with the link's key, to [requester] alone. */
fun encodeVmlsJoinAnswer(
    invitation: RoomInvitation,
    linkSecretKey: ByteArray,
    requester: String,
    answer: VmlsJoinAnswer,
    now: Long,
    nonce: ByteArray = Entropy.bytes(32),
    auxRand: ByteArray = Entropy.bytes(32),
): NostrEvent {
    require(Schnorr.publicKeyHex(linkSecretKey) == invitation.canonicalInviter) { "only the link's key answers" }
    require(HEX64.matches(requester.lowercase())) { "a requester is a 32-byte hex pubkey" }
    val body = buildJsonObject {
        put("v", VMLS_JOIN_ANSWER_VERSION)
        put("m", MARKER)
        put("request", answer.requestId)
        when (answer) {
            is VmlsJoinAnswer.Admitted -> {
                put("ok", true)
                put("keeper", answer.keeper)
                put("rz", answer.rendezvous)
                put("counter", answer.counter)
                put("box", answer.box)
                put("name", answer.name)
            }
            is VmlsJoinAnswer.Declined -> put("ok", false)
        }
    }
    val key = Nip44.conversationKey(linkSecretKey, requester.lowercase().hexToBytes())
    return Events.sign(
        secretKey = linkSecretKey,
        kind = KIND_INVITATION_GRANT,
        createdAt = now,
        tags = listOf(
            listOf("d", deriveInvitationId(invitation)),
            listOf("p", requester.lowercase()),
        ),
        content = Nip44.encrypt(body.toString(), key, nonce),
        auxRand = auxRand,
    )
}

/** Null for anything but a fresh answer from [invitation]'s key to one of [requestIds]. */
fun decodeVmlsJoinAnswer(
    event: NostrEvent,
    invitation: RoomInvitation,
    requesterSecretKey: ByteArray,
    requestIds: Set<String>,
    now: Long,
    maxAgeSeconds: Long = INVITATION_MAX_AGE_SECONDS,
): VmlsJoinAnswer? = try {
    val requester = Schnorr.publicKeyHex(requesterSecretKey)
    if (event.kind != KIND_INVITATION_GRANT || !Events.verify(event)) null
    else if (!event.pubkey.equals(invitation.canonicalInviter, ignoreCase = true)) null
    else if (kotlin.math.abs(now - event.createdAt) > maxAgeSeconds) null
    else if (event.tagValue("d") != deriveInvitationId(invitation)) null
    else if (!event.tagValue("p").equals(requester, ignoreCase = true)) null
    else {
        val key = Nip44.conversationKey(requesterSecretKey, event.pubkey.hexToBytes())
        val body = Json.parseToJsonElement(Nip44.decrypt(event.content, key)).jsonObject
        val version = (body["v"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        val request = body.getValue("request").jsonPrimitive.content
        val ok = (body.getValue("ok") as JsonPrimitive).takeUnless { it.isString }?.content
        when {
            version != VMLS_JOIN_ANSWER_VERSION || body.getValue("m").jsonPrimitive.content != MARKER || request !in requestIds -> null
            ok == "false" -> VmlsJoinAnswer.Declined(request)
            ok != "true" -> null
            else -> VmlsJoinAnswer.Admitted(
                requestId = request,
                keeper = body.getValue("keeper").jsonPrimitive.content,
                rendezvous = body.getValue("rz").jsonPrimitive.content,
                counter = (body.getValue("counter") as JsonPrimitive).takeUnless { it.isString }!!.longOrNull!!,
                box = body.getValue("box").jsonPrimitive.content,
                name = body.getValue("name").jsonPrimitive.content,
            )
        }
    }
} catch (_: Exception) {
    null
}
