package dev.forgesworn.kithmoot.epoch

import dev.forgesworn.kithmoot.crypto.*
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.RoomNearbyDiscovery
import dev.forgesworn.kithmoot.relay.RoomRoute
import dev.forgesworn.kithmoot.session.RoomEpochState
import dev.forgesworn.kithmoot.session.RoomSession
import dev.forgesworn.kithmoot.storage.RoomStorage
import dev.forgesworn.kithmoot.storage.SavedRoom
import dev.forgesworn.kithmoot.storage.NativeKeeperReference
import dev.forgesworn.kithmoot.storage.RoomRepository
import kotlinx.serialization.json.*
import java.util.Base64
import java.util.Collections
import java.util.IdentityHashMap
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
    @Synchronized internal fun consume(): KeeperSeed {
        check(!consumed) { "Keeper creation was already consumed" }
        val seed = KeeperSeed(KeeperMaterial(secret.clone(), host.inviterSecretKey.clone()),
            host.invitation.bearer.clone(), keeperEvent(welcome))
        close(); return seed
    }
    @Synchronized override fun close() {
        consumed = true; secret.fill(0); host.inviterSecretKey.fill(0); host.invitation.bearer.fill(0)
    }
    override fun toString() = "NativeKeeperCreation(room=$room, signingMaterial=<private>)"
    companion object {
        fun fresh(now: Long, roomRelays: List<String>? = null, ends: Long? = null, destruct: Boolean = false): NativeKeeperCreation =
            freshChecked(now, roomRelays, ends, destruct,
                { createRoomInvitation(persistent = true) }, { Entropy.bytes(32) })

        /** Only fresh() can supply minting sources. No application caller may
         * import existing signing/base material through this private gate. */
        private fun freshChecked(now: Long, roomRelays: List<String>?, ends: Long?, destruct: Boolean,
            createHost: () -> RoomInvitationHost, createSecret: () -> ByteArray): NativeKeeperCreation {
            require(now in 0..KEEPER_MAX_TIME)
            // Signed invitation URLs have a different canonical root-path form
            // from endpoint pins. Validate the complete list before minting keys.
            val signedRelays = roomRelays?.let(::canonicalRoomRelays)
            // A signed envelope that cannot decode must never mint an authority.
            ends?.let { require(it in 1..((1L shl 53) - 1)) { "room end must be a positive safe integer" } }
            require(persistentInvitationEventBytes(now, ends, signedRelays, destruct) <= NativeKeeperJournal.MAX_EVENT_BYTES) {
                "Invitation event exceeds source bound"
            }
            val host = createHost()
            var secret: ByteArray? = null
            try {
                val base = createSecret().also { secret = it }
                require(base.size == 32)
                return NativeKeeperCreation(base, host, encodePersistentInvitation(host, base, now,
                    ends = ends, relays = signedRelays, destruct = destruct), now)
            } catch (error: Exception) {
                secret?.fill(0); host.inviterSecretKey.fill(0); host.invitation.bearer.fill(0); throw error
            }
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
internal class NativeRekeyRefusedException(cause: Exception) : IllegalStateException("Native rekey refused before signing", cause)
internal class KeeperMaterial(val base: ByteArray, val signer: ByteArray) {
    fun wipe() { base.fill(0); signer.fill(0) }
}
/** Fresh material transfers once; the durable Record then owns the invitation. */
internal class KeeperSeed(val material: KeeperMaterial, val bearer: ByteArray, val welcome: NostrEvent)

/** Exclusive one-room signing authority. No network, subscriptions or UI are
 * opened here. All IO/signing calls belong on the controller's IO dispatcher;
 * the handoff guard alone is nonblocking and reads only in-memory state. */
internal class NativeKeeperJournal private constructor(private val storage: RoomStorage,
    val binding: NativeKeeperBinding, private val now: () -> Long,
    creation: NativeKeeperCreation?, ownerCredential: NostrEvent?) : AutoCloseable {
    data class Snapshot(val phase: KeeperPhase, val epoch: Int, val epochId: String, val high: Long,
        val revision: Long, val removed: List<String>, val members: List<String>, val cause: String?,
        val pending: List<NostrEvent>, val nearbyBytes: Int, val internetBytes: Int, val suspended: Boolean,
        val epochCause: String?, val retirementOriginals: List<String> = emptyList(), val missingRetirementSlots: Int = 0,
        val deviceCount: Int = 0, val invitationGeneration: Int = 0,
        val replacement: ReplacementStatus? = null)
    data class Handoff(val event: NostrEvent, val lane: RekeyLane, val attempt: Int, val pending: Boolean,
        val archived: Boolean = false, val invitationGeneration: Int? = null, val replacement: Boolean = false)
    enum class ReplacementStage { ORIGINALS_RETAINED, NOTICE_ARCHIVED, INDEX_VERIFIED }
    data class ReplacementStatus(val previousGeneration: Int, val proposedGeneration: Int,
        val previousInvitation: String, val proposedInvitation: String, val retirement: String,
        val welcome: String, val stage: ReplacementStage)
    /** A constructed or foreign handle has no authority: only the source's
     * retained instance can be consumed, once, at its exact revision. */
    class RekeyProposal internal constructor()
    class RetirementProposal internal constructor()
    class ReplacementProposal internal constructor()
    private data class ReplacementPlan(val handle: ReplacementProposal, val revision: Long,
        val epoch: Int, val phase: KeeperPhase, val generation: Int, val reference: JsonObject, val joinUrl: String)
    private data class RetirementPlan(val handle: RetirementProposal, val revision: Long, val epoch: Int, val phase: KeeperPhase)
    private data class RekeyPlan(val handle: RekeyProposal, val revision: Long, val epoch: Int, val phase: KeeperPhase,
        val credentials: List<NostrEvent>, val removed: List<String>, val closed: Boolean,
        val destruct: Boolean, val scheduled: Boolean)
    private data class RekeyAudience(val gone: List<String>, val members: List<String>, val devices: List<String>)
    private data class Cached(val request: NostrEvent, val answer: NostrEvent, val epoch: Int, val offers: Int,
        val lane: RekeyLane, val handed: Boolean = false, val generation: Int = 0)
    private data class Spend(val at: Long, val lane: RekeyLane?, val bytes: Int, val fresh: Boolean)
    private data class Pending(val events: List<NostrEvent>, val epoch: Int, val secret: ByteArray,
        val removed: List<String>, val members: List<String>, val phase: KeeperPhase, val at: Long,
        val destruct: Boolean, val queued: Set<String> = emptySet(), val attempts: Map<String, Int> = emptyMap(),
        val offered: Map<String, Int> = emptyMap())
    private data class WelcomeDelivery(val event: NostrEvent, val attempts: Int = 0, val nextAt: Long = 0, val accepted: Boolean = false)
    /** One invitation truth, with its delivery counters in the same commit.
     * Schema 4/5 still represent exactly their original generation zero. */
    private data class InvitationState(val bearer: ByteArray, val welcome: NostrEvent,
        val delivery: WelcomeDelivery? = null, val generation: Int = 0)
    private data class InvitationHistory(val invitation: InvitationState, val retirement: String)
    private data class Replacement(val proposed: InvitationState, val previousReference: JsonObject,
        val previousJoinUrl: String, val proposedReference: JsonObject, val proposedJoinUrl: String,
        val retirement: NostrEvent, val alreadyArchived: Boolean,
        val stage: ReplacementStage = ReplacementStage.ORIGINALS_RETAINED,
        val attempts: Map<String, Int> = emptyMap(), val offered: Map<String, Int> = emptyMap(),
        val welcomeNextAt: Long = 0)
    private data class Device(val participant: String, val device: String, val credential: NostrEvent,
        val verifiedAt: Long, val removed: Boolean = false)
    private data class Retirement(val event: NostrEvent, val invitation: String, val epoch: Int,
        val attempts: Map<String, Int>, val offered: Map<String, Int>)
    private data class Record(val epoch: Int, val secret: ByteArray, val phase: KeeperPhase, val removed: List<String>,
        val members: List<String>, val at: Long, val high: Long, val revision: Long, val destruct: Boolean,
        val cause: String?, val invitation: InvitationState, val answers: List<Cached> = emptyList(), val spends: List<Spend> = emptyList(), val pending: Pending? = null,
        val epochCause: String? = null, val terminalPredecessor: RoomEpoch? = null,
        val courierReady: Boolean = false,
        val devices: List<Device> = emptyList(), val retirements: List<Retirement> = emptyList(),
        val legacyRetirementSlots: Int = 0, val schema: Int = 5,
        val history: List<InvitationHistory> = emptyList(), val replacement: Replacement? = null)
    private val lock = ReentrantLock()
    private val lease = Any()
    private lateinit var material: KeeperMaterial
    private lateinit var data: Record
    // Track each decoded private buffer immediately. A later strict field or
    // signature failure must wipe it even before Record/material is assigned.
    private val decodedBuffers = Collections.newSetFromMap(IdentityHashMap<ByteArray, Boolean>())
    private var selected: (() -> Boolean)? = null
    private var bound = false
    private var end: Long? = null
    private var proposal: RekeyPlan? = null
    private var rejectedProposal: RekeyPlan? = null
    private var retirementProposal: RetirementPlan? = null
    private var replacementProposal: ReplacementPlan? = null
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
                val seed = creation.consume()
                material = seed.material
                val active = InvitationState(seed.bearer, seed.welcome)
                decodedBuffers.add(active.bearer)
                val invitation = invitationOf(active)
                val welcome = try { requireNotNull(decodePersistentInvitation(active.welcome, invitation)) }
                    finally { invitation.bearer.fill(0) }
                try {
                    end = welcome.endsAt
                    data = Record(0, material.base.clone(), KeeperPhase.ACTIVE, emptyList(), listOf(binding.participant),
                        at, at, 0, welcome.destruct, null, active,
                        devices = listOf(Device(binding.participant, binding.device, frozenOwner, at)))
                    save(data, initial = true)
                } finally { welcome.secret.fill(0) }
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
    private fun invitationOf(active: InvitationState) = RoomInvitation(active.bearer.clone(), binding.authority, true)
    private fun invitationUnlocked() = invitationOf(data.invitation)
    fun epoch(): RoomEpoch = lock.withLock { usable(); RoomEpoch(data.epoch, data.secret) }
    fun welcome(): NostrEvent = lock.withLock { usable(); keeperEvent(data.invitation.welcome) }
    fun snapshot(): Snapshot = lock.withLock {
        usable()
        Snapshot(data.phase, data.epoch, deriveEpoch(RoomEpoch(data.epoch, data.secret)).id, data.high, data.revision,
            data.removed.toList(), data.members.toList(), data.cause, data.pending?.events?.map(::keeperEvent).orEmpty(),
            debt(RekeyLane.NEARBY, data.high), debt(RekeyLane.INTERNET, data.high), !allowed(), data.epochCause,
            data.retirements.map { it.event.id }, data.legacyRetirementSlots, data.devices.size,
            data.invitation.generation, data.replacement?.let { r -> ReplacementStatus(data.invitation.generation,
                r.proposed.generation, invitationId(), invitationId(r.proposed), r.retirement.id,
                r.proposed.welcome.id, r.stage) })
    }
    /** UI callback guard: no signing, storage IO, reservation or waiting. */
    fun canShareInvitation(revision: Long, epoch: Int): Boolean {
        if (!lock.tryLock()) return false
        try { return allowed() && invitationAvailable(revision, epoch) }
        catch (_: Exception) { return false }
        finally { lock.unlock() }
    }
    /** IO reader only. Wait for an in-flight source inspection before applying
     * the same observation guard; a busy lock is not a durable denial. */
    fun canReadObservedInvitation(revision: Long, epoch: Int): Boolean = lock.withLock {
        try { allowed() && invitationAvailable(revision, epoch) }
        catch (_: Exception) { false }
    }
    /** Only a fresh IO reader holding this source's exclusive unbound lease.
     * This reads the actual source, never promotes a SavedRoom hint. */
    fun canReadStoredInvitation(): Boolean = lock.withLock {
        try { !bound && invitationAvailable(data.revision, data.epoch) }
        catch (_: Exception) { false }
    }
    private fun invitationAvailable(revision: Long, epoch: Int): Boolean = !closed && !failed &&
        data.courierReady && data.phase == KeeperPhase.ACTIVE && data.pending == null && data.replacement == null &&
        data.revision == revision && data.epoch == epoch && !ended(time())

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
        if (!allowed() || !binding.route.internet || data.phase != KeeperPhase.ACTIVE || data.pending != null || data.replacement != null || ended(at)) return@withLock null
        var kept = data.invitation.delivery
        if (kept == null || at - kept.event.createdAt >= WELCOME_REFRESH_SECONDS) {
            val event = if (kept == null && at - data.invitation.welcome.createdAt < WELCOME_REFRESH_SECONDS) keeperEvent(data.invitation.welcome)
            else {
                if (!reserve(at, null, 0, true)) return@withLock null
                val invitation = invitationUnlocked()
                try {
                    val body = requireNotNull(decodePersistentInvitation(data.invitation.welcome, invitation))
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
        if (!reserve(at, RekeyLane.INTERNET, keeperBytes(next.event), false, data.copy(invitation = data.invitation.copy(delivery = next)))) return@withLock null
        Handoff(keeperEvent(next.event), RekeyLane.INTERNET, next.attempts, false, invitationGeneration = data.invitation.generation)
    }

    /** Only verified retained requests can become an approval card. No incoming pubkey guess. */
    fun unknownParticipants(): List<String> = lock.withLock {
        usable(); val at = time(); val baseKey = deriveRoom(material.base).roomKey
        try {
            if (data.phase != KeeperPhase.ACTIVE || data.replacement != null) return@withLock emptyList()
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
        var inspected: StoredRoomEpoch? = null
        try {
            val receiver = requireNotNull(vault.get(binding.room)).also { inspected = it }
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
        } finally {
            inspected?.currentSecret?.fill(0); inspected?.pending?.secret?.fill(0)
            expected.secret.fill(0); expected.terminalPredecessor?.secret?.fill(0)
        }
    }

    fun approve(participant: String) = lock.withLock {
        writable(); check(data.phase == KeeperPhase.ACTIVE) { "Invitation is retired" }
        require(keeperHex(participant) && participant !in data.removed)
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
    fun answer(requestEvent: NostrEvent, lane: RekeyLane): Handoff? = answer(requestEvent, lane, null)
    fun answer(requestEvent: NostrEvent, lane: RekeyLane, generation: Int?): Handoff? = lock.withLock {
        usable()
        if (data.replacement != null || generation != null && generation != data.invitation.generation ||
            requestEvent.tagValue("d") != invitationId()) return@withLock null
        reserveAnswer(requestEvent, lane, KIND_INVITATION_REQUEST) { at ->
            decodeLivePersistentRequest(requestEvent, LivePersistentContext(invitationUnlocked(), binding.room), at)
                ?: return@reserveAnswer null
            if (!reserve(at, null, 0, true)) return@reserveAnswer null
            encodeLivePersistentAnswer(LivePersistentContext(invitationUnlocked(), binding.room),
                requestEvent, data.invitation.welcome, material.signer, data.epoch.toLong(), at)
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
        if (previous?.request?.kind == KIND_INVITATION_REQUEST && previous.generation != data.invitation.generation) return null
        if (previous != null && !epochAnswerAllowed(previous, at)) return null
        if (previous != null && (previous.request != requestEvent || previous.epoch != data.epoch || previous.offers >= 3 ||
                answerDeadline(previous) <= at)) return null
        if (previous == null && (kept.size >= MAX_ANSWERS ||
                data.spends.count { it.fresh && at - it.at <= 60 } >= 16)) return null
        val answer = previous?.answer ?: freshAnswer(at) ?: return null
        val cached = Cached(keeperEvent(requestEvent), keeperEvent(answer), data.epoch, (previous?.offers ?: 0) + 1, lane,
            generation = if (kind == KIND_INVITATION_REQUEST) data.invitation.generation else 0)
        if (requestDeadline(requestEvent) <= at || answerDeadline(cached) <= at) return null
        val next = data.copy(answers = kept.filter { it.request.id != requestEvent.id } + cached)
        if (!reserve(at, lane, keeperBytes(answer), false, next)) return null
        return Handoff(keeperEvent(answer), lane, cached.offers, false,
            invitationGeneration = cached.generation.takeIf { kind == KIND_INVITATION_REQUEST })
    }
    private fun answerPhase(kind: Int) = if (kind == KIND_INVITATION_REQUEST) data.phase == KeeperPhase.ACTIVE && data.replacement == null
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

    private fun checkedRekey(credentials: List<NostrEvent>, removed: List<String>, closed: Boolean,
        destruct: Boolean, scheduled: Boolean, at: Long): RekeyAudience {
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
        require(rekeyEventBytes(binding.room, binding.authority, data.epoch + 1, devices, removed, at,
            closed = closed, commit = true, members = members, scheduled = scheduled,
            destruct = closed && (data.destruct || destruct)) <= MAX_EVENT_BYTES) { "Rekey event exceeds source bound" }
        if (closed) {
            retirementCapacity(terminal = true)
            require(retirementBytes(at, true, data.destruct || destruct) <= MAX_EVENT_BYTES)
        }
        val size = rekeyEventBytes(binding.room, binding.authority, data.epoch + 1, devices, removed, at,
            closed = closed, commit = true, members = members, scheduled = scheduled,
            destruct = closed && (data.destruct || destruct))
        // Only byte layout is needed here. This unsigned placeholder is never
        // persisted/exported; its JSON byte count equals the actual encoder.
        val shell = NostrEvent(KIND_ROOM_REKEY, at, emptyList(), "", binding.authority, "1".repeat(64), "0".repeat(128))
        val layout = shell.copy(content = "A".repeat(size - keeperBytes(shell)))
        val events = if (closed) listOf(retirementLayout(at, true, data.destruct || destruct), layout) else listOf(layout)
        transitionFits(Pending(events, data.epoch + 1, ByteArray(32), gone, members,
            if (closed) KeeperPhase.CLOSED else data.phase, at, data.destruct || destruct))
        return RekeyAudience(gone, members, devices)
    }

    /** No write, hold, entropy or signature. The compatibility caller list
     * must match the complete source-qualified audience. */
    fun preflightRekey(credentials: List<NostrEvent>, removed: List<String> = emptyList(),
        closed: Boolean = false, destruct: Boolean = false, scheduled: Boolean = false): RekeyProposal = lock.withLock {
        writable(); val at = time(); require(credentials.size <= 32)
        val frozen = if (closed) emptyList() else credentials.map { requireNotNull(retainedCredential(it)) }
        val gone = removed.toList()
        checkedRekey(frozen, gone, closed, destruct, scheduled, at)
        val plan = RekeyPlan(RekeyProposal(), data.revision, data.epoch, data.phase, frozen, gone, closed, destruct, scheduled)
        proposal = plan; rejectedProposal = null; plan.handle
    }

    /** Controls obtain their audience from the independent source, never
     * from the online roster or the expiring request cache. */
    fun preflightMembers(removed: List<String> = emptyList(), closed: Boolean = false,
        destruct: Boolean = false, scheduled: Boolean = false): RekeyProposal = lock.withLock {
        val gone = keeperMembers(data.removed + removed)
        require(gone.all { it in data.members || it in data.removed }) { "Removal needs a source-qualified participant" }
        preflightRekey(if (closed) emptyList() else data.devices.filter {
            !it.removed && it.participant in data.members && it.participant !in gone
        }.map { keeperEvent(it.credential) }, removed, closed, destruct, scheduled)
    }

    /** Signing follows all fresh checks under the same lock. A typed refusal
     * can only originate before successor entropy/signature/persistence. */
    fun prepareRekey(handle: RekeyProposal): List<NostrEvent> = lock.withLock {
        val plan = proposal
        require(plan != null && plan.handle === handle) { "Unissued or foreign rekey proposal" }
        proposal = null; rejectedProposal = null
        val at: Long
        val audience: RekeyAudience
        try {
            writable(); at = time()
            require(plan.revision == data.revision && plan.epoch == data.epoch && plan.phase == data.phase) { "Stale rekey proposal" }
            audience = checkedRekey(plan.credentials, plan.removed, plan.closed, plan.destruct, plan.scheduled, at)
        } catch (error: Exception) {
            rejectedProposal = plan
            throw NativeRekeyRefusedException(error)
        }
        val nextSecret = Entropy.bytes(32)
        try {
            val next = RoomEpoch(data.epoch + 1, nextSecret)
            val event = encodeRekeyEvent(binding.room, material.signer, deriveEpoch(RoomEpoch(data.epoch, data.secret)), next,
                audience.devices, plan.removed, at, closed = plan.closed, commit = true, members = audience.members,
                scheduled = plan.scheduled, destruct = plan.closed && (data.destruct || plan.destruct))
            require(keeperBytes(event) <= MAX_EVENT_BYTES)
            val target = if (plan.closed) KeeperPhase.CLOSED else data.phase
            val events = if (plan.closed) listOf(retirement(at, true, data.destruct || plan.destruct), event) else listOf(event)
            val pending = Pending(events, next.epoch, nextSecret.clone(), audience.gone, audience.members, target, at, data.destruct || plan.destruct)
            save(data.copy(phase = target, high = at, pending = pending))
            events.map(::keeperEvent)
        } finally { nextSecret.fill(0) }
    }

    /** Existing non-controller owners also get unsigned preflight. */
    fun prepareRekey(credentials: List<NostrEvent>, removed: List<String> = emptyList(),
        closed: Boolean = false, destruct: Boolean = false, scheduled: Boolean = false): List<NostrEvent> =
        prepareRekey(preflightRekey(credentials, removed, closed, destruct, scheduled))

    private fun canResumeRejected(handle: RekeyProposal): Boolean {
        if (!lock.tryLock()) return false
        try {
            val plan = rejectedProposal ?: return false
            return plan.handle === handle && !closed && !failed && allowed() && data.pending == null &&
                data.revision == plan.revision && data.epoch == plan.epoch && data.phase == plan.phase &&
                data.phase != KeeperPhase.CLOSED && !ended(time())
        } catch (_: Exception) { return false }
        finally { lock.unlock() }
    }

    /** The selected controller may undo only its proved pre-signing refusal.
     * Source locks are never held while waiting for the live epoch barrier. */
    suspend fun resumeRejectedRekey(handle: RekeyProposal, vault: EpochVault, session: RoomSession) {
        val expected = lock.withLock {
            check(canResumeRejected(handle)); data.copy(secret = data.secret.clone())
        }
        val keys = deriveEpoch(RoomEpoch(expected.epoch, expected.secret))
        try {
            session.abortKeeperTransition(binding.room, binding.authority, binding.participant, binding.device,
                keys, expected.removed, vault, expected.epochCause) { canResumeRejected(handle) }
            verifyReceiver(vault, session)
            lock.withLock { check(canResumeRejected(handle)); rejectedProposal = null }
        } finally { expected.secret.fill(0); keys.key.fill(0) }
    }

    fun preflightRetirement(): RetirementProposal = lock.withLock {
        writable(); check(data.phase == KeeperPhase.ACTIVE)
        checkedRetirement(time())
        RetirementPlan(RetirementProposal(), data.revision, data.epoch, data.phase).also { retirementProposal = it }.handle
    }
    private fun checkedRetirement(at: Long) {
        retirementCapacity(terminal = false)
        val event = retirementLayout(at, false, false)
        require(keeperBytes(event) <= MAX_EVENT_BYTES)
        transitionFits(Pending(listOf(event), data.epoch, data.secret, data.removed, data.members,
            KeeperPhase.RETIRED, at, data.destruct))
    }
    fun prepareRetirement(handle: RetirementProposal): NostrEvent = lock.withLock {
        val plan = retirementProposal
        require(plan != null && plan.handle === handle) { "Unissued or foreign retirement proposal" }
        retirementProposal = null
        writable(); check(data.phase == KeeperPhase.ACTIVE)
        require(plan.revision == data.revision && plan.epoch == data.epoch && plan.phase == data.phase) { "Stale retirement proposal" }
        val at = time(); checkedRetirement(at)
        val event = retirement(at, false, false)
        val pending = Pending(listOf(event), data.epoch, data.secret.clone(), data.removed, data.members, KeeperPhase.RETIRED, at, data.destruct)
        save(data.copy(phase = KeeperPhase.RETIRED, high = at, pending = pending)); keeperEvent(event)
    }
    fun prepareRetirement(): NostrEvent = prepareRetirement(preflightRetirement())

    /** Issued against the actual index under its monitor. No entropy, signing,
     * write, receiver hold or dispatch is part of this policy/capacity check. */
    fun preflightReplacement(rooms: RoomRepository): ReplacementProposal = rooms.withNativeIndex(this) { current -> lock.withLock {
        writable(); current.verifyNativeAuthority(this)
        val at = time(); checkedReplacement(current.json.getValue("nativeAuthority").jsonObject, current.joinUrl, at)
        ReplacementPlan(ReplacementProposal(), data.revision, data.epoch, data.phase, data.invitation.generation,
            current.json.getValue("nativeAuthority").jsonObject, current.joinUrl).also { replacementProposal = it }.handle
    } }

    fun prepareReplacement(handle: ReplacementProposal, rooms: RoomRepository): ReplacementStatus =
        rooms.withNativeIndex(this) { current -> prepareReplacementChecked(handle, current,
            { Entropy.bytes(32) }, { invitation, policy, at -> encodePersistentInvitation(
                RoomInvitationHost(invitation, material.signer), material.base, at,
                ends = policy.endsAt, relays = policy.relays, destruct = policy.destruct) },
            { at -> retirement(at, false, false) }) }

    /** Private so an application caller cannot inject existing invitation material. */
    private fun prepareReplacementChecked(handle: ReplacementProposal, current: SavedRoom, createBearer: () -> ByteArray,
        makeWelcome: (RoomInvitation, RoomAdmission, Long) -> NostrEvent, makeRetirement: (Long) -> NostrEvent): ReplacementStatus = lock.withLock {
        val plan = replacementProposal
        require(plan != null && plan.handle === handle) { "Unissued or foreign replacement proposal" }
        replacementProposal = null
        writable(); val at = time()
        require(plan.revision == data.revision && plan.epoch == data.epoch && plan.phase == data.phase &&
            plan.generation == data.invitation.generation && current.json["nativeAuthority"] == plan.reference && current.joinUrl == plan.joinUrl) {
            "Stale replacement proposal"
        }
        current.verifyNativeAuthority(this)
        checkedReplacement(plan.reference, plan.joinUrl, at)
        val policy = welcomePolicy(data.invitation)
        var bearer: ByteArray? = null
        try {
            val nextBearer = createBearer().also { bearer = it }
            require(nextBearer.size == 32 && (data.history.map { it.invitation } + data.invitation).none { it.bearer.contentEquals(nextBearer) })
            val invitation = RoomInvitation(nextBearer, binding.authority, true)
            val proposed = InvitationState(nextBearer, makeWelcome(invitation, policy, at), generation = data.invitation.generation + 1)
            val notice = if (data.phase == KeeperPhase.RETIRED) currentRetirement().event else makeRetirement(at)
            require(proposed.welcome.createdAt == at && keeperBytes(proposed.welcome) <= MAX_EVENT_BYTES && keeperBytes(notice) <= MAX_EVENT_BYTES)
            val decoded = requireNotNull(decodePersistentInvitation(proposed.welcome, invitation))
            try { require(decoded.secret.contentEquals(material.base) && decoded.endsAt == policy.endsAt &&
                decoded.relays == policy.relays && decoded.destruct == policy.destruct) }
            finally { decoded.secret.fill(0) }
            val old = invitationOf(data.invitation)
            try { require(decodeInvitationRetirement(notice, old) && notice.content == retirementBody(false, false) &&
                notice.tags == retirementTags(invitationId()) && (data.phase == KeeperPhase.RETIRED || notice.createdAt == at)) }
            finally { old.bearer.fill(0) }
            val payload = requireNotNull(decodeInvitationUrl(plan.joinUrl))
            val url = try { encodeInvitationUrl(plan.joinUrl.substringBefore('#'), invitation, payload.relays, payload.policy) }
                finally { payload.invitation.bearer.fill(0) }
            val r = Replacement(proposed, plan.reference, plan.joinUrl,
                NativeKeeperReference.encode(binding, invitationId(proposed), proposed.generation), url, keeperEvent(notice),
                data.phase == KeeperPhase.RETIRED)
            val spent = data.spends.filter { at - it.at <= window(it.lane) }
            val count = if (r.alreadyArchived) 1 else 2
            save(data.copy(schema = 6, high = at, replacement = r,
                spends = spent + List(count) { Spend(at, null, 0, true) }))
            requireNotNull(snapshot().replacement)
        } finally {
            policy.secret.fill(0)
            bearer?.let { candidate -> if (invitationBuffers(data).none { it === candidate }) candidate.fill(0) }
        }
    }

    private fun currentRetirement() = data.retirements.single { it.invitation == invitationId() &&
        it.event.content == retirementBody(false, false) }

    private fun checkedReplacement(reference: JsonObject, url: String, at: Long) {
        require(data.schema in setOf(5, 6) && data.legacyRetirementSlots == 0 && data.courierReady &&
            data.invitation.generation < MAX_RETIREMENTS - 1 && data.history.size == data.invitation.generation)
        validateReferenceLink(reference, url, data.invitation)
        val alreadyArchived = data.phase == KeeperPhase.RETIRED
        val notice = if (alreadyArchived) currentRetirement().event else {
            retirementCapacity(terminal = false); retirementLayout(at, false, false)
        }
        val count = if (alreadyArchived) 1 else 2
        val spent = data.spends.filter { at - it.at <= window(it.lane) }
        require(spent.size + count <= MAX_SPENDS && spent.count { it.fresh && at - it.at <= 60 } + count <= 16) {
            "Replacement exceeds source signing/spending budget"
        }
        val policy = welcomePolicy(data.invitation)
        try {
            val id = "1".repeat(64)
            val tags = withRoomExpiration(listOf(listOf("d", id)), policy.endsAt)
            val shell = NostrEvent(KIND_GROUP_INVITATION, at, tags, "", binding.authority, id, "0".repeat(128))
            val size = persistentInvitationEventBytes(at, policy.endsAt, policy.relays, policy.destruct)
            require(size <= MAX_EVENT_BYTES && keeperBytes(notice) <= MAX_EVENT_BYTES)
            val welcome = shell.copy(content = "A".repeat(size - keeperBytes(shell)))
            val proposed = InvitationState(ByteArray(32), welcome, generation = data.invitation.generation + 1)
            val payload = requireNotNull(decodeInvitationUrl(url))
            val nextUrl = try { encodeInvitationUrl(url.substringBefore('#'), RoomInvitation(proposed.bearer, binding.authority, true), payload.relays, payload.policy) }
                finally { payload.invitation.bearer.fill(0) }
            val maps = buildMap<String, Int> {
                if (!alreadyArchived) RekeyLane.entries.filter(binding::permits).forEach { put("${it.name}:${notice.id}", 8) }
                if (binding.route.internet) put("INTERNET:${welcome.id}", 8)
            }
            val r = Replacement(proposed, reference, url, NativeKeeperReference.encode(binding, id, proposed.generation),
                nextUrl, notice, alreadyArchived, attempts = maps, offered = maps, welcomeNextAt = KEEPER_MAX_TIME)
            // Include a full bounded spend array, all retained history/caches,
            // complete per-original maps and maximum-width counters in BOTH files.
            val maximalSpends = data.spends + List(MAX_SPENDS - data.spends.size) {
                Spend(KEEPER_MAX_TIME, if (binding.route.internet) RekeyLane.INTERNET else RekeyLane.NEARBY, MAX_EVENT_BYTES, false)
            }
            val pending = data.copy(schema = 6, high = KEEPER_MAX_TIME, revision = KEEPER_MAX_TIME,
                replacement = r, spends = maximalSpends)
            replacementFits(pending)
            replacementFits(replacementCompleted(pending.copy(retirements = if (alreadyArchived) data.retirements else
                data.retirements + Retirement(notice, invitationId(), data.epoch,
                    maps.filterKeys { it.substringAfter(':') == notice.id }, maps.filterKeys { it.substringAfter(':') == notice.id }))))
        } finally { policy.secret.fill(0) }
    }
    private fun replacementFits(record: Record) {
        val bytes = encode(record).toString().toByteArray(Charsets.UTF_8)
        try { require(bytes.size <= MAX_FILE_BYTES) { "Replacement exceeds source file bound" } }
        finally { bytes.fill(0) }
    }
    private fun replacementCompleted(record: Record): Record {
        val r = requireNotNull(record.replacement)
        val key = "INTERNET:${r.proposed.welcome.id}"
        val delivery = r.attempts[key]?.let { WelcomeDelivery(keeperEvent(r.proposed.welcome), it,
            r.welcomeNextAt, r.offered[key] != null) }
        return record.copy(invitation = r.proposed.copy(delivery = delivery), phase = KeeperPhase.ACTIVE,
            history = record.history + InvitationHistory(record.invitation, r.retirement.id),
            replacement = null, cause = r.proposed.welcome.id, at = r.proposed.welcome.createdAt)
    }

    fun replacementOriginals(): List<NostrEvent> = lock.withLock {
        usable(); val r = data.replacement ?: return@withLock emptyList()
        (if (r.alreadyArchived) emptyList() else listOf(r.retirement)) + if (binding.route.internet) listOf(r.proposed.welcome) else emptyList()
    }.map(::keeperEvent)

    fun reserveReplacement(id: String, lane: RekeyLane): Handoff? = lock.withLock {
        usable(); val at = time(); val r = data.replacement ?: return@withLock null
        if (!allowed() || !binding.permits(lane) || ended(at) || r.stage == ReplacementStage.INDEX_VERIFIED) return@withLock null
        val event = when {
            id == r.retirement.id && !r.alreadyArchived && r.stage == ReplacementStage.ORIGINALS_RETAINED -> r.retirement
            id == r.proposed.welcome.id && lane == RekeyLane.INTERNET && binding.route.internet && r.welcomeNextAt <= at -> r.proposed.welcome
            else -> return@withLock null
        }
        if (r.offered.keys.any { it.substringAfter(':') == id }) return@withLock null
        val key = "${lane.name}:$id"; val count = (r.attempts[key] ?: 0) + 1
        if (count > 8) return@withLock null
        val nextAt = if (event.kind == KIND_GROUP_INVITATION) at + minOf(300L, 5L * (1L shl minOf(6, count - 1))) else r.welcomeNextAt
        val next = r.copy(attempts = r.attempts + (key to count), welcomeNextAt = nextAt)
        if (!reserve(at, lane, keeperBytes(event), false, data.copy(replacement = next))) return@withLock null
        Handoff(keeperEvent(event), lane, count, false, invitationGeneration = r.proposed.generation, replacement = true)
    }
    private fun canHandoffReplacement(h: Handoff): Boolean {
        val r = data.replacement ?: return false
        val key = "${h.lane.name}:${h.event.id}"
        return !h.pending && !h.archived && h.invitationGeneration == r.proposed.generation && r.stage != ReplacementStage.INDEX_VERIFIED &&
            r.attempts[key] == h.attempt && r.offered[key] != h.attempt &&
            (h.event == r.retirement && !r.alreadyArchived && r.stage == ReplacementStage.ORIGINALS_RETAINED ||
                h.event == r.proposed.welcome && h.lane == RekeyLane.INTERNET)
    }
    private fun replacementCustody(r: Replacement) = (r.alreadyArchived || r.offered.keys.any { it.substringAfter(':') == r.retirement.id }) &&
        (!binding.route.internet || r.offered.keys.any { it.substringAfter(':') == r.proposed.welcome.id })
    fun replacementReadyForIndex(): Boolean = lock.withLock {
        usable(); data.replacement?.let { replacementCustody(it) && it.stage != ReplacementStage.ORIGINALS_RETAINED } == true
    }

    fun archiveReplacementNotice(): Boolean = lock.withLock {
        usable(); check(allowed()); val r = data.replacement ?: return@withLock false
        if (r.stage != ReplacementStage.ORIGINALS_RETAINED) return@withLock true
        if (!r.alreadyArchived && r.offered.keys.none { it.substringAfter(':') == r.retirement.id }) return@withLock false
        val kept = if (r.alreadyArchived) data.retirements else data.retirements + Retirement(keeperEvent(r.retirement),
            invitationId(), data.epoch, r.attempts.filterKeys { it.substringAfter(':') == r.retirement.id },
            r.offered.filterKeys { it.substringAfter(':') == r.retirement.id })
        save(data.copy(high = time(), at = maxOf(data.at, r.retirement.createdAt), retirements = kept,
            replacement = r.copy(stage = ReplacementStage.NOTICE_ARCHIVED))); true
    }

    /** Called only under the actual repository monitor. No whole stale room is
     * used as the write value; all unrelated current metadata is retained. */
    internal fun replacementIndexValue(current: SavedRoom): SavedRoom = lock.withLock {
        usable(); val r = requireNotNull(data.replacement); require(replacementCustody(r) && r.stage != ReplacementStage.ORIGINALS_RETAINED)
        require(!ended(time()) && (!bound || allowed()))
        requireIndexIdentity(current)
        if (current.json["nativeAuthority"] == r.proposedReference && current.joinUrl == r.proposedJoinUrl) return@withLock current
        require(current.json["nativeAuthority"] == r.previousReference && current.joinUrl == r.previousJoinUrl) { "Index conflicts with pending invitation" }
        SavedRoom.decode(JsonObject(current.json + mapOf("nativeAuthority" to r.proposedReference,
            "joinUrl" to JsonPrimitive(r.proposedJoinUrl), "retired" to JsonPrimitive(false))))
    }
    internal fun requireReplacementIndex(current: SavedRoom) = lock.withLock {
        usable(); val r = requireNotNull(data.replacement); require(replacementCustody(r)); requireIndexIdentity(current)
        require(current.json["nativeAuthority"] == r.proposedReference && current.joinUrl == r.proposedJoinUrl)
    }
    private fun requireIndexIdentity(current: SavedRoom) {
        require(current.id == binding.room && current.nativeAuthority?.pin == binding.pin && current.authority == binding.authority &&
            current.participant == binding.participant && current.devicePubkey == binding.device && current.route == binding.route &&
            (if (current.route.internet) current.relays.map(::canonicalRelayUrl).sorted() else emptyList()) == binding.relays &&
            !current.movedOn && !current.anonymous && !current.secondary && "host" !in current.json)
    }
    internal fun requirePendingIndex(current: SavedRoom) = lock.withLock {
        usable(); val r = requireNotNull(data.replacement); requireIndexIdentity(current)
        require(current.json["nativeAuthority"] == r.previousReference && current.joinUrl == r.previousJoinUrl ||
            current.json["nativeAuthority"] == r.proposedReference && current.joinUrl == r.proposedJoinUrl) {
            "Index conflicts with retained invitation replacement"
        }
    }
    fun reconcileReplacementIndex(rooms: RoomRepository, receiver: EpochVault, ledger: RoomRekeyLedger) {
        verifyReplacementStores(receiver, ledger)
        rooms.installNativeReplacement(this)
        rooms.withNativeIndex(this) { current -> lock.withLock {
            requireReplacementIndex(current); val r = requireNotNull(data.replacement)
            require(!bound || allowed())
            if (r.stage != ReplacementStage.INDEX_VERIFIED)
                save(data.copy(high = time(), replacement = r.copy(stage = ReplacementStage.INDEX_VERIFIED)))
        } }
    }
    private fun verifyReplacementStores(receiver: EpochVault, ledger: RoomRekeyLedger) {
        val expected = lock.withLock { usable(); require(data.replacement != null && data.courierReady); data.copy(secret = data.secret.clone()) }
        var inspected: StoredRoomEpoch? = null
        try {
            val actual = requireNotNull(receiver.get(binding.room)).also { inspected = it }
            require(actual.stableRoom == binding.room && actual.authority == binding.authority && actual.phase == EpochPhase.ACTIVE &&
                actual.pending == null && actual.currentEpoch == expected.epoch && actual.currentSecret.contentEquals(expected.secret) &&
                actual.removed == expected.removed && actual.activationCause == expected.epochCause)
            val q = ledger.binding
            require(q.room == binding.room && q.authority == binding.authority && q.device == binding.device &&
                q.route == binding.route && q.meshScope == binding.meshScope && q.relays == binding.relays)
            ledger.status() // Actual opened durable courier; a corrupt/missing marked file never supplies it.
            lock.withLock { usable(); require(data.revision == expected.revision && !ended(time())) }
        } finally {
            inspected?.currentSecret?.fill(0); inspected?.pending?.secret?.fill(0)
            expected.secret.fill(0)
        }
    }
    internal fun verifyPendingReplacementStores(receiver: EpochVault, ledger: RoomRekeyLedger) = verifyReplacementStores(receiver, ledger)
    fun completeReplacement(rooms: RoomRepository, receiver: EpochVault, live: RoomSession, controller: NativeKeeperController) {
        verifyReceiver(receiver, live)
        rooms.withNativeIndex(this) { current -> lock.withLock {
            requireReplacementIndex(current); val r = requireNotNull(data.replacement)
            check(allowed() && r.stage == ReplacementStage.INDEX_VERIFIED &&
                controller.ownsInvitationInstallation(this, r.proposed.generation, invitationId(r.proposed)))
            save(replacementCompleted(data).copy(high = time()))
        } }
    }

    /** Explicit retry only; completion/reopen never gives a new attempt budget. */
    fun reserveRetirement(id: String, lane: RekeyLane): Handoff? = lock.withLock {
        usable(); val at = time()
        if (!allowed() || data.pending != null || data.replacement != null || !binding.permits(lane) || ended(at) ||
            data.phase == KeeperPhase.CLOSED && data.destruct) return@withLock null
        val kept = data.retirements.singleOrNull { it.event.id == id } ?: return@withLock null
        if (data.phase == KeeperPhase.ACTIVE && data.history.none { it.retirement == id }) return@withLock null
        if (kept.event.tagValue("expiration")?.let { it.toLong() <= at } == true) return@withLock null
        val key = "${lane.name}:$id"; val attempt = (kept.attempts[key] ?: 0) + 1
        if (attempt > 8) return@withLock null
        val next = kept.copy(attempts = kept.attempts + (key to attempt))
        if (!reserve(at, lane, keeperBytes(kept.event), false,
            data.copy(retirements = data.retirements.map { if (it === kept) next else it }))) return@withLock null
        Handoff(keeperEvent(kept.event), lane, attempt, false, archived = true)
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
            if (handoff.invitationGeneration != null && handoff.invitationGeneration != data.invitation.generation && !handoff.replacement) return false
            val at = runCatching { clock() }.getOrNull() ?: return false
            if (at < data.high) return false
            if (ended(at) || handoff.event.tagValue("expiration")?.let { (it.toLongOrNull() ?: return false) <= at } == true) return false
            if (handoff.replacement) return canHandoffReplacement(handoff)
            if (handoff.archived) return !handoff.pending && data.pending == null && data.replacement == null &&
                !(data.phase == KeeperPhase.CLOSED && data.destruct) &&
                data.retirements.any { kept ->
                    val key = "${handoff.lane.name}:${handoff.event.id}"
                    (data.phase != KeeperPhase.ACTIVE || data.history.any { it.retirement == kept.event.id }) &&
                        kept.event == handoff.event && kept.attempts[key] == handoff.attempt && kept.offered[key] != handoff.attempt
                }
            if (handoff.event.kind == KIND_GROUP_INVITATION) return data.phase == KeeperPhase.ACTIVE && data.pending == null && data.replacement == null &&
                handoff.lane == RekeyLane.INTERNET && data.invitation.delivery?.let {
                    it.event == handoff.event && it.attempts == handoff.attempt && !it.accepted && at - it.event.createdAt < WELCOME_REFRESH_SECONDS
                } == true
            return if (handoff.pending) data.pending?.let { p -> p.events.any { it == handoff.event } &&
                handoff.event.id !in p.queued && p.attempts["${handoff.lane.name}:${handoff.event.id}"] == handoff.attempt &&
                p.offered["${handoff.lane.name}:${handoff.event.id}"] != handoff.attempt } == true
            else data.pending == null && data.answers.any {
                answerPhase(it.request.kind) && answerDeadline(it) > at && it.answer == handoff.event &&
                    (it.request.kind != KIND_INVITATION_REQUEST || it.generation == data.invitation.generation) &&
                    it.epoch == data.epoch && it.offers == handoff.attempt && it.lane == handoff.lane && !it.handed && epochAnswerAllowed(it, at) }
        } finally { lock.unlock() }
    }
    fun offered(handoff: Handoff) = lock.withLock {
        usable()
        if (!canHandoff(handoff)) return@withLock
        if (handoff.replacement) {
            val r = requireNotNull(data.replacement)
            val key = "${handoff.lane.name}:${handoff.event.id}"
            save(data.copy(high = time(), replacement = r.copy(offered = r.offered + (key to handoff.attempt))))
            return@withLock
        }
        if (handoff.archived) {
            if (!canHandoff(handoff)) return@withLock
            val kept = data.retirements.single { it.event == handoff.event }
            val next = kept.copy(offered = kept.offered + ("${handoff.lane.name}:${handoff.event.id}" to handoff.attempt))
            save(data.copy(high = time(), retirements = data.retirements.map { if (it === kept) next else it }))
            return@withLock
        }
        if (handoff.event.kind == KIND_GROUP_INVITATION) {
            val kept = data.invitation.delivery ?: return@withLock
            require(handoff.lane == RekeyLane.INTERNET && kept.event == handoff.event && kept.attempts == handoff.attempt)
            save(data.copy(high = time(), invitation = data.invitation.copy(delivery = kept.copy(accepted = true))))
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
                retirements = data.retirements + archivedRetirements(pending),
                terminalPredecessor = if (pending.phase == KeeperPhase.CLOSED) RoomEpoch(data.epoch, data.secret) else null))
            expected.secret.fill(0); true
        }
    }

    private fun retirementTags(id: String) = buildList {
        add(listOf("d", id)); ends()?.let { add(listOf("expiration", it.toString())) }
    }
    private fun retirementBody(ended: Boolean, destruct: Boolean) = buildJsonObject {
        put("v", 1); if (ended) put("ended", true); if (destruct) put("destruct", true)
    }.toString()
    private fun retirementBytes(at: Long, ended: Boolean, destruct: Boolean) = keeperBytes(NostrEvent(
        KIND_INVITATION_RETIREMENT, at, retirementTags("0".repeat(64)), retirementBody(ended, destruct),
        binding.authority, "0".repeat(64), "0".repeat(128)))
    private fun invitationId(active: InvitationState = data.invitation): String {
        val invitation = invitationOf(active)
        return try { deriveInvitationId(invitation) } finally { invitation.bearer.fill(0) }
    }
    private fun retirementLayout(at: Long, ended: Boolean, destruct: Boolean) = NostrEvent(
        KIND_INVITATION_RETIREMENT, at, retirementTags(invitationId()), retirementBody(ended, destruct),
        binding.authority, "0".repeat(64), "0".repeat(128))
    private fun retirementCapacity(terminal: Boolean) {
        require(data.retirements.size + data.legacyRetirementSlots < MAX_RETIREMENTS - if (terminal) 0 else 1) {
            "Retirement archive is full"
        }
    }
    private fun archivedRetirements(pending: Pending) = pending.events.filter { it.kind == KIND_INVITATION_RETIREMENT }.map { event ->
        Retirement(keeperEvent(event), invitationId(), pending.epoch,
            pending.attempts.filterKeys { it.substringAfter(':') == event.id },
            pending.offered.filterKeys { it.substringAfter(':') == event.id })
    }
    /** Complete unsigned layouts, including completion's archive overhead and
     * maximal per-original maps. No entropy, signing, write or live hold. */
    private fun transitionFits(pending: Pending) {
        require(data.revision < KEEPER_MAX_TIME)
        fun fits(record: Record) {
            val bytes = encode(record.copy(revision = data.revision + 1)).toString().toByteArray(Charsets.UTF_8)
            try { require(bytes.size <= MAX_FILE_BYTES) { "Source transition exceeds file bound" } }
            finally { bytes.fill(0) }
        }
        val maps = buildMap<String, Int> {
            pending.events.forEach { event -> RekeyLane.entries.filter(binding::permits).forEach { lane -> put("${lane.name}:${event.id}", 8) } }
        }
        val sized = pending.copy(attempts = maps, offered = maps)
        fits(data.copy(phase = pending.phase, high = pending.at, pending = sized))
        fits(data.copy(epoch = pending.epoch, secret = pending.secret, phase = pending.phase,
            removed = pending.removed, members = pending.members, at = pending.at, high = pending.at,
            destruct = pending.destruct, cause = pending.events.last().id, pending = null,
            epochCause = pending.events.singleOrNull { it.kind == KIND_ROOM_REKEY }?.id ?: data.epochCause,
            devices = data.devices.map { it.copy(removed = it.participant in pending.removed) },
            retirements = data.retirements + archivedRetirements(sized),
            terminalPredecessor = if (pending.phase == KeeperPhase.CLOSED) RoomEpoch(data.epoch, data.secret) else null))
    }
    private fun retirement(at: Long, ended: Boolean, destruct: Boolean): NostrEvent {
        val invitation = invitationUnlocked()
        val id = try { deriveInvitationId(invitation) } finally { invitation.bearer.fill(0) }
        return Events.sign(material.signer, KIND_INVITATION_RETIREMENT, at, retirementTags(id), retirementBody(ended, destruct))
    }
    private fun ends() = end
    private fun ended(at: Long) = ends()?.let { at >= it } == true
    private fun allowed() = runCatching { selected?.invoke() == true }.getOrDefault(false)
    private fun clock() = now().also { require(it in 0..KEEPER_MAX_TIME) }
    private fun time() = clock().also { require(it >= data.high) { "Keeper clock moved backwards" } }
    private fun usable() { check(!closed && !failed) { "Keeper is closed or persistence failed" } }
    private fun writable() { usable(); check(allowed() && data.pending == null && data.replacement == null && data.phase != KeeperPhase.CLOSED && !ended(time())) }
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
            invitationBuffers(old).filter { oldBuffer -> invitationBuffers(committed).none { it === oldBuffer } }.forEach { it.fill(0) }
            if (old.secret !== committed.secret) old.secret.fill(0)
            if (old.pending?.secret !== committed.pending?.secret) old.pending?.secret?.fill(0)
            if (old.terminalPredecessor !== committed.terminalPredecessor) old.terminalPredecessor?.secret?.fill(0)
        } catch (error: Exception) {
            failed = true; selected = null
            invitationBuffers(committed).filter { nextBuffer -> invitationBuffers(data).none { it === nextBuffer } }.forEach { it.fill(0) }
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
        proposal = null; rejectedProposal = null; retirementProposal = null; replacementProposal = null
        if (::material.isInitialized) material.wipe()
        if (::data.isInitialized) {
            invitationBuffers(data).forEach { it.fill(0) }; data.secret.fill(0)
            data.pending?.secret?.fill(0); data.terminalPredecessor?.secret?.fill(0)
        }
        decodedBuffers.forEach { it.fill(0) }; decodedBuffers.clear()
    }
    private fun invitationBuffers(record: Record) = listOf(record.invitation.bearer) +
        record.history.map { it.invitation.bearer } + listOfNotNull(record.replacement?.proposed?.bearer)

    private fun encodeInvitationState(active: InvitationState) = buildJsonObject {
        put("generation", active.generation); put("bearer", active.bearer.toHex()); put("welcome", active.welcome.toJson())
        put("delivery", active.delivery?.let { d -> buildJsonObject {
            put("event", d.event.toJson()); put("attempts", d.attempts); put("nextAt", d.nextAt); put("accepted", d.accepted)
        } } ?: JsonNull)
    }
    private fun encodeReplacement(r: Replacement) = buildJsonObject {
        put("proposed", encodeInvitationState(r.proposed)); put("previousReference", r.previousReference)
        put("previousJoinUrl", r.previousJoinUrl); put("proposedReference", r.proposedReference); put("proposedJoinUrl", r.proposedJoinUrl)
        put("retirement", r.retirement.toJson()); put("alreadyArchived", r.alreadyArchived); put("stage", r.stage.name)
        put("attempts", counters(r.attempts)); put("offered", counters(r.offered)); put("welcomeNextAt", r.welcomeNextAt)
    }
    private fun counters(values: Map<String, Int>) = buildJsonObject { values.toSortedMap().forEach { (key, value) -> put(key, value) } }
    private fun decodedSecret(obj: JsonObject, name: String) = secret(obj, name).also { decodedBuffers.add(it) }
    private fun decodeInvitationState(obj: JsonObject): InvitationState {
        require(obj.keys == setOf("generation", "bearer", "welcome", "delivery"))
        val generation = integer(obj, "generation"); require(generation in 0 until MAX_RETIREMENTS)
        val bearer = decodedSecret(obj, "bearer")
        val welcome = event(obj.getValue("welcome"))
        val delivery = obj.getValue("delivery").takeUnless { it == JsonNull }?.jsonObject?.let { d ->
            require(d.keys == setOf("event", "attempts", "nextAt", "accepted"))
            WelcomeDelivery(event(d.getValue("event")), integer(d, "attempts"), long(d, "nextAt"), boolean(d, "accepted"))
        }
        return InvitationState(bearer, welcome, delivery, generation)
    }
    private fun decodeReplacement(obj: JsonObject): Replacement {
        require(obj.keys == setOf("proposed", "previousReference", "previousJoinUrl", "proposedReference", "proposedJoinUrl",
            "retirement", "alreadyArchived", "stage", "attempts", "offered", "welcomeNextAt"))
        fun counts(name: String) = obj.getValue(name).jsonObject.mapValues { (_, value) -> value.jsonPrimitive.also { require(!it.isString) }.int }
        return Replacement(decodeInvitationState(obj.getValue("proposed").jsonObject), obj.getValue("previousReference").jsonObject,
            text(obj, "previousJoinUrl"), obj.getValue("proposedReference").jsonObject, text(obj, "proposedJoinUrl"),
            event(obj.getValue("retirement")), boolean(obj, "alreadyArchived"), ReplacementStage.valueOf(text(obj, "stage")),
            counts("attempts"), counts("offered"), long(obj, "welcomeNextAt"))
    }
    private fun retainedInvitation(generation: Int): InvitationState = if (generation == data.invitation.generation) data.invitation
        else requireNotNull(data.history.singleOrNull { it.invitation.generation == generation }).invitation
    private fun welcomePolicy(active: InvitationState): RoomAdmission {
        val invitation = invitationOf(active)
        return try { requireNotNull(decodePersistentInvitation(active.welcome, invitation)) }
        finally { invitation.bearer.fill(0) }
    }
    private fun validateInvitation(active: InvitationState, policy: RoomAdmission) {
        val body = welcomePolicy(active)
        try {
            require(body.secret.contentEquals(material.base) && body.endsAt == policy.endsAt &&
                body.relays == policy.relays && body.destruct == policy.destruct && active.welcome.createdAt <= data.high)
            active.delivery?.let { d ->
                require(binding.route.internet && d.attempts in 1..8 && d.nextAt in 0..data.high + 300 &&
                    d.event.createdAt in active.welcome.createdAt..data.high)
                val invitation = invitationOf(active)
                val delivered = try { requireNotNull(decodePersistentInvitation(d.event, invitation)) }
                    finally { invitation.bearer.fill(0) }
                try { require(delivered.secret.contentEquals(material.base) && delivered.endsAt == body.endsAt &&
                    delivered.relays == body.relays && delivered.destruct == body.destruct) }
                finally { delivered.secret.fill(0) }
            }
        } finally { body.secret.fill(0) }
    }
    private fun validateGenerations() {
        require(data.schema == 6 && data.legacyRetirementSlots == 0 && data.courierReady &&
            (data.invitation.generation > 0 || data.replacement != null))
        val all = data.history.map { it.invitation } + data.invitation
        require(all.map { it.generation } == (0..data.invitation.generation).toList())
        require(all.map { it.bearer.toHex() }.distinct().size == all.size &&
            all.map(::invitationId).distinct().size == all.size && all.map { it.welcome.id }.distinct().size == all.size)
        require(all.zipWithNext().all { (a, b) -> a.welcome.createdAt <= b.welcome.createdAt })
        require(data.history.map { it.retirement }.distinct().size == data.history.size)
        val policy = welcomePolicy(data.invitation)
        try {
            all.forEach { validateInvitation(it, policy) }
            data.history.forEach { h ->
                val retirement = data.retirements.single { it.event.id == h.retirement }
                require(retirement.invitation == invitationId(h.invitation) && retirement.event.content == retirementBody(false, false) &&
                    retirement.event.createdAt <= retainedInvitation(h.invitation.generation + 1).welcome.createdAt)
            }
            data.replacement?.let { r ->
                require(data.pending == null && data.phase != KeeperPhase.CLOSED && r.proposed.generation == data.invitation.generation + 1)
                require(r.proposed.delivery == null && r.proposed.welcome.createdAt >= data.invitation.welcome.createdAt)
                require(all.none { it.bearer.contentEquals(r.proposed.bearer) || invitationId(it) == invitationId(r.proposed) || it.welcome.id == r.proposed.welcome.id })
                validateInvitation(r.proposed, policy)
                validateReferenceLink(r.previousReference, r.previousJoinUrl, data.invitation)
                require(r.proposedReference == NativeKeeperReference.encode(binding, invitationId(r.proposed), r.proposed.generation))
                validateReferenceLink(r.proposedReference, r.proposedJoinUrl, r.proposed)
                val old = requireNotNull(decodeInvitationUrl(r.previousJoinUrl))
                try {
                    require(r.proposedJoinUrl == encodeInvitationUrl(r.previousJoinUrl.substringBefore('#'),
                        RoomInvitation(r.proposed.bearer, binding.authority, true), old.relays, old.policy))
                } finally { old.invitation.bearer.fill(0) }
                val oldInvitation = invitationOf(data.invitation)
                try { require(decodeInvitationRetirement(r.retirement, oldInvitation)) }
                finally { oldInvitation.bearer.fill(0) }
                require(r.retirement.content == retirementBody(false, false) && r.retirement.tags == retirementTags(invitationId()) &&
                    r.retirement.createdAt in data.invitation.welcome.createdAt..r.proposed.welcome.createdAt)
                require(r.alreadyArchived || r.retirement.createdAt == r.proposed.welcome.createdAt)
                val archived = data.retirements.singleOrNull { it.event.id == r.retirement.id }
                require((archived != null) == (r.alreadyArchived || r.stage != ReplacementStage.ORIGINALS_RETAINED))
                if (r.alreadyArchived) require(data.phase == KeeperPhase.RETIRED && r.attempts.keys.none { it.substringAfter(':') == r.retirement.id })
                if (archived != null) require(archived.event == r.retirement)
                require(r.attempts.size <= 3 && r.welcomeNextAt in 0..data.high + 300)
                r.attempts.forEach { (key, count) ->
                    val lane = RekeyLane.valueOf(key.substringBefore(':')); val id = key.substringAfter(':')
                    require(binding.permits(lane) && count in 1..8 && key == "${lane.name}:$id" &&
                        (id == r.proposed.welcome.id && lane == RekeyLane.INTERNET || id == r.retirement.id && !r.alreadyArchived))
                }
                require(r.offered.all { (key, count) -> count in 1..(r.attempts[key] ?: 0) })
                if (r.stage != ReplacementStage.ORIGINALS_RETAINED && !r.alreadyArchived) {
                    require(r.offered.keys.any { it.substringAfter(':') == r.retirement.id })
                    require(archived?.attempts == r.attempts.filterKeys { it.substringAfter(':') == r.retirement.id } &&
                        archived.offered == r.offered.filterKeys { it.substringAfter(':') == r.retirement.id })
                }
                if (r.stage == ReplacementStage.INDEX_VERIFIED) require(replacementCustody(r))
            }
        } finally { policy.secret.fill(0) }
        require(data.retirements.all { it.invitation == invitationId() || data.history.any { h -> h.retirement == it.event.id } })
    }
    private fun validateReferenceLink(reference: JsonObject, url: String, active: InvitationState) {
        require(NativeKeeperReference.decode(reference).pin == binding.pin && NativeKeeperReference.generation(reference) == active.generation)
        val version = integer(reference, "v")
        require(reference == NativeKeeperReference.encode(binding, invitationId(active), active.generation.takeIf { version == 2 }))
        val payload = requireNotNull(decodeInvitationUrl(url))
        try { require(payload.invitation == RoomInvitation(active.bearer, binding.authority, true)) }
        finally { payload.invitation.bearer.fill(0) }
    }

    private fun encode(record: Record): JsonObject = buildJsonObject {
        put("v", maxOf(5, record.schema)); put("pin", binding.pin); put("base", material.base.toHex()); put("signer", material.signer.toHex())
        if (record.schema == 6) {
            put("activeInvitation", encodeInvitationState(record.invitation))
            put("invitationHistory", buildJsonArray { record.history.forEach { kept -> add(buildJsonObject {
                put("invitation", encodeInvitationState(kept.invitation)); put("retirement", kept.retirement)
            }) } })
            put("replacement", record.replacement?.let(::encodeReplacement) ?: JsonNull)
        } else {
            put("bearer", record.invitation.bearer.toHex()); put("welcome", record.invitation.welcome.toJson())
        }
        put("epoch", record.epoch); put("secret", record.secret.toHex()); put("phase", record.phase.name)
        put("removed", strings(record.removed)); put("members", strings(record.members)); put("at", record.at)
        put("high", record.high); put("revision", record.revision); put("destruct", record.destruct)
        put("cause", record.cause?.let(::JsonPrimitive) ?: JsonNull)
        put("epochCause", record.epochCause?.let(::JsonPrimitive) ?: JsonNull)
        put("courierReady", record.courierReady)
        put("legacyRetirementSlots", record.legacyRetirementSlots)
        put("retirements", buildJsonArray { record.retirements.forEach { kept -> add(buildJsonObject {
            put("event", kept.event.toJson()); put("invitation", kept.invitation); put("epoch", kept.epoch)
            put("attempts", buildJsonObject { kept.attempts.toSortedMap().forEach { (key, value) -> put(key, value) } })
            put("offered", buildJsonObject { kept.offered.toSortedMap().forEach { (key, value) -> put(key, value) } })
        }) } })
        put("devices", buildJsonArray { record.devices.forEach { add(buildJsonObject {
            put("participant", it.participant); put("device", it.device); put("credential", it.credential.toJson())
            put("verifiedAt", it.verifiedAt); put("removed", it.removed)
        }) } })
        if (record.schema != 6) put("welcomeDelivery", record.invitation.delivery?.let { delivery -> buildJsonObject {
            put("event", delivery.event.toJson()); put("attempts", delivery.attempts)
            put("nextAt", delivery.nextAt); put("accepted", delivery.accepted)
        } } ?: JsonNull)
        put("terminalPredecessor", record.terminalPredecessor?.let { previous -> buildJsonObject {
            put("epoch", previous.epoch); put("secret", previous.secret.toHex())
        } } ?: JsonNull)
        put("answers", buildJsonArray { record.answers.forEach { add(buildJsonObject {
            put("request", it.request.toJson()); put("answer", it.answer.toJson()); put("epoch", it.epoch); put("offers", it.offers)
            put("lane", it.lane.name); put("handed", it.handed)
            if (record.schema == 6) put("generation", it.generation)
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
        val version = integer(root, "v")
        if (version !in 4..6) throw NativeKeeperMigrationRequiredException()
        val fields = setOf("v", "pin", "base", "signer", "bearer", "welcome", "epoch", "secret", "phase", "removed",
            "members", "at", "high", "revision", "destruct", "cause", "epochCause", "terminalPredecessor", "answers", "spends", "pending", "courierReady", "welcomeDelivery", "devices")
        val expectedFields = if (version == 6) fields - setOf("bearer", "welcome", "welcomeDelivery") +
            setOf("retirements", "legacyRetirementSlots", "activeInvitation", "invitationHistory", "replacement")
            else fields + if (version == 5) setOf("retirements", "legacyRetirementSlots") else emptySet()
        require(root.keys == expectedFields)
        require(text(root, "pin") == binding.pin)
        fun decodedSecret(obj: JsonObject, name: String) = secret(obj, name).also { decodedBuffers.add(it) }
        material = KeeperMaterial(decodedSecret(root, "base"), decodedSecret(root, "signer"))
        val active = if (version == 6) decodeInvitationState(root.getValue("activeInvitation").jsonObject)
            else InvitationState(decodedSecret(root, "bearer"), event(root.getValue("welcome")))
        val history = if (version == 6) root.getValue("invitationHistory").jsonArray.also {
            require(it.size < MAX_RETIREMENTS)
        }.map { raw ->
            val obj = raw.jsonObject; require(obj.keys == setOf("invitation", "retirement"))
            InvitationHistory(decodeInvitationState(obj.getValue("invitation").jsonObject),
                text(obj, "retirement").also { require(keeperHex(it)) })
        } else emptyList()
        val replacement = if (version == 6) root.getValue("replacement").takeUnless { it == JsonNull }
            ?.jsonObject?.let(::decodeReplacement) else null
        require(deriveRoom(material.base).roomId == binding.room && Schnorr.publicKeyHex(material.signer) == binding.authority)
        val invitation = invitationOf(active)
        val welcome = try { requireNotNull(decodePersistentInvitation(active.welcome, invitation)) }
            finally { invitation.bearer.fill(0) }
        decodedBuffers.add(welcome.secret)
        end = welcome.endsAt
        require(welcome.secret.contentEquals(material.base))
        val epoch = integer(root, "epoch"); val phase = KeeperPhase.valueOf(text(root, "phase")); val current = decodedSecret(root, "secret")
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
            require(it.keys == setOf("epoch", "secret"))
            val number = integer(it, "epoch"); require(number in 0..MAX_EPOCH)
            // RoomEpoch copies before checking its range. Reject that field
            // before it could allocate an unowned private copy and throw.
            RoomEpoch(number, decodedSecret(it, "secret")).also { previous -> decodedBuffers.add(previous.secret) }
        }
        val destruct = boolean(root, "destruct"); require(!welcome.destruct || destruct)
        val answers = root.getValue("answers").jsonArray.also { require(it.size <= MAX_ANSWERS) }.map { raw ->
            val obj = raw.jsonObject; require(obj.keys == setOf("request", "answer", "epoch", "offers", "lane", "handed") +
                if (version == 6) setOf("generation") else emptySet())
            Cached(event(obj.getValue("request")), event(obj.getValue("answer")), integer(obj, "epoch"), integer(obj, "offers"),
                RekeyLane.valueOf(text(obj, "lane")), boolean(obj, "handed"), if (version == 6) integer(obj, "generation") else 0)
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
            Pending(events, integer(p, "epoch"), decodedSecret(p, "secret"), members(p, "removed"), members(p, "members"),
                KeeperPhase.valueOf(text(p, "phase")), long(p, "at"), boolean(p, "destruct"), queued, attempts, offered)
        }
        require(if (phase == KeeperPhase.CLOSED && pending == null) predecessor != null && predecessor.epoch == epoch - 1 && cause == epochCause else predecessor == null)
        val courierReady = boolean(root, "courierReady")
        val delivery = if (version == 6) active.delivery else root.getValue("welcomeDelivery").takeUnless { it == JsonNull }?.jsonObject?.let { obj ->
            require(binding.route.internet && obj.keys == setOf("event", "attempts", "nextAt", "accepted"))
            val value = WelcomeDelivery(event(obj.getValue("event")), integer(obj, "attempts"), long(obj, "nextAt"), boolean(obj, "accepted"))
            require(value.attempts in 1..8 && value.nextAt in 0..high + 300 && value.event.createdAt in active.welcome.createdAt..high)
            val link = invitationOf(active)
            val body = try { requireNotNull(decodePersistentInvitation(value.event, link)) }
                finally { link.bearer.fill(0) }
            try { require(body.secret.contentEquals(material.base) && body.endsAt == welcome.endsAt &&
                body.relays == welcome.relays && body.destruct == welcome.destruct) }
            finally { body.secret.fill(0) }
            value
        }
        welcome.secret.fill(0)
        val retirements = if (version == 4) emptyList() else root.getValue("retirements").jsonArray.also {
            require(it.size <= MAX_RETIREMENTS)
        }.map { raw ->
            val obj = raw.jsonObject
            require(obj.keys == setOf("event", "invitation", "epoch", "attempts", "offered"))
            fun counters(name: String) = obj.getValue(name).jsonObject.mapValues { (_, value) ->
                value.jsonPrimitive.also { require(!it.isString) }.int
            }
            Retirement(event(obj.getValue("event")), text(obj, "invitation"), integer(obj, "epoch"), counters("attempts"), counters("offered"))
        }
        val legacySlots = if (version >= 5) integer(root, "legacyRetirementSlots") else when (phase) {
            KeeperPhase.ACTIVE -> 0
            KeeperPhase.RETIRED -> if (pending?.events?.any { it.kind == KIND_INVITATION_RETIREMENT } == true) 0 else 1
            KeeperPhase.CLOSED -> if (pending != null) 1 else 2
        }
        require(legacySlots in 0..2 && retirements.size + legacySlots <= MAX_RETIREMENTS)
        val pendingRetirements = pending?.events?.count { it.kind == KIND_INVITATION_RETIREMENT } ?: 0
        require(retirements.size + legacySlots + pendingRetirements <= MAX_RETIREMENTS)
        data = Record(epoch, current, phase, removed, known, at, high, revision, destruct, cause,
            active.copy(delivery = delivery), answers, spends, pending,
            epochCause, predecessor, courierReady, devices, retirements, legacySlots, version, history, replacement)
        require(retirements.map { it.event.id }.distinct().size == retirements.size)
        require(retirements.none { kept -> pending?.events?.any { it.id == kept.event.id } == true })
        require(phase != KeeperPhase.ACTIVE || legacySlots == 0 && retirements.none {
            it.invitation == invitationId() && data.replacement?.let { r ->
                r.stage != ReplacementStage.ORIGINALS_RETAINED && r.retirement == it.event
            } != true
        })
        if (version == 6) validateGenerations()
        require(phase == KeeperPhase.CLOSED || legacySlots <= 1)
        retirements.forEach(::validateRetirement)
        if (phase != KeeperPhase.ACTIVE) require(retirements.isNotEmpty() || legacySlots > 0 || pendingRetirements > 0)
        require(answers.map { it.request.id }.distinct().size == answers.size)
        answers.forEach(::validateAnswer)
        pending?.let(::validatePending)
        require(phase != KeeperPhase.CLOSED || pending != null || epoch > 0)
        require(spends.count { it.lane == null && !it.fresh && high - it.at <= 60 } <= 64 && spends.count { it.fresh && high - it.at <= 60 } <= 16)
        for (lane in RekeyLane.entries) require(debt(lane, high) <= if (lane == RekeyLane.NEARBY) 16 * 1024 else 64 * 1024)
    }
    private fun validateRetirement(kept: Retirement) {
        val value = kept.event
        val active = (data.history.map { it.invitation } + data.invitation).single { invitationId(it) == kept.invitation }
        val invitation = invitationOf(active)
        try {
            require(value.kind == KIND_INVITATION_RETIREMENT && value.pubkey == binding.authority &&
                kept.invitation == deriveInvitationId(invitation) && decodeInvitationRetirement(value, invitation))
            require(value.createdAt in active.welcome.createdAt..data.at && kept.epoch in 0..data.epoch)
            require(value.tags == retirementTags(kept.invitation))
            val body = Json.parseToJsonElement(value.content).jsonObject
            val terminal = body["ended"] == JsonPrimitive(true)
            val destructive = body["destruct"] == JsonPrimitive(true)
            require(value.content == retirementBody(terminal, destructive) && (!destructive || terminal && data.destruct))
            require(!terminal || active.generation == data.invitation.generation && data.phase == KeeperPhase.CLOSED && kept.epoch == data.epoch)
            require(data.phase != KeeperPhase.ACTIVE || data.history.any { it.retirement == value.id } ||
                data.replacement?.let { it.stage != ReplacementStage.ORIGINALS_RETAINED && it.retirement == value } == true)
        } finally { invitation.bearer.fill(0) }
        require(kept.attempts.size <= 2 && kept.offered.isNotEmpty())
        kept.attempts.forEach { (key, attempt) ->
            val lane = RekeyLane.valueOf(key.substringBefore(':'))
            require(binding.permits(lane) && key == "${lane.name}:${value.id}" && attempt in 1..8)
        }
        require(kept.offered.all { (key, attempt) -> attempt in 1..(kept.attempts[key] ?: 0) })
    }
    private fun validateAnswer(c: Cached) {
        require(c.epoch in 0..data.epoch && c.offers in 1..3 && binding.permits(c.lane))
        require(c.request.createdAt <= data.high + 5 && c.answer.createdAt <= data.high && requestDeadline(c.request) > c.answer.createdAt)
        if (c.request.kind == KIND_EPOCH_REQUEST) { require(c.generation == 0); validateEpochAnswer(c); return }
        require(c.request.kind == KIND_INVITATION_REQUEST)
        val active = retainedInvitation(c.generation)
        val invitation = invitationOf(active)
        val request = try { requireNotNull(decodeLivePersistentRequest(c.request, LivePersistentContext(invitation, binding.room), c.answer.createdAt)) }
            finally { invitation.bearer.fill(0) }
        require(c.answer.kind == KIND_INVITATION_GRANT && c.answer.pubkey == binding.authority && Events.verify(c.answer))
        require(c.answer.tags == listOf(listOf("d", invitationId(active)), listOf("p", request.requester),
            listOf("expiration", minOf(request.expiresAt, c.answer.createdAt + 30).toString())))
        val key = Nip44.conversationKey(material.signer, request.requester.hexToBytes())
        val body = try { Nip44.decrypt(c.answer.content, key) } finally { key.fill(0) }
        val expected = buildJsonObject {
            put("v", 1); put("profile", "persistent-live"); put("request", request.requestId); put("room", binding.room); put("epoch", c.epoch)
            put("invitation", buildJsonObject {
                val w = active.welcome
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
        const val MAX_RETIREMENTS = 16
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
