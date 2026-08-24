package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.KIND_ROSTER
import dev.forgesworn.kithmoot.protocol.KIND_SIGNAL_WRAP
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.Room
import dev.forgesworn.kithmoot.protocol.RosterEntry
import dev.forgesworn.kithmoot.protocol.SignalBody
import dev.forgesworn.kithmoot.protocol.TrackRef
import dev.forgesworn.kithmoot.protocol.UnwrappedSignal
import dev.forgesworn.kithmoot.protocol.decodeRosterEvent
import dev.forgesworn.kithmoot.protocol.encodeRosterEvent
import dev.forgesworn.kithmoot.protocol.unwrapSignal
import dev.forgesworn.kithmoot.protocol.wrapSignal
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.RoomTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

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
    private val random: Random = Random.Default,
) {

    private val lock = Any()
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
    private val chatSeen = mutableSetOf<String>()
    private val chatLog = mutableListOf<ChatMessage>()

    private var responseJob: Job? = null
    private val jobs = mutableListOf<Job>()
    private var joined = false

    private var tracks: List<TrackRef> = emptyList()
    private var claims: Map<String, Long> = emptyMap()

    private val _participants = MutableStateFlow<List<Participant>>(emptyList())

    /** The room as people, not as devices. */
    val participants: StateFlow<List<Participant>> = _participants.asStateFlow()

    private val _remoteDevices = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Every device we should hold a peer connection to: all the devices in the
     * roster belonging to somebody who is not us. Our own other devices are
     * excluded - connecting a laptop to its owner's phone would burn bandwidth
     * to send a person their own face.
     */
    val remoteDevices: StateFlow<Set<String>> = _remoteDevices.asStateFlow()

    private val _localRoles = MutableStateFlow(LocalRoles())
    val localRoles: StateFlow<LocalRoles> = _localRoles.asStateFlow()

    private val _chat = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chat: StateFlow<List<ChatMessage>> = _chat.asStateFlow()

    private val _signals = MutableSharedFlow<UnwrappedSignal>(replay = 0, extraBufferCapacity = 256)

    /** Negotiation traffic, already unwrapped and already checked against the roster. */
    val signals: SharedFlow<UnwrappedSignal> = _signals.asSharedFlow()

    // --- lifecycle -----------------------------------------------------------

    fun join() {
        synchronized(lock) {
            if (joined) return
            joined = true
        }
        jobs += scope.launch {
            transport.subscribe(listOf(rosterFilter())).collect(::onRosterEvent)
        }
        jobs += scope.launch {
            transport.subscribe(listOf(chatFilter())).collect(::onChatEvent)
        }
        jobs += scope.launch {
            transport.subscribe(listOf(signalFilter())).collect(::onSignalEvent)
        }
        jobs += scope.launch {
            while (true) {
                delay(timing.heartbeatIntervalMs)
                announce()
            }
        }
        jobs += scope.launch {
            while (true) {
                delay(timing.sweepIntervalMs)
                sweep()
            }
        }
        announce()
    }

    /**
     * Stands down.
     *
     * The wire format has no departure message, so this publishes a last entry
     * with no tracks and no claims - which releases the microphone immediately -
     * and lets the presence sweep remove us once the TTL lapses. A device that
     * is switched off mid-call is removed the same way, so there is only one
     * path to test.
     */
    fun leave() {
        val cancelling: List<Job>
        synchronized(lock) {
            if (!joined) return
            joined = false
            tracks = emptyList()
            claims = emptyMap()
            cancelling = jobs.toList()
            jobs.clear()
        }
        announce()
        responseJob?.cancel()
        for (job in cancelling) job.cancel()
    }

    // --- publishing ----------------------------------------------------------

    /** Says who we are and what we are publishing, right now. */
    fun announce() {
        val entry = synchronized(lock) {
            RosterEntry(
                participant = identity.participant,
                device = identity.devicePubkey,
                credential = identity.credential,
                tracks = tracks,
                claims = claims,
                updatedAt = now(),
            ).also { roster[identity.devicePubkey] = it }
        }
        transport.publish(
            encodeRosterEvent(
                entry = entry,
                roomId = room.roomId,
                roomKey = room.roomKey,
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

    fun sendChat(body: String) {
        val text = body.trim()
        if (text.isEmpty()) return
        val sentAt = now()
        val event = encodeChatEvent(
            body = text,
            participant = identity.participant,
            credential = identity.credential,
            roomId = room.roomId,
            roomKey = room.roomKey,
            deviceSecretKey = identity.deviceSecretKey,
            sentAt = sentAt,
        )
        transport.publish(event)
        // Shown at once rather than waiting for a relay to echo it back. The id
        // is the event id, so the echo is de-duplicated against this.
        ingestChat(
            ChatMessage(
                id = event.id,
                participant = identity.participant,
                device = identity.devicePubkey,
                body = text,
                sentAt = sentAt,
            ),
        )
    }

    fun sendSignal(toDevice: String, body: SignalBody) {
        transport.publish(
            wrapSignal(
                body = body,
                senderSecretKey = identity.deviceSecretKey,
                recipientPubkey = toDevice,
            ).wrap,
        )
    }

    // --- incoming ------------------------------------------------------------

    internal fun onRosterEvent(event: NostrEvent) {
        val entry = decodeRosterEvent(event, room.roomId, room.roomKey, now()) ?: return
        if (entry.device == identity.devicePubkey) return

        val respond: Boolean
        synchronized(lock) {
            val existing = roster[entry.device]
            // Strictly older is dropped; equal is accepted. Timestamps are
            // whole seconds, and an announce and the answer to it routinely
            // land inside the same second.
            if (existing != null && entry.updatedAt < existing.updatedAt) return
            roster[entry.device] = entry
            respond = respondedTo.add(entry.device)
        }
        recompute()
        if (respond) scheduleResponse()
    }

    internal fun onChatEvent(event: NostrEvent) {
        val message = decodeChatEvent(event, room.roomId, room.roomKey, now()) ?: return
        ingestChat(message)
    }

    internal fun onSignalEvent(event: NostrEvent) {
        val signal = unwrapSignal(event, identity.deviceSecretKey, room.roomId) ?: return
        val sender = synchronized(lock) { roster[signal.from] }
        // Signals from devices we cannot see in the roster are refused, and so
        // are signals from our own other devices. There is no race in the first
        // check: a device only learns our pubkey by hearing our announce, and it
        // only hears our announce because we answered its own - which means it
        // was already in our roster before it could address us.
        if (sender == null || sender.participant == identity.participant) return
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
                announce()
            }
        }
    }

    private fun sweep() {
        var changed = false
        synchronized(lock) {
            val cutoff = now() - timing.presenceTtlSeconds
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

    private fun ingestChat(message: ChatMessage) {
        synchronized(lock) {
            if (!chatSeen.add(message.id)) return
            chatLog += message
            chatLog.sortBy { it.sentAt }
            while (chatLog.size > timing.chatHistory) {
                chatSeen.remove(chatLog.removeAt(0).id)
            }
            _chat.value = chatLog.toList()
        }
    }

    private fun recompute() {
        val snapshot = synchronized(lock) { roster.values.toList() }
        val grouped = groupByParticipant(snapshot)
        _participants.value = grouped
        _remoteDevices.value = snapshot
            .filter { it.participant != identity.participant }
            .map { it.device }
            .toSet()
        val me = grouped.firstOrNull { it.participant == identity.participant }
        _localRoles.value = LocalRoles(
            holdsMic = me?.micDevice == identity.devicePubkey,
            holdsMonitor = me?.monitorDevice == identity.devicePubkey,
            micDevice = me?.micDevice,
            monitorDevice = me?.monitorDevice,
        )
    }

    // --- filters -------------------------------------------------------------

    private fun rosterFilter() = Filter(
        kinds = listOf(KIND_ROSTER),
        tags = mapOf("#d" to listOf(room.roomId)),
    )

    private fun chatFilter() = Filter(
        kinds = listOf(KIND_CHAT),
        tags = mapOf("#d" to listOf(room.roomId)),
    )

    private fun signalFilter() = Filter(
        kinds = listOf(KIND_SIGNAL_WRAP),
        tags = mapOf("#p" to listOf(identity.devicePubkey)),
    )
}
