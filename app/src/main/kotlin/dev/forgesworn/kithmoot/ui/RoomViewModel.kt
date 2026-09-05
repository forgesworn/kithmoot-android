package dev.forgesworn.kithmoot.ui

import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import dev.forgesworn.kithmoot.ui.room.PublicProfile
import dev.forgesworn.kithmoot.ui.room.decodePublicProfile

import android.app.Application
import android.content.Intent
import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.storage.RoomRecoveryException
import dev.forgesworn.kithmoot.storage.RoomStorageException
import dev.forgesworn.kithmoot.storage.SavedRoom
import dev.forgesworn.kithmoot.storage.SavedRoomSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.media.LocalTrack
import dev.forgesworn.kithmoot.media.WebRtcEngine
import dev.forgesworn.kithmoot.protocol.JoinUrlException
import dev.forgesworn.kithmoot.protocol.InvitationPayload
import dev.forgesworn.kithmoot.protocol.KindredTier
import dev.forgesworn.kithmoot.protocol.KIND_INVITATION_GRANT
import dev.forgesworn.kithmoot.protocol.KIND_INVITATION_REQUEST
import dev.forgesworn.kithmoot.protocol.KIND_INVITATION_RETIREMENT
import dev.forgesworn.kithmoot.protocol.RoomAdmission
import dev.forgesworn.kithmoot.protocol.Room
import dev.forgesworn.kithmoot.protocol.RoomInvitationHost
import dev.forgesworn.kithmoot.protocol.createRoomInvitation
import dev.forgesworn.kithmoot.protocol.decodeRoomAdmissionGrant
import dev.forgesworn.kithmoot.protocol.decodeInvitationRequest
import dev.forgesworn.kithmoot.protocol.decodeInvitationRetirement
import dev.forgesworn.kithmoot.protocol.decodeInvitationUrl
import dev.forgesworn.kithmoot.protocol.decodeJoinUrl
import dev.forgesworn.kithmoot.protocol.deriveRoom
import dev.forgesworn.kithmoot.protocol.deriveInvitationId
import dev.forgesworn.kithmoot.protocol.encodeInvitationGrant
import dev.forgesworn.kithmoot.protocol.encodeInvitationRequest
import dev.forgesworn.kithmoot.protocol.encodeInvitationRetirement
import dev.forgesworn.kithmoot.protocol.encodeInvitationUrl
import dev.forgesworn.kithmoot.protocol.encodeJoinUrl
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.OkHttpRelaySockets
import dev.forgesworn.kithmoot.relay.RelayPool
import dev.forgesworn.kithmoot.service.ScreenShareService
import dev.forgesworn.kithmoot.session.ChatMessage
import dev.forgesworn.kithmoot.session.KITHMOOT_JOIN_BASE
import dev.forgesworn.kithmoot.session.PrimaryIdentity
import dev.forgesworn.kithmoot.session.RoomIdentity
import dev.forgesworn.kithmoot.session.RoomSession
import dev.forgesworn.kithmoot.session.mediaAudience
import dev.forgesworn.kithmoot.session.Roles
import dev.forgesworn.kithmoot.session.SecondaryIdentity
import dev.forgesworn.kithmoot.session.decodeInvitationPairingLink
import dev.forgesworn.kithmoot.session.decodePairingLink
import dev.forgesworn.kithmoot.session.encodeInvitationPairingLink
import dev.forgesworn.kithmoot.session.encodePairingLink
import dev.forgesworn.kithmoot.ui.room.ParticipantTile
import dev.forgesworn.kithmoot.ui.room.buildTiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.EglBase
import org.webrtc.PeerConnection
import org.webrtc.VideoTrack

/** Which screen the app is on. Two screens; a navigation library would be scaffolding. */
enum class Stage { START, ROOM }

data class StartState(
    val joinUrl: String = "",
    val relays: String = DEFAULT_RELAYS.joinToString("\n"),
    val busy: Boolean = false,
    val error: String? = null,
    val roomName: String = "",
    val loadingRooms: Boolean = true,
    val storageError: Boolean = false,
    val savedRooms: List<SavedRoomSummary> = emptyList(),
)

data class RoomState(
    val roomId: String = "",
    val name: String = "",
    val joinUrl: String = "",
    val relaysUp: Int = 0,
    val relaysTotal: Int = 0,
    val tiles: List<ParticipantTile> = emptyList(),
    val chat: List<ChatMessage> = emptyList(),
    val profilesEnabled: Boolean = false,
    val profiles: Map<String, PublicProfile> = emptyMap(),
    val selfParticipant: String = "",
    val selfDevice: String = "",
    val micOn: Boolean = false,
    val cameraOn: Boolean = false,
    val screenOn: Boolean = false,
    /** True when this device took up a pairing link rather than opening the room. */
    val secondary: Boolean = false,
    val canAddDevice: Boolean = false,
    val canRotateInvitation: Boolean = false,
    val pairingLink: String? = null,
    /**
     * Whether anything in this room that says it is an agent is sent this
     * device's camera and microphone.
     *
     * Off by default, and it is a switch on the SENDER: off means the tracks
     * are never handed to the connection to an agent, so the media does not
     * leave this device for them. A request not to listen would be a request;
     * not sending is a fact.
     */
    val agentsMayHear: Boolean = false,
    /** How many members of this room say they are agents. The switch is
     *  hidden when there are none, because it would mean nothing. */
    val agentCount: Int = 0,
    /**
     * Set when the room has moved past this client: somebody was removed, the
     * room is published under a key this client cannot follow, and it will
     * hear nothing further. Said out loud rather than left as silence.
     */
    val movedOn: Int? = null,
    /** Set when the media stack could not be brought up. The room still works without it. */
    val mediaFault: String? = null,
    val notice: String? = null,
) {
    val self: ParticipantTile? get() = tiles.firstOrNull { it.isSelf }
    val deviceCount: Int get() = self?.deviceCount ?: 1
}

/** Relays used when a room is opened here, or when a join URL names none. */
val DEFAULT_RELAYS: List<String> = listOf("wss://relay.damus.io", "wss://nos.lol")

/** How long a device credential is good for. A day outlives any meeting. */
private const val CREDENTIAL_TTL_SECONDS = 24L * 60 * 60
private const val INVITATION_TIMEOUT_MS = 60_000L
private const val INVITATION_RETRY_MS = 2_000L
private class RetiredInvitationException : Exception()

/**
 * Everything the two screens need, and the only thing that owns a session.
 *
 * The screens are deliberately inert - they read state and call methods here.
 * That keeps the join, leave and re-join paths in one place, which matters
 * because the interesting failure in this application is a half-torn-down room:
 * a relay pool still publishing after the user has left, or a second session
 * opened over the top of a live one.
 */
class RoomViewModel(application: Application) : AndroidViewModel(application) {

    private val _stage = MutableStateFlow(Stage.START)
    val stage: StateFlow<Stage> = _stage.asStateFlow()

    private val _start = MutableStateFlow(StartState())
    val start: StateFlow<StartState> = _start.asStateFlow()

    private val _room = MutableStateFlow(RoomState())
    val room: StateFlow<RoomState> = _room.asStateFlow()

    /**
     * Every renderable video track, keyed `device|trackId`.
     *
     * Kept apart from [RoomState] on purpose. Tracks arrive and vanish on
     * WebRTC's own threads at a rate that has nothing to do with the roster, and
     * folding them into the room state would rebuild every tile each time a
     * keyframe-worth of plumbing changed.
     */
    private val _videos = MutableStateFlow<Map<String, VideoTrack>>(emptyMap())
    val videos: StateFlow<Map<String, VideoTrack>> = _videos.asStateFlow()

    private var sessionScope: CoroutineScope? = null
    private var pool: RelayPool? = null
    private var session: RoomSession? = null
    private var engine: WebRtcEngine? = null
    private var identity: RoomIdentity? = null
    private var roomSecret: ByteArray? = null
    private var roomInvitation: InvitationPayload? = null
    private var roomInvitationHost: RoomInvitationHost? = null
    private var invitationHostJob: Job? = null
    private var relayUrls: List<String> = emptyList()
    private var opening: Job? = null

    /**
     * Serialises opening and closing a room.
     *
     * Leaving and joining are both several steps long and both tear down the
     * same fields. Without this, a quick leave-then-join interleaves the two and
     * the new session's relay pool is stopped by the old session's teardown.
     */
    private val gate = Mutex()
    private val entering = AtomicBoolean(false)
    private val savedRooms = (application as KithMootApplication).savedRooms
    private var savedRoom: SavedRoom? = null

    init { refreshSavedRooms() }

    /** The GL context the renderers share. Null until the media stack is up. */
    val eglBase: EglBase? get() = engine?.eglBase

    // --- start screen --------------------------------------------------------

    fun onRoomNameChanged(value: String) {
        _start.update { it.copy(roomName = value.take(80)) }
    }

    fun refreshSavedRooms() {
        _start.update { it.copy(loadingRooms = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val rooms = savedRooms.list()
                _start.update { it.copy(savedRooms = rooms, loadingRooms = false, storageError = false, error = null) }
            } catch (_: RoomStorageException) { storageFailed() }
        }
    }

    private fun storageFailed() {
        _start.update { it.copy(loadingRooms = false, storageError = true, savedRooms = emptyList(),
            error = "Saved rooms could not be unlocked or saved. Try again. Your saved data has been kept.") }
    }

    fun forgetRoom(id: String) = changeSavedRooms { savedRooms.forget(id) }
    fun renameRoom(id: String, name: String) = changeSavedRooms { savedRooms.update(id) { it.renamed(name) } }
    fun resetSavedRooms() = changeSavedRooms { savedRooms.reset() }

    private fun changeSavedRooms(change: () -> Unit) {
        if (_stage.value != Stage.START || !entering.compareAndSet(false, true)) return
        _start.update { it.copy(busy = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                change()
                val rooms = savedRooms.list()
                _start.update { it.copy(savedRooms = rooms, storageError = false, error = null) }
            } catch (_: RoomStorageException) { storageFailed() }
            finally { entering.set(false); _start.update { it.copy(busy = false) } }
        }
    }

    fun reopenRoom(id: String) = enter {
        openSaved(savedRooms.get(id) ?: throw RoomRecoveryException("This room is no longer saved on this device."))
    }

    private suspend fun openSaved(saved: SavedRoom) {
        val who = saved.identity(epochSeconds())
        open(deriveRoom(saved.secret), saved.secret, saved.relays, who, saved.secondary,
            saved.joinUrl, saved.invitation, saved.host(epochSeconds()), saved.policy, saved)
    }

    private fun primaryFor(roomId: String, now: Long): RoomIdentity = savedRooms.get(roomId)?.identity(now)
        ?: PrimaryIdentity.create(roomId, now + CREDENTIAL_TTL_SECONDS, now)

    /** Storage and network failures stay on the entry screen; parallel taps cannot open two sessions. */
    private fun enter(block: suspend () -> Unit) {
        if (_stage.value != Stage.START) {
            note("Leave this room before opening another. The invitation will be waiting on the home screen.")
            return
        }
        if (!entering.compareAndSet(false, true)) return
        _start.update { it.copy(busy = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                start.first { !it.loadingRooms }
                if (!_start.value.storageError) block()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                gate.withLock { closeSession(); _room.value = RoomState(); _stage.value = Stage.START }
                when (e) {
                    is RoomStorageException -> storageFailed()
                    is RoomRecoveryException -> _start.update { it.copy(error = e.message) }
                    else -> _start.update { it.copy(error = "The room could not be opened. Try again.") }
                }
            } finally { entering.set(false); _start.update { it.copy(busy = false) } }
        }
    }

    fun onJoinUrlChanged(value: String) {
        _start.value = _start.value.copy(joinUrl = value, error = null)
    }

    fun onRelaysChanged(value: String) {
        _start.value = _start.value.copy(relays = value, error = null)
    }

    /**
     * Opens a room.
     *
     * All of it runs off the main thread. Making a participant key, minting a
     * credential and announcing are a key generation, two signatures and a
     * NIP-44 encryption, and the first of them loads libsecp256k1 - which on a
     * cold, busy device is comfortably long enough for the platform to call the
     * application unresponsive.
     */
    fun startRoom() {
        val relays = parseRelays(_start.value.relays)
        if (relays.isEmpty()) {
            _start.value = _start.value.copy(error = "Name at least one relay.")
            return
        }
        val name = _start.value.roomName
        enter {
            val secret = Entropy.bytes(32)
            val invitationHost = createRoomInvitation()
            val invitation = InvitationPayload(invitationHost.invitation, relays, null)
            val derived = deriveRoom(secret)
            val at = epochSeconds()
            val primary = PrimaryIdentity.create(
                roomId = derived.roomId,
                expiresAt = at + CREDENTIAL_TTL_SECONDS,
                createdAt = at,
            )
            open(
                derived = derived,
                secret = secret,
                relays = relays,
                who = primary,
                secondary = false,
                joinUrl = encodeInvitationUrl(KITHMOOT_JOIN_BASE, invitation.invitation, relays),
                invitation = invitation,
                invitationHost = invitationHost,
                localName = name,
            )
        }
    }

    /** Joins from a pasted or tapped link. The one entry point for both. */
    fun joinFromUrl(raw: String) {
        val url = raw.trim()
        if (url.isEmpty()) {
            _start.value = _start.value.copy(error = "Paste a join link first.")
            return
        }
        enter { join(url) }
    }

    private suspend fun join(url: String) {
        val invitation = try {
            decodeInvitationUrl(url)
        } catch (e: JoinUrlException) {
            _start.value = _start.value.copy(busy = false, error = e.message ?: "That is not a join link.")
            return
        } catch (_: Exception) {
            _start.value = _start.value.copy(busy = false, error = "That is not a join link.")
            return
        }

        if (invitation != null) {
            if (decodeInvitationPairingLink(url) == null) {
                savedRooms.findInvitation(url)?.let { openSaved(it); return }
            }
            joinInvitation(url, invitation)
            return
        }

        val payload = try {
            decodeJoinUrl(url)
        } catch (e: JoinUrlException) {
            _start.value = _start.value.copy(busy = false, error = e.message ?: "That is not a join link.")
            return
        } catch (_: Exception) {
            _start.value = _start.value.copy(busy = false, error = "That is not a join link.")
            return
        }

        val derived = deriveRoom(payload.secret)
        val relays = payload.relays.ifEmpty { parseRelays(_start.value.relays).ifEmpty { DEFAULT_RELAYS } }
        val at = epochSeconds()

        // A pairing link carries a device key and a credential, so this device
        // joins as another of that person's devices rather than as a stranger.
        val pairing = decodePairingLink(url)
        if (pairing != null) {
            val secondary = SecondaryIdentity.adopt(
                credential = pairing.credential,
                deviceSecretKey = pairing.deviceSecretKey,
                roomId = derived.roomId,
                now = at,
            )
            if (secondary == null) {
                _start.value = _start.value.copy(
                    busy = false,
                    error = "That pairing link has expired, or it was minted for a different room.",
                )
                return
            }
            open(
                derived,
                payload.secret,
                relays,
                secondary,
                secondary = true,
                joinUrl = encodeJoinUrl(KITHMOOT_JOIN_BASE, payload.secret, relays, payload.policy),
                policy = payload.policy,
            )
            return
        }

        savedRooms.get(derived.roomId)?.let { openSaved(it); return }
        val primary = primaryFor(derived.roomId, at)
        open(
            derived,
            payload.secret,
            relays,
            primary,
            secondary = primary is SecondaryIdentity,
            joinUrl = encodeJoinUrl(KITHMOOT_JOIN_BASE, payload.secret, relays, payload.policy),
            policy = payload.policy,
        )
    }

    private suspend fun joinInvitation(url: String, payload: InvitationPayload) {
        val relays = payload.relays.ifEmpty { parseRelays(_start.value.relays).ifEmpty { DEFAULT_RELAYS } }
        val admission = try {
            requestAdmission(payload, relays)
        } catch (_: RetiredInvitationException) {
            _start.value = _start.value.copy(
                busy = false,
                error = "This invitation was retired. Ask for the current room link.",
            )
            return
        }
        if (admission == null) {
            _start.value = _start.value.copy(
                busy = false,
                error = "The room is not answering this invitation. Ask for a fresh link.",
            )
            return
        }
        val secret = admission.secret

        val derived = deriveRoom(secret)
        val at = epochSeconds()
        val pairing = decodeInvitationPairingLink(url)
        if (pairing != null) {
            val secondary = SecondaryIdentity.adopt(
                credential = pairing.credential,
                deviceSecretKey = pairing.deviceSecretKey,
                roomId = derived.roomId,
                now = at,
            )
            if (secondary == null) {
                _start.value = _start.value.copy(
                    busy = false,
                    error = "That pairing link has expired, or it was minted for a different room.",
                )
                return
            }
            open(
                derived,
                secret,
                relays,
                secondary,
                secondary = true,
                joinUrl = encodeInvitationUrl(KITHMOOT_JOIN_BASE, payload.invitation, relays, payload.policy),
                invitation = payload,
                invitationHost = admission.delegate,
                policy = payload.policy,
            )
            return
        }

        val primary = primaryFor(derived.roomId, at)
        open(
            derived,
            secret,
            relays,
            primary,
            secondary = primary is SecondaryIdentity,
            joinUrl = encodeInvitationUrl(KITHMOOT_JOIN_BASE, payload.invitation, relays, payload.policy),
            invitation = payload,
            invitationHost = admission.delegate,
            policy = payload.policy,
        )
    }

    /** Exchange the bearer for a traffic secret and a bounded responder
     * delegation, without an account or prompt. */
    private suspend fun requestAdmission(payload: InvitationPayload, relays: List<String>): RoomAdmission? {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val transport = RelayPool(relays, OkHttpRelaySockets(), scope)
        val requesterKey = Entropy.bytes(32)
        val request = encodeInvitationRequest(payload.invitation, requesterKey, epochSeconds())
        val invitationId = deriveInvitationId(payload.invitation)
        transport.start()
        return try {
            withTimeoutOrNull(INVITATION_TIMEOUT_MS) {
                coroutineScope {
                    // Start collecting before the first publish. Invitation
                    // events are ephemeral, so subscribing one line later is
                    // enough to miss a fast response for good.
                    val response = async(start = CoroutineStart.UNDISPATCHED) {
                        transport.subscribe(
                            listOf(
                                Filter(
                                    kinds = listOf(KIND_INVITATION_GRANT),
                                    tags = mapOf(
                                        "#d" to listOf(invitationId),
                                        "#p" to listOf(Schnorr.publicKeyHex(requesterKey)),
                                    ),
                                ),
                                Filter(
                                    authors = listOf(payload.invitation.canonicalInviter),
                                    kinds = listOf(KIND_INVITATION_RETIREMENT),
                                    tags = mapOf("#d" to listOf(invitationId)),
                                ),
                            ),
                        ).mapNotNull { event ->
                            if (decodeInvitationRetirement(event, payload.invitation)) {
                                throw RetiredInvitationException()
                            }
                            decodeRoomAdmissionGrant(
                                event,
                                payload.invitation,
                                requesterKey,
                                request.id,
                                epochSeconds(),
                            )
                        }.first()
                    }
                    val retry = launch {
                        while (isActive) {
                            transport.publish(request)
                            delay(INVITATION_RETRY_MS)
                        }
                    }
                    try {
                        response.await()
                    } finally {
                        retry.cancel()
                    }
                }
            }
        } finally {
            transport.stop()
            scope.cancel()
        }
    }

    /** Auto-admit holders of the current link while any admitted member is
     * online, and stop permanently on the creator's durable tombstone. */
    private fun serveInvitation(
        scope: CoroutineScope,
        transport: RelayPool,
        host: RoomInvitationHost,
        secret: ByteArray,
    ): Job {
        val invitationId = deriveInvitationId(host.invitation)
        val responder = Schnorr.publicKeyHex(host.inviterSecretKey)
        return scope.launch {
            val answered = LinkedHashSet<String>()
            var retired = false
            transport.subscribe(
                listOf(
                    Filter(
                        kinds = listOf(KIND_INVITATION_REQUEST),
                        tags = mapOf(
                            "#d" to listOf(invitationId),
                            "#p" to listOf(host.invitation.canonicalInviter),
                        ),
                    ),
                    Filter(
                        authors = listOf(host.invitation.canonicalInviter),
                        kinds = listOf(KIND_INVITATION_RETIREMENT),
                        tags = mapOf("#d" to listOf(invitationId)),
                    ),
                ),
            ).collect { event ->
                if (event.kind == KIND_INVITATION_RETIREMENT) {
                    if (!decodeInvitationRetirement(event, host.invitation)) return@collect
                    retired = true
                    gate.withLock {
                        if (roomInvitation?.invitation == host.invitation) {
                            roomInvitationHost = null
                            savedRoom?.let { persistLiveRoom(it.id) { saved -> saved.invitationRetired() } }
                            _room.value = _room.value.copy(
                                canRotateInvitation = false,
                                notice = "This invitation was retired by its creator. The live room is unchanged.",
                            )
                        }
                    }
                    return@collect
                }
                if (retired || dev.forgesworn.kithmoot.protocol.verifyInvitationDelegation(host.invitation, host.delegation, epochSeconds()) == null) return@collect
                val request = decodeInvitationRequest(event, host.invitation, epochSeconds()) ?: return@collect
                // Lenient relays sometimes retain and replay ephemeral
                // requests. A newly admitted delegate must not answer the
                // request that admitted itself.
                if (request.device == responder) return@collect
                if (!answered.add(request.requestId)) return@collect
                while (answered.size > 256) answered.remove(answered.first())
                transport.publish(
                    encodeInvitationGrant(
                        host,
                        request.device,
                        request.requestId,
                        secret,
                        epochSeconds(),
                    ),
                )
            }
        }
    }

    // --- session lifecycle ---------------------------------------------------

    private suspend fun open(
        derived: Room,
        secret: ByteArray,
        relays: List<String>,
        who: RoomIdentity,
        secondary: Boolean,
        joinUrl: String,
        invitation: InvitationPayload? = null,
        invitationHost: RoomInvitationHost? = null,
        policy: dev.forgesworn.kithmoot.protocol.RoomPolicy? = null,
        restoring: SavedRoom? = null,
        localName: String = "",
    ) = gate.withLock {
        if (policy != null && policy.tier != KindredTier.OPEN) {
            _start.value = _start.value.copy(
                busy = false,
                error = "This room requires a Kindred proof. This Android build cannot obtain one yet.",
            )
            return@withLock
        }
        val previous = savedRooms.get(derived.roomId)
        if (previous != null && (previous.participant != who.participant || (!previous.secondary && secondary))) {
            throw RoomRecoveryException("This room is saved with a different identity. Forget the saved room first if you want to replace it.")
        }
        val record = (restoring ?: SavedRoom.create(secret, who, joinUrl, relays,
            previous?.name ?: localName, epochSeconds(), invitationHost,
            previous?.authority ?: invitation?.invitation?.canonicalInviter)
            .let { if (previous != null) it.retainingHistory(previous) else it }).opened(epochSeconds())
        savedRooms.save(record)
        val summaries = savedRooms.list()
        _start.update { it.copy(savedRooms = summaries) }
        closeSession()
        savedRoom = record
        val scope = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job]))
        val transport = RelayPool(relays, OkHttpRelaySockets(), scope)
        val live = RoomSession(
            derived,
            who,
            transport,
            scope,
            policy = policy,
            // The root inviter, and the only key whose rekey this client
            // believes. A legacy link carries none, and a room opened from
            // one goes quiet the old way if it ever moves on.
            authority = record.authority,
        )

        sessionScope = scope
        pool = transport
        session = live
        identity = who
        roomSecret = secret
        roomInvitation = invitation
        roomInvitationHost = record.host(epochSeconds())
        relayUrls = relays

        _room.value = RoomState(
            roomId = derived.roomId,
            name = record.name,
            joinUrl = joinUrl,
            relaysTotal = relays.size,
            selfParticipant = who.participant,
            selfDevice = who.devicePubkey,
            secondary = secondary,
            canAddDevice = who is PrimaryIdentity,
            canRotateInvitation = record.host(epochSeconds())?.delegation?.isEmpty() == true,
        )
        _start.value = _start.value.copy(error = null, busy = false)
        _stage.value = Stage.ROOM

        scope.launch {
            _room.map { state -> if (state.profilesEnabled) (state.tiles.map { it.participant } + state.chat.map { it.participant }).distinct().sorted().take(500) else emptyList() }
                .distinctUntilChanged().collectLatest { authors ->
                    if (authors.isEmpty()) return@collectLatest
                    val requested = authors.toSet()
                    kotlinx.coroutines.withTimeoutOrNull(10_000) {
                        transport.subscribe(listOf(Filter(kinds = listOf(0), authors = authors, limit = authors.size))).collect { event ->
                            val profile = decodePublicProfile(event, requested, epochSeconds()) ?: return@collect
                            _room.update { state ->
                                if (!state.profilesEnabled || state.roomId != derived.roomId) state else {
                                    val old = state.profiles[event.pubkey]
                                    if (old != null && (old.createdAt > profile.createdAt || (old.createdAt == profile.createdAt && old.eventId >= profile.eventId))) state
                                    else state.copy(profiles = state.profiles + (event.pubkey to profile))
                                }
                            }
                        }
                    }
                }
        }
        transport.start()
        record.host(epochSeconds())?.let { host ->
            invitationHostJob = serveInvitation(scope, transport, host, secret)
        }
        live.join()
        // This device plays the room's audio unless one of your others takes it
        // over. Claiming rather than assuming is what lets that handover happen.
        live.claim(Roles.MONITOR)

        scope.launch {
            combine(live.participants, live.chat) { people, chat -> people to chat }
                .collect { (people, chat) ->
                    _room.value = _room.value.copy(
                        tiles = buildTiles(people, who.participant, who.devicePubkey),
                        chat = chat,
                    )
                }
        }
        scope.launch {
            transport.connected.collect { up ->
                gate.withLock {
                    if (session !== live) return@withLock
                    _room.update { it.copy(relaysUp = up.size) }
                    // The transport's offline queue is bounded and expires. Replay
                    // durable retirements on reconnect, including rotations made
                    // during this session, so a long outage cannot drop them.
                    if (up.isNotEmpty()) savedRoom?.retirements?.forEach(transport::publish)
                }
            }
        }
        scope.launch {
            live.movedOn.collect { epoch ->
                gate.withLock {
                    if (session !== live) return@withLock
                    _room.value = _room.value.copy(movedOn = epoch)
                    if (epoch != null) {
                        invitationHostJob?.cancel()
                        roomInvitationHost = null
                        _room.value = _room.value.copy(canRotateInvitation = false)
                        persistLiveRoom(record.id) { it.keysChanged() }
                    }
                }
            }
        }
        scope.launch {
            live.localRoles.collect { roles ->
                // Another of your devices has taken the microphone. Let go of
                // the hardware rather than sitting on a hot mic: the roster
                // already stops anyone hearing this one, but a person looking at
                // a lit microphone button believes they are being heard.
                if (_room.value.micOn && !roles.holdsMic && roles.micDevice != null) {
                    engine?.localMedia?.stopMicrophone()
                }
            }
        }

        // The media stack is brought up off the main thread and is allowed to
        // fail. WebRTC needs native libraries that some devices and most
        // emulators do not have; a room that is text and presence only is worth
        // far more than a crash on the way in.
        opening = scope.launch {
            val built = withContext(Dispatchers.Default) {
                runCatching { WebRtcEngine(getApplication(), live, scope, iceServers()) }
            }
            val media = built.getOrElse { failure ->
                _room.value = _room.value.copy(
                    mediaFault = "Audio and video are unavailable on this device: " +
                        (failure.message ?: failure::class.java.simpleName),
                )
                return@launch
            }
            engine = media
            media.localMedia.onScreenShareStopped = { stopScreenShare() }
            media.localMedia.onCameraLost = { cameraLost() }
            media.start()

            scope.launch {
                combine(media.remoteTracks, media.localMedia.tracks) { remote, local ->
                    buildMap {
                        for (track in remote) {
                            (track.track as? VideoTrack)?.let { put(key(track.device, track.trackId), it) }
                        }
                        for (track in local) {
                            (track.track as? VideoTrack)?.let { put(key(who.devicePubkey, track.trackId), it) }
                        }
                    }
                }.collect { _videos.value = it }
            }
            scope.launch {
                media.localMedia.tracks.collect { tracks -> onLocalTracks(tracks) }
            }
            // Who this device's media may go to, re-decided whenever the
            // switch moves or an agent arrives.
            scope.launch {
                live.agentDevices.collect { agents ->
                    _room.value = _room.value.copy(agentCount = agents.size)
                    applyAudience(media, agents)
                }
            }
        }
    }

    /**
     * Turn "agents can hear me" on or off, and act on it now.
     *
     * The rule is applied to every connection this device holds, so an agent
     * that was receiving stops receiving at once rather than at the next
     * renegotiation - and one that arrives later is judged by the same rule
     * when its connection is opened.
     */
    fun setAgentsMayHear(on: Boolean) {
        _room.value = _room.value.copy(agentsMayHear = on)
        val media = engine ?: return
        applyAudience(media, session?.agentDevices?.value ?: emptySet())
    }

    private fun applyAudience(media: WebRtcEngine, agents: Set<String>) {
        media.setAudience(mediaAudience(agents, _room.value.agentsMayHear))
    }

    fun leave() {
        if (!entering.compareAndSet(false, true)) return
        val live = session
        // The screen changes at once; the last announce and the teardown are a
        // signature and a pile of socket closes, and nobody should watch them.
        _videos.value = emptyMap()
        _room.value = RoomState()
        _stage.value = Stage.START
        _start.update { it.copy(busy = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                gate.withLock { try { live?.leave() } finally { closeSession() } }
            } finally {
                entering.set(false)
                _start.update { it.copy(busy = false) }
                refreshSavedRooms()
            }
        }
    }

    private fun closeSession() {
        dev.forgesworn.kithmoot.ui.room.forgetProfilePictures()
        opening?.cancel()
        opening = null
        ScreenShareService.stop(getApplication())
        engine?.stop()
        engine?.dispose()
        engine = null
        pool?.stop()
        pool = null
        session = null
        identity = null
        savedRoom = null
        roomSecret = null
        roomInvitation = null
        roomInvitationHost = null
        invitationHostJob?.cancel()
        invitationHostJob = null
        sessionScope?.coroutineContext?.get(Job)?.cancel()
        sessionScope = null
    }

    override fun onCleared() {
        super.onCleared()
        closeSession()
    }

    /** Runs a control off the main thread. Every one of them ends in a signature. */
    private fun act(block: () -> Unit) {
        viewModelScope.launch(Dispatchers.Default) { block() }
    }

    // --- controls ------------------------------------------------------------

    fun toggleMicrophone() = act {
        val media = engine?.localMedia ?: return@act note("No microphone on this device.")
        val live = session ?: return@act
        if (_room.value.micOn) {
            media.stopMicrophone()
            live.release(Roles.MIC)
        } else {
            // The claim goes first, and not for tidiness: the moment a track
            // appears the roster is republished, and a device that published a
            // microphone it had not yet claimed would see one of its own others
            // still holding the role and shut itself straight back off.
            live.claim(Roles.MIC)
            if (media.startMicrophone() == null) {
                live.release(Roles.MIC)
                return@act note("The microphone would not start.")
            }
        }
    }

    fun toggleCamera() = act {
        val media = engine?.localMedia ?: return@act note("No camera on this device.")
        if (_room.value.cameraOn) {
            media.stopCamera()
        } else if (media.startCamera() == null) {
            note("No camera is available here.")
        }
    }

    fun switchCamera() {
        engine?.localMedia?.switchCamera()
    }

    /**
     * The camera went away without being asked.
     *
     * The capturer is already dead by the time this arrives; what is left is to
     * let go of it, so the roster stops advertising a camera track that carries
     * nothing and the control stops claiming to be on. Handed to [act] rather
     * than run here, because it arrives on the capturer's own thread and
     * releasing the capturer from there would wait on that same thread.
     */
    private fun cameraLost() = act {
        if (!_room.value.cameraOn) return@act
        engine?.localMedia?.stopCamera()
        note("Android took the camera away. Tap Camera to start it again.")
    }

    /**
     * Starts sharing the screen from the consent the user has just given.
     *
     * The foreground service goes up first and we wait for it to actually be
     * foreground. On Android 14 and later the platform refuses to create a
     * projection at all unless a `mediaProjection` service is already running,
     * and the failure is a `SecurityException` rather than a null.
     */
    fun startScreenShare(permission: Intent) {
        val media = engine?.localMedia ?: return note("Screen sharing needs the media stack.")
        val scope = sessionScope ?: return
        scope.launch {
            ScreenShareService.start(getApplication())
            val running = withTimeoutOrNull(5_000) { ScreenShareService.running.first { it } }
            if (running != true) {
                ScreenShareService.stop(getApplication())
                return@launch note("Android would not start the screen-sharing notification.")
            }
            val started = withContext(Dispatchers.Default) { runCatching { media.startScreenShare(permission) } }
            if (started.getOrNull() == null) {
                ScreenShareService.stop(getApplication())
                note("Screen sharing did not start: " + (started.exceptionOrNull()?.message ?: "the capture was refused"))
            }
        }
    }

    fun stopScreenShare() = act {
        engine?.localMedia?.stopScreenShare()
        ScreenShareService.stop(getApplication())
    }

    fun screenShareDeclined() {
        note("Screen sharing needs Android's permission. Nothing was shared.")
    }

    fun setProfilesEnabled(enabled: Boolean) {
        if (!enabled) dev.forgesworn.kithmoot.ui.room.forgetProfilePictures()
        _room.update { it.copy(profilesEnabled = enabled, profiles = if (enabled) it.profiles else emptyMap()) }
    }

    fun sendChat(body: String) = act { session?.sendChat(body) }

    fun react(message: ChatMessage, emoji: String) = act {
        val live = session ?: return@act
        runCatching {
            val reaction = dev.forgesworn.kithmoot.session.toggleReaction(_room.value.chat, message, _room.value.selfParticipant, emoji)
            live.sendChat(dev.forgesworn.kithmoot.session.reactionText(reaction), reaction)
        }.onFailure { note("The reaction could not be sent. Try again.") }
    }

    // --- adding a device -----------------------------------------------------

    /**
     * Mints a link that makes another device this same person.
     *
     * Only the device holding the participant key can do this, because doing it
     * means signing a credential. A device that joined from a pairing link
     * cannot pass the identity on, which is the point of not giving it the key.
     */
    fun mintPairingLink() = act {
        val primary = identity as? PrimaryIdentity
            ?: return@act note("Only the device that opened the room can add another device.")
        val secret = roomSecret ?: return@act
        val live = session ?: return@act
        val at = epochSeconds()
        val deviceKey = Entropy.bytes(32)
        val credential = primary.enrol(
            devicePubkey = Schnorr.publicKeyHex(deviceKey),
            roomId = live.room.roomId,
            expiresAt = at + CREDENTIAL_TTL_SECONDS,
            createdAt = at,
        )
        _room.value = _room.value.copy(
            pairingLink = roomInvitation?.let { invitation ->
                encodeInvitationPairingLink(
                    invitation = invitation.invitation,
                    relays = relayUrls,
                    policy = invitation.policy,
                    deviceSecretKey = deviceKey,
                    credential = credential,
                )
            } ?: encodePairingLink(
                    secret = secret,
                    relays = relayUrls,
                    deviceSecretKey = deviceKey,
                    credential = credential,
                ),
        )
    }

    fun dismissPairingLink() {
        _room.value = _room.value.copy(pairingLink = null)
    }

    /** Replace the public admission capability without moving the live room. */
    fun rotateInvitation() {
        viewModelScope.launch(Dispatchers.IO) {
            gate.withLock {
                val oldHost = roomInvitationHost
                if (oldHost == null || oldHost.delegation.isNotEmpty()) return@withLock note("Only the device that opened this room can rotate its link.")
                val saved = savedRoom ?: return@withLock
                val secret = roomSecret ?: return@withLock
                val scope = sessionScope ?: return@withLock
                val transport = pool ?: return@withLock
                val nextHost = createRoomInvitation()
                val nextInvitation = InvitationPayload(nextHost.invitation, relayUrls, saved.policy)
                val url = encodeInvitationUrl(KITHMOOT_JOIN_BASE, nextHost.invitation, relayUrls, saved.policy)
                val retirement = encodeInvitationRetirement(oldHost.invitation, oldHost.inviterSecretKey, epochSeconds())
                val next = try {
                    saved.rotated(nextHost, url, retirement).also(savedRooms::save)
                } catch (_: Exception) { return@withLock note("The new invitation could not be saved. The current link is unchanged.") }
                savedRoom = next
                transport.publish(retirement)
                invitationHostJob?.cancel()
                roomInvitationHost = nextHost
                roomInvitation = nextInvitation
                invitationHostJob = serveInvitation(scope, transport, nextHost, secret)
                _room.update { it.copy(joinUrl = url, notice = "A fresh link is ready. The old link's retirement will be sent when a relay connects. Existing members stay.") }
            }
        }
    }

    private suspend fun persistLiveRoom(id: String, change: (SavedRoom) -> SavedRoom) {
        withContext(Dispatchers.IO) {
            try {
                val saved = savedRooms.update(id, change)
                if (savedRoom?.id == id) savedRoom = saved
            } catch (_: RoomStorageException) {
                note("The room's changed access could not be saved. Check its current invitation before returning.")
            }
        }
    }

    /** Says something short to the person in the room. Shown once, then cleared. */
    fun showNotice(message: String) = note(message)

    fun dismissNotice() {
        _room.value = _room.value.copy(notice = null)
    }

    // --- internals -----------------------------------------------------------

    private fun onLocalTracks(tracks: List<LocalTrack>) {
        _room.value = _room.value.copy(
            micOn = tracks.any { it.role == Roles.MIC },
            cameraOn = tracks.any { it.role == Roles.CAMERA },
            screenOn = tracks.any { it.role == Roles.SCREEN },
        )
    }

    private fun note(message: String) {
        _room.value = _room.value.copy(notice = message)
    }

    private fun iceServers(): List<PeerConnection.IceServer> = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
    )
}

internal fun key(device: String, trackId: String): String = "$device|$trackId"

internal fun epochSeconds(): Long = System.currentTimeMillis() / 1000

/** Accepts a list separated by newlines, commas or spaces, and keeps only websocket URLs. */
internal fun parseRelays(text: String): List<String> = text
    .split('\n', ',', ' ', '\t')
    .map { it.trim() }
    .filter { it.startsWith("ws://") || it.startsWith("wss://") }
    .distinct()
