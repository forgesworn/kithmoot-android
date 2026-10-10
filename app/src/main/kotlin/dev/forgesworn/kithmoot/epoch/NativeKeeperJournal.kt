package dev.forgesworn.kithmoot.epoch

import dev.forgesworn.kithmoot.crypto.*
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.RoomNearbyDiscovery
import dev.forgesworn.kithmoot.relay.RoomRoute
import dev.forgesworn.kithmoot.session.RoomEpochState
import dev.forgesworn.kithmoot.session.RoomSession
import dev.forgesworn.kithmoot.storage.RoomStorage
import kotlinx.serialization.json.*
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Cannot be imported from a saved host or deserialised into an old authority.
 * Signing material is transferred once, only to a new journal. */
internal class NativeKeeperCreation private constructor(private val secret: ByteArray,
    private val host: RoomInvitationHost, private val welcome: NostrEvent, val createdAt: Long) : AutoCloseable {
    val room = deriveRoom(secret).roomId
    val authority = host.invitation.canonicalInviter
    private var consumed = false
    @Synchronized fun roomSecret(): ByteArray { check(!consumed); return secret.clone() }
    @Synchronized fun invitation(): RoomInvitation {
        check(!consumed); return RoomInvitation(host.invitation.bearer.clone(), authority, true)
    }
    @Synchronized fun welcome(): NostrEvent { check(!consumed); return keeperEvent(welcome) }
    @Synchronized internal fun consume(): KeeperMaterial {
        check(!consumed) { "Keeper creation was already consumed" }
        val material = KeeperMaterial(secret.clone(), host.inviterSecretKey.clone(), host.invitation.bearer.clone(), keeperEvent(welcome))
        close(); return material
    }
    @Synchronized override fun close() {
        consumed = true; secret.fill(0); host.inviterSecretKey.fill(0); host.invitation.bearer.fill(0)
    }
    override fun toString() = "NativeKeeperCreation(room=$room, signingMaterial=<private>)"
    companion object {
        fun fresh(now: Long, roomRelays: List<String>? = null, ends: Long? = null, destruct: Boolean = false): NativeKeeperCreation {
            require(now in 0..KEEPER_MAX_TIME)
            // Signed invitation URLs have a different canonical root-path form
            // from endpoint pins. Validate the complete list before minting keys.
            val signedRelays = roomRelays?.let(::canonicalRoomRelays)
            val host = createRoomInvitation(persistent = true)
            val secret = Entropy.bytes(32)
            try { return NativeKeeperCreation(secret, host, encodePersistentInvitation(host, secret, now,
                ends = ends, relays = signedRelays, destruct = destruct), now) }
            catch (error: Exception) { secret.fill(0); host.inviterSecretKey.fill(0); host.invitation.bearer.fill(0); throw error }
        }
    }
}

internal class NativeKeeperBinding(val room: String, val authority: String, val participant: String,
    val device: String, val route: RoomRoute, relays: List<String>) {
    val relays = relays.map(::canonicalRelayUrl).sorted()
    val meshScope = if (route.nearby) RoomNearbyDiscovery.scope(room) else null
    val owner = "$room:$authority"
    init {
        require(listOf(room, authority, participant, device).all(::keeperHex))
        require(this.relays.distinct().size == this.relays.size && this.relays.size <= 8)
        require(if (route.internet) this.relays.isNotEmpty() else this.relays.isEmpty())
    }
    val pin = Digests.sha256(buildJsonObject {
        put("profile", "native-keeper-v1"); put("room", room); put("root", authority)
        put("participant", participant); put("device", device); put("route", route.stored)
        put("mesh", meshScope?.let(::JsonPrimitive) ?: JsonNull)
        put("relays", JsonArray(this@NativeKeeperBinding.relays.map(::JsonPrimitive)))
    }.toString().toByteArray(Charsets.UTF_8)).toHex()
    fun permits(lane: RekeyLane) = if (lane == RekeyLane.NEARBY) route.nearby else route.internet
}

internal enum class KeeperPhase { ACTIVE, RETIRED, CLOSED }
internal class NativeKeeperMigrationRequiredException : IllegalStateException("Native authority journal needs explicit migration")
internal class KeeperMaterial(val base: ByteArray, val signer: ByteArray, val bearer: ByteArray, val welcome: NostrEvent) {
    fun wipe() { base.fill(0); signer.fill(0); bearer.fill(0) }
}

/** Exclusive one-room signing authority. No network, subscriptions or UI are
 * opened here. All IO/signing calls belong on the controller's IO dispatcher;
 * the handoff guard alone is nonblocking and reads only in-memory state. */
internal class NativeKeeperJournal private constructor(private val storage: RoomStorage,
    val binding: NativeKeeperBinding, private val now: () -> Long,
    creation: NativeKeeperCreation?, ownerCredential: NostrEvent?) : AutoCloseable {
    data class Snapshot(val phase: KeeperPhase, val epoch: Int, val epochId: String, val high: Long,
        val revision: Long, val removed: List<String>, val members: List<String>, val cause: String?,
        val pending: List<NostrEvent>, val nearbyBytes: Int, val internetBytes: Int, val suspended: Boolean,
        val epochCause: String?)
    data class Handoff(val event: NostrEvent, val lane: RekeyLane, val attempt: Int, val pending: Boolean)
    private data class Cached(val request: NostrEvent, val answer: NostrEvent, val epoch: Int, val offers: Int,
        val lane: RekeyLane, val handed: Boolean = false)
    private data class Spend(val at: Long, val lane: RekeyLane?, val bytes: Int, val fresh: Boolean)
    private data class Pending(val events: List<NostrEvent>, val epoch: Int, val secret: ByteArray,
        val removed: List<String>, val members: List<String>, val phase: KeeperPhase, val at: Long,
        val destruct: Boolean, val queued: Set<String> = emptySet(), val attempts: Map<String, Int> = emptyMap(),
        val offered: Map<String, Int> = emptyMap())
    private data class WelcomeDelivery(val event: NostrEvent, val attempts: Int = 0, val nextAt: Long = 0, val accepted: Boolean = false)
    private data class Device(val participant: String, val device: String, val credential: NostrEvent,
        val verifiedAt: Long, val removed: Boolean = false)
    private data class Record(val epoch: Int, val secret: ByteArray, val phase: KeeperPhase, val removed: List<String>,
        val members: List<String>, val at: Long, val high: Long, val revision: Long, val destruct: Boolean,
        val cause: String?, val answers: List<Cached> = emptyList(), val spends: List<Spend> = emptyList(), val pending: Pending? = null,
        val epochCause: String? = null, val terminalPredecessor: RoomEpoch? = null,
        val courierReady: Boolean = false, val welcomeDelivery: WelcomeDelivery? = null,
        val devices: List<Device> = emptyList())
    private val lock = ReentrantLock()
    private val lease = Any()
    private lateinit var material: KeeperMaterial
    private lateinit var data: Record
    private var selected: (() -> Boolean)? = null
    private var bound = false
    private var end: Long? = null
    @Volatile private var closed = false
    @Volatile private var failed = false

    init {
        synchronized(ownerGate(binding.owner)) { check(owners.putIfAbsent(binding.owner, lease) == null) { "Keeper already has an authority owner" } }
        try {
            val bytes = storage.read()
            if (creation == null) {
                check(bytes != null) { "Keeper journal is missing; a saved host cannot reconstruct it" }
                try { restore(bytes) } finally { bytes.fill(0) }
                time() // Do not mint older answers after a clock rollback.
            } else {
                if (bytes != null) { bytes.fill(0); error("Keeper journal already exists") }
                require(creation.room == binding.room && creation.authority == binding.authority)
                val at = clock(); require(creation.createdAt in maxOf(0, at - 90)..at + 5)
                val frozenOwner = requireNotNull(retainedCredential(requireNotNull(ownerCredential)))
                val checked = verifyDeviceCredential(frozenOwner, binding.room, at) as? CredentialCheck.Valid
                require(checked?.participant == binding.participant && checked.device == binding.device)
                require(keeperBytes(frozenOwner) <= MAX_CREDENTIAL_BYTES)
                material = creation.consume()
                val welcome = requireNotNull(decodePersistentInvitation(material.welcome, invitationUnlocked()))
                end = welcome.endsAt
                data = Record(0, material.base.clone(), KeeperPhase.ACTIVE, emptyList(), listOf(binding.participant),
                    at, at, 0, welcome.destruct, null,
                    devices = listOf(Device(binding.participant, binding.device, frozenOwner, at)))
                save(data, initial = true)
            }
        } catch (error: Exception) {
            wipe(); closed = true
            synchronized(ownerGate(binding.owner)) { owners.remove(binding.owner, lease) }
            throw error
        }
    }

    fun bind(stillSelected: () -> Boolean) = lock.withLock {
        usable(); check(!bound) { "Keeper already has a dispatch owner" }; bound = true; selected = stillSelected
    }
    fun suspendExports() = lock.withLock { selected = null }
    fun invitation(): RoomInvitation = lock.withLock { usable(); invitationUnlocked() }
    private fun invitationUnlocked() = RoomInvitation(material.bearer.clone(), binding.authority, true)
    fun epoch(): RoomEpoch = lock.withLock { usable(); RoomEpoch(data.epoch, data.secret) }
    fun welcome(): NostrEvent = lock.withLock { usable(); keeperEvent(material.welcome) }
    fun snapshot(): Snapshot = lock.withLock {
        usable()
        Snapshot(data.phase, data.epoch, deriveEpoch(RoomEpoch(data.epoch, data.secret)).id, data.high, data.revision,
            data.removed.toList(), data.members.toList(), data.cause, data.pending?.events?.map(::keeperEvent).orEmpty(),
            debt(RekeyLane.NEARBY, data.high), debt(RekeyLane.INTERNET, data.high), !allowed(), data.epochCause)
    }
    fun persistenceFailed() = failed
    fun courierReady() = lock.withLock { usable(); data.courierReady }
    fun mayInitialiseReceiver() = lock.withLock {
        usable(); !data.courierReady && data.phase == KeeperPhase.ACTIVE && data.epoch == 0 &&
            data.pending == null && data.spends.isEmpty() && data.answers.isEmpty()
    }
    /** Actual empty ledger, before any dispatch. Once committed, missing state cannot regain credit. */
    fun recordCourierCreated(ledger: RoomRekeyLedger) {
        val q = ledger.binding
        require(q.room == binding.room && q.authority == binding.authority && q.device == binding.device &&
            q.route == binding.route && q.meshScope == binding.meshScope && q.relays == binding.relays)
        val actual = ledger.status()
        require(actual.suspended && actual.entries.isEmpty() && actual.expired == 0L && actual.nearbyBytes == 0 && actual.internetBytes == 0)
        lock.withLock {
            usable(); check(!bound && data.pending == null && data.epoch == 0 && data.phase == KeeperPhase.ACTIVE)
            if (!data.courierReady) save(data.copy(courierReady = true, high = time()))
        }
    }

    /** Original Internet welcome only. Debt and backoff commit before any offer. */
    fun reserveWelcome(): Handoff? = lock.withLock {
        usable(); val at = time()
        if (!allowed() || !binding.route.internet || data.phase != KeeperPhase.ACTIVE || data.pending != null || ended(at)) return@withLock null
        var kept = data.welcomeDelivery
        if (kept == null || at - kept.event.createdAt >= WELCOME_REFRESH_SECONDS) {
            val event = if (kept == null && at - material.welcome.createdAt < WELCOME_REFRESH_SECONDS) keeperEvent(material.welcome)
            else {
                if (!reserve(at, null, 0, true)) return@withLock null
                val invitation = invitationUnlocked()
                try {
                    val body = requireNotNull(decodePersistentInvitation(material.welcome, invitation))
                    try { encodePersistentInvitation(RoomInvitationHost(invitation, material.signer), material.base, at,
                        ends = body.endsAt, relays = body.relays, destruct = body.destruct) }
                    finally { body.secret.fill(0) }
                } finally { invitation.bearer.fill(0) }
            }
            kept = WelcomeDelivery(event)
        }
        if (kept.accepted || kept.attempts >= 8 || kept.nextAt > at) return@withLock null
        val next = kept.copy(attempts = kept.attempts + 1,
            nextAt = at + minOf(300L, 5L * (1L shl minOf(6, kept.attempts))))
        if (!reserve(at, RekeyLane.INTERNET, keeperBytes(next.event), false, data.copy(welcomeDelivery = next))) return@withLock null
        Handoff(keeperEvent(next.event), RekeyLane.INTERNET, next.attempts, false)
    }

    /** Only verified retained requests can become an approval card. No incoming pubkey guess. */
    fun unknownParticipants(): List<String> = lock.withLock {
        usable(); val at = time(); val baseKey = deriveRoom(material.base).roomKey
        try {
            data.answers.filter { it.epoch == data.epoch && it.request.kind == KIND_EPOCH_REQUEST && requestDeadline(it.request) > at }
                .mapNotNull { decodeEpochRequest(it.request, binding.room, material.signer, baseKey, at)?.participant }
                .filter { it !in data.members && it !in data.removed }.distinct().sorted()
        } finally { baseKey.fill(0) }
    }

    /** Cold readiness reads actual receiver owners outside the source lock.
     * A terminal agreement proves closure, never permission to host answers. */
    fun verifyReceiver(vault: EpochVault, session: RoomSession? = null): KeeperPhase {
        val expected = lock.withLock {
            usable(); time(); check(data.pending == null) { "Original source transition still needs recovery" }
            data.copy(secret = data.secret.clone(), terminalPredecessor = data.terminalPredecessor?.let { RoomEpoch(it.epoch, it.secret) })
        }
        try {
            val receiver = requireNotNull(vault.get(binding.room))
            require(receiver.stableRoom == binding.room && receiver.authority == binding.authority &&
                receiver.pending == null && receiver.removed == expected.removed)
            if (expected.phase == KeeperPhase.CLOSED) {
                val previous = requireNotNull(expected.terminalPredecessor)
                require(receiver.phase == EpochPhase.CLOSED && receiver.terminalCause == expected.epochCause &&
                    receiver.currentEpoch == previous.epoch && receiver.currentSecret.contentEquals(previous.secret))
                if (session != null) require(session.keeperProfileMatches(binding.room, binding.authority, binding.participant, binding.device) &&
                    session.epochState.value == RoomEpochState.Closed(expected.epoch))
            } else {
                require(!ended(clock())) { "Room has ended" }
                require(receiver.phase == EpochPhase.ACTIVE && receiver.activationCause == expected.epochCause &&
                    receiver.currentEpoch == expected.epoch && receiver.currentSecret.contentEquals(expected.secret))
                val live = requireNotNull(session)
                require(live.keeperProfileMatches(binding.room, binding.authority, binding.participant, binding.device))
                val keys = deriveEpoch(RoomEpoch(expected.epoch, expected.secret))
                try { require(live.keeperEpochMatches(keys, expected.removed)) }
                finally { keys.key.fill(0) }
            }
            return lock.withLock {
                usable(); time(); require(data.revision == expected.revision && data.pending == null) { "Source changed during receiver verification" }
                expected.phase
            }
        } finally { expected.secret.fill(0); expected.terminalPredecessor?.secret?.fill(0) }
    }

    fun approve(participant: String) = lock.withLock {
        writable(); require(keeperHex(participant) && participant !in data.removed)
        if (participant in data.members) return@withLock
        check(data.members.size < MAX_MEMBERS)
        val at = time()
        val qualified = data.answers.filter { it.epoch == data.epoch && it.request.kind == KIND_EPOCH_REQUEST && requestDeadline(it.request) > at }
            .mapNotNull { verifiedRequest(it.request, at) }.filter { it.request.participant == participant }
        require(qualified.isNotEmpty()) { "Approval needs a current qualified device request" }
        var devices = data.devices
        for (request in qualified) devices = requireNotNull(enrol(devices, request)) { "Device enrolment refused" }
        save(data.copy(members = (data.members + participant).sorted(), devices = devices, high = at))
    }

    private fun verifiedRequest(event: NostrEvent, at: Long): VerifiedEpochRequest? {
        val baseKey = deriveRoom(material.base).roomKey
        return try { decodeVerifiedEpochRequest(event, binding.room, material.signer, baseKey, at) }
        finally { baseKey.fill(0) }
    }

    /** Historical bindings never evict/remap; the caller persists this list
     * before any grant. Only this source's verified decoder supplies evidence. */
    private fun enrol(devices: List<Device>, qualified: VerifiedEpochRequest): List<Device>? {
        val identity = qualified.request
        val credential = retainedCredential(qualified.credential()) ?: return null
        if (identity.participant in data.removed) return null
        val previous = devices.singleOrNull { it.device == identity.device }
        if (previous != null && (previous.participant != identity.participant || previous.removed ||
                credential.createdAt < previous.credential.createdAt)) return null
        if (previous == null && devices.size >= MAX_DEVICES) return null
        val next = Device(identity.participant, identity.device, keeperEvent(credential), qualified.verifiedAt)
        return (devices.filter { it.device != identity.device } + next).sortedBy { it.device }
    }
    private fun retainedCredential(value: NostrEvent): NostrEvent? {
        if (keeperBytes(value) > MAX_CREDENTIAL_BYTES) return null
        // Use exactly the on-disk event contract now, rather than committing a
        // credential whose spelling/structure cannot survive a source reopen.
        return runCatching { event(value.toJson()) }.getOrNull()
    }

    /** A prepared answer is local durable reservation, never delivery. Replays
     * keep the same signature/expiry. Epoch changes cannot re-sign old requests. */
    fun answer(requestEvent: NostrEvent, lane: RekeyLane): Handoff? = lock.withLock {
        reserveAnswer(requestEvent, lane, KIND_INVITATION_REQUEST) { at ->
            decodeLivePersistentRequest(requestEvent, LivePersistentContext(invitationUnlocked(), binding.room), at)
                ?: return@reserveAnswer null
            if (!reserve(at, null, 0, true)) return@reserveAnswer null
            encodeLivePersistentAnswer(LivePersistentContext(invitationUnlocked(), binding.room),
                requestEvent, material.welcome, material.signer, data.epoch.toLong(), at)
        }
    }

    /** A base-room capability proves admission, not approval. Only this source
     * journal may issue the committed epoch; the receiver vault is not a signer. */
    fun answerEpoch(requestEvent: NostrEvent, lane: RekeyLane): Handoff? = lock.withLock {
        reserveAnswer(requestEvent, lane, KIND_EPOCH_REQUEST) { at ->
            val qualified = verifiedRequest(requestEvent, at) ?: return@reserveAnswer null
            val request = qualified.request
            val refused = when {
                request.participant in data.removed -> "removed"
                request.participant !in data.members -> "unknown"
                else -> null
            }
            if (refused == null) {
                val devices = enrol(data.devices, qualified) ?: return@reserveAnswer null
                if (devices != data.devices) save(data.copy(devices = devices, high = at))
            }
            if (!reserve(at, null, 0, true)) return@reserveAnswer null
            val current = if (refused == null) RoomEpoch(data.epoch, data.secret) else null
            try { encodeEpochGrant(binding.room, material.signer, request.device, request.request, at,
                epoch = current, removed = data.removed, refused = refused,
                members = if (refused == null) data.members else null, roomEnds = ends()) }
            finally { current?.secret?.fill(0) }
        }
    }

    private fun reserveAnswer(requestEvent: NostrEvent, lane: RekeyLane, kind: Int,
        freshAnswer: (Long) -> NostrEvent?): Handoff? {
        usable(); val at = time()
        if (!allowed() || !binding.permits(lane) || data.pending != null || !answerPhase(kind) || ended(at) ||
            requestEvent.kind != kind) return null
        if (keeperBytes(requestEvent) > 4096 || !reserve(at, null, 0, false)) return null
        val kept = data.answers.filter { requestDeadline(it.request) > at }
        val previous = kept.firstOrNull { it.request.id == requestEvent.id }
        if (previous != null && !epochAnswerAllowed(previous, at)) return null
        if (previous != null && (previous.request != requestEvent || previous.epoch != data.epoch || previous.offers >= 3 ||
                answerDeadline(previous) <= at)) return null
        if (previous == null && (kept.size >= MAX_ANSWERS ||
                data.spends.count { it.fresh && at - it.at <= 60 } >= 16)) return null
        val answer = previous?.answer ?: freshAnswer(at) ?: return null
        val cached = Cached(keeperEvent(requestEvent), keeperEvent(answer), data.epoch, (previous?.offers ?: 0) + 1, lane)
        if (requestDeadline(requestEvent) <= at || answerDeadline(cached) <= at) return null
        val next = data.copy(answers = kept.filter { it.request.id != requestEvent.id } + cached)
        if (!reserve(at, lane, keeperBytes(answer), false, next)) return null
        return Handoff(keeperEvent(answer), lane, cached.offers, false)
    }
    private fun answerPhase(kind: Int) = if (kind == KIND_INVITATION_REQUEST) data.phase == KeeperPhase.ACTIVE
        else kind == KIND_EPOCH_REQUEST && data.phase != KeeperPhase.CLOSED
    private fun requestDeadline(event: NostrEvent): Long = if (event.kind == KIND_INVITATION_REQUEST)
        event.tagValue("expiration")!!.toLong() else minOf(event.createdAt + EPOCH_MAX_AGE_SECONDS,
            event.tagValue("expiration")?.toLong() ?: KEEPER_MAX_TIME)
    private fun answerDeadline(cached: Cached): Long = minOf(requestDeadline(cached.request),
        if (cached.answer.kind == KIND_INVITATION_GRANT) cached.answer.tagValue("expiration")!!.toLong()
        else cached.answer.createdAt + EPOCH_MAX_AGE_SECONDS, ends() ?: KEEPER_MAX_TIME)

    /** A refreshed registry credential cannot extend an old request's authority.
     * Recheck its own credential and present membership on retry and handoff. */
    private fun epochAnswerAllowed(cached: Cached, at: Long): Boolean {
        if (cached.request.kind != KIND_EPOCH_REQUEST) return true
        val qualified = verifiedRequest(cached.request, at) ?: return false
        val request = qualified.request
        val key = Nip44.conversationKey(material.signer, request.device.hexToBytes())
        val body = try { Json.parseToJsonElement(Nip44.decrypt(cached.answer.content, key)).jsonObject }
        finally { key.fill(0) }
        if (body["refused"] != null) return true // No epoch secret was released.
        return request.participant in data.members && request.participant !in data.removed &&
            data.devices.any { it.device == request.device && it.participant == request.participant && !it.removed }
    }

    /** Signing is inside the same serialized transaction as challenge answers.
     * Nothing is returned until the exact event and next secret are committed. */
    fun prepareRekey(credentials: List<NostrEvent>, removed: List<String> = emptyList(),
        closed: Boolean = false, destruct: Boolean = false, scheduled: Boolean = false): List<NostrEvent> = lock.withLock {
        writable(); val at = time()
        require(data.epoch < MAX_EPOCH && credentials.size <= 32 && (!destruct || closed))
        require(!scheduled || removed.isEmpty() && !closed)
        val gone = keeperMembers(data.removed + removed)
        val members = data.members.filter { it !in gone }
        require(closed || binding.participant !in gone) { "The native owner cannot remove itself while hosting" }
        val eligible = data.devices.filter { !it.removed && it.participant in members }
        val devices = if (closed) emptyList() else {
            require(eligible.size <= 32 && credentials.size == eligible.size) { "Rekey needs the complete qualified device audience" }
            val supplied = credentials.map { credential ->
                val check = verifyDeviceCredential(credential, binding.room, at) as? CredentialCheck.Valid
                require(check != null && keeperHex(check.device))
                require(eligible.singleOrNull { it.device == check.device }?.let {
                    it.participant == check.participant && it.credential == credential
                } == true) { "Rekey credential needs source qualification" }
                check.device
            }
            require(supplied.distinct().size == supplied.size)
            require(supplied.toSet() == eligible.map { it.device }.toSet())
            supplied.sorted()
        }
        require(closed || binding.device in devices) { "The authority receiver needs its own successor seal" }
        val nextSecret = Entropy.bytes(32)
        try {
            val next = RoomEpoch(data.epoch + 1, nextSecret)
            val event = encodeRekeyEvent(binding.room, material.signer, deriveEpoch(RoomEpoch(data.epoch, data.secret)), next,
                devices, removed, at, closed = closed, commit = true, members = members, scheduled = scheduled, destruct = closed && (data.destruct || destruct))
            require(keeperBytes(event) <= MAX_EVENT_BYTES)
            val target = if (closed) KeeperPhase.CLOSED else data.phase
            val events = if (closed) listOf(retirement(at, true, data.destruct || destruct), event) else listOf(event)
            val pending = Pending(events, next.epoch, nextSecret.clone(), gone, members, target, at, data.destruct || destruct)
            save(data.copy(phase = target, high = at, pending = pending))
            events.map(::keeperEvent)
        } finally { nextSecret.fill(0) }
    }

    fun prepareRetirement(): NostrEvent = lock.withLock {
        writable(); check(data.phase == KeeperPhase.ACTIVE)
        val at = time(); val event = retirement(at, false, false)
        val pending = Pending(listOf(event), data.epoch, data.secret.clone(), data.removed, data.members, KeeperPhase.RETIRED, at, data.destruct)
        save(data.copy(phase = KeeperPhase.RETIRED, high = at, pending = pending)); keeperEvent(event)
    }

    /** Only for notices the controller dispatches directly. A rekey handed to
     * the separate durable courier is recorded with queued() instead. */
    fun reservePending(id: String, lane: RekeyLane): Handoff? = lock.withLock {
        usable(); val at = time(); val pending = data.pending ?: return@withLock null
        if (!allowed() || !binding.permits(lane) || ended(at)) return@withLock null
        val event = pending.events.firstOrNull { it.id == id } ?: return@withLock null
        if (id in pending.queued) return@withLock null
        val key = "${lane.name}:$id"; val attempt = (pending.attempts[key] ?: 0) + 1
        if (attempt > 8) return@withLock null
        val next = data.copy(pending = pending.copy(attempts = pending.attempts + (key to attempt)))
        if (!reserve(at, lane, keeperBytes(event), false, next)) return@withLock null
        Handoff(keeperEvent(event), lane, attempt, true)
    }
    fun pendingNeedsOffer(id: String): Boolean = lock.withLock {
        usable(); val pending = data.pending ?: return@withLock false
        require(pending.events.any { it.id == id })
        id !in pending.queued && pending.offered.keys.none { it.substringAfter(':') == id }
    }
    fun canHandoff(handoff: Handoff): Boolean {
        if (!lock.tryLock()) return false
        try {
            if (closed || failed || !allowed() || !binding.permits(handoff.lane)) return false
            val at = runCatching { clock() }.getOrNull() ?: return false
            if (at < data.high) return false
            if (ended(at) || handoff.event.tagValue("expiration")?.let { (it.toLongOrNull() ?: return false) <= at } == true) return false
            if (handoff.event.kind == KIND_GROUP_INVITATION) return data.phase == KeeperPhase.ACTIVE && data.pending == null &&
                handoff.lane == RekeyLane.INTERNET && data.welcomeDelivery?.let {
                    it.event == handoff.event && it.attempts == handoff.attempt && !it.accepted && at - it.event.createdAt < WELCOME_REFRESH_SECONDS
                } == true
            return if (handoff.pending) data.pending?.let { p -> p.events.any { it == handoff.event } &&
                handoff.event.id !in p.queued && p.attempts["${handoff.lane.name}:${handoff.event.id}"] == handoff.attempt &&
                p.offered["${handoff.lane.name}:${handoff.event.id}"] != handoff.attempt } == true
            else data.pending == null && data.answers.any {
                answerPhase(it.request.kind) && answerDeadline(it) > at && it.answer == handoff.event &&
                    it.epoch == data.epoch && it.offers == handoff.attempt && it.lane == handoff.lane && !it.handed && epochAnswerAllowed(it, at) }
        } finally { lock.unlock() }
    }
    fun offered(handoff: Handoff) = lock.withLock {
        usable()
        if (handoff.event.kind == KIND_GROUP_INVITATION) {
            val kept = data.welcomeDelivery ?: return@withLock
            require(handoff.lane == RekeyLane.INTERNET && kept.event == handoff.event && kept.attempts == handoff.attempt)
            save(data.copy(high = time(), welcomeDelivery = kept.copy(accepted = true)))
            return@withLock
        }
        if (handoff.pending) {
            val pending = data.pending ?: return@withLock
            val key = "${handoff.lane.name}:${handoff.event.id}"
            if (pending.events.none { it == handoff.event } || pending.attempts[key] != handoff.attempt) return@withLock
            save(data.copy(high = time(), pending = pending.copy(offered = pending.offered + (key to handoff.attempt))))
        } else {
            val answer = data.answers.firstOrNull { it.answer == handoff.event && it.offers == handoff.attempt && it.lane == handoff.lane }
                ?: return@withLock
            save(data.copy(high = time(), answers = data.answers.map { if (it === answer) it.copy(handed = true) else it }))
        }
    }
    /** Caller must first get successful durable admission from the actual
     * courier, not infer it from an API attempt or a retained SavedRoom. */
    fun queued(original: NostrEvent) = lock.withLock {
        usable(); val pending = requireNotNull(data.pending)
        require(original.kind == KIND_ROOM_REKEY && pending.events.any { it == original })
        save(data.copy(high = time(), pending = pending.copy(queued = pending.queued + original.id)))
    }

    /** Reads both real receiver owners outside the authority lock. Their exact
     * agreement, plus recorded local handoffs, is required before new answers. */
    fun completePending(vault: EpochVault, session: RoomSession): Boolean {
        val expected = lock.withLock { usable(); data.pending ?: return false }
        val receiver = requireNotNull(vault.get(binding.room))
        require(session.keeperProfileMatches(binding.room, binding.authority, binding.participant, binding.device))
        val live = session.epochKeys(); val livePhase = session.epochState.value
        val rekey = expected.events.singleOrNull { it.kind == KIND_ROOM_REKEY }
        if (rekey != null && expected.phase != KeeperPhase.CLOSED) require(receiver.activationCause == rekey.id && session.keeperAppliedRekey(rekey, receiver.activationCause))
        return lock.withLock {
            usable(); check(allowed()); val pending = data.pending ?: return@withLock false
            require(pending.events == expected.events && pending.epoch == expected.epoch && pending.secret.contentEquals(expected.secret))
            require(pending.queued + pending.offered.keys.map { it.substringAfter(':') } == pending.events.map { it.id }.toSet())
            require(receiver.stableRoom == binding.room && receiver.authority == binding.authority && receiver.pending == null)
            require(receiver.removed == pending.removed)
            if (pending.phase == KeeperPhase.CLOSED) {
                val cause = pending.events.single { it.kind == KIND_ROOM_REKEY }.id
                require(receiver.phase == EpochPhase.CLOSED && receiver.terminalCause == cause &&
                    receiver.currentEpoch == data.epoch && receiver.currentSecret.contentEquals(data.secret) && livePhase is RoomEpochState.Closed && livePhase.epoch == pending.epoch)
            } else {
                val keys = deriveEpoch(RoomEpoch(pending.epoch, pending.secret))
                require(receiver.phase == EpochPhase.ACTIVE && receiver.currentEpoch == pending.epoch && receiver.currentSecret.contentEquals(pending.secret))
                require(livePhase is RoomEpochState.Active && live.epoch == keys.epoch && live.id == keys.id && live.key.contentEquals(keys.key))
                if (rekey == null) require(receiver.activationCause == data.epochCause)
            }
            val cause = pending.events.last().id
            save(data.copy(epoch = pending.epoch, secret = pending.secret.clone(), phase = pending.phase, removed = pending.removed,
                members = pending.members, at = pending.at, high = time(), destruct = pending.destruct, cause = cause, pending = null,
                epochCause = rekey?.id ?: data.epochCause,
                devices = data.devices.map { it.copy(removed = it.participant in pending.removed) },
                terminalPredecessor = if (pending.phase == KeeperPhase.CLOSED) RoomEpoch(data.epoch, data.secret) else null))
            expected.secret.fill(0); true
        }
    }

    private fun retirement(at: Long, ended: Boolean, destruct: Boolean): NostrEvent = Events.sign(material.signer,
        KIND_INVITATION_RETIREMENT, at, buildList {
            add(listOf("d", deriveInvitationId(invitationUnlocked())))
            ends()?.let { add(listOf("expiration", it.toString())) }
        }, buildJsonObject { put("v", 1); if (ended) put("ended", true); if (destruct) put("destruct", true) }.toString())
    private fun ends() = end
    private fun ended(at: Long) = ends()?.let { at >= it } == true
    private fun allowed() = runCatching { selected?.invoke() == true }.getOrDefault(false)
    private fun clock() = now().also { require(it in 0..KEEPER_MAX_TIME) }
    private fun time() = clock().also { require(it >= data.high) { "Keeper clock moved backwards" } }
    private fun usable() { check(!closed && !failed) { "Keeper is closed or persistence failed" } }
    private fun writable() { usable(); check(allowed() && data.pending == null && data.phase != KeeperPhase.CLOSED && !ended(time())) }
    private fun debt(lane: RekeyLane, at: Long) = data.spends.filter { it.lane == lane && at - it.at <= window(lane) }.sumOf { it.bytes }
    private fun reserve(at: Long, lane: RekeyLane?, bytes: Int, fresh: Boolean, next: Record = data): Boolean {
        val spent = data.spends.filter { at - it.at <= window(it.lane) }
        if (spent.size >= MAX_SPENDS || lane == null && !fresh && spent.count { it.lane == null && !it.fresh } >= 64 ||
            fresh && spent.count { it.fresh && at - it.at <= 60 } >= 16 ||
            lane != null && debt(lane, at) + bytes > if (lane == RekeyLane.NEARBY) 16 * 1024 else 64 * 1024) return false
        require(bytes in 0..MAX_EVENT_BYTES)
        save(next.copy(high = at, spends = spent + Spend(at, lane, bytes, fresh))); return true
    }
    private fun save(next: Record, initial: Boolean = false) {
        usable()
        val committed = next.copy(revision = if (initial) 0 else data.revision + 1)
        var bytes: ByteArray? = null
        try {
            require(committed.revision in 0..KEEPER_MAX_TIME)
            bytes = encode(committed).toString().toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_FILE_BYTES); storage.write(bytes)
            val old = data; data = committed
            if (old.secret !== committed.secret) old.secret.fill(0)
            if (old.pending?.secret !== committed.pending?.secret) old.pending?.secret?.fill(0)
            if (old.terminalPredecessor !== committed.terminalPredecessor) old.terminalPredecessor?.secret?.fill(0)
        } catch (error: Exception) {
            failed = true; selected = null
            if (committed.secret !== data.secret) committed.secret.fill(0)
            if (committed.pending?.secret !== data.pending?.secret) committed.pending?.secret?.fill(0)
            if (committed.terminalPredecessor !== data.terminalPredecessor) committed.terminalPredecessor?.secret?.fill(0)
            throw error
        }
        finally { bytes?.fill(0) }
    }
    override fun close() = lock.withLock {
        if (!closed) { closed = true; selected = null; wipe(); synchronized(ownerGate(binding.owner)) { owners.remove(binding.owner, lease) } }
    }
    private fun wipe() {
        if (::material.isInitialized) material.wipe()
        if (::data.isInitialized) { data.secret.fill(0); data.pending?.secret?.fill(0); data.terminalPredecessor?.secret?.fill(0) }
    }

    private fun encode(record: Record): JsonObject = buildJsonObject {
        put("v", 4); put("pin", binding.pin); put("base", material.base.toHex()); put("signer", material.signer.toHex())
        put("bearer", material.bearer.toHex()); put("welcome", material.welcome.toJson())
        put("epoch", record.epoch); put("secret", record.secret.toHex()); put("phase", record.phase.name)
        put("removed", strings(record.removed)); put("members", strings(record.members)); put("at", record.at)
        put("high", record.high); put("revision", record.revision); put("destruct", record.destruct)
        put("cause", record.cause?.let(::JsonPrimitive) ?: JsonNull)
        put("epochCause", record.epochCause?.let(::JsonPrimitive) ?: JsonNull)
        put("courierReady", record.courierReady)
        put("devices", buildJsonArray { record.devices.forEach { add(buildJsonObject {
            put("participant", it.participant); put("device", it.device); put("credential", it.credential.toJson())
            put("verifiedAt", it.verifiedAt); put("removed", it.removed)
        }) } })
        put("welcomeDelivery", record.welcomeDelivery?.let { delivery -> buildJsonObject {
            put("event", delivery.event.toJson()); put("attempts", delivery.attempts)
            put("nextAt", delivery.nextAt); put("accepted", delivery.accepted)
        } } ?: JsonNull)
        put("terminalPredecessor", record.terminalPredecessor?.let { previous -> buildJsonObject {
            put("epoch", previous.epoch); put("secret", previous.secret.toHex())
        } } ?: JsonNull)
        put("answers", buildJsonArray { record.answers.forEach { add(buildJsonObject {
            put("request", it.request.toJson()); put("answer", it.answer.toJson()); put("epoch", it.epoch); put("offers", it.offers)
            put("lane", it.lane.name); put("handed", it.handed)
        }) } })
        put("spends", buildJsonArray { record.spends.forEach { add(buildJsonObject {
            put("at", it.at); put("lane", it.lane?.name?.let(::JsonPrimitive) ?: JsonNull); put("bytes", it.bytes); put("fresh", it.fresh)
        }) } })
        put("pending", record.pending?.let { p -> buildJsonObject {
            put("events", JsonArray(p.events.map { it.toJson() })); put("epoch", p.epoch); put("secret", p.secret.toHex())
            put("removed", strings(p.removed)); put("members", strings(p.members)); put("phase", p.phase.name); put("at", p.at)
            put("destruct", p.destruct); put("queued", strings(p.queued.sorted()))
            put("attempts", buildJsonObject { p.attempts.toSortedMap().forEach { (key, value) -> put(key, value) } })
            put("offered", buildJsonObject { p.offered.toSortedMap().forEach { (key, value) -> put(key, value) } })
        } } ?: JsonNull)
    }
    private fun restore(bytes: ByteArray) {
        require(bytes.size <= MAX_FILE_BYTES)
        val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        if (integer(root, "v") != 4) throw NativeKeeperMigrationRequiredException()
        require(root.keys == setOf("v", "pin", "base", "signer", "bearer", "welcome", "epoch", "secret", "phase", "removed",
            "members", "at", "high", "revision", "destruct", "cause", "epochCause", "terminalPredecessor", "answers", "spends", "pending", "courierReady", "welcomeDelivery", "devices"))
        require(text(root, "pin") == binding.pin)
        material = KeeperMaterial(secret(root, "base"), secret(root, "signer"), secret(root, "bearer"), event(root.getValue("welcome")))
        require(deriveRoom(material.base).roomId == binding.room && Schnorr.publicKeyHex(material.signer) == binding.authority)
        val welcome = requireNotNull(decodePersistentInvitation(material.welcome, invitationUnlocked()))
        end = welcome.endsAt
        require(welcome.secret.contentEquals(material.base))
        val epoch = integer(root, "epoch"); val phase = KeeperPhase.valueOf(text(root, "phase")); val current = secret(root, "secret")
        val removed = members(root, "removed"); val known = members(root, "members")
        val at = long(root, "at"); val high = long(root, "high"); val revision = long(root, "revision")
        require(epoch in 0..MAX_EPOCH && high in 0..KEEPER_MAX_TIME && at in 0..high && revision in 0..KEEPER_MAX_TIME)
        require(known.none { it in removed })
        val devices = root.getValue("devices").jsonArray.also { require(it.size in 1..MAX_DEVICES) }.map { raw ->
            val obj = raw.jsonObject
            require(obj.keys == setOf("participant", "device", "credential", "verifiedAt", "removed"))
            val credential = event(obj.getValue("credential")); require(keeperBytes(credential) <= MAX_CREDENTIAL_BYTES)
            val verifiedAt = long(obj, "verifiedAt"); require(verifiedAt in 0..high)
            val checked = verifyDeviceCredential(credential, binding.room, verifiedAt) as? CredentialCheck.Valid
            val participant = text(obj, "participant"); val device = text(obj, "device")
            require(checked?.participant == participant && checked.device == device && keeperHex(participant) && keeperHex(device))
            val tombstone = boolean(obj, "removed")
            require(tombstone == (participant in removed) && (participant in known || participant in removed))
            Device(participant, device, keeperEvent(credential), verifiedAt, tombstone)
        }
        require(devices.map { it.device } == devices.map { it.device }.distinct().sorted())
        require(devices.any { it.device == binding.device && it.participant == binding.participant })
        require(known.all { p -> devices.any { it.participant == p && !it.removed } })
        if (epoch == 0) require(current.contentEquals(material.base))
        val cause = if (root.getValue("cause") == JsonNull) null else text(root, "cause").also { require(keeperHex(it)) }
        require(epoch == 0 || cause != null)
        val epochCause = if (root.getValue("epochCause") == JsonNull) null else text(root, "epochCause").also { require(keeperHex(it)) }
        require((epoch == 0) == (epochCause == null))
        val predecessor = root.getValue("terminalPredecessor").takeUnless { it == JsonNull }?.jsonObject?.let {
            require(it.keys == setOf("epoch", "secret")); RoomEpoch(integer(it, "epoch"), secret(it, "secret"))
        }
        val destruct = boolean(root, "destruct"); require(!welcome.destruct || destruct)
        val answers = root.getValue("answers").jsonArray.also { require(it.size <= MAX_ANSWERS) }.map { raw ->
            val obj = raw.jsonObject; require(obj.keys == setOf("request", "answer", "epoch", "offers", "lane", "handed"))
            Cached(event(obj.getValue("request")), event(obj.getValue("answer")), integer(obj, "epoch"), integer(obj, "offers"),
                RekeyLane.valueOf(text(obj, "lane")), boolean(obj, "handed"))
        }
        val spends = root.getValue("spends").jsonArray.also { require(it.size <= MAX_SPENDS) }.map { raw ->
            val obj = raw.jsonObject; require(obj.keys == setOf("at", "lane", "bytes", "fresh"))
            val lane = obj.getValue("lane").takeUnless { it == JsonNull }?.jsonPrimitive?.let { require(it.isString); RekeyLane.valueOf(it.content) }
            Spend(long(obj, "at"), lane, integer(obj, "bytes"), boolean(obj, "fresh")).also {
                require(it.at in 0..high && it.bytes in 0..MAX_EVENT_BYTES && (if (lane == null) it.bytes == 0 else binding.permits(lane) && it.bytes > 0 && !it.fresh))
            }
        }
        val pending = root.getValue("pending").takeUnless { it == JsonNull }?.jsonObject?.let { p ->
            require(p.keys == setOf("events", "epoch", "secret", "removed", "members", "phase", "at", "destruct", "queued", "attempts", "offered"))
            val events = p.getValue("events").jsonArray.map(::event); require(events.size in 1..2)
            val attempts = p.getValue("attempts").jsonObject.mapValues { (_, value) -> value.jsonPrimitive.also { require(!it.isString) }.int }
            val offered = p.getValue("offered").jsonObject.mapValues { (_, value) -> value.jsonPrimitive.also { require(!it.isString) }.int }
            val queued = members(p, "queued", max = 2).toSet()
            Pending(events, integer(p, "epoch"), secret(p, "secret"), members(p, "removed"), members(p, "members"),
                KeeperPhase.valueOf(text(p, "phase")), long(p, "at"), boolean(p, "destruct"), queued, attempts, offered)
        }
        require(if (phase == KeeperPhase.CLOSED && pending == null) predecessor != null && predecessor.epoch == epoch - 1 && cause == epochCause else predecessor == null)
        val courierReady = boolean(root, "courierReady")
        val delivery = root.getValue("welcomeDelivery").takeUnless { it == JsonNull }?.jsonObject?.let { obj ->
            require(binding.route.internet && obj.keys == setOf("event", "attempts", "nextAt", "accepted"))
            val value = WelcomeDelivery(event(obj.getValue("event")), integer(obj, "attempts"), long(obj, "nextAt"), boolean(obj, "accepted"))
            require(value.attempts in 1..8 && value.nextAt in 0..high + 300 && value.event.createdAt in material.welcome.createdAt..high)
            val body = requireNotNull(decodePersistentInvitation(value.event, invitationUnlocked()))
            try { require(body.secret.contentEquals(material.base) && body.endsAt == welcome.endsAt &&
                body.relays == welcome.relays && body.destruct == welcome.destruct) }
            finally { body.secret.fill(0) }
            value
        }
        welcome.secret.fill(0)
        data = Record(epoch, current, phase, removed, known, at, high, revision, destruct, cause, answers, spends, pending, epochCause, predecessor, courierReady, delivery, devices)
        require(answers.map { it.request.id }.distinct().size == answers.size)
        answers.forEach(::validateAnswer)
        pending?.let(::validatePending)
        require(phase != KeeperPhase.CLOSED || pending != null || epoch > 0)
        require(spends.count { it.lane == null && !it.fresh && high - it.at <= 60 } <= 64 && spends.count { it.fresh && high - it.at <= 60 } <= 16)
        for (lane in RekeyLane.entries) require(debt(lane, high) <= if (lane == RekeyLane.NEARBY) 16 * 1024 else 64 * 1024)
    }
    private fun validateAnswer(c: Cached) {
        require(c.epoch in 0..data.epoch && c.offers in 1..3 && binding.permits(c.lane))
        require(c.request.createdAt <= data.high + 5 && c.answer.createdAt <= data.high && requestDeadline(c.request) > c.answer.createdAt)
        if (c.request.kind == KIND_EPOCH_REQUEST) { validateEpochAnswer(c); return }
        require(c.request.kind == KIND_INVITATION_REQUEST)
        val request = requireNotNull(decodeLivePersistentRequest(c.request, LivePersistentContext(invitationUnlocked(), binding.room), c.answer.createdAt))
        require(c.answer.kind == KIND_INVITATION_GRANT && c.answer.pubkey == binding.authority && Events.verify(c.answer))
        require(c.answer.tags == listOf(listOf("d", deriveInvitationId(invitationUnlocked())), listOf("p", request.requester),
            listOf("expiration", minOf(request.expiresAt, c.answer.createdAt + 30).toString())))
        val key = Nip44.conversationKey(material.signer, request.requester.hexToBytes())
        val body = try { Nip44.decrypt(c.answer.content, key) } finally { key.fill(0) }
        val expected = buildJsonObject {
            put("v", 1); put("profile", "persistent-live"); put("request", request.requestId); put("room", binding.room); put("epoch", c.epoch)
            put("invitation", buildJsonObject {
                val w = material.welcome
                put("id", w.id); put("pubkey", w.pubkey); put("created_at", w.createdAt); put("kind", w.kind)
                put("tags", JsonArray(w.tags.map { strings(it) })); put("content", w.content); put("sig", w.sig)
            })
        }.toString()
        require(body == expected)
    }
    private fun validateEpochAnswer(c: Cached) {
        val baseKey = deriveRoom(material.base).roomKey
        val request = try { requireNotNull(decodeEpochRequest(c.request, binding.room, material.signer, baseKey, c.answer.createdAt)) }
            finally { baseKey.fill(0) }
        require(c.answer.kind == KIND_EPOCH_GRANT && c.answer.pubkey == binding.authority && Events.verify(c.answer))
        require(c.answer.tags == withRoomExpiration(listOf(listOf("d", binding.room), listOf("p", request.device)), ends()))
        val key = Nip44.conversationKey(material.signer, request.device.hexToBytes())
        val plain = try { Nip44.decrypt(c.answer.content, key) } finally { key.fill(0) }
        val body = Json.parseToJsonElement(plain).jsonObject
        require(integer(body, "v") == 1 && text(body, "request") == request.request)
        val refused = body["refused"]?.let { text(body, "refused") }
        val expected = if (refused != null) {
            require(refused == "removed" || refused == "unknown")
            if (refused == "removed") require(request.participant in data.removed)
            buildJsonObject { put("v", 1); put("request", request.request); put("refused", refused) }
        } else {
            require(data.devices.any { it.device == request.device && it.participant == request.participant })
            require(integer(body, "epoch") == c.epoch)
            val removed = members(body, "removed"); val known = members(body, "members")
            require(request.participant in known && request.participant !in removed && known.none { it in removed })
            require(known.all { it in data.members || it in data.removed })
            if (c.epoch == data.epoch) require(removed == data.removed)
            val encodedSecret = if (c.epoch > 0) text(body, "secret") else null
            if (encodedSecret != null) {
                val secret = Base64.getUrlDecoder().decode(encodedSecret)
                try {
                    require(secret.size == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(secret) == encodedSecret)
                    if (c.epoch == data.epoch) require(secret.contentEquals(data.secret))
                } finally { secret.fill(0) }
            }
            buildJsonObject {
                put("v", 1); put("request", request.request); put("epoch", c.epoch)
                encodedSecret?.let { put("secret", it) }; put("removed", strings(removed)); put("members", strings(known))
            }
        }
        require(plain == expected.toString())
    }
    private fun validatePending(p: Pending) {
        require(p.phase == data.phase && p.at in data.at..data.high && p.queued.all { id -> p.events.any { it.id == id && it.kind == KIND_ROOM_REKEY } })
        require(p.events.map { it.id }.distinct().size == p.events.size && p.members.none { it in p.removed })
        require(!data.destruct || p.destruct)
        for ((key, attempts) in p.attempts) {
            val lane = RekeyLane.valueOf(key.substringBefore(':')); val id = key.substringAfter(':')
            require(binding.permits(lane) && p.events.any { it.id == id } && attempts in 1..8)
        }
        require(p.offered.all { (key, attempt) -> attempt in 1..(p.attempts[key] ?: 0) })
        val rekey = p.events.singleOrNull { it.kind == KIND_ROOM_REKEY }
        if (rekey == null) {
            require(p.phase == KeeperPhase.RETIRED && p.events.size == 1 && p.epoch == data.epoch && p.secret.contentEquals(data.secret) &&
                p.removed == data.removed && p.members == data.members && p.destruct == data.destruct)
        } else {
            val previous = RoomEpoch(data.epoch, data.secret); val keys = deriveEpoch(previous)
            try {
                val evidence = requireNotNull(readRekeyEvidence(rekey, binding.room, binding.authority, data.epoch, keys.key))
                require(p.epoch == evidence.epoch && evidence.commit == epochCommitment(binding.room, p.epoch, p.secret))
                require(evidence.closed == (p.phase == KeeperPhase.CLOSED) && evidence.members == p.members &&
                    p.removed == keeperMembers(data.removed + evidence.removed) && rekey.createdAt == p.at)
                require(p.destruct == (data.destruct || evidence.destruct))
                val eligible = data.devices.filter { !it.removed && it.participant in p.members }
                val recipients = if (evidence.closed) emptySet() else eligible.map { it.device }.toSet()
                val body = Json.parseToJsonElement(Nip44.decrypt(rekey.content, keys.key)).jsonObject
                require(body.getValue("keys").jsonObject.keys == recipients)
                if (!evidence.closed) eligible.forEach {
                    val checked = verifyDeviceCredential(it.credential, binding.room, p.at) as? CredentialCheck.Valid
                    require(checked?.participant == it.participant && checked.device == it.device)
                }
            } finally { previous.secret.fill(0); keys.key.fill(0) }
        }
        val retirements = p.events.filter { it.kind == KIND_INVITATION_RETIREMENT }
        require(p.events.all { it.kind == KIND_INVITATION_RETIREMENT || it.kind == KIND_ROOM_REKEY })
        require(retirements.size == if (p.phase == KeeperPhase.CLOSED || rekey == null) 1 else 0)
        retirements.forEach {
            require(decodeInvitationRetirement(it, invitationUnlocked()) && it.createdAt == p.at)
            val body = buildJsonObject { put("v", 1); if (p.phase == KeeperPhase.CLOSED) put("ended", true); if (p.phase == KeeperPhase.CLOSED && p.destruct) put("destruct", true) }.toString()
            require(it.content == body && it.tags == buildList {
                add(listOf("d", deriveInvitationId(invitationUnlocked()))); ends()?.let { end -> add(listOf("expiration", end.toString())) }
            })
        }
    }
    companion object {
        const val MAX_FILE_BYTES = 2 * 1024 * 1024
        const val MAX_EVENT_BYTES = 16 * 1024
        private const val MAX_MEMBERS = 128
        private const val MAX_ANSWERS = 128
        private const val MAX_SPENDS = 256
        private const val MAX_DEVICES = 128
        private const val MAX_CREDENTIAL_BYTES = 4096
        private const val WELCOME_REFRESH_SECONDS = 6 * 60 * 60L
        private val owners = ConcurrentHashMap<String, Any>()
        private val gates = ConcurrentHashMap<String, Any>()
        private fun ownerGate(owner: String) = gates.computeIfAbsent(owner) { Any() }
        internal fun hasActiveOwners() = owners.isNotEmpty()
        fun <T> withInactiveOwner(owner: String, action: () -> T): T = synchronized(ownerGate(owner)) {
            check(!owners.containsKey(owner)); action()
        }
        fun create(storage: RoomStorage, binding: NativeKeeperBinding, creation: NativeKeeperCreation,
            ownerCredential: NostrEvent, now: () -> Long = { System.currentTimeMillis() / 1000 }) =
            NativeKeeperJournal(storage, binding, now, creation, ownerCredential)
        fun open(storage: RoomStorage, binding: NativeKeeperBinding, now: () -> Long = { System.currentTimeMillis() / 1000 }) =
            NativeKeeperJournal(storage, binding, now, null, null)
        private fun window(lane: RekeyLane?) = if (lane == RekeyLane.NEARBY) 62 * 60L else 60L
        private fun strings(value: List<String>) = JsonArray(value.map(::JsonPrimitive))
        private fun text(obj: JsonObject, name: String) = obj.getValue(name).jsonPrimitive.also { require(it.isString) }.content
        private fun long(obj: JsonObject, name: String) = obj.getValue(name).jsonPrimitive.also { require(!it.isString) }.long
        private fun integer(obj: JsonObject, name: String) = long(obj, name).also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) }.toInt()
        private fun boolean(obj: JsonObject, name: String) = obj.getValue(name).jsonPrimitive.let { require(!it.isString); requireNotNull(it.booleanOrNull) }
        private fun secret(obj: JsonObject, name: String) = text(obj, name).also { require(keeperHex(it)) }.hexToBytes()
        private fun members(obj: JsonObject, name: String, max: Int = MAX_MEMBERS): List<String> =
            obj.getValue(name).jsonArray.map { it.jsonPrimitive.also { p -> require(p.isString) }.content }.also {
                require(it.size <= max && it == keeperMembers(it))
            }
        private fun event(raw: JsonElement): NostrEvent {
            val obj = raw.jsonObject
            require(obj.keys == setOf("kind", "created_at", "tags", "content", "pubkey", "id", "sig"))
            require(keeperHex(text(obj, "id")) && keeperHex(text(obj, "pubkey")) && Regex("[0-9a-f]{128}").matches(text(obj, "sig")))
            integer(obj, "kind"); require(long(obj, "created_at") in 0..KEEPER_MAX_TIME); text(obj, "content")
            require(obj.getValue("tags").jsonArray.all { tag -> tag.jsonArray.all { it.jsonPrimitive.isString } })
            return NostrEvent.fromJson(obj).also { require(keeperBytes(it) <= MAX_EVENT_BYTES && Events.verify(it)) }
        }
    }
}

private const val KEEPER_MAX_TIME = 9_007_199_254_740_000L
private fun keeperHex(value: String) = Regex("[0-9a-f]{64}").matches(value)
private fun keeperMembers(keys: List<String>) = keys.also { require(it.size <= 128 && it.all(::keeperHex)) }.distinct().sorted()
private fun keeperBytes(event: NostrEvent) = event.toCompactJson().toByteArray(Charsets.UTF_8).size
private fun keeperEvent(event: NostrEvent) = event.copy(tags = event.tags.map { it.toList() })
