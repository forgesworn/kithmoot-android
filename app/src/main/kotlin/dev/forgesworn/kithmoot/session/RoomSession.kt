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
import dev.forgesworn.kithmoot.protocol.KIND_MEMBER_EPOCH_GRANT
import dev.forgesworn.kithmoot.protocol.KIND_MEMBER_EPOCH_REQUEST
import dev.forgesworn.kithmoot.protocol.MemberEpochGrant
import dev.forgesworn.kithmoot.protocol.decodeMemberEpochGrant
import dev.forgesworn.kithmoot.protocol.encodeMemberEpochRequest
import dev.forgesworn.kithmoot.protocol.KIND_SIGNAL_WRAP
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.CallBellState
import dev.forgesworn.kithmoot.protocol.CallMembership
import dev.forgesworn.kithmoot.protocol.EncodeCallBellOptions
import dev.forgesworn.kithmoot.protocol.encodeCallBellEvent
import dev.forgesworn.kithmoot.protocol.KindredProof
import dev.forgesworn.kithmoot.protocol.Room
import dev.forgesworn.kithmoot.protocol.RoomPolicy
import dev.forgesworn.kithmoot.protocol.KindredTier
import dev.forgesworn.kithmoot.protocol.CredentialCheck
import dev.forgesworn.kithmoot.protocol.verifyDeviceCredential
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
import dev.forgesworn.kithmoot.epoch.EpochOpening
import dev.forgesworn.kithmoot.epoch.epochOpening
import dev.forgesworn.kithmoot.epoch.MemberDeskDecision
import dev.forgesworn.kithmoot.epoch.MemberEpochDesk
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.RoomTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.TreeMap
import kotlin.random.Random

private const val CHAT_CONFIRM_TIMEOUT_MS = 75_000L
private const val DEFAULT_EPOCH_SETTLE_MS = 1_500L
private const val EPOCH_RECOVERY_TIMEOUT_MS = 30_000L
/** How long an unprompted epoch question waits for an answer: the web client's request timeout. */
private const val EPOCH_PROBE_TIMEOUT_MS = 20_000L
/** How often a fresh member epoch request goes out while an epoch question is open: fold-kit's `retryMs`. */
private const val MEMBER_EPOCH_RETRY_MS = 4_000L
/** How long the first member request waits for the relays to replay the room's rekeys, so the rollback floor is known. */
private const val REKEY_REPLAY_WAIT_MS = 1_500L
/** How many of this device's member request ids a grant may answer. */
private const val MEMBER_EPOCH_REQUESTS_KEPT = 16

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

/** How many epochs a session has left it still reads chat on: the web client's `MAX_PAST_EPOCHS`. */
const val MAX_PAST_EPOCHS = 4

/**
 * This session moved from epoch [from] straight to [to] without the keys of
 * the epochs between, so whatever was said in them cannot be read here. [at]
 * is unix seconds. The web client's `EpochGap`.
 */
data class EpochGap(val from: Int, val to: Int, val at: Long)

/**
 * Two rekeys signed by the room's authority for one epoch: [kept] is the
 * event this device follows, [other] the one it does not. Whoever followed
 * [other] is in a different room, however alike the two look. The web
 * client's `EpochConflict`.
 */
data class EpochConflict(val epoch: Int, val kept: String, val other: String)

/** An epoch this session has left, and when it was left (unix seconds). */
private class PastEpoch(val keys: EpochKeys, val leftAt: Long)

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
    /** Milliseconds, for chat's `sentAtMs`; written only when it agrees with [now]'s second. */
    private val nowMs: () -> Long = System::currentTimeMillis,
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
     * The member desk (`MemberEpochResponder`): lets this device, while in step at an epoch
     * above 0, bring another member's device up to date when the authority's device is away.
     * Null where the room is not followed, on the authority device, and in anonymous rooms.
     */
    private val memberEpochDesk: MemberEpochDesk? = null,
    /**
     * Epoch secrets and authority rekeys this session has learnt, for the member desk to hand
     * on later: every authority-signed rekey the relays show, and every epoch a member grant
     * proved. Advisory; see `EpochVault.remember`.
     */
    private val onEpochHistory: suspend (List<RoomEpoch>, List<NostrEvent>) -> Unit = { _, _ -> },
    /**
     * The epoch the responder that admitted this device said the room is at
     * (`RoomAdmission.epoch`), or one this device was told earlier and has not
     * reached. Above [initialEpoch], the session asks the authority for it
     * before it says anything, as the web client's `expectedEpoch` does. See
     * [epochOpening].
     */
    private val expectedEpoch: Int? = null,
    /**
     * Whether, told nothing newer, the session may ask the authority once as
     * it opens whether the room has moved on. Off by default; the app turns it
     * on for rooms whose traffic shape does not matter (not a quiet room).
     */
    private val epochProbe: Boolean = false,
    /**
     * A device-local, encrypted metadata catalogue for the signed-in person's
     * verified outer events. It is deliberately not a relay operation.
     */
    private val onVerifiedOwnEvent: (NostrEvent) -> Unit = {},
    /**
     * `false` never publishes a call bell (kind 1464) when this device is
     * the first onto a call or the last off it - a test-only escape hatch,
     * mirroring the web client's `callBell` option in `session.ts`.
     */
    private val callBellEnabled: Boolean = true,
    private val chatOutbox: PendingChatOutbox? = null,
    /**
     * A conference room's end, unix seconds: every event this session signs
     * carries a NIP-40 `expiration` no later than it, so relays drop the
     * room's traffic when it ends. Null for a room that does not end, whose
     * events are unchanged. See `withRoomExpiration`.
     */
    private val ends: Long? = null,
) {

    private val lock = Any()
    private val epochMutex = Mutex()
    private var activeEpoch = initialEpoch
    private val pendingRekeys = TreeMap<Int, NostrEvent>()
    /**
     * The rollback floor: the newest epoch an authority-signed rekey has been seen for on the
     * relays. A member grant short of it is refused, so a member removed at that epoch cannot
     * hold this device one epoch back on a key it still has. Read outside [epochMutex], since an
     * epoch question is asked while holding it.
     */
    private val rekeyFloor = java.util.concurrent.atomic.AtomicInteger(0)
    /** Completes once the relays have had [REKEY_REPLAY_WAIT_MS] to replay the room's rekeys. */
    private val rekeysReplayed = CompletableDeferred<Unit>()
    /** The authority's rekey this session followed into each epoch, by event id. Under [epochMutex]. */
    private val followed = HashMap<Int, String>()
    /**
     * Epochs this session has left and still reads chat on. A member whose
     * device has not followed the rekey yet, or a relay slow to deliver, still
     * lands a message on the epoch left; and a member's grant that carried
     * this device over epochs hands their keys too, so what was said in them
     * can be read. At most [MAX_PAST_EPOCHS], none left longer ago than chat
     * is kept. Under [lock].
     */
    private val pastEpochs = TreeMap<Int, PastEpoch>()
    /** Everybody a rekey this session followed removed, lower case: refused on the epochs left. Under [lock]. */
    private val removedParticipants = mutableSetOf<String>()
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
     * When each roster entry last arrived, by our clock. Presence is timed
     * from this rather than from the sender's timestamp, so a peer whose
     * clock runs slow is not treated as having gone quiet.
     */
    private val seenAt = mutableMapOf<String, Long>()

    /**
     * Whether the call engine has a working connection to a device. Set by
     * the engine; read under the session lock, so it must only read state,
     * never wait on the engine.
     */
    @Volatile var mediaConnected: (String) -> Boolean = { false }

    /**
     * Deduplication and rate limiting for signalling - two of the three rules
     * §3 of the design says are reused from NIP-AC. The third, staleness, is
     * applied inside [unwrapSignal].
     */
    private val signalGuard = SignalGuard()
    private val annotationGuard = SignalGuard(480)
    private val chatSeen = mutableSetOf<String>()
    private val chatSendGate = Mutex()
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

    /**
     * The call this device says it is on, restated on every announcement
     * while it lasts. See [CallMembership]: a call is a claim on presence, not
     * a kind of its own, and it ends when the last device stops saying it.
     */
    private var call: CallMembership? = null

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

    private val _epochGaps = MutableStateFlow<List<EpochGap>>(emptyList())

    /** Every jump this session made over epochs it holds no key for. */
    val epochGaps: StateFlow<List<EpochGap>> = _epochGaps.asStateFlow()

    private val _epochConflicts = MutableStateFlow<List<EpochConflict>>(emptyList())

    /** Every epoch the authority's rekeys disagree about, as this session saw them. */
    val epochConflicts: StateFlow<List<EpochConflict>> = _epochConflicts.asStateFlow()

    fun epochKeys(): EpochKeys = synchronized(lock) { EpochKeys(activeEpoch.epoch, activeEpoch.id, activeEpoch.key) }

    suspend fun retryEpoch() = epochMutex.withLock {
        check(epochGate != null) { "This room has no pinned epoch authority" }
        val state = _epochState.value
        val current = epochKeys().epoch
        // Told the room is ahead, with no rekey in hand to read: only the
        // authority can help, so ask it again rather than draining nothing.
        if (state is RoomEpochState.RecoveryNeeded && state.expectedEpoch > current && pendingRekeys.keys.none { it > current }) {
            recoverFromAuthority(state.expectedEpoch, state.reason)
        } else {
            drainRekeys()
        }
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
            jobs += scope.launch {
                delay(REKEY_REPLAY_WAIT_MS)
                rekeysReplayed.complete(Unit)
            }
            if (memberEpochDesk != null && epochResponder == null) startMemberDesk(memberEpochDesk)
            if (epochResponder != null) {
                jobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    transport.subscribe(listOf(epochRequestFilter())).collect { request ->
                        epochResponder.invoke(request)?.let(transport::publishRecovery)
                    }
                }
            }
            val opening = epochOpening(expectedEpoch, epochKeys().epoch, epochGate != null, epochResponder != null, epochProbe)
            // Told where the room is, there is nothing to wait for; told
            // nothing, wait for the rekeys a relay replays.
            if (opening !is EpochOpening.Recover && epochSettleMs > 0) delay(epochSettleMs)
            when (opening) {
                is EpochOpening.Recover -> beginRecoveryAtOpen(opening.epoch)
                EpochOpening.Probe -> jobs += scope.launch { probeAuthority() }
                EpochOpening.None -> Unit
            }
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
        var offCall: CallMembership? = null
        synchronized(lock) {
            if (!joined) return
            farewell = publicationAllowed
            joined = false
            publicationAllowed = false
            tracks = emptyList()
            claims = emptyMap()
            // A farewell is never on a call. Leaving the room is leaving
            // everything in it. The last device off a call rings it closed,
            // decided on the presence as it stood before this farewell.
            val leaving = call
            if (leaving != null && !othersOnCallLocked(leaving.id)) offCall = leaving
            call = null
            cancelling = jobs.toList()
            jobs.clear()
            traffic = trafficJobs.toList()
            trafficJobs.clear()
            pastEpochs.clear()
        }
        offCall?.let { ringBell(CallBellState.END, it) }
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
                // Omitted on a farewell, as the web client omits it: a device
                // on its way out is not on the call either, and the last thing
                // it publishes should not say it is.
                call = if (left) null else call,
            ).also { roster[identity.devicePubkey] = it }
        }
        transport.publish(
            encodeRosterEvent(
                entry = entry,
                roomId = epoch.id,
                roomKey = epoch.key,
                deviceSecretKey = identity.deviceSecretKey,
                roomEnds = ends,
            ),
        )
        recompute()
    }

    /**
     * Go on a call, or off it.
     *
     * Starting and joining are the same act: say which call this device is on.
     * A fresh id starts one, somebody else's id joins theirs, and null drops
     * off it. Tracks are a separate matter - a device can be on a call with
     * everything switched off, which is how somebody listens in from a train.
     * Mirrors `Session.setCall` in the web client's `src/session.ts`.
     */
    fun setCall(membership: CallMembership?) {
        val bells = synchronized(lock) {
            val previous = call
            val next = membership?.let { CallMembership(it.id.lowercase(), it.since) }
            call = next
            bellsFor(previous, next)
        }
        announce(reply = true)
        for ((state, on) in bells) ringBell(state, on)
    }

    /** The call this device says it is on, if any. */
    fun currentCall(): CallMembership? = synchronized(lock) { call }

    /**
     * Decided on the presence as it stood before the change: the bell is
     * for the first device on a call and the last one off it. Must be
     * called under [lock], since it reads [roster]. Mirrors `Session.setCall`
     * in the web client's `src/session.ts`.
     */
    private fun bellsFor(previous: CallMembership?, next: CallMembership?): List<Pair<CallBellState, CallMembership>> {
        val bells = mutableListOf<Pair<CallBellState, CallMembership>>()
        if (previous != null && previous.id != next?.id && !othersOnCallLocked(previous.id)) {
            bells += CallBellState.END to previous
        }
        if (next != null && next.id != previous?.id && !othersOnCallLocked(next.id)) {
            bells += CallBellState.START to next
        }
        return bells
    }

    /** Whether any other present endpoint - another device, or another page
     *  session of this one - says it is on [id]. Must be called under [lock]. */
    private fun othersOnCallLocked(id: String): Boolean =
        roster.values.any { it.device != identity.devicePubkey && !it.left && it.call?.id == id }

    /**
     * Publish one call bell (kind 1464) for a phone waiting with the app
     * closed - see `protocol/CallBell.kt`. Fire and forget: it never holds
     * up going on or off a call, and a failure costs only the ring, so it
     * is swallowed.
     */
    private fun ringBell(state: CallBellState, call: CallMembership) {
        if (!callBellEnabled) return
        try {
            val epoch = epochKeys()
            val event = encodeCallBellEvent(
                EncodeCallBellOptions(
                    roomId = room.roomId,
                    key = epoch.key,
                    deviceSecretKey = identity.deviceSecretKey,
                    state = state,
                    call = call,
                    createdAt = now(),
                    roomEnds = ends,
                ),
            )
            transport.publish(event)
        } catch (_: Exception) {
            // Only the ring is lost; going on or off the call already happened.
        }
    }

    /**
     * The calls in progress in this room, best first. See [callsOf].
     *
     * Read off presence, so it is only ever as fresh as the last heartbeat,
     * which is exactly right: a call is on while somebody present says it is.
     */
    fun calls(): List<CallView> = callsOf(_participants.value)

    /**
     * What this device is publishing, for the roster.
     *
     * Best-effort like a heartbeat, not fail-closed like a chat message: the
     * engine calls this on every local track change, and a camera toggle
     * does not stop for a secure update. While the gate is shut the set is
     * kept and nothing is published; the successor epoch's first
     * announcement (`applyEpoch`) and every heartbeat after it carry it.
     */
    fun setTracks(tracks: List<TrackRef>) {
        synchronized(lock) { this.tracks = tracks }
        announceIfPublishing()
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
        synchronized(lock) {
            val at = now()
            val latest = maxOf(claims[role] ?: 0L, roster.values
                .filter { it.participant == identity.participant && it.updatedAt >= at - timing.presenceTtlSeconds }
                .maxOfOrNull { it.claims[role] ?: 0L } ?: 0L)
            check(latest < at + 60) { "Device clocks disagree. Wait a moment and try the handover again." }
            claims = claims + (role to maxOf(at, latest + 1))
        }
        announce()
    }

    fun release(role: String) {
        synchronized(lock) { claims = claims - role }
        announce()
    }

    /** [sentAt] in milliseconds when the clocks agree on the second; a test's fixed [now] gets none. */
    private fun millisWithin(sentAt: Long): Long? = nowMs().takeIf { Math.floorDiv(it, 1000L) == sentAt }

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
            sentAtMs = millisWithin(sentAt),
            proof = proof,
            reaction = reaction,
            credentialRoomId = room.roomId,
            roomEnds = ends,
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
            sentAtMs = millisWithin(sentAt),
            proof = proof,
            reaction = reaction,
            credentialRoomId = room.roomId,
            roomEnds = ends,
        )
        val message = decodeOwnChat(event, sentAt, epoch)
        if (!transport.publishConfirmed(event, CHAT_CONFIRM_TIMEOUT_MS)) return false
        if (ingestChat(message)) retainOwnOuterEvent(event, message)
        return true
    }

    /** Persist the exact ciphertext before first publication, then reuse it on every retry. */
    suspend fun sendChatDurable(body: String, reaction: ChatReaction? = null,
        onRetained: suspend () -> Unit = {}): Boolean = chatSendGate.withLock {
        val outbox = checkNotNull(chatOutbox) { "This room has no durable message journal" }
        check(outbox.pending() == null) { "A message is waiting for relay confirmation. Retry it before sending another." }
        check(publicationAllowed) { "Room publication is blocked during a secure update" }
        val text = body.trim()
        if (text.isEmpty()) return@withLock false
        require(text.length <= MAX_CHAT_TEXT_LENGTH)
        val at = now()
        val generation = transport.publicationGeneration()
        val epoch = epochKeys()
        val event = encodeChatEvent(text, identity.participant, identity.credential, epoch.id, epoch.key,
            identity.deviceSecretKey, at, proof, reaction = reaction, credentialRoomId = room.roomId, roomEnds = ends,
            sentAtMs = millisWithin(at))
        decodeOwnChat(event, at, epoch)
        outbox.retain(epoch.id, event)
        onRetained()
        publishPendingChat(outbox, epoch.id, event, generation)
    }

    suspend fun pendingChat(): Boolean = chatOutbox?.pending() != null

    suspend fun retryPendingChat(): Boolean = chatSendGate.withLock {
        val outbox = chatOutbox ?: return@withLock false
        val pending = outbox.pending() ?: return@withLock false
        val generation = transport.publicationGeneration()
        publishPendingChat(outbox, pending.epochId, pending.event, generation)
    }

    /** Explicitly abandon the local retry. The relay may already have accepted this event. */
    suspend fun discardPendingChat() = chatSendGate.withLock { chatOutbox?.clear() }

    private suspend fun publishPendingChat(outbox: PendingChatOutbox, epochId: String, event: NostrEvent,
        generation: Long): Boolean {
        check(publicationAllowed) { "Room publication is blocked during a secure update. The message remains on this phone." }
        val epoch = epochKeys()
        check(epoch.id == epochId) { "The room keys changed. This message remains on this phone and cannot be replayed." }
        check(verifyDeviceCredential(identity.credential, room.roomId, now()) is CredentialCheck.Valid) {
            "This device's room credential expired. The message remains on this phone."
        }
        policy?.let {
            check(evaluateAccess(it, identity.participant, proof, now(), room.roomId).admitted) {
                "Room access is no longer valid. This message remains on this phone and cannot be replayed."
            }
        }
        check(event.createdAt >= now() - CHAT_RETENTION_SECONDS) {
            "This message is too old to replay. It remains on this phone."
        }
        check(ends == null || now() < ends) { "This conference room has ended. The message remains on this phone." }
        val message = decodeOwnChat(event, event.createdAt, epoch)
        val credentialDeadline = identity.credential.tagValue("expiration")?.toLongOrNull() ?: 0L
        val accessDeadline = policy?.takeIf { it.tier != KindredTier.OPEN }
            ?.let { proof?.expiresAt ?: 0L } ?: Long.MAX_VALUE
        if (!transport.publishConfirmedGuarded(event, generation, {
                publicationAllowed && now() < credentialDeadline && now() < accessDeadline && now() < (ends ?: Long.MAX_VALUE) &&
                    event.createdAt >= now() - CHAT_RETENTION_SECONDS
            }, CHAT_CONFIRM_TIMEOUT_MS)) return false
        if (ingestChat(message)) retainOwnOuterEvent(event, message)
        outbox.confirm(event.id)
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
            sentAtMs = millisWithin(sentAt),
            proof = proof,
            invite = invite,
            credentialRoomId = room.roomId,
            roomEnds = ends,
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
                roomEnds = ends,
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
        // Presence is timed from arrival, so an entry that was already out of
        // date when it arrived must not start a fresh window: a relay replaying
        // an old announce would otherwise bring back a device that has gone.
        if (entry.updatedAt < now() - timing.presenceTtlSeconds) return

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
                seenAt.remove(entry.device)
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
                seenAt[entry.device] = now()
                respond = respondedTo.add(entry.device)
            }
        }
        recompute()
        if (respond) scheduleResponse()
    }

    internal fun onChatEvent(event: NostrEvent) {
        val tag = event.tagValue("d")
        val (epoch, left) = synchronized(lock) {
            if (tag == activeEpoch.id) activeEpoch to false
            else pastEpochs.values.firstOrNull { it.keys.id == tag }?.let { it.keys to true }
        } ?: return
        val message = decodeChatEvent(event, epoch.id, epoch.key, now(), policy, credentialRoomId = room.roomId) ?: return
        // Somebody removed from the room still holds the keys of the epochs
        // before their removal: on those, what they write now is refused.
        if (left && synchronized(lock) { message.participant.lowercase() in removedParticipants }) return
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
        if (!signalGuard.admitEvent("inner:${signal.id}")) return

        val sender = synchronized(lock) { roster[signal.from] }
        // The roster authenticates sibling devices too. Only our exact local
        // device is excluded; paired cameras need ordinary negotiation.
        if (sender == null || signal.from == identity.devicePubkey) return
        if (signal.body.type == "annotation") {
            if (!annotationGuard.admitSender(signal.from, at)) return
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
        if (!signalGuard.admitSender(signal.from, at)) return
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
            val lapsed = roster.filter { (device, entry) -> (seenAt[device] ?: entry.updatedAt) < cutoff }.keys - identity.devicePubkey
            // Media still flowing from a device is stronger evidence that it
            // is here than a heartbeat carried by someone else's relay. A
            // relay can hang or drop a socket for a minute while the call
            // carries on, and closing a working call because the relay went
            // quiet is what dropped calls on one bad relay out of three. When
            // the connection really goes, it stops reading connected and the
            // ordinary timeout takes over. The web client does the same.
            val gone = lapsed.filterNot { device ->
                mediaConnected(device).also { alive -> if (alive) seenAt[device] = now() }
            }
            for (device in gone) {
                roster.remove(device)
                seenAt.remove(device)
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
    private suspend fun onRekeyEvent(event: NostrEvent) {
        val trusted = authority ?: return
        val epoch = peekRekeyEpoch(event, room.roomId, trusted) ?: return
        // Before the lock: an epoch question in progress holds it, and must see the floor rise.
        rekeyFloor.accumulateAndGet(epoch, ::maxOf)
        runCatching { onEpochHistory(emptyList(), listOf(event)) }.onFailure { if (it is CancellationException) throw it }
        epochMutex.withLock { onAuthorityRekey(event, epoch) }
    }

    private suspend fun onAuthorityRekey(event: NostrEvent, epoch: Int) {
        // The first rekey held for an epoch is the one followed; another the
        // authority signed for the same epoch splits the room, and is said.
        val known = followed[epoch] ?: pendingRekeys[epoch]?.id
        if (known != null && known != event.id) {
            noteConflict(EpochConflict(epoch, known, event.id))
            return
        }
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
                    followed[notice.epoch] = nextEvent.id
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

    /**
     * Told at admission that the room is at [target], past this device: hold
     * every publication now, before `join` decides whether traffic may start,
     * and ask the authority without holding `join` up. Applying the answer
     * starts traffic under the recovered epoch (see [applyEpoch]); no answer
     * leaves the room saying it needs recovery, never looking current.
     */
    private suspend fun beginRecoveryAtOpen(target: Int) {
        val held = epochMutex.withLock {
            if (epochKeys().epoch >= target || _epochState.value !is RoomEpochState.Active) return@withLock false
            blockForRekey()
            _epochState.value = RoomEpochState.Updating(target)
            true
        }
        if (!held) return
        jobs += scope.launch {
            epochMutex.withLock {
                // A replayed rekey may have carried this device there already.
                if (epochKeys().epoch < target) recoverFromAuthority(target, "The room has moved on and its authority has not restored this device yet")
            }
        }
    }

    /**
     * Ask the authority, once and without blocking anything, where the room
     * is. An answer naming a newer epoch is followed exactly as a recovery
     * would be; a refusal is the authority's word and is final; silence, or an
     * answer no further on than this device, changes nothing.
     */
    private suspend fun probeAuthority() {
        val response = try {
            askAuthority(EPOCH_PROBE_TIMEOUT_MS)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return
        epochMutex.withLock {
            if (_epochState.value !is RoomEpochState.Active) return
            val current = epochKeys().epoch
            val target = when (response) {
                is EpochAnswer.Member -> response.grant.epoch.epoch.takeIf { it > current } ?: return
                is EpochAnswer.Authority -> when (val grant = response.grant) {
                    is EpochGrant.Current -> grant.epoch.takeIf { it > current && grant.secret != null } ?: return
                    is EpochGrant.Refused -> current + 1
                }
            }
            blockForRekey()
            _epochState.value = RoomEpochState.Updating(target)
            answerFromAuthority(response, target, "The room has moved on and its authority has not restored this device yet")
        }
    }

    /** An answer to an epoch question: the authority's grant or refusal, or a member's verified grant. */
    private sealed interface EpochAnswer {
        val event: NostrEvent
        class Authority(override val event: NostrEvent, val grant: EpochGrant) : EpochAnswer
        class Member(override val event: NostrEvent, val grant: MemberEpochGrant) : EpochAnswer
    }

    /**
     * One epoch question, and the first answer that checks out; null when none came in
     * [timeoutMs]. The authority is asked once (kind 20468). The room's current members are
     * asked too (kind 20471), every [MEMBER_EPOCH_RETRY_MS] with a fresh request, once the
     * relays have had their chance to replay the room's rekeys: a member's grant is believed
     * only as far as the authority's own signatures prove it, and never short of the newest
     * rekey seen ([rekeyFloor]). As fold-kit's `requestRoomEpoch({ members })` does.
     */
    private suspend fun askAuthority(timeoutMs: Long): EpochAnswer? {
        val trusted = requireNotNull(authority)
        return coroutineScope {
            val settled = CompletableDeferred<EpochAnswer>()
            val request = encodeEpochRequest(
                room.roomId, trusted, room.roomKey, identity.deviceSecretKey, identity.credential, now(), proof,
                roomEnds = ends,
            )
            val listeners = mutableListOf<Job>()
            listeners += launch(start = CoroutineStart.UNDISPATCHED) {
                transport.subscribe(listOf(epochGrantFilter())).collect { event ->
                    if (settled.isCompleted) return@collect
                    decodeEpochGrant(event, room.roomId, trusted, identity.deviceSecretKey, request.id, now())
                        ?.let { settled.complete(EpochAnswer.Authority(event, it)) }
                }
            }
            val asked = ArrayDeque<String>()
            listeners += launch(start = CoroutineStart.UNDISPATCHED) {
                transport.subscribe(listOf(memberGrantFilter())).collect { event ->
                    if (settled.isCompleted) return@collect
                    val current = epochKeys()
                    val ids = synchronized(asked) { asked.toList() }
                    decodeMemberEpochGrant(
                        event, room.roomId, trusted, identity.deviceSecretKey, ids, current.epoch, current.key,
                        identity.participant, now(), expected = rekeyFloor.get().takeIf { it > 0 },
                    )?.let { settled.complete(EpochAnswer.Member(event, it)) }
                }
            }
            listeners += launch {
                rekeysReplayed.await()
                while (!settled.isCompleted) {
                    val ask = encodeMemberEpochRequest(
                        room.roomId, trusted, room.roomKey, identity.deviceSecretKey, identity.credential,
                        epochKeys().epoch, now(), proof, roomEnds = ends,
                    )
                    synchronized(asked) {
                        asked.addLast(ask.id)
                        while (asked.size > MEMBER_EPOCH_REQUESTS_KEPT) asked.removeFirst()
                    }
                    transport.publishRecovery(ask)
                    delay(MEMBER_EPOCH_RETRY_MS)
                }
            }
            try {
                transport.publishRecovery(request)
                withTimeoutOrNull(timeoutMs) { settled.await() }
            } finally {
                listeners.forEach(Job::cancel)
            }
        }
    }

    /** Recover directly to the authority's signed current epoch over the stable room channel. */
    private suspend fun recoverFromAuthority(expectedEpoch: Int, reason: String) {
        if (authority == null) return requireRecovery(expectedEpoch, reason)
        blockForRekey()
        _epochState.value = RoomEpochState.Updating(expectedEpoch)
        val response = try {
            askAuthority(EPOCH_RECOVERY_TIMEOUT_MS)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            requireRecovery(expectedEpoch, error.message ?: reason)
            return
        }
        if (response == null) {
            requireRecovery(expectedEpoch, reason)
            return
        }
        answerFromAuthority(response, expectedEpoch, reason)
    }

    /**
     * Follow an answer: the authority's refusal is terminal, and a newer epoch - the
     * authority's, or one a member's grant proved - is committed and entered.
     */
    private suspend fun answerFromAuthority(response: EpochAnswer, expectedEpoch: Int, reason: String) {
        if (response is EpochAnswer.Member) return answerFromMember(response, expectedEpoch, reason)
        val event = response.event
        when (val grant = (response as EpochAnswer.Authority).grant) {
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

    /**
     * Enter the epoch a member's grant proved. `decodeMemberEpochGrant` has already checked
     * the whole chain against the authority's signatures, so it is committed as a catch-up,
     * exactly as the authority's own grant would be; the chain is kept so this device can hand
     * it on in turn.
     */
    private suspend fun answerFromMember(response: EpochAnswer.Member, expectedEpoch: Int, reason: String) {
        val grant = response.grant
        if (grant.epoch.epoch <= epochKeys().epoch) {
            requireRecovery(expectedEpoch, "The answer did not prove a newer room epoch")
            return
        }
        val notice = RekeyNotice(grant.epoch.epoch, grant.removed, null, false, grant.epoch.secret, response.event.createdAt, catchUp = true)
        val outcome = try {
            requireNotNull(epochGate).invoke(response.event, notice)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            requireRecovery(expectedEpoch, error.message ?: reason)
            return
        }
        if (outcome == EpochGateResult.PENDING) return
        try {
            // Every epoch the grant carried this device over is read as one left.
            applyEpoch(notice, crossed = grant.chain.dropLast(1))
            grant.chain.zip(grant.rekeys).forEach { (value, rekey) -> followed.putIfAbsent(value.epoch, rekey.id) }
            pendingRekeys.keys.removeAll { it <= notice.epoch }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            requireRecovery(notice.epoch, error.message ?: "The recovered room update could not move every local subsystem")
            return
        }
        runCatching { onEpochHistory(grant.chain, grant.rekeys) }.onFailure { if (it is CancellationException) throw it }
    }

    /** The epoch this session is in step at, above 0, for the member desk; null when it is not. */
    private fun inStepEpoch(): Int? {
        val state = _epochState.value as? RoomEpochState.Active ?: return null
        return state.epoch.takeIf { it > 0 && it == epochKeys().epoch }
    }

    /**
     * Run the member desk for as long as the session lasts: every 20472 on the room tells it
     * somebody answered a device, and every 20471 may be answered after its jitter. Each
     * answer is a child of the request listener, so leaving the room cancels it.
     */
    private fun startMemberDesk(desk: MemberEpochDesk) {
        jobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
            transport.subscribe(listOf(memberGrantSeenFilter())).collect { desk.onGrant(it) }
        }
        jobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
            coroutineScope {
                transport.subscribe(listOf(memberRequestFilter())).collect { event ->
                    val decision = desk.onRequest(event, inStepEpoch())
                    if (decision is MemberDeskDecision.Answer) launch {
                        if (decision.delayMs > 0) delay(decision.delayMs)
                        desk.answer(decision.request, inStepEpoch())?.let(transport::publishRecovery)
                    }
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

    private suspend fun applyEpoch(notice: RekeyNotice, crossed: List<RoomEpoch> = emptyList()) {
        val secret = requireNotNull(notice.secret)
        val next = deriveEpoch(RoomEpoch(notice.epoch, secret))
        stopTraffic()
        transport.rekey(next.key)
        onEpochApplied(notice, next)
        val gap = synchronized(lock) {
            val from = activeEpoch
            val between = crossed.filter { it.epoch > from.epoch && it.epoch < next.epoch }.distinctBy { it.epoch }
            keepPastLocked(listOf(from) + between.map(::deriveEpoch), notice.at)
            notice.removed.forEach { removedParticipants += it.lowercase() }
            activeEpoch = next
            notice.removed.forEach { removed ->
                roster.entries.removeAll { it.value.participant.equals(removed, ignoreCase = true) }
            }
            respondedTo.clear()
            departed.clear()
            EpochGap(from.epoch, next.epoch, now()).takeIf { next.epoch > from.epoch + 1 + between.size }
        }
        if (gap != null) _epochGaps.value = _epochGaps.value + gap
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

    /** Keep [left] as epochs left at [leftAt], then drop the oldest past the age and count limits. */
    private fun keepPastLocked(left: List<EpochKeys>, leftAt: Long) {
        for (keys in left) pastEpochs[keys.epoch] = PastEpoch(keys, leftAt)
        val oldest = now() - CHAT_RETENTION_SECONDS
        pastEpochs.values.removeAll { it.leftAt < oldest }
        while (pastEpochs.size > MAX_PAST_EPOCHS) pastEpochs.pollFirstEntry()
    }

    private fun noteConflict(conflict: EpochConflict) {
        val seen = _epochConflicts.value
        if (seen.none { it.epoch == conflict.epoch && it.other == conflict.other }) _epochConflicts.value = seen + conflict
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

    private fun memberGrantFilter() = Filter(
        kinds = listOf(KIND_MEMBER_EPOCH_GRANT),
        tags = mapOf("#d" to listOf(room.roomId), "#p" to listOf(identity.devicePubkey)),
    )

    private fun memberGrantSeenFilter() = Filter(
        kinds = listOf(KIND_MEMBER_EPOCH_GRANT),
        tags = mapOf("#d" to listOf(room.roomId)),
    )

    private fun memberRequestFilter() = Filter(
        kinds = listOf(KIND_MEMBER_EPOCH_REQUEST),
        tags = mapOf("#d" to listOf(room.roomId)),
    )

    private fun rosterFilter() = Filter(
        kinds = listOf(KIND_ROSTER),
        tags = mapOf("#d" to listOf(epochKeys().id)),
    )

    /** Chat on the current epoch and on every epoch left that is still read, in one filter. */
    private fun chatFilter() = Filter(
        kinds = listOf(KIND_CHAT),
        tags = mapOf("#d" to synchronized(lock) { listOf(activeEpoch.id) + pastEpochs.descendingMap().values.map { it.keys.id } }),
        since = now() - CHAT_RETENTION_SECONDS,
    )

    private fun signalFilter() = Filter(
        kinds = listOf(KIND_SIGNAL_WRAP),
        tags = mapOf("#p" to listOf(identity.devicePubkey)),
    )
}
