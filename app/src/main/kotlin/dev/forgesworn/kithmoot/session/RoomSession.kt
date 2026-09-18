package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.KIND_ROOM_REKEY
import dev.forgesworn.kithmoot.protocol.KIND_EPOCH_GRANT
import dev.forgesworn.kithmoot.protocol.KIND_EPOCH_REQUEST
import dev.forgesworn.kithmoot.protocol.Lane
import dev.forgesworn.kithmoot.protocol.laneOfRelays
import dev.forgesworn.kithmoot.protocol.KIND_ROSTER
import dev.forgesworn.kithmoot.protocol.EpochKeys
import dev.forgesworn.kithmoot.protocol.RekeyNotice
import dev.forgesworn.kithmoot.protocol.EpochGrant
import dev.forgesworn.kithmoot.protocol.RoomEpoch
import dev.forgesworn.kithmoot.protocol.decodeRekeyEvent
import dev.forgesworn.kithmoot.protocol.decodeEpochGrant
import dev.forgesworn.kithmoot.protocol.deriveEpoch
import dev.forgesworn.kithmoot.protocol.encodeEpochRequest
import dev.forgesworn.kithmoot.protocol.peekRekeyEpoch
import dev.forgesworn.kithmoot.protocol.KIND_SIGNAL_WRAP
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.KindredProof
import dev.forgesworn.kithmoot.protocol.Room
import dev.forgesworn.kithmoot.protocol.RoomPolicy
import dev.forgesworn.kithmoot.protocol.RosterEntry
import dev.forgesworn.kithmoot.protocol.ScreenAnnotation
import dev.forgesworn.kithmoot.protocol.SignalBody
import dev.forgesworn.kithmoot.protocol.SignalGuard
import dev.forgesworn.kithmoot.protocol.TrackRef
import dev.forgesworn.kithmoot.protocol.UnwrappedSignal
import dev.forgesworn.kithmoot.protocol.decodeRosterEvent
import dev.forgesworn.kithmoot.protocol.encodeRosterEvent
import dev.forgesworn.kithmoot.protocol.evaluateAccess
import dev.forgesworn.kithmoot.protocol.isValidScreenAnnotation
import dev.forgesworn.kithmoot.protocol.unwrapSignal
import dev.forgesworn.kithmoot.protocol.wrapSignal
import dev.forgesworn.kithmoot.crypto.SecureTimingRandom
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.RoomTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.TreeMap
import kotlin.random.Random

private const val CHAT_CONFIRM_TIMEOUT_MS = 75_000L
private const val DEFAULT_EPOCH_SETTLE_MS = 1_500L
private const val EPOCH_RECOVERY_TIMEOUT_MS = 30_000L

/**
 * The timings that govern presence. All of them are guesses that can be tuned;
 * none of them changes what is correct.
 */
data class SessionTiming(
    /**
     * The window a re-announce is scattered across.
     *
     * When one device joins a room of twenty, all twenty are about to answer it
     * at once. Spreading the answers over a jitter window turns a burst that a
     * relay will rate-limit into a trickle it will accept.
     */
    val announceJitterMs: Long = 750,
    /** How often a device restates that it is still here. */
    val heartbeatIntervalMs: Long = 20_000,
    /** How long a device stays in the roster after its last heartbeat. */
    val presenceTtlSeconds: Long = 75,
    val sweepIntervalMs: Long = 5_000,
    /** How many chat lines are kept in memory. */
    val chatHistory: Int = 500,
)

/** Which singular roles this device holds, and who holds them if not us. */
data class LocalRoles(
    val holdsMic: Boolean = false,
    val holdsMonitor: Boolean = false,
    val micDevice: String? = null,
    val monitorDevice: String? = null,
)

/**
 * A screen-share drawing, attributed to the participant it came from -
 * every device of theirs, and every stroke of theirs however it arrived,
 * carries the same participant key, so a colour and a name label stay one
 * person's rather than one wire event's. Mirrors the web client's
 * `RemoteAnnotation` (`src/mesh.ts`).
 */
data class RemoteAnnotation(val participant: String, val device: String, val annotation: ScreenAnnotation)

enum class EpochGateResult { COMMITTED, PENDING }

sealed interface RoomEpochState {
    data class Active(val epoch: Int, val trafficRoom: String) : RoomEpochState
    data class Updating(val epoch: Int) : RoomEpochState
    data class RecoveryNeeded(val expectedEpoch: Int, val reason: String) : RoomEpochState
    data class Removed(val epoch: Int) : RoomEpochState
    data class Closed(val epoch: Int) : RoomEpochState
}

/**
 * One device's participation in one room.
 *
 * The behaviour that matters, and the reason this is not just a subscription
 * wrapper, is **announce-and-respond**. Roster events are kind 20461, which is
 * in the ephemeral range: relays do not store them and will not replay them to
 * a new subscriber. A device that joins and only listens therefore hears
 * nothing, forever, because everyone else already announced before it arrived.
 *
 * So an arriving device announces, and every device already present answers by
 * re-announcing itself. Without the answer, whoever joins second sees an empty
 * room. With a naive answer, every answer looks like an arrival to somebody and
 * the room melts down into an announce storm. Both failure modes are guarded
 * here and both are covered by tests.
 */
class RoomSession(
    val room: Room,
    val identity: RoomIdentity,
    private val transport: RoomTransport,
    private val scope: CoroutineScope,
    private val timing: SessionTiming = SessionTiming(),
    /** Unix seconds, as the wire format uses. */
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    private val random: Random = SecureTimingRandom(),
    private val policy: RoomPolicy? = null,
    private val proof: KindredProof? = null,
    /**
     * The room's authority: the root inviter from the join link, and the only
     * key whose rekey this client believes.
     *
     * Null for a legacy link that carries no inviter, in which case a room
     * that moves on is simply a room that goes quiet - which is the
     * behaviour this exists to replace, and cannot be replaced without
     * somebody to trust. See `peekRekeyEpoch`.
     */
    private val authority: String? = null,
    initialEpoch: EpochKeys = EpochKeys(0, room.roomId, room.roomKey),
    private val epochSettleMs: Long = DEFAULT_EPOCH_SETTLE_MS,
    private val epochGate: (suspend (NostrEvent, RekeyNotice) -> EpochGateResult)? = null,
    private val onEpochApplied: suspend (RekeyNotice, EpochKeys) -> Unit = { _, _ -> },
    private val onEpochBlocked: () -> Unit = {},
    private val onEpochReady: (EpochKeys) -> Unit = {},
    /** Present only on the authority device; validates a request and returns its signed answer. */
    private val epochResponder: (suspend (NostrEvent) -> NostrEvent?)? = null,
    /**
     * A device-local, encrypted metadata catalogue for the signed-in person's
     * verified outer events. It is deliberately not a relay operation.
     */
    private val onVerifiedOwnEvent: (NostrEvent) -> Unit = {},
) {

    private val lock = Any()
    private val epochMutex = Mutex()
    private var activeEpoch = initialEpoch
    private val pendingRekeys = TreeMap<Int, NostrEvent>()
    private val roster = linkedMapOf<String, RosterEntry>()

    /**
     * Devices we have already answered.
     *
     * This one set is the whole loop guard. We answer a device the first time we
     * see it and never again, so an answer - which looks exactly like an
     * announce - cannot provoke another answer from someone who has already
     * answered us. The exchange settles after one round trip.
     */
    private val respondedTo = mutableSetOf<String>()

    /**
     * Devices that said goodbye, and when. An entry stamped at or before a
     * device's farewell is one a slower relay delivered late, and is not a
     * return; one stamped after it is a genuine rejoin, and is an arrival
     * again. Forgotten once the presence timeout has passed, by which point the
     * late entry would have lapsed anyway.
     */
    private val departed = mutableMapOf<String, Long>()

    /**
     * Deduplication and rate limiting for signalling - two of the three rules
     * §3 of the design says are reused from NIP-AC. The third, staleness, is
     * applied inside [unwrapSignal].
     */
    private val signalGuard = SignalGuard()
    private val chatSeen = mutableSetOf<String>()
    private val chatLog = mutableListOf<ChatMessage>()
    private val chatSenderTimes = linkedMapOf<String, MutableList<Long>>()

    private var responseJob: Job? = null
    private val jobs = mutableListOf<Job>()
    private val trafficJobs = mutableListOf<Job>()
    private var joined = false
    private var settled = false
    @Volatile private var publicationAllowed = false
    @Volatile private var transportBlocked = false

    private var tracks: List<TrackRef> = emptyList()
    private var claims: Map<String, Long> = emptyMap()

    private val _participants = MutableStateFlow<List<Participant>>(emptyList())

    /** The room as people, not as devices. */
    val participants: StateFlow<List<Participant>> = _participants.asStateFlow()

    private val _remoteDevices = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Connect every other device, including our own paired cameras. Audio
     * monitoring separately excludes our own participant to prevent feedback.
     */
    val remoteDevices: StateFlow<Set<String>> = _remoteDevices.asStateFlow()

    private val _movedOn = MutableStateFlow<Int?>(null)

    /** The observed successor epoch while its transition is pending or terminal. */
    val movedOn: StateFlow<Int?> = _movedOn.asStateFlow()

    private val _epochState = MutableStateFlow<RoomEpochState>(RoomEpochState.Active(initialEpoch.epoch, initialEpoch.id))
    val epochState: StateFlow<RoomEpochState> = _epochState.asStateFlow()

    fun epochKeys(): EpochKeys = synchronized(lock) { EpochKeys(activeEpoch.epoch, activeEpoch.id, activeEpoch.key) }

    suspend fun retryEpoch() = epochMutex.withLock {
        check(epochGate != null) { "This room has no pinned epoch authority" }
        drainRekeys()
    }

    private val _agentDevices = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Every remote device that says it is an automated participant.
     *
     * Self-declared - see `RosterEntry.agent` - and published here so the
     * media engine can be told who this device's person is willing to be
     * heard by. A device is in this set if ANY of its participant's devices
     * declares itself an agent: the switch is about a person, and a person
     * who brought an agent and a phone under one participant key is one
     * member either way.
     */
    val agentDevices: StateFlow<Set<String>> = _agentDevices.asStateFlow()

    private val _profileTwoDevices = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Every remote device whose roster entry says it speaks call profile 2:
     * fixed media slots, reliable signalling and pair health.
     *
     * Only the exact number 2 counts (call reliability spec section 2.1), and
     * absence means profile 1, which is every client from before this work and
     * the honest default for anything that does not say. The media engine reads
     * it per pair; a pair is profile 2 only when both ends say so.
     */
    val profileTwoDevices: StateFlow<Set<String>> = _profileTwoDevices.asStateFlow()

    private val _localRoles = MutableStateFlow(LocalRoles())
    val localRoles: StateFlow<LocalRoles> = _localRoles.asStateFlow()

    private val _chat = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chat: StateFlow<List<ChatMessage>> = _chat.asStateFlow()

    private val _signals = MutableSharedFlow<UnwrappedSignal>(replay = 0, extraBufferCapacity = 256)

    /** Negotiation traffic, already unwrapped and already checked against the roster. */
    val signals: SharedFlow<UnwrappedSignal> = _signals.asSharedFlow()

    private val _annotations = MutableSharedFlow<RemoteAnnotation>(replay = 0, extraBufferCapacity = 64)

    /**
     * Temporary screen-share drawing, already unwrapped, checked against the
     * roster and rate-limited exactly like [signals], and separately
     * validated against the documented shape - never chat history and never
     * replayed to a device that was absent. See [onSignalEvent].
     */
    val annotations: SharedFlow<RemoteAnnotation> = _annotations.asSharedFlow()

    // --- lifecycle -----------------------------------------------------------

    suspend fun join() {
        policy?.let {
            val decision = evaluateAccess(it, identity.participant, proof, now(), room.roomId)
            require(decision.admitted) { decision.reason }
        }
        synchronized(lock) {
            if (joined) return
            joined = true
        }
        if (authority != null) {
            jobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
                transport.subscribe(listOf(rekeyFilter())).collect(::onRekeyEvent)
            }
            if (epochResponder != null) {
                jobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    transport.subscribe(listOf(epochRequestFilter())).collect { request ->
                        epochResponder.invoke(request)?.let(transport::publishRecovery)
                    }
                }
            }
            if (epochSettleMs > 0) delay(epochSettleMs)
        }
        settled = true
        var resumedTransition = false
        if (_epochState.value is RoomEpochState.Active) {
            startTrafficJobs()
            resumedTransition = transportBlocked
            if (transportBlocked) {
                transport.completeRekey()
                transportBlocked = false
            }
            synchronized(lock) { publicationAllowed = true }
        }
        jobs += scope.launch {
            while (true) {
                delay(timing.heartbeatIntervalMs)
                announceIfPublishing()
            }
        }
        jobs += scope.launch {
            while (true) {
                delay(timing.sweepIntervalMs)
                sweep()
            }
        }
        announceIfPublishing()
        if (resumedTransition) onEpochReady(epochKeys())
    }

    private fun startTrafficJobs() {
        if (trafficJobs.isNotEmpty()) return
        trafficJobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
            transport.subscribe(listOf(rosterFilter())).collect(::onRosterEvent)
        }
        trafficJobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
            transport.subscribe(listOf(chatFilter())).collect(::onChatEvent)
        }
        trafficJobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
            transport.subscribe(listOf(signalFilter())).collect(::onSignalEvent)
        }
    }

    /**
     * Stands down.
     *
     * The last entry this device publishes carries no tracks and no claims -
     * which releases the microphone immediately - and is marked `left`, so
     * everybody else drops it now rather than when its presence lapses. It is
     * marked `reply` too, because a farewell is not an arrival and must not
     * provoke every remaining device into re-announcing at it. A device that
     * is switched off mid-call is removed by the presence sweep instead, so
     * there is only one path to test.
     */
    fun leave() {
        val cancelling: List<Job>
        val traffic: List<Job>
        val farewell: Boolean
        synchronized(lock) {
            if (!joined) return
            farewell = publicationAllowed
            joined = false
            publicationAllowed = false
            tracks = emptyList()
            claims = emptyMap()
            cancelling = jobs.toList()
            jobs.clear()
            traffic = trafficJobs.toList()
            trafficJobs.clear()
        }
        if (farewell) publishAnnouncement(reply = true, left = true)
        responseJob?.cancel()
        for (job in cancelling) job.cancel()
        for (job in traffic) job.cancel()
    }

    // --- publishing ----------------------------------------------------------

    /** Says who we are and what we are publishing, right now. `reply` marks
     *  an answer or a farewell rather than an arrival; `left` marks the
     *  farewell itself. */
    fun announce(reply: Boolean = false, left: Boolean = false) {
        synchronized(lock) {
            check(publicationAllowed) { "Room publication is blocked during a secure update" }
            publishAnnouncement(reply, left)
        }
    }

    /**
     * Background presence is best-effort. A secure epoch transition may close
     * the publication gate after a heartbeat or delayed reply has been queued;
     * in that case the stale presence entry must be dropped, not crash the app
     * or race traffic onto the predecessor epoch. The shared lock makes closing
     * the gate and publishing one of these entries mutually exclusive.
     * Deliberate caller actions still use [announce] and keep its fail-closed
     * behaviour.
     */
    private fun announceIfPublishing(reply: Boolean = false) {
        synchronized(lock) {
            if (!joined || !publicationAllowed) return
            publishAnnouncement(reply, left = false)
        }
    }

    private fun publishAnnouncement(reply: Boolean, left: Boolean) {
        val epoch = epochKeys()
        val entry = synchronized(lock) {
            RosterEntry(
                participant = identity.participant,
                device = identity.devicePubkey,
                credential = identity.credential,
                proof = proof,
                tracks = tracks,
                claims = claims,
                updatedAt = now(),
                reply = reply,
                left = left,
                // Absent unless this build is switched on, so the wire does not
                // change for anybody until it is. Saying it is what lets a far
                // end open a profile-2 pair with this device; a pair is profile
                // 2 only when both entries say so.
                callProfile = if (CALL_PROFILE_2_ENABLED) CALL_PROFILE_2 else null,
            ).also { roster[identity.devicePubkey] = it }
        }
        transport.publish(
            encodeRosterEvent(
                entry = entry,
                roomId = epoch.id,
                roomKey = epoch.key,
                deviceSecretKey = identity.deviceSecretKey,
            ),
        )
        recompute()
    }

    fun setTracks(tracks: List<TrackRef>) {
        synchronized(lock) { this.tracks = tracks }
        announce()
    }

    /**
     * Takes a singular role for this device.
     *
     * There is no negotiation and no lock: the claim is stamped with the current
     * time and published, and every client independently arbitrates. Most recent
     * wins, so picking up your phone moves the microphone to your phone without
     * anything having to agree first.
     */
    fun claim(role: String) {
        synchronized(lock) { claims = claims + (role to now()) }
        announce()
    }

    fun release(role: String) {
        synchronized(lock) { claims = claims - role }
        announce()
    }

    fun sendChat(body: String, reaction: ChatReaction? = null) {
        check(publicationAllowed) { "Room publication is blocked during a secure update" }
        val text = body.trim()
        if (text.isEmpty()) return
        require(text.length <= MAX_CHAT_TEXT_LENGTH) { "chat message exceeds $MAX_CHAT_TEXT_LENGTH characters" }
        val sentAt = now()
        val epoch = epochKeys()
        val event = encodeChatEvent(
            body = text,
            participant = identity.participant,
            credential = identity.credential,
            roomId = epoch.id,
            roomKey = epoch.key,
            deviceSecretKey = identity.deviceSecretKey,
            sentAt = sentAt,
            proof = proof,
            reaction = reaction,
            credentialRoomId = room.roomId,
        )
        transport.publish(event)
        // Shown at once rather than waiting for a relay to echo it back. The id
        // is the event id, so the echo is de-duplicated against this.
        decodeChatEvent(event, epoch.id, epoch.key, sentAt, policy, credentialRoomId = room.roomId)?.let {
            if (ingestChat(it)) retainOwnOuterEvent(event, it)
        }
    }

    /** A person's message is shown as sent only after at least one relay accepts it. */
    suspend fun sendChatConfirmed(body: String, reaction: ChatReaction? = null): Boolean {
        check(publicationAllowed) { "Room publication is blocked during a secure update" }
        val text = body.trim()
        if (text.isEmpty()) return false
        require(text.length <= MAX_CHAT_TEXT_LENGTH) { "chat message exceeds $MAX_CHAT_TEXT_LENGTH characters" }
        val sentAt = now()
        val epoch = epochKeys()
        val event = encodeChatEvent(
            body = text,
            participant = identity.participant,
            credential = identity.credential,
            roomId = epoch.id,
            roomKey = epoch.key,
            deviceSecretKey = identity.deviceSecretKey,
            sentAt = sentAt,
            proof = proof,
            reaction = reaction,
            credentialRoomId = room.roomId,
        )
        val message = decodeOwnChat(event, sentAt, epoch)
        if (!transport.publishConfirmed(event, CHAT_CONFIRM_TIMEOUT_MS)) return false
        if (ingestChat(message)) retainOwnOuterEvent(event, message)
        return true
    }

    /** A room capability is exposed only after at least one relay confirms its durable event. */
    suspend fun sendInviteConfirmed(invite: ChatInvite): Boolean {
        check(publicationAllowed) { "Room publication is blocked during a secure update" }
        val sentAt = now()
        val epoch = epochKeys()
        val event = encodeChatEvent(
            body = inviteText(),
            participant = identity.participant,
            credential = identity.credential,
            roomId = epoch.id,
            roomKey = epoch.key,
            deviceSecretKey = identity.deviceSecretKey,
            sentAt = sentAt,
            proof = proof,
            invite = invite,
            credentialRoomId = room.roomId,
        )
        val message = decodeOwnChat(event, sentAt, epoch)
        if (!transport.publishConfirmed(event, CHAT_CONFIRM_TIMEOUT_MS)) return false
        if (ingestChat(message)) retainOwnOuterEvent(event, message)
        return true
    }

    /** A confirmed send cannot report success for an event this room refuses. */
    private fun decodeOwnChat(event: NostrEvent, sentAt: Long, epoch: EpochKeys): ChatMessage {
        decodeChatEvent(event, epoch.id, epoch.key, sentAt, policy, credentialRoomId = room.roomId)?.let { return it }
        if (decodeChatEvent(event, epoch.id, epoch.key, sentAt, credentialRoomId = room.roomId) != null) {
            error("The current room policy refused this sender.")
        }
        error("The generated message failed local integrity validation.")
    }

    fun sendSignal(toDevice: String, body: SignalBody) {
        check(publicationAllowed) { "Room publication is blocked during a secure update" }
        val epoch = epochKeys()
        transport.publish(
            wrapSignal(
                body = body.copy(roomId = epoch.id),
                senderSecretKey = identity.deviceSecretKey,
                recipientPubkey = toDevice,
                // Stamped on the session's own clock, which is what the
                // recipient's staleness check is measured against.
                createdAt = now(),
            ).wrap,
        )
    }

    // --- incoming ------------------------------------------------------------

    internal fun onRosterEvent(event: NostrEvent) {
        val epoch = epochKeys()
        val entry = decodeRosterEvent(event, epoch.id, epoch.key, now(), room.roomId) ?: return
        policy?.let {
            if (!evaluateAccess(it, entry.participant, entry.proof, now(), room.roomId).admitted) return
        }
        if (entry.device == identity.devicePubkey) return

        val respond: Boolean
        synchronized(lock) {
            val existing = roster[entry.device]
            // Strictly older is dropped; equal is accepted. Timestamps are
            // whole seconds, and an announce and the answer to it routinely
            // land inside the same second.
            if (existing != null && entry.updatedAt < existing.updatedAt) return
            if (entry.left) {
                // A farewell. The device goes now, not when its presence
                // lapses, and the moment it left is kept so a slower relay
                // delivering something it said earlier cannot put it back.
                // Forgetting that we answered it is what lets a genuine
                // rejoin be answered again.
                departed[entry.device] = entry.updatedAt
                respondedTo.remove(entry.device)
                if (roster.remove(entry.device) == null) return
                respond = false
            } else {
                val leftAt = departed[entry.device]
                if (leftAt != null) {
                    // Stamped at or before the farewell: delivered late, not
                    // come back. After it: they really are back.
                    if (entry.updatedAt <= leftAt) return
                    departed.remove(entry.device)
                }
                roster[entry.device] = entry
                respond = respondedTo.add(entry.device)
            }
        }
        recompute()
        if (respond) scheduleResponse()
    }

    internal fun onChatEvent(event: NostrEvent) {
        val epoch = epochKeys()
        val message = decodeChatEvent(event, epoch.id, epoch.key, now(), policy, credentialRoomId = room.roomId) ?: return
        if (ingestChat(message)) retainOwnOuterEvent(event, message)
    }

    internal fun onSignalEvent(event: NostrEvent) {
        val at = now()

        // Deduplication first, because it is the cheapest check and the most
        // common case it catches - the same wrap arriving from every relay we
        // published to - costs a NIP-44 decryption otherwise.
        if (!signalGuard.admitEvent(event.id) || !signalGuard.admitUnwrap(at)) return

        // Unwrapping applies the staleness rule, judged by the session's own
        // clock rather than the wall clock.
        val signal = unwrapSignal(event, identity.deviceSecretKey, epochKeys().id, now = at) ?: return

        // Rate limiting against the *sending device* rather than the wrap's
        // pubkey: every wrap is signed by a fresh ephemeral key, so the only
        // stable identity a budget can be held against is the one inside.
        if (!signalGuard.admitEvent("inner:${signal.id}") || !signalGuard.admitSender(signal.from, at)) return

        val sender = synchronized(lock) { roster[signal.from] }
        // The roster authenticates sibling devices too. Only our exact local
        // device is excluded; paired cameras need ordinary negotiation.
        if (sender == null || signal.from == identity.devicePubkey) return
        if (signal.body.type == "annotation") {
            // Shape-checked here, after the same roster and rate-limit checks
            // every other signal passes, and never falls through to ordinary
            // negotiation: an old reader that does not know this branch
            // still safely ignores the type in WebRtcEngine.
            val annotation = signal.body.annotation
            if (annotation != null && isValidScreenAnnotation(annotation)) {
                _annotations.tryEmit(RemoteAnnotation(sender.participant, signal.from, annotation))
            }
            return
        }
        _signals.tryEmit(signal)
    }

    // --- announce-and-respond ------------------------------------------------

    /**
     * Answers a newly seen device, after a jitter delay, at most once at a time.
     *
     * Coalescing matters as much as the jitter does. Twenty devices arriving in
     * a burst - which is what a relay reconnect looks like - schedule one answer
     * between them, not twenty.
     */
    private fun scheduleResponse() {
        synchronized(lock) {
            if (responseJob?.isActive == true) return
            responseJob = scope.launch {
                delay(random.nextLong(timing.announceJitterMs + 1))
                announceIfPublishing(reply = true)
            }
        }
    }

    private fun sweep() {
        var changed = false
        synchronized(lock) {
            val cutoff = now() - timing.presenceTtlSeconds
            // A farewell only needs remembering for as long as an entry from
            // before it could still be delivered and still be fresh.
            departed.entries.removeAll { it.value < cutoff }
            val gone = roster.filterValues { it.updatedAt < cutoff }.keys - identity.devicePubkey
            for (device in gone) {
                roster.remove(device)
                // Forgetting that we answered them is what lets a genuine rejoin
                // be answered again, later, without opening the loop back up:
                // they are gone from the roster, so the next thing we hear from
                // them really is an arrival.
                respondedTo.remove(device)
                changed = true
            }
        }
        if (changed) recompute()
    }

    /**
     * The lane a message sent now would take: the weakest of the relays this
     * session writes to. Null when the transport cannot say. Shown beside the
     * box people type into, so the answer is there before they send.
     */
    fun sendLane(): Lane? = laneOfRelays(transport.describe(), transport.circleRelays())

    private fun retainOwnOuterEvent(event: NostrEvent, message: ChatMessage) {
        if (message.participant == identity.participant) onVerifiedOwnEvent(event)
    }

    private fun ingestChat(incoming: ChatMessage): Boolean {
        // The lane is the reader's finding: the relays this session reads
        // over, never anything the message says about itself.
        val message = incoming.copy(lane = laneOfRelays(transport.describe(), transport.circleRelays()))
        synchronized(lock) {
            val at = now()
            if (message.sentAt < at - CHAT_RETENTION_SECONDS) return false
            if (!chatSeen.add(message.id)) return false
            val times = chatSenderTimes.getOrPut(message.device) { mutableListOf() }
            times.removeAll { it < at - CHAT_RETENTION_SECONDS }
            if (times.count { kotlin.math.abs(it - message.sentAt) < 60 } >= MAX_CHAT_MESSAGES_PER_MINUTE) {
                chatSeen.remove(message.id)
                return false
            }
            times += message.sentAt
            while (chatSenderTimes.size > timing.chatHistory) {
                chatSenderTimes.remove(chatSenderTimes.keys.first())
            }
            chatLog += message
            chatLog.sortBy { it.sentAt }
            while (chatLog.size > timing.chatHistory) {
                chatSeen.remove(chatLog.removeAt(0).id)
            }
            _chat.value = chatLog.toList()
            return true
        }
    }

    private fun recompute() {
        val snapshot = synchronized(lock) { roster.values.toList() }
        val grouped = groupByParticipant(snapshot)
        _participants.value = grouped
        val remote = snapshot.filter { it.device != identity.devicePubkey }
        _remoteDevices.value = remote.map { it.device }.toSet()
        val agentParticipants = grouped.filter { it.agent }.map { it.participant }.toSet()
        _agentDevices.value = remote.filter { it.participant in agentParticipants }.map { it.device }.toSet()
        _profileTwoDevices.value = remote.filter { it.callProfile == CALL_PROFILE_2 }.map { it.device }.toSet()
        val me = grouped.firstOrNull { it.participant == identity.participant }
        _localRoles.value = LocalRoles(
            holdsMic = me?.micDevice == identity.devicePubkey,
            holdsMonitor = me?.monitorDevice == identity.devicePubkey,
            micDevice = me?.micDevice,
            monitorDevice = me?.monitorDevice,
        )
    }

    // --- filters -------------------------------------------------------------

    /**
     * A rekey the authority published for this room.
     *
     * Tagged with the ROOM id rather than the epoch id, because the room id
     * never moves - a device credential binds to it - so a client that has
     * fallen several epochs behind still sees every later rekey and can
     * still say how far behind it is.
     */
    private suspend fun onRekeyEvent(event: NostrEvent) = epochMutex.withLock {
        val trusted = authority ?: return
        val epoch = peekRekeyEpoch(event, room.roomId, trusted) ?: return
        val current = epochKeys()
        if (epoch <= current.epoch) return
        pendingRekeys[epoch] = event
        if (epochGate == null) {
            if (epoch > (_movedOn.value ?: 0)) _movedOn.value = epoch
            return
        }
        drainRekeys()
    }

    private suspend fun drainRekeys() {
        while (true) {
            val current = epochKeys()
            val nextEvent = pendingRekeys[current.epoch + 1]
            if (nextEvent == null) {
                val ahead = pendingRekeys.lastKeyOrNull()?.takeIf { it > current.epoch }
                if (ahead != null) recoverFromAuthority(ahead, "The room update was missed on this device")
                return
            }
            val trusted = requireNotNull(authority)
            val notice = decodeRekeyEvent(nextEvent, room.roomId, trusted, current, identity.deviceSecretKey)
            if (notice == null) {
                recoverFromAuthority(current.epoch + 1, "The room update could not be authenticated")
                return
            }
            if (notice.secret == null && !notice.closed && notice.removed.none { it.equals(identity.participant, ignoreCase = true) }) {
                recoverFromAuthority(notice.epoch, "The room authority must restore this device")
                return
            }
            blockForRekey()
            _epochState.value = RoomEpochState.Updating(notice.epoch)
            val outcome = try {
                requireNotNull(epochGate).invoke(nextEvent, notice)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _epochState.value = RoomEpochState.RecoveryNeeded(notice.epoch, error.message ?: "The room update could not be recovered on this device")
                _movedOn.value = notice.epoch
                return
            }
            if (outcome == EpochGateResult.PENDING) return
            when {
                notice.closed -> {
                    stopTraffic()
                    pendingRekeys.remove(notice.epoch)
                    _epochState.value = RoomEpochState.Closed(notice.epoch)
                    _movedOn.value = notice.epoch
                    return
                }
                notice.secret == null && notice.removed.any { it.equals(identity.participant, ignoreCase = true) } -> {
                    stopTraffic()
                    pendingRekeys.remove(notice.epoch)
                    _epochState.value = RoomEpochState.Removed(notice.epoch)
                    _movedOn.value = notice.epoch
                    return
                }
                notice.secret == null -> {
                    recoverFromAuthority(notice.epoch, "The room authority must restore this device")
                    return
                }
                else -> try {
                    applyEpoch(notice)
                    pendingRekeys.remove(notice.epoch)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    _epochState.value = RoomEpochState.RecoveryNeeded(
                        notice.epoch, error.message ?: "The room update could not move every local subsystem",
                    )
                    _movedOn.value = notice.epoch
                    return
                }
            }
        }
    }

    /** Recover directly to the authority's signed current epoch over the stable room channel. */
    private suspend fun recoverFromAuthority(expectedEpoch: Int, reason: String) {
        val trusted = authority ?: return requireRecovery(expectedEpoch, reason)
        blockForRekey()
        _epochState.value = RoomEpochState.Updating(expectedEpoch)
        val response = try {
            coroutineScope {
                val request = encodeEpochRequest(
                    room.roomId, trusted, identity.deviceSecretKey, identity.credential, now(), proof,
                )
                val answer = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(EPOCH_RECOVERY_TIMEOUT_MS) {
                        transport.subscribe(listOf(epochGrantFilter())).mapNotNull { event ->
                            decodeEpochGrant(
                                event, room.roomId, trusted, identity.deviceSecretKey, request.id, now(),
                            )?.let { event to it }
                        }.first()
                    }
                }
                transport.publishRecovery(request)
                answer.await()
            }
        } catch (timeout: TimeoutCancellationException) {
            requireRecovery(expectedEpoch, reason)
            return
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            requireRecovery(expectedEpoch, error.message ?: reason)
            return
        }
        val (event, grant) = response
        when (grant) {
            is EpochGrant.Refused -> {
                val current = epochKeys()
                val terminal = RekeyNotice(
                    current.epoch + 1,
                    if (grant.reason == "removed") listOf(identity.participant) else emptyList(),
                    null,
                    grant.reason == "closed",
                    null,
                    event.createdAt,
                    catchUp = true,
                )
                val outcome = try {
                    requireNotNull(epochGate).invoke(event, terminal)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    requireRecovery(expectedEpoch, error.message ?: reason)
                    return
                }
                if (outcome == EpochGateResult.PENDING) return
                stopTraffic()
                _epochState.value = if (terminal.closed) RoomEpochState.Closed(expectedEpoch) else RoomEpochState.Removed(expectedEpoch)
                _movedOn.value = expectedEpoch
            }
            is EpochGrant.Current -> {
                val current = epochKeys()
                val secret = grant.secret
                if (grant.epoch <= current.epoch || secret == null) {
                    requireRecovery(expectedEpoch, "The authority did not prove a newer room epoch")
                    return
                }
                val notice = RekeyNotice(grant.epoch, grant.removed, null, false, secret, event.createdAt, catchUp = true)
                val outcome = try {
                    requireNotNull(epochGate).invoke(event, notice)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    requireRecovery(expectedEpoch, error.message ?: reason)
                    return
                }
                if (outcome == EpochGateResult.PENDING) return
                try {
                    applyEpoch(notice)
                    pendingRekeys.keys.removeAll { it <= notice.epoch }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    requireRecovery(notice.epoch, error.message ?: "The recovered room update could not move every local subsystem")
                }
            }
        }
    }

    private suspend fun requireRecovery(epoch: Int, reason: String) {
        blockForRekey()
        _epochState.value = RoomEpochState.RecoveryNeeded(epoch, reason)
        if (epoch > (_movedOn.value ?: 0)) _movedOn.value = epoch
    }

    private suspend fun blockForRekey() {
        synchronized(lock) {
            publicationAllowed = false
            responseJob?.cancel()
        }
        if (!transportBlocked) {
            onEpochBlocked()
            transport.beginRekey()
            transportBlocked = true
        }
    }

    private suspend fun applyEpoch(notice: RekeyNotice) {
        val secret = requireNotNull(notice.secret)
        val next = deriveEpoch(RoomEpoch(notice.epoch, secret))
        stopTraffic()
        transport.rekey(next.key)
        onEpochApplied(notice, next)
        synchronized(lock) {
            activeEpoch = next
            notice.removed.forEach { removed ->
                roster.entries.removeAll { it.value.participant.equals(removed, ignoreCase = true) }
            }
            respondedTo.clear()
            departed.clear()
        }
        _epochState.value = RoomEpochState.Active(next.epoch, next.id)
        _movedOn.value = null
        recompute()
        if (settled && joined) {
            startTrafficJobs()
            transport.completeRekey()
            transportBlocked = false
            synchronized(lock) { publicationAllowed = true }
            announceIfPublishing(reply = true)
            onEpochReady(next)
        }
    }

    private fun stopTraffic() {
        trafficJobs.forEach(Job::cancel)
        trafficJobs.clear()
    }

    private fun <K, V> TreeMap<K, V>.lastKeyOrNull(): K? = if (isEmpty()) null else lastKey()

    private fun rekeyFilter() = Filter(
        kinds = listOf(KIND_ROOM_REKEY),
        authors = listOfNotNull(authority),
        tags = mapOf("#d" to listOf(room.roomId)),
    )

    private fun epochRequestFilter() = Filter(
        kinds = listOf(KIND_EPOCH_REQUEST),
        tags = mapOf("#d" to listOf(room.roomId), "#p" to listOfNotNull(authority)),
    )

    private fun epochGrantFilter() = Filter(
        kinds = listOf(KIND_EPOCH_GRANT),
        authors = listOfNotNull(authority),
        tags = mapOf("#d" to listOf(room.roomId), "#p" to listOf(identity.devicePubkey)),
    )

    private fun rosterFilter() = Filter(
        kinds = listOf(KIND_ROSTER),
        tags = mapOf("#d" to listOf(epochKeys().id)),
    )

    private fun chatFilter() = Filter(
        kinds = listOf(KIND_CHAT),
        tags = mapOf("#d" to listOf(epochKeys().id)),
        since = now() - CHAT_RETENTION_SECONDS,
    )

    private fun signalFilter() = Filter(
        kinds = listOf(KIND_SIGNAL_WRAP),
        tags = mapOf("#p" to listOf(identity.devicePubkey)),
    )
}
