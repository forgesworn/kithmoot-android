package dev.forgesworn.kithmoot.storage

import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.account.ParticipantSigner
import dev.forgesworn.kithmoot.session.PrimaryIdentity
import dev.forgesworn.kithmoot.session.RoomIdentity
import dev.forgesworn.kithmoot.session.SecondaryIdentity
import dev.forgesworn.kithmoot.session.conferenceEndedMessage
import dev.forgesworn.kithmoot.relay.RoomRoute
import dev.forgesworn.kithmoot.relay.TorOnlyRelayUrls
import dev.forgesworn.kithmoot.epoch.KeeperPhase
import dev.forgesworn.kithmoot.epoch.NativeKeeperBinding
import dev.forgesworn.kithmoot.epoch.NativeKeeperJournal
import kotlinx.serialization.json.*

internal const val SAVED_CREDENTIAL_TTL = 24L * 60 * 60

/**
 * The least life a kept account credential must have left to be reused when
 * the room is opened again. Half its life, because this client does not
 * renew a credential inside a session: a room opened on one with an hour
 * left would stop sending an hour later.
 */
internal const val KEPT_CREDENTIAL_MIN_REMAINING = SAVED_CREDENTIAL_TTL / 2

/**
 * How long the credential minted for a Ring me room lasts: seven days, so a
 * phone left alone over a weekend, or a signer left locked for a few days,
 * still rings and answers. A room credential has no maximum lifetime on any
 * verifier (only the person form is capped, at thirty days), and it is signed
 * by the account's signer and bound to one device key in one room, so a lost
 * phone is contained by that room and by this expiry either way.
 */
internal const val RING_CREDENTIAL_TTL = 7L * 24 * 60 * 60

/**
 * A Ring me room's kept credential is renewed once it has less than this left:
 * half its life, so a signer that is locked for the first three and a half days
 * of the window is still asked again later, quietly, before anything lapses.
 */
internal const val RING_CREDENTIAL_RENEW_BELOW = RING_CREDENTIAL_TTL / 2

class RoomRecoveryException(message: String) : Exception(message)

/** The UI receives labels and identifiers, never the saved capabilities. */
data class SavedRoomSummary(val id: String, val name: String, val secondary: Boolean, val openedAt: Long, val project: String? = null,
    /** The signed-in account this room was joined as, when it was; such a room opens only while that account is signed in. */
    val account: String? = null,
    /** This room has a locally-created identity and may use only the Tor carrier. */
    val anonymous: Boolean = false,
    /** The room's invitation has been retired, or its keys have moved on: it still opens (the error explains), but nothing new can join it. */
    val ended: Boolean = false,
    /** There is a link worth sharing: not ended, not a paired secondary device, and the saved link holds an invitation payload. */
    val canShareInvite: Boolean = false,
    /** A conference room's end, unix seconds; null for a room that does not end. */
    val endsAt: Long? = null,
    /** Pinned to the top of the home list on this device. Never leaves it: not in account bookmarks, not on a relay. */
    val pinned: Boolean = false,
    /** The room self-destructs when it ends: see [SavedRoom.destruct]. */
    val destruct: Boolean = false,
    /** When this device first knew the room, for scaling its countdown: see [SavedRoom.startsAt]. */
    val startsAt: Long? = null,
    /** The other member of a two-person policy this saved identity belongs to. */
    val privatePeer: String? = null,
    val route: RoomRoute = RoomRoute.INTERNET)

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
    /** A public reference, never proof of source availability or receiver readiness. */
    internal val nativeAuthority: NativeKeeperBinding?
        get() = json["nativeAuthority"]?.let(NativeKeeperReference::decode)

    /** Fresh sources only. Legacy host transfer requires a separately qualified migration. */
    internal fun withNativeAuthority(source: NativeKeeperJournal): SavedRoom {
        require("host" !in json) { "Legacy hosting requires explicit authority transfer" }
        val binding = source.binding
        val state = source.snapshot()
        require(state.suspended && state.phase == KeeperPhase.ACTIVE && state.epoch == 0 && state.pending.isEmpty() && state.cause == null &&
            state.invitationGeneration == 0 && state.replacement == null)
        require(!retired && !movedOn && !anonymous && !secondary)
        val payload = requireNotNull(invitation).invitation
        val sourceInvitation = source.invitation()
        val sourceEpoch = source.epoch()
        val savedSecret = secret
        try {
            require(payload == sourceInvitation && sourceEpoch.secret.contentEquals(savedSecret))
            val welcome = requireNotNull(decodePersistentInvitation(source.welcome(), payload))
            try { require(welcome.secret.contentEquals(savedSecret) && welcome.endsAt == ends && welcome.destruct == destruct) }
            finally { welcome.secret.fill(0) }
        } finally {
            sourceInvitation.bearer.fill(0)
            sourceEpoch.secret.fill(0)
            savedSecret.fill(0)
        }
        require(source.snapshot() == state) { "Source changed during reference installation" }
        nativeAuthority?.let { require(it.pin == binding.pin) }
        return changed { put("nativeAuthority", NativeKeeperReference.encode(binding, deriveInvitationId(payload))) }.also { it.validate() }
    }

    /** Checks the actual independent source after open; saved fields cannot recreate it. */
    internal fun verifyNativeAuthority(source: NativeKeeperJournal) {
        require(source.binding.pin == requireNotNull(nativeAuthority).pin)
        val before = source.snapshot()
        require(NativeKeeperReference.generation(json.getValue("nativeAuthority")) == before.invitationGeneration) {
            "Saved invitation generation does not match the native source"
        }
        val payload = requireNotNull(invitation).invitation
        try {
            val sourceInvitation = source.invitation()
            try {
                require(payload == sourceInvitation) { "Saved invitation does not match the native source" }
                val welcome = requireNotNull(decodePersistentInvitation(source.welcome(), payload))
                val savedSecret = secret
                try { require(welcome.secret.contentEquals(savedSecret)) }
                finally { welcome.secret.fill(0); savedSecret.fill(0) }
            } finally { sourceInvitation.bearer.fill(0) }
        } finally { payload.bearer.fill(0) }
        require(source.snapshot() == before) { "Source changed during reference verification" }
    }
    /**
     * The highest epoch this device has been told the room is at - by the
     * responder that admitted it (`RoomAdmission.epoch`) - kept so a room
     * whose authority did not answer at the time asks again next time, and
     * says it needs recovery meanwhile, rather than opening as if current.
     * Null for a room never told, and every record older than this field.
     */
    val epochHint: Int? get() = json["epochHint"]?.jsonPrimitive?.intOrNull
    /** Old records predate this mode and therefore remain ordinary direct rooms. */
    val anonymous: Boolean get() = json["anonymous"]?.jsonPrimitive?.boolean ?: false
    /** Local transport selection; never copied into account bookmarks or invitations. */
    val route: RoomRoute get() = RoomRoute.fromStored(json["route"]?.jsonPrimitive?.also {
        require(it.isString) { "Invalid room connection mode" }
    }?.content)
    val secondary: Boolean get() = identityJson.text("type") == "secondary"
    /** Joined as a signed-in account, whose key is with a signer and not in this store. */
    val viaAccount: Boolean get() = identityJson.text("type") == "account"
    val participant: String get() = when (identityJson.text("type")) {
        "secondary" -> NostrEvent.fromJson(identityJson.getValue("credential")).pubkey
        "account" -> identityJson.text("participant")
        else -> Schnorr.publicKeyHex(identityJson.text("participantKey").keyBytes())
    }
    /** This device's own device pubkey in this room, readable without a
     *  signer - unlike [identity], which an "account" room needs one for.
     *  Used to keep the background call listener from ringing for its own
     *  other rooms' bells. */
    val devicePubkey: String get() = Schnorr.publicKeyHex(identityJson.text("deviceKey").keyBytes())
    /** This device's own device secret key in this room, readable without a signer like
     *  [devicePubkey]: what opens the copy a rekey seals to this device while the room is
     *  closed (`BackgroundRekeyFollower`). A fresh copy; the caller wipes it. */
    fun deviceSecretKey(): ByteArray = identityJson.text("deviceKey").keyBytes()
    val openedAt: Long get() = json.getValue("openedAt").jsonPrimitive.long
    /** The project this room is filed under on this device, if any. A label
     *  and nothing more: it changes nothing about the room or who is in it. */
    val project: String? get() = json["project"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
    /** Pinned on this device. A record written before pins existed has no key, so it reads false. */
    val pinned: Boolean get() = json["pinned"]?.jsonPrimitive?.boolean ?: false
    val retired: Boolean get() = json["retired"]?.jsonPrimitive?.boolean ?: false
    val movedOn: Boolean get() = json["movedOn"]?.jsonPrimitive?.boolean ?: false
    /** A conference room's end, unix seconds, from its group invitation.
     *  Null for a room that does not end, and for every record older than
     *  conference rooms. */
    val ends: Long? get() = json["ends"]?.jsonPrimitive?.long
    /** A conference room past its end: it cannot be opened, replied to or shared. */
    fun ended(now: Long): Boolean = conferenceEnded(ends, now)
    /**
     * The room self-destructs (fold-kit 0.9.0 `destruct`, from its group
     * invitation or its closing rekey): when it ends, by its time or by its
     * authority closing it, this device deletes what it wrote there and
     * forgets it. Kept so a device offline at the end still does so when the
     * app next starts, which needs this record's device key, so the record
     * stays until then. Never taken back once set.
     */
    val destruct: Boolean get() = json["destruct"]?.jsonPrimitive?.booleanOrNull == true
    /** Unix seconds this device first knew a room that ends, for scaling the
     *  countdown to the room's lifetime (a late joiner's is shorter than the
     *  room's: close enough for choosing colours). Null for a room with no
     *  end, and every record older than the countdown. */
    val startsAt: Long? get() = json["startsAt"]?.jsonPrimitive?.longOrNull
    /** The heads-up at the start of the countdown's red stage has been shown for this room. */
    val destructHeadsUp: Boolean get() = json["destructHeadsUp"]?.jsonPrimitive?.booleanOrNull == true
    val retirements: List<NostrEvent> get() = json["retirements"]?.jsonArray?.map { NostrEvent.fromJson(it) } ?: emptyList()
    private val identityJson: JsonObject get() = json.getValue("identity").jsonObject

    /** A retained reading capability, never a renewed signing identity. Directory
     * membership and bookmarks alone cannot admit a workspace activity reader. */
    fun workspaceAdmission(account: String?, now: Long): Boolean {
        if (account == null || !viaAccount || participant != account || openedAt <= 0 ||
            anonymous || retired || movedOn || ends != null || destruct || policy?.quiet == true || !route.internet) return false
        val credential = runCatching { NostrEvent.fromJson(identityJson.getValue("credential")) }.getOrNull() ?: return false
        if (credential.createdAt > now + 120) return false
        val verified = verifyDeviceCredential(credential, id, credential.createdAt)
        return verified is CredentialCheck.Valid && verified.participant == account && verified.device == devicePubkey
    }

    fun summary(now: Long = System.currentTimeMillis() / 1000): SavedRoomSummary {
        val ended = retired || movedOn || ended(now)
        return SavedRoomSummary(id, name, secondary, openedAt, project, participant.takeIf { viaAccount }, anonymous,
            ended = ended, canShareInvite = !ended && !secondary && joinUrl.substringAfter('#', "").isNotBlank(), endsAt = ends,
            pinned = pinned, destruct = destruct, startsAt = startsAt, route = route,
            privatePeer = if (anonymous) null else dev.forgesworn.kithmoot.session.dmPeer(policy, participant))
    }

    fun withRoute(route: RoomRoute): SavedRoom = changed {
        if (route == RoomRoute.INTERNET) remove("route") else put("route", route.stored)
    }.also { it.validate() }

    /** Never resolves or calls an account signer. Reuses only valid authority already held here. */
    fun offlineIdentity(now: Long, accountPubkey: String? = null): RoomIdentity {
        if (!viaAccount) return identity(now)
        if (accountPubkey != participant) throw RoomRecoveryException(accountNeeded())
        if (movedOn || ended(now)) throw RoomRecoveryException("This room has ended or changed its keys.")
        val credential = keptCredential(now, 0)
            ?: throw RoomRecoveryException("This room's device credential has expired. Reopen using Internet to renew it before going nearby-only.")
        return PrimaryIdentity(dev.forgesworn.kithmoot.account.OfflineParticipantSigner(participant),
            identityJson.text("deviceKey").keyBytes(), credential)
    }

    /** The identity for a room this device holds the keys for. A room joined as an account needs [identity] with its signer. */
    fun identity(now: Long): RoomIdentity {
        if (movedOn) throw RoomRecoveryException("This room has changed its keys. Ask for a current invitation.")
        ends?.takeIf { ended(now) }?.let { throw RoomRecoveryException(conferenceEndedMessage(it)) }
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
     *
     * A kept credential with at least [reuseWhileRemaining] seconds left is
     * reused; otherwise a new one is minted to last [lifetime]. Opening a room
     * keeps the defaults; a Ring me room asks for [RING_CREDENTIAL_TTL], and
     * the renewal that keeps it fresh for [RING_CREDENTIAL_RENEW_BELOW].
     */
    suspend fun identity(
        now: Long,
        signer: ParticipantSigner?,
        lifetime: Long = SAVED_CREDENTIAL_TTL,
        reuseWhileRemaining: Long = KEPT_CREDENTIAL_MIN_REMAINING,
    ): RoomIdentity {
        if (!viaAccount) return identity(now)
        if (movedOn) throw RoomRecoveryException("This room has changed its keys. Ask for a current invitation.")
        ends?.takeIf { ended(now) }?.let { throw RoomRecoveryException(conferenceEndedMessage(it)) }
        if (signer == null || signer.pubkey != participant) throw RoomRecoveryException(accountNeeded())
        val device = identityJson.text("deviceKey").keyBytes()
        // The credential minted last time, while it has life enough left:
        // opening a conversation again must not wait on a bunker or a signer
        // app that may take seconds, or never answer.
        keptCredential(now, reuseWhileRemaining)?.let { return PrimaryIdentity(signer, device, it) }
        return PrimaryIdentity.createWith(signer, id, now + lifetime, now, device)
    }

    /**
     * What signs a message sent without opening the room, as a reply from a
     * notification: this device's key and a credential no signer has to be
     * asked for. A key held here mints its own; a paired device has its
     * pairing's; an account room has the credential kept from its last
     * opening, while it lasts. Null when only a signer could make one.
     */
    fun headlessSigning(now: Long): HeadlessSigning? {
        if (movedOn || ended(now)) return null
        val device = identityJson.text("deviceKey").keyBytes()
        if (viaAccount) return keptCredential(now, 0)?.let { HeadlessSigning(participant, it, device) }
        val identity = runCatching { identity(now) }.getOrNull() ?: return null
        return HeadlessSigning(identity.participant, identity.credential, identity.deviceSecretKey)
    }

    /** When the account credential kept with this room expires, or null when
     *  there is none still valid at [now]. See `service/CredentialRenewal.kt`. */
    fun keptCredentialExpiry(now: Long): Long? = keptCredential(now, 0)?.tagValue("expiration")?.toLongOrNull()

    /** The account credential kept with this room, if it still authorises this
     *  device as [participant] here at [now] with at least [minRemaining] seconds left. */
    private fun keptCredential(now: Long, minRemaining: Long): NostrEvent? {
        if (!viaAccount) return null
        val credential = runCatching { NostrEvent.fromJson(identityJson.getValue("credential")) }.getOrNull() ?: return null
        val check = verifyDeviceCredential(credential, id, now)
        if (check !is CredentialCheck.Valid || check.participant != participant || check.device != devicePubkey) return null
        val expiresAt = credential.tagValue("expiration")?.toLongOrNull() ?: return null
        return credential.takeIf { expiresAt - now >= minRemaining }
    }

    /** Keeps the credential [identity] carries, for a room joined as an account,
     *  so the next opening can reuse it. Anything else is returned unchanged. */
    fun keepingCredential(identity: RoomIdentity): SavedRoom {
        if (!viaAccount || identity !is PrimaryIdentity || identity.participant != participant || identity.devicePubkey != devicePubkey) return this
        val check = verifyDeviceCredential(identity.credential, id, identity.credential.createdAt)
        if (check !is CredentialCheck.Valid) return this
        return changed { this["identity"] = JsonObject(identityJson.toMutableMap().apply { this["credential"] = identity.credential.toJson() }) }
            .also { it.validate() }
    }

    private fun accountNeeded(): String =
        "This room was joined as ${dev.forgesworn.kithmoot.account.shortNpub(participant)}. Sign in as that account to open it."

    /** Expired admission delegations cannot be renewed by a saved member. */
    fun host(now: Long): RoomInvitationHost? {
        if (retired || movedOn || ended(now)) return null
        val host = storedHost() ?: return null
        return host.takeIf { verifyInvitationDelegation(it.invitation, it.delegation, now) != null }
    }

    private fun storedHost(): RoomInvitationHost? {
        if ("nativeAuthority" in json) {
            require("host" !in json) { "Native and legacy authorities cannot coexist" }
            return null
        }
        val stored = json["host"]?.jsonObject ?: return null
        val payload = requireNotNull(invitation)
        val chain = stored.getValue("delegation").jsonArray.map { InvitationDelegation.fromJson(it.jsonObject) }
        require(chain.all { it.room == id })
        return RoomInvitationHost(payload.invitation, stored.text("key").keyBytes(), chain)
    }

    fun opened(now: Long): SavedRoom = changed { put("openedAt", now) }
    /** What a quiet room keeps on this device between visits: the drop-key
     *  counters spent this epoch, and the messages still waiting for a slot.
     *  See `session/QuietTransport.kt`. Null when nothing is kept. */
    val quietState: JsonObject? get() = json["quiet"] as? JsonObject
    fun withQuietState(state: JsonObject?): SavedRoom = changed { if (state == null) remove("quiet") else put("quiet", state) }
    fun renamed(name: String): SavedRoom = changed { put("name", cleanName(name, id)) }
    /** The rename this device last took as the room's shared name, with its
     *  order key, kept so a link written before the rename does not put the
     *  old name back and so this device can post it again after every copy
     *  has left the relays. Null while nobody has renamed the room. See
     *  `protocol/RoomName.kt`. */
    val sharedName: RoomNameRecord? get() = (json["sharedName"] as? JsonObject)?.let {
        val at = it.getValue("at").jsonPrimitive.long
        RoomNameRecord(it.text("name"), it.text("id"), at, sentAt = Math.floorDiv(at, 1000L))
    }
    /** The room's shared name is now [record]'s: kept with its order key, and
     *  this device's name for the room follows it. */
    fun withSharedName(record: RoomNameRecord): SavedRoom = changed {
        put("name", cleanName(record.name, id))
        put("sharedName", buildJsonObject {
            put("name", record.name)
            put("id", record.id)
            put("at", record.at)
        })
    }.also { it.validate() }
    fun inProject(project: String?): SavedRoom = changed {
        val clean = project?.trim()?.take(48).orEmpty()
        if (clean.isEmpty()) remove("project") else put("project", clean)
    }
    /** Learnt that the room self-destructs: from its closing rekey, or a
     *  later copy of its invitation. Sticky, so there is no way back. */
    fun withDestruct(): SavedRoom = if (destruct) this else changed { put("destruct", true) }.also { it.validate() }
    /** Lifetime learned from another admitted copy. Never extend an end or undo destruction. */
    fun withRoomLifetime(end: Long?, selfDestruct: Boolean, start: Long?): SavedRoom {
        if (invitation?.invitation?.persistent != true) return this
        val nextEnd = listOfNotNull(ends, end).minOrNull()
        val nextStart = listOfNotNull(startsAt, start).minOrNull()
        val nextDestruct = destruct || selfDestruct
        if (nextEnd == ends && nextStart == startsAt && nextDestruct == destruct) return this
        return changed {
            nextEnd?.let { put("ends", it) }
            nextStart?.let { put("startsAt", it) }
            if (nextDestruct) put("destruct", true)
        }.also { it.validate() }
    }

    fun withDestructHeadsUp(): SavedRoom = if (destructHeadsUp) this else changed { put("destructHeadsUp", true) }
    fun withPinned(pinned: Boolean): SavedRoom = changed { if (pinned) put("pinned", JsonPrimitive(true)) else remove("pinned") }
    fun withRelays(relays: List<String>): SavedRoom = changed {
        require(relays.isNotEmpty() && relays.size <= 16)
        require(relays.all { it.startsWith("ws://") || it.startsWith("wss://") })
        put("relays", JsonArray(relays.map(::JsonPrimitive)))
    }.also { it.validate() }

    /** The newest signed `relays` record this device has taken from the
     *  room's authority, kept across visits so an old copy of the room does
     *  not re-adopt a version it has already moved past. Null until this
     *  room has ever seen one. Whether this record's copy in the control
     *  log is stale enough to repost is a per-visit judgement, not a saved
     *  one - see `RoomWork`'s in-memory `roomRelaysSeenAt`, which mirrors
     *  the web client keeping that clock only for the current session. */
    val roomRelayRecord: RoomRelaysRecord? get() = (json["roomRelays"] as? JsonObject)?.let {
        RoomRelaysRecord(it.getValue("relays").jsonArray.map { url -> url.jsonPrimitive.content }, it.getValue("version").jsonPrimitive.long, it.text("sig"))
    }
    fun withRoomRelaysRecord(record: RoomRelaysRecord): SavedRoom = changed {
        put("roomRelays", buildJsonObject {
            put("relays", JsonArray(record.relays.map(::JsonPrimitive)))
            put("version", record.version)
            put("sig", record.sig)
        })
    }.also { it.validate() }

    /** The room's own relays: the ones it was made on, fixed, at most eight.
     *  Every member's pool includes them, ahead of its own (see
     *  `RoomRelays.atOpen`); a room sheltered behind a Bothy uses only the
     *  ones its guard accepts. Empty for an anonymous room, whose link's
     *  onion relays are already its own, and any room that has not learnt
     *  them yet. */
    val roomRelays: List<String> get() = json["fixedRelays"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
    /** [roomRelays] came from the room's signed group invitation, or this
     *  device made the room, rather than from a link's unsigned hints. */
    val roomRelaysSigned: Boolean get() = json["fixedRelaysSigned"]?.jsonPrimitive?.boolean ?: false
    /** Every relay all of this room's members use: [roomRelays], then the
     *  ones its authority's newest `relays` record added. */
    val sharedRelays: List<String> get() = (roomRelays + (roomRelayRecord?.relays ?: emptyList())).distinct()

    /** Learns the room's relays. A signed list (from the group invitation)
     *  replaces whatever was held; an unsigned one (a link's hints) is taken
     *  only when nothing was. An empty list changes nothing. */
    fun withRoomRelays(relays: List<String>, signed: Boolean): SavedRoom {
        if (relays.isEmpty()) return this
        if (!signed && roomRelays.isNotEmpty()) return this
        if (relays == roomRelays && signed == roomRelaysSigned) return this
        return changed {
            put("fixedRelays", JsonArray(relays.map(::JsonPrimitive)))
            if (signed) put("fixedRelaysSigned", true) else remove("fixedRelaysSigned")
        }.also { it.validate() }
    }

    /** Remember [epoch] as told, keeping the highest; a null or no-higher hint changes nothing. */
    fun withEpochHint(epoch: Int?): SavedRoom {
        if (epoch == null || epoch < 0 || epoch <= (epochHint ?: -1)) return this
        return changed { put("epochHint", epoch.toLong()) }.also { it.validate() }
    }

    /**
     * Pin the root inviter of this room's invitation link as its authority,
     * for a record saved before the authority was recorded. That is what the
     * web client pins for the same link (`link.invitation.inviter`), and
     * without it the room never follows a rekey. A record with an authority,
     * or with no invitation (a legacy secret link), is unchanged.
     */
    fun withInvitationAuthority(): SavedRoom {
        if (authority != null) return this
        val inviter = invitation?.invitation?.canonicalInviter ?: return this
        return changed { put("authority", inviter) }.also { it.validate() }
    }

    fun invitationRetired(): SavedRoom = changed { put("retired", true); remove("host") }
    fun keysChanged(): SavedRoom = changed { put("movedOn", true); remove("host") }
    fun retainingHistory(previous: SavedRoom): SavedRoom {
        val previousNative = previous.nativeAuthority
        if (previousNative != null) {
            require(id == previous.id && participant == previous.participant && devicePubkey == previous.devicePubkey) {
                "Native keeper ownership cannot change through a bookmark refresh"
            }
            require("host" !in json) { "A bookmark cannot add a legacy signer to a native authority" }
            nativeAuthority?.let { require(it.pin == previousNative.pin) }
            // Keep the exact source invitation and public binding when an old link is reopened.
            if (invitation?.invitation != previous.invitation?.invitation) return previous.opened(openedAt)
            require(authority == null || authority == previousNative.authority)
        }
        // An old meeting link must not replace durable membership or creator authority.
        if (previous.invitation?.invitation?.persistent == true && invitation?.invitation?.persistent != true) {
            return previous.opened(openedAt)
        }
        return changed {
            if (previousNative != null) {
                put("nativeAuthority", previous.json.getValue("nativeAuthority"))
                put("authority", previousNative.authority)
                put("relays", previous.json.getValue("relays"))
                if (previousNative.route == RoomRoute.INTERNET) remove("route") else put("route", previousNative.route.stored)
                if (previous.movedOn) put("movedOn", true)
            }
            // A bookmark/link refresh cannot silently re-enable room internet traffic.
            previous.json["route"]?.let { this["route"] = it }
            // The end is the room's, not the link's: a later opening that did
            // not learn it (a synced bookmark) must not forget it.
            if (ends == null) previous.ends?.let { put("ends", it) }
            // So is self-destruct, which no later opening takes back, and
            // when this device first knew the room, which the countdown scales by.
            if (previous.destruct) put("destruct", true)
            previous.startsAt?.let { put("startsAt", it) }
            if (previous.destructHeadsUp) put("destructHeadsUp", true)
            // What this device was told about the room's epoch is the room's too.
            previous.epochHint?.takeIf { it > (epochHint ?: -1) }?.let { put("epochHint", it.toLong()) }
            // So are its relays: a link's hints never displace what the
            // room's signed invitation said, and an authority's record is kept.
            if (previous.roomRelays.isNotEmpty() && (roomRelays.isEmpty() || (previous.roomRelaysSigned && !roomRelaysSigned))) {
                put("fixedRelays", JsonArray(previous.roomRelays.map(::JsonPrimitive)))
                if (previous.roomRelaysSigned) put("fixedRelaysSigned", true) else remove("fixedRelaysSigned")
            }
            if (roomRelayRecord == null) previous.json["roomRelays"]?.let { this["roomRelays"] = it }
            // A name the room's members chose outranks whatever the link said.
            if (sharedName == null) previous.sharedName?.let { shared ->
                previous.json["sharedName"]?.let { this["sharedName"] = it }
                put("name", cleanName(shared.name, id))
            }
            put("retirements", JsonArray(previous.retirements.map { it.toJson() }))
            if (invitation?.invitation == previous.invitation?.invitation && previous.retired) {
                put("retired", true)
                remove("host")
            }
        }.also { if (previousNative != null || it.nativeAuthority != null) it.validate() }
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
        val selectedRoute = route
        require(selectedRoute.internet || !destruct) { "Self-destructing rooms still need their Internet cleanup route." }
        require(!selectedRoute.nearby || (!anonymous && policy?.quiet != true)) {
            "Nearby routes are not yet available for anonymous or quiet rooms."
        }
        require(id.matches(Regex("[0-9a-f]{64}")))
        require(deriveRoom(secret).roomId == id)
        require(name.isNotBlank() && name.length <= 80)
        require(openedAt >= 0)
        require(relays.size <= 16 && (!selectedRoute.internet || relays.isNotEmpty())) { "Configure this room’s relays before choosing an Internet connection." }
        require(relays.all { it.startsWith("wss://") || it.startsWith("ws://") })
        if (anonymous) TorOnlyRelayUrls.assertRoomTransport(relays, emptyList())
        authority?.let { require(it.matches(Regex("[0-9a-f]{64}"))) }
        json["epochHint"]?.let { require(it is JsonPrimitive && !it.isString && (it.intOrNull ?: -1) >= 0) }
        json["ends"]?.let { require(it is JsonPrimitive && !it.isString && (it.longOrNull ?: 0L) > 0L) }
        if (ends != null) require(invitation?.invitation?.persistent == true) { "Only a group room can end." }
        json["destruct"]?.let { require(it is JsonPrimitive && !it.isString && it.booleanOrNull == true) }
        if (destruct) require(invitation?.invitation?.persistent == true) { "Only a group room can self-destruct." }
        json["startsAt"]?.let { require(it is JsonPrimitive && !it.isString && (it.longOrNull ?: -1L) >= 0L) }
        json["destructHeadsUp"]?.let { require(it is JsonPrimitive && !it.isString && it.booleanOrNull == true) }
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
                require("participantKey" !in identityJson)
                require(identityJson.text("participant").matches(Regex("[0-9a-f]{64}")))
                // A kept credential is this account's, for this device in this room.
                identityJson["credential"]?.let {
                    val credential = NostrEvent.fromJson(it)
                    val check = verifyDeviceCredential(credential, id, credential.createdAt)
                    require(check is CredentialCheck.Valid && check.participant == participant && check.device == devicePubkey)
                }
            }
            else -> error("Unknown saved identity")
        }
        if (anonymous) require(!secondary && !viaAccount) { "Anonymous rooms need a local primary identity." }
        nativeAuthority?.let { binding ->
            require("host" !in json && !secondary && !anonymous && policy?.quiet != true)
            require(binding.room == id && binding.authority == authority && binding.participant == participant && binding.device == devicePubkey)
            require(invitation?.invitation?.let { it.persistent && it.canonicalInviter == binding.authority } == true)
            require(json.getValue("nativeAuthority").jsonObject.text("invitation") == deriveInvitationId(requireNotNull(invitation).invitation))
            require(binding.route == selectedRoute) { "Native authority route needs an explicit journal policy transition" }
            require(binding.relays == if (selectedRoute.internet) relays.map(::canonicalRelayUrl).sorted() else emptyList<String>()) {
                "Native authority relays need an explicit journal policy transition"
            }
        }
        storedHost()
        require(retirements.size <= 128)
        require(retirements.all { it.kind == KIND_INVITATION_RETIREMENT && Events.verify(it) })
        roomRelayRecord?.let { require(it.relays.isNotEmpty() && it.relays.size <= MAX_ROOM_RELAYS && it.version >= 0 && it.sig.matches(Regex("[0-9a-f]{128}"))) }
        json["fixedRelays"]?.let { require(invitationRelaysOf(it) != null) { "The room's relays are not a valid list." } }
        json["sharedName"]?.let {
            val shared = requireNotNull(sharedName)
            require(DisplayName.sanitise(shared.name) == shared.name && shared.id.matches(Regex("[0-9a-f]{32}")) && shared.at > 0) { "The room's shared name is not valid." }
        }
        json["fixedRelaysSigned"]?.let { require(it is JsonPrimitive && it.booleanOrNull == true && roomRelays.isNotEmpty()) }
    }

    companion object {
        fun create(secret: ByteArray, identity: RoomIdentity, joinUrl: String, relays: List<String>,
                   name: String, now: Long, host: RoomInvitationHost?, authority: String?, anonymous: Boolean = false,
                   ends: Long? = null, roomRelays: List<String> = emptyList(), roomRelaysSigned: Boolean = false,
                   destruct: Boolean = false, route: RoomRoute = RoomRoute.INTERNET): SavedRoom {
            val id = deriveRoom(secret).roomId
            return SavedRoom(buildJsonObject {
                put("id", id)
                put("secret", secret.toHex())
                put("joinUrl", joinUrl)
                put("relays", JsonArray(relays.map(::JsonPrimitive)))
                put("name", cleanName(name, id))
                put("openedAt", now)
                if (route != RoomRoute.INTERNET) put("route", route.stored)
                if (anonymous) put("anonymous", true)
                ends?.let { put("ends", it); put("startsAt", now) }
                if (destruct) put("destruct", true)
                if (roomRelays.isNotEmpty()) {
                    put("fixedRelays", JsonArray(roomRelays.map(::JsonPrimitive)))
                    if (roomRelaysSigned) put("fixedRelaysSigned", true)
                }
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
                                put("credential", identity.credential.toJson())
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

/** Everything a chat event needs signed: never the participant key. Contains a secret. */
class HeadlessSigning(val participant: String, val credential: NostrEvent, val deviceSecretKey: ByteArray) {
    /** When [credential] stops authorising this device; zero if it does not say. */
    val credentialExpiresAt: Long get() = credential.tagValue("expiration")?.toLongOrNull() ?: 0L
    override fun toString(): String = "HeadlessSigning(redacted)"
}

private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
private fun String.keyBytes(): ByteArray = hexToBytes().also { require(it.size == 32) }
private fun MutableMap<String, JsonElement>.put(key: String, value: String) { this[key] = JsonPrimitive(value) }
private fun MutableMap<String, JsonElement>.put(key: String, value: Boolean) { this[key] = JsonPrimitive(value) }
private fun MutableMap<String, JsonElement>.put(key: String, value: Long) { this[key] = JsonPrimitive(value) }
