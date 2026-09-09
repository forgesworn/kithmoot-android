package dev.forgesworn.kithmoot.storage

import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.account.ParticipantSigner
import dev.forgesworn.kithmoot.session.PrimaryIdentity
import dev.forgesworn.kithmoot.session.RoomIdentity
import dev.forgesworn.kithmoot.session.SecondaryIdentity
import kotlinx.serialization.json.*

internal const val SAVED_CREDENTIAL_TTL = 24L * 60 * 60

class RoomRecoveryException(message: String) : Exception(message)

/** The UI receives labels and identifiers, never the saved capabilities. */
data class SavedRoomSummary(val id: String, val name: String, val secondary: Boolean, val openedAt: Long, val project: String? = null,
    /** The signed-in account this room was joined as, when it was; such a room opens only while that account is signed in. */
    val account: String? = null)

/** Contains secrets. Its string representation deliberately contains none. */
class SavedRoom private constructor(internal val json: JsonObject) {
    val id: String get() = json.text("id")
    val name: String get() = json.text("name")
    val secret: ByteArray get() = json.text("secret").keyBytes()
    val joinUrl: String get() = json.text("joinUrl")
    val invitation: InvitationPayload? get() = decodeInvitationUrl(joinUrl)
    val policy: RoomPolicy? get() = invitation?.policy ?: if (invitation == null) decodeJoinUrl(joinUrl).policy else null
    val relays: List<String> get() = json.getValue("relays").jsonArray.map { it.jsonPrimitive.content }
    val authority: String? get() = json["authority"]?.jsonPrimitive?.content
    val secondary: Boolean get() = identityJson.text("type") == "secondary"
    /** Joined as a signed-in account, whose key is with a signer and not in this store. */
    val viaAccount: Boolean get() = identityJson.text("type") == "account"
    val participant: String get() = when (identityJson.text("type")) {
        "secondary" -> NostrEvent.fromJson(identityJson.getValue("credential")).pubkey
        "account" -> identityJson.text("participant")
        else -> Schnorr.publicKeyHex(identityJson.text("participantKey").keyBytes())
    }
    val openedAt: Long get() = json.getValue("openedAt").jsonPrimitive.long
    /** The project this room is filed under on this device, if any. A label
     *  and nothing more: it changes nothing about the room or who is in it. */
    val project: String? get() = json["project"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
    val retired: Boolean get() = json["retired"]?.jsonPrimitive?.boolean ?: false
    val movedOn: Boolean get() = json["movedOn"]?.jsonPrimitive?.boolean ?: false
    val retirements: List<NostrEvent> get() = json["retirements"]?.jsonArray?.map { NostrEvent.fromJson(it) } ?: emptyList()
    private val identityJson: JsonObject get() = json.getValue("identity").jsonObject

    fun summary(): SavedRoomSummary = SavedRoomSummary(id, name, secondary, openedAt, project, participant.takeIf { viaAccount })

    /** The identity for a room this device holds the keys for. A room joined as an account needs [identity] with its signer. */
    fun identity(now: Long): RoomIdentity {
        if (movedOn) throw RoomRecoveryException("This room has changed its keys. Ask for a current invitation.")
        val device = identityJson.text("deviceKey").keyBytes()
        return when (identityJson.text("type")) {
            "primary" -> PrimaryIdentity.create(id, now + SAVED_CREDENTIAL_TTL, now,
                identityJson.text("participantKey").keyBytes(), device)
            "secondary" -> SecondaryIdentity.adopt(
                NostrEvent.fromJson(identityJson.getValue("credential")), device, id, now,
            ) ?: throw RoomRecoveryException("This device's pairing has expired. Pair it again from your main device.")
            "account" -> throw RoomRecoveryException(accountNeeded())
            else -> error("Unknown saved identity")
        }
    }

    /**
     * The identity, with the signed-in account's signer for a room joined as
     * that account: a fresh device credential, one signature, which the
     * person may have to approve in their signer. Any other account, or none,
     * cannot open the room, and says so rather than joining as a stranger.
     */
    suspend fun identity(now: Long, signer: ParticipantSigner?): RoomIdentity {
        if (!viaAccount) return identity(now)
        if (movedOn) throw RoomRecoveryException("This room has changed its keys. Ask for a current invitation.")
        if (signer == null || signer.pubkey != participant) throw RoomRecoveryException(accountNeeded())
        return PrimaryIdentity.createWith(signer, id, now + SAVED_CREDENTIAL_TTL, now, identityJson.text("deviceKey").keyBytes())
    }

    private fun accountNeeded(): String =
        "This room was joined as ${dev.forgesworn.kithmoot.account.shortNpub(participant)}. Sign in as that account to open it."

    /** Expired admission delegations cannot be renewed by a saved member. */
    fun host(now: Long): RoomInvitationHost? {
        if (retired || movedOn) return null
        val host = storedHost() ?: return null
        return host.takeIf { verifyInvitationDelegation(it.invitation, it.delegation, now) != null }
    }

    private fun storedHost(): RoomInvitationHost? {
        val stored = json["host"]?.jsonObject ?: return null
        val payload = requireNotNull(invitation)
        val chain = stored.getValue("delegation").jsonArray.map { InvitationDelegation.fromJson(it.jsonObject) }
        require(chain.all { it.room == id })
        return RoomInvitationHost(payload.invitation, stored.text("key").keyBytes(), chain)
    }

    fun opened(now: Long): SavedRoom = changed { put("openedAt", now) }
    fun renamed(name: String): SavedRoom = changed { put("name", cleanName(name, id)) }
    fun inProject(project: String?): SavedRoom = changed {
        val clean = project?.trim()?.take(48).orEmpty()
        if (clean.isEmpty()) remove("project") else put("project", clean)
    }
    fun invitationRetired(): SavedRoom = changed { put("retired", true); remove("host") }
    fun keysChanged(): SavedRoom = changed { put("movedOn", true); remove("host") }
    fun retainingHistory(previous: SavedRoom): SavedRoom {
        // An old meeting link must not replace durable membership or creator authority.
        if (previous.invitation?.invitation?.persistent == true && invitation?.invitation?.persistent != true) {
            return previous.opened(openedAt)
        }
        return changed {
            put("retirements", JsonArray(previous.retirements.map { it.toJson() }))
            if (invitation?.invitation == previous.invitation?.invitation && previous.retired) {
                put("retired", true)
                remove("host")
            }
        }
    }

    /** Save both the new capability and the old signed tombstone before publishing. */
    fun rotated(host: RoomInvitationHost, url: String, retirement: NostrEvent): SavedRoom {
        require(retirements.size < 128) { "Too many saved invitation changes" }
        require(decodeInvitationRetirement(retirement, requireNotNull(invitation).invitation))
        return changed {
            put("joinUrl", url)
            put("host", hostJson(host))
            put("retired", false)
            put("retirements", JsonArray(retirements.map { it.toJson() } + retirement.toJson()))
        }.also { it.validate() }
    }

    private fun changed(block: MutableMap<String, JsonElement>.() -> Unit): SavedRoom =
        SavedRoom(JsonObject(json.toMutableMap().apply(block)))

    override fun toString(): String = "SavedRoom(id=$id, secrets=<redacted>)"

    private fun validate() {
        require(id.matches(Regex("[0-9a-f]{64}")))
        require(deriveRoom(secret).roomId == id)
        require(name.isNotBlank() && name.length <= 80)
        require(openedAt >= 0)
        require(relays.isNotEmpty() && relays.size <= 16)
        require(relays.all { it.startsWith("wss://") || it.startsWith("ws://") })
        authority?.let { require(it.matches(Regex("[0-9a-f]{64}"))) }
        if (invitation == null) require(decodeJoinUrl(joinUrl).secret.contentEquals(secret))
        Schnorr.publicKeyHex(identityJson.text("deviceKey").keyBytes())
        when (identityJson.text("type")) {
            "primary" -> {
                require("credential" !in identityJson)
                Schnorr.publicKeyHex(identityJson.text("participantKey").keyBytes())
            }
            "secondary" -> {
                require("participantKey" !in identityJson)
                val credential = NostrEvent.fromJson(identityJson.getValue("credential"))
                require(SecondaryIdentity.adopt(credential, identityJson.text("deviceKey").keyBytes(), id, credential.createdAt) != null)
            }
            "account" -> {
                require("participantKey" !in identityJson && "credential" !in identityJson)
                require(identityJson.text("participant").matches(Regex("[0-9a-f]{64}")))
            }
            else -> error("Unknown saved identity")
        }
        storedHost()
        require(retirements.size <= 128)
        require(retirements.all { it.kind == KIND_INVITATION_RETIREMENT && Events.verify(it) })
    }

    companion object {
        fun create(secret: ByteArray, identity: RoomIdentity, joinUrl: String, relays: List<String>,
                   name: String, now: Long, host: RoomInvitationHost?, authority: String?): SavedRoom {
            val id = deriveRoom(secret).roomId
            return SavedRoom(buildJsonObject {
                put("id", id)
                put("secret", secret.toHex())
                put("joinUrl", joinUrl)
                put("relays", JsonArray(relays.map(::JsonPrimitive)))
                put("name", cleanName(name, id))
                put("openedAt", now)
                authority?.let { put("authority", it) }
                put("identity", buildJsonObject {
                    put("deviceKey", identity.deviceSecretKey.toHex())
                    when (identity) {
                        is PrimaryIdentity -> {
                            val key = identity.participantKeyForStorage()
                            if (key != null) {
                                put("type", "primary")
                                put("participantKey", key.toHex())
                            } else {
                                put("type", "account")
                                put("participant", identity.participant)
                            }
                        }
                        is SecondaryIdentity -> {
                            put("type", "secondary")
                            put("credential", identity.credential.toJson())
                        }
                    }
                })
                host?.let { put("host", hostJson(it)) }
            }).also { it.validate() }
        }

        internal fun decode(json: JsonObject): SavedRoom = SavedRoom(json).also { it.validate() }
        private fun cleanName(value: String, id: String): String = value.trim().take(80).ifEmpty { "Room ${id.take(8)}" }
        private fun hostJson(host: RoomInvitationHost): JsonObject = buildJsonObject {
            put("key", host.inviterSecretKey.toHex())
            put("delegation", JsonArray(host.delegation.map { it.toJson() }))
        }
    }
}

private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
private fun String.keyBytes(): ByteArray = hexToBytes().also { require(it.size == 32) }
private fun MutableMap<String, JsonElement>.put(key: String, value: String) { this[key] = JsonPrimitive(value) }
private fun MutableMap<String, JsonElement>.put(key: String, value: Boolean) { this[key] = JsonPrimitive(value) }
private fun MutableMap<String, JsonElement>.put(key: String, value: Long) { this[key] = JsonPrimitive(value) }
