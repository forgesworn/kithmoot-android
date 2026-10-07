package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.relay.StoredLinkRoute
import dev.forgesworn.kithmoot.storage.RoomStorage
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException

/**
 * Where a persona's witness traffic goes: null while it may send none (C7), so
 * covered writes hold. [writerSeed] is the persona's own Link identity, a copy
 * wiped once this returns: an owner keeps only what its engine copied.
 */
fun interface WitnessChannels {
    fun forPersona(persona: String, writerSeed: ByteArray?, route: StoredLinkRoute?): WitnessChannel?
}

/** What the app can show about a persona's coordination (C5). */
sealed class CoordinationStatus {
    /** No genesis yet: covered writes hold until the keeper enrols the persona. */
    data object NotEnrolled : CoordinationStatus()
    /** Not confirmed by the witness (unreachable, refused or a candidate in flight): covered writes hold. */
    data class Pending(val refused: Boolean) : CoordinationStatus()
    data object Active : CoordinationStatus()
    /** `RestoreFenced` for good; [subject] is the marker's, for the keeper's retire line. */
    data class Fenced(val reason: String, val subject: String?) : CoordinationStatus()
}

/** What the keeper's enrol line needs (B3): `bothyd witness enrol --subject --installation --writer --initial-digest`. */
data class CoordinationGenesis(val subject: String, val installation: String, val initialDigest: String)

/**
 * Where a persona's enrolment stands, read from its file and marker alone (no
 * witness traffic), for the "Restore witness" screen. Every value is public at
 * the box; the writer's seed never leaves the vault.
 */
sealed class WitnessEnrolment {
    /** Nothing prepared, or an earlier installation superseded: a fresh enrolment may start. */
    data object None : WitnessEnrolment()
    /** The installation id and writer are minted; the keeper's witness pairing code comes next. */
    data class Prepared(val writer: String) : WitnessEnrolment()
    /** Paired with the box whose Link node id is [box]; genesis comes next. */
    data class Paired(val writer: String, val box: String) : WitnessEnrolment()
    /** Genesis taken: the keeper runs [line] on the box. Whether it is active is [CoordinationStatus]. */
    data class Enrolled(val line: String?, val box: String?) : WitnessEnrolment()
    /** Fenced for good; the keeper retires [subject] (`bothyd witness retire --subject`). */
    data class Fenced(val reason: String, val subject: String?) : WitnessEnrolment()
}

internal sealed class Gate {
    data object Ready : Gate()
    data object Pending : Gate()
    data class Fenced(val reason: String) : Gate()
    data object NotEnrolled : Gate()
}

internal sealed class CommitOutcome {
    data object Written : CommitOutcome()
    data object Unchanged : CommitOutcome()
    data object Pending : CommitOutcome()
    data class Fenced(val reason: String) : CommitOutcome()
}

/** The promoted record's public view, taken at the last promotion, for reads that release nothing. */
internal class Snapshot<V>(val view: V?, val fenced: String?)

/** A session snapshot's plaintext at its witnessed [generation]; the reader wipes [plaintext]. */
internal class WitnessedSnapshot(val generation: Long, val plaintext: ByteArray)

/**
 * One persona installation under the restore-witness coordinator (contract
 * §4.3, P3-03b-2). Every method but [snapshot] runs under the persona's lock,
 * which is held across the witness round trip: the coordinator keeps one
 * outstanding challenge, so a second request mid-trip would turn the first
 * answer into a spurious `Held`.
 *
 * The in-memory coordinator is a cache of the file at [revision]; it is
 * dropped and reopened from what is stored after any failed persist, any
 * `Stale` answer, or when another writer changed the file.
 */
internal class PersonaCoordination<V>(
    val persona: String,
    val store: CoordinatedPersonaStore,
    private val witness: VaultWitness,
    private val channels: WitnessChannels,
    private val random: SecureRandom,
    private val viewOf: (ByteArray?) -> V?,
) {
    private var coordinator: WitnessCoordinator? = null
    private var revision = -1L
    private var file: PersonaFile? = null
    /** The last [open] reopened the coordinator and already asked the witness. */
    private var justRead = false
    /** Each snapshot's hash as the core was given it, so a file swapped since is never opened. */
    private val hashes = HashMap<Pair<String, Long>, ByteArray>()
    @Volatile var snapshot: Snapshot<V>? = null
        private set

    // ---- open and reconcile ----

    /** Loads the file and, if it changed, reopens the coordinator, reads the witness and runs the duty. */
    suspend fun open(): Gate {
        justRead = false
        val marker = store.marker()
        val current = when (val read = store.read()) {
            FileRead.Missing -> {
                drop()
                return when {
                    marker == null || marker.state == Marker.State.Superseded -> Gate.NotEnrolled
                    marker.fenced -> fenced(marker.reason ?: MISSING_FILE)
                    // Never "file missing, start fresh": the marker proves a
                    // file existed. Fenced and persisted, so the retire line shows.
                    else -> { store.fenceMarker(MISSING_FILE); fenced(MISSING_FILE) }
                }
            }
            FileRead.SealLost -> return sealLost()
            is FileRead.Present -> read.file
        }
        if (marker?.fenced == true) {
            if (marker.reason == CLEARED && !current.cleared) {
                // A clear was interrupted after its marker fence: finish it,
                // never reopen the healthy-looking file it left behind.
                clear()
                return open()
            }
            // A marker fence is terminal evidence; only a cleared file is still opened, for its duty.
            if (marker.reason != CLEARED) { drop(); return fenced(marker.reason ?: MISSING_SEAL_KEY) }
        }
        val open = coordinator
        if (open != null && current.revision == revision) {
            file?.wipe()
            file = current
            return gate(open)
        }
        drop()
        file = current
        revision = current.revision
        justRead = true
        val state = current.state ?: return Gate.NotEnrolled
        // `open` hashes only the sealed bytes, so the inner key's loss is checked here.
        if (!store.innerKeyPresent() && (current.active.isNotEmpty() || current.staged != null || !current.cleared)) return sealLost()
        val c = guarded { witness.open(state, activeEntries(current), stagedEntries(current)) }
        coordinator = c
        persistState(c)
        c.fenced()?.let { reason ->
            retiringDuty()
            return fenced(reason)
        }
        if (current.cleared) {
            retiringDuty()
            return fenced(INSTALLATION_REPLACED)
        }
        if (!takeSnapshot()) return sealLost()
        val gate = try { readWitness(c) } finally { retiringDuty() }
        return gate
    }

    /** [open], then finish a pending candidate or read the witness until confirmed. */
    suspend fun ready(): Gate {
        val gate = open()
        if (gate != Gate.Pending || justRead) return gate
        val c = coordinator ?: return Gate.Pending
        return if (file?.staged != null) finishPending(c) else readWitness(c)
    }

    /**
     * [open], then a fresh witness read even when already confirmed (C2): a
     * confirmation otherwise lasts the whole process, although the witness may
     * since have retired the subject or moved past this installation. A read
     * that cannot be had leaves the coordinator unconfirmed, so [Gate.Pending]
     * comes back and the caller decides whether to keep what it showed; only
     * what the witness says fences.
     */
    suspend fun recheck(): Gate {
        val gate = open()
        if ((gate != Gate.Ready && gate != Gate.Pending) || justRead) return gate
        val c = coordinator ?: return Gate.Pending
        return if (file?.staged != null) finishPending(c) else readWitness(c, fresh = true)
    }

    private suspend fun readWitness(c: WitnessCoordinator, fresh: Boolean = false): Gate {
        if (c.fenced() != null) return fenced(c.fenced()!!)
        if (!fresh && c.confirmed()) return Gate.Ready
        val request = guarded { c.read() }
        val decision = guarded { c.onRead(read(request)) }
        persistState(c)
        return act(c, decision)
    }

    private suspend fun finishPending(c: WitnessCoordinator): Gate {
        val request = guarded { c.resend() }
        val decision = guarded { c.onAdvance(advance(request)) }
        persistState(c)
        return act(c, decision)
    }

    private suspend fun act(c: WitnessCoordinator, decision: WitnessDecision): Gate = when (decision) {
        WitnessDecision.Active -> Gate.Ready
        WitnessDecision.Resend -> finishPending(c)
        is WitnessDecision.Promote -> promote(c)
        is WitnessDecision.Fenced -> { retiringDuty(); fenced(decision.reason) }
        else -> Gate.Pending
    }

    private fun promote(c: WitnessCoordinator): Gate {
        val current = file ?: return Gate.Pending
        val staged = current.staged ?: return Gate.Pending
        val stagedSessions = current.stagedSessions ?: return Gate.Pending
        val promotion = guarded { c.promote() }
        promotion.use {
            val next = current.next(state = it.state, active = staged, staged = null, sessions = stagedSessions)
            persist(next)
            val marks = try {
                c.promoted(it)
            } catch (_: WitnessCoordinatorException) {
                // Persisted, but it no longer fits: reopen from what is stored.
                drop()
                return Gate.Pending
            }
            // The core's marks are the witnessed manifest: they must be exactly the file's sessions.
            if (marks.associate { m -> m.session.toHex() to m.generation } != next.sessions) {
                drop()
                throw MlsVaultUnavailableException(IllegalStateException("The witnessed sessions differ from the stored ones"))
            }
        }
        if (!takeSnapshot()) return sealLost()
        // Old snapshots go only once the promoted file naming their successors is persisted (§4.4).
        file?.let { promoted ->
            val live = promoted.sessions.toList().toSet()
            hashes.keys.retainAll(live)
            runCatching { store.sweepSnapshots(live) }
        }
        return Gate.Ready
    }

    private fun gate(c: WitnessCoordinator): Gate {
        c.fenced()?.let { return fenced(it) }
        if (file?.cleared == true) return fenced(INSTALLATION_REPLACED)
        return if (c.confirmed()) Gate.Ready else Gate.Pending
    }

    // ---- covered writes ----

    /** The promoted record's plaintext, only while the witness confirms it. */
    suspend fun confirmed(): Pair<Gate, ByteArray?> {
        val gate = ready()
        if (gate != Gate.Ready) return gate to null
        return try { Gate.Ready to promotedPlain() } catch (_: SealLostException) { sealLost() to null }
    }

    /**
     * A covered write (the note's seven steps): [change] sees the promoted
     * record only and answers its next plaintext, or null to write nothing.
     * Nothing is released unless this answers [CommitOutcome.Written] or
     * [CommitOutcome.Unchanged].
     */
    suspend fun commit(change: (ByteArray?) -> ByteArray?): CommitOutcome {
        when (val gate = ready()) {
            Gate.Ready -> Unit
            Gate.Pending, Gate.NotEnrolled -> return CommitOutcome.Pending
            is Gate.Fenced -> return CommitOutcome.Fenced(gate.reason)
        }
        val c = coordinator ?: return CommitOutcome.Pending
        val current = file?.takeIf { it.staged == null } ?: return CommitOutcome.Pending
        val plain = try { promotedPlain() } catch (_: SealLostException) { return CommitOutcome.Fenced(sealLost().reason) }
        val candidate = try {
            val next = change(plain) ?: return CommitOutcome.Unchanged
            try {
                // An unchanged record keeps its exact sealed bytes: nothing to witness.
                if (plain != null && next.contentEquals(plain)) return CommitOutcome.Unchanged
                current.active + (RECORD_HEX to store.seal(current, RECORD_ID, next))
            } finally {
                next.fill(0)
            }
        } finally {
            plain?.fill(0)
        }
        return advanceTo(c, current, candidate, current.sessions)
    }

    /**
     * Stages [objects] and [sessions] as the candidate after [current], has the
     * witness advance to it and promotes it: the note's steps 2 to 4.
     */
    private suspend fun advanceTo(c: WitnessCoordinator, current: PersonaFile, objects: Map<String, ByteArray>, sessions: Map<String, Long>): CommitOutcome {
        val candidate = objects
        val staged = try {
            c.stage(entries(candidate, sessions, fresh = false))
        } catch (error: WitnessCoordinatorException) {
            if (error.code == "SequenceExhausted") {
                persistState(c)
                return CommitOutcome.Fenced(c.fenced() ?: "sequence-exhausted").also { fenced(it.reason) }
            }
            drop()
            throw MlsVaultUnavailableException(error)
        }
        val request = staged.use {
            persist(current.next(state = it.state, staged = candidate, stagedSessions = sessions))
            try {
                c.staged(it)
            } catch (_: WitnessCoordinatorException) {
                // Persisted but stale: the next open finishes exactly what is stored.
                drop()
                return CommitOutcome.Pending
            }
        }
        val decision = guarded { c.onAdvance(advance(request)) }
        persistState(c)
        return when (decision) {
            is WitnessDecision.Promote -> when (val gate = promote(c)) {
                Gate.Ready -> CommitOutcome.Written
                is Gate.Fenced -> CommitOutcome.Fenced(gate.reason)
                else -> CommitOutcome.Pending
            }
            is WitnessDecision.Fenced -> { retiringDuty(); CommitOutcome.Fenced(fenced(decision.reason).reason) }
            else -> CommitOutcome.Pending
        }
    }

    // ---- MLS sessions (P3-03b-3a) ----

    /** The hash of [session]'s snapshot at [generation] as the core was given it, if this open has it. */
    fun snapshotHash(session: String, generation: Long): ByteArray? = hashes[session to generation]?.copyOf()

    /** Each session's witnessed generation, by session id in hex, only while the witness confirms them. */
    suspend fun sessionMarks(): Pair<Gate, Map<String, Long>?> {
        val gate = ready()
        if (gate != Gate.Ready) return gate to null
        val c = coordinator ?: return Gate.Pending to null
        val marks = guarded { c.sessionMarks() }.associate { it.session.toHex() to it.generation }
        return Gate.Ready to marks
    }

    /**
     * [session]'s snapshot plaintext at its witnessed generation, to open the
     * engine session at that mark (§4.3: the mark is the session generation in
     * the witnessed manifest). Null while not confirmed, or for no such session.
     */
    suspend fun sessionSnapshot(session: String): Pair<Gate, WitnessedSnapshot?> {
        val (gate, marks) = sessionMarks()
        if (gate != Gate.Ready || marks == null) return gate to null
        val generation = marks[session] ?: return Gate.Ready to null
        val current = file ?: return Gate.Pending to null
        val sealed = store.readSnapshot(session, generation)
        val expected = hashes[session to generation]
        if (sealed == null || expected == null || !guarded { witness.objectHash(sealed) }.contentEquals(expected)) {
            // Changed on disk since the core checked it: the next open fences.
            drop()
            throw MlsVaultUnavailableException(IllegalStateException("A session snapshot changed after it was checked"))
        }
        val plain = try { store.openSnapshot(current, session, generation, sealed) } catch (_: SealLostException) { return sealLost() to null }
        return Gate.Ready to WitnessedSnapshot(generation, plain)
    }

    /**
     * A session's next snapshot as a covered write: sealed into its own file,
     * then staged, witnessed and promoted with the persona's other objects.
     * The caller acknowledges [generation] to the engine (`commit_ack`) and
     * releases the step's effects only on [CommitOutcome.Written].
     */
    suspend fun commitSession(session: String, generation: Long, plain: ByteArray): CommitOutcome {
        require(CoordinatedPersonaStore.SESSION_HEX.matches(session) && generation > 0)
        when (val gate = ready()) {
            Gate.Ready -> Unit
            Gate.Pending, Gate.NotEnrolled -> return CommitOutcome.Pending
            is Gate.Fenced -> return CommitOutcome.Fenced(gate.reason)
        }
        val c = coordinator ?: return CommitOutcome.Pending
        val current = file?.takeIf { it.staged == null } ?: return CommitOutcome.Pending
        val previous = current.sessions[session]
        // The engine's generations only rise; anything else is a host fault, never written.
        if (previous != null && generation <= previous) throw MlsVaultUnavailableException(IllegalStateException("A session generation did not advance"))
        if (previous == null && current.sessions.size >= PersonaFile.MAX_SESSIONS) throw MlsVaultUnavailableException(IllegalStateException("Too many sessions"))
        val sealed = store.sealSnapshot(current, session, generation, plain)
        // The file first: a candidate never names a snapshot that is not durable.
        store.writeSnapshot(session, generation, sealed)
        return advanceTo(c, current, current.active, current.sessions + (session to generation))
    }

    /** Removes [session] from the manifest (the room is left or forgotten); its files go at promotion. */
    suspend fun dropSession(session: String): CommitOutcome {
        when (val gate = ready()) {
            Gate.Ready -> Unit
            Gate.Pending, Gate.NotEnrolled -> return CommitOutcome.Pending
            is Gate.Fenced -> return CommitOutcome.Fenced(gate.reason)
        }
        val c = coordinator ?: return CommitOutcome.Pending
        val current = file?.takeIf { it.staged == null } ?: return CommitOutcome.Pending
        if (session !in current.sessions) return CommitOutcome.Unchanged
        return advanceTo(c, current, current.active, current.sessions - session)
    }

    // ---- genesis, status, clear and the retiring duty ----

    /**
     * Mints and persists the persona's own installation id before any pairing
     * (PR 5 adds the seed). Null while a marker stands that was not
     * superseded: an earlier installation's subject may still be live at the box.
     */
    fun prepare(): ByteArray? {
        val marker = store.marker()
        when (val read = store.read()) {
            is FileRead.Present -> {
                val prepared = read.file.takeIf { it.state == null && mayEnrol(marker, it) } ?: return null
                if (prepared.writerSeed == null) { drop(); persist(prepared.seeded(CoordinatedPersonaStore.random32(random))) }
                return prepared.installation.copyOf()
            }
            FileRead.SealLost -> return null
            FileRead.Missing -> if (marker != null && marker.state != Marker.State.Superseded) return null
        }
        drop()
        val fresh = PersonaFile.fresh(persona, CoordinatedPersonaStore.random32(random), random.nextLong() ushr 2, CoordinatedPersonaStore.random32(random))
        persist(fresh)
        return fresh.installation.copyOf()
    }

    /**
     * Pairs the persona's own writer with the keeper's box: [pair] scans the
     * witness-only pairing code into a Link engine started from the writer's
     * seed (a copy, wiped afterwards) and answers the booked route, which is
     * kept in the persona's file. Only before genesis: the route is what
     * genesis pins the witness key from. False when the persona may not enrol.
     */
    suspend fun pairWitness(pair: suspend (ByteArray) -> StoredLinkRoute): Boolean {
        prepare() ?: return false
        val prepared = (store.read() as? FileRead.Present)?.file ?: throw MlsVaultUnavailableException(IllegalStateException("The persona was not prepared"))
        if (prepared.state != null) return false
        val seed = prepared.writerSeed?.copyOf() ?: throw MlsVaultUnavailableException(IllegalStateException("The persona has no writer"))
        val route = try { pair(seed) } finally { seed.fill(0) }
        drop()
        persist(prepared.paired(route))
        return true
    }

    /** The pinned box's Link node id from the paired route's card, or null before pairing. */
    fun pairedBox(): ByteArray? {
        val current = (store.read() as? FileRead.Present)?.file ?: return null
        return try { current.witnessRoute?.let(WriterIdentity::boxNodeId) } finally { current.wipe() }
    }

    /**
     * Genesis over the **empty** persona, for the keeper's explicit enrolment:
     * device enrolment is then the first witnessed advance. Refuses an
     * installation that already has a genesis.
     */
    fun genesis(subject: ByteArray, witnessKey: ByteArray): CoordinationGenesis? {
        require(subject.size == 32 && witnessKey.size == 32)
        prepare() ?: return null
        val prepared = (store.read() as? FileRead.Present)?.file ?: throw MlsVaultUnavailableException(IllegalStateException("The persona was not prepared"))
        if (prepared.state != null) return null
        var current = prepared
        val marker = store.marker()
        val interrupted = marker?.takeIf { it.state == Marker.State.Genesis }
        // An interrupted genesis's subject and installation were never shown
        // to the keeper; both are still tombstoned, and a fresh installation
        // id replaces the prepared one.
        val retired = marker?.retired.orEmpty() + listOfNotNull(interrupted?.let { Marker.Tombstone(it.subject, it.installation) })
        if (interrupted != null) {
            // The writer was never enrolled at the box either, so its seed and
            // pairing carry over: the keeper need not pair again.
            current = PersonaFile.fresh(
                persona, CoordinatedPersonaStore.random32(random), random.nextLong() ushr 2,
                current.writerSeed?.copyOf() ?: CoordinatedPersonaStore.random32(random), current.witnessRoute,
            )
            persist(current)
        }
        val installation = current.installation.toHex()
        if (retired.any { it.subject == subject.toHex() || it.installation == installation }) return null
        val seed = current.writerSeed ?: throw MlsVaultUnavailableException(IllegalStateException("The persona has no writer"))
        // A paired persona pins exactly the box it paired with: the same key
        // authenticates the Link session and signs the receipts.
        current.witnessRoute?.let { route -> if (WriterIdentity.boxNodeId(route)?.contentEquals(witnessKey) != true) return null }
        val writer = WriterIdentity.nodeId(seed).toHex()
        drop()
        val genesis = guarded { witness.genesis(subject, current.installation, witnessKey, emptyList()) }
        store.newInnerKey()
        // The marker first: from here a missing file is never a fresh start.
        store.writeMarker(Marker(Marker.State.Genesis, null, subject.toHex(), writer, installation, retired, genesis.digest.toHex()))
        persist(current.next(state = genesis.state))
        drop()
        snapshot = null
        return CoordinationGenesis(subject.toHex(), current.installation.toHex(), genesis.digest.toHex())
    }

    /** Where enrolment stands, from the file and marker alone (no witness traffic). */
    fun enrolment(): WitnessEnrolment {
        val marker = store.marker()
        if (marker?.fenced == true) return WitnessEnrolment.Fenced(marker.reason ?: MISSING_SEAL_KEY, marker.subject)
        return when (val read = store.read()) {
            FileRead.Missing ->
                if (marker == null || marker.state == Marker.State.Superseded) WitnessEnrolment.None
                else WitnessEnrolment.Fenced(MISSING_FILE, marker.subject)
            FileRead.SealLost -> WitnessEnrolment.Fenced(MISSING_SEAL_KEY, marker?.subject)
            is FileRead.Present -> {
                val current = read.file
                try {
                    val box = current.witnessRoute?.let { WriterIdentity.boxNodeId(it)?.toHex() }
                    when {
                        current.state != null -> WitnessEnrolment.Enrolled(
                            marker?.let { m -> current.writerSeed?.let { enrolLine(m, current.installation.toHex(), WriterIdentity.nodeId(it).toHex()) } },
                            box,
                        )
                        !mayEnrol(marker, current) -> WitnessEnrolment.Fenced(marker?.reason ?: MISSING_FILE, marker?.subject)
                        current.writerSeed == null -> WitnessEnrolment.None
                        else -> {
                            val writer = WriterIdentity.nodeId(current.writerSeed).toHex()
                            if (box == null) WitnessEnrolment.Prepared(writer) else WitnessEnrolment.Paired(writer, box)
                        }
                    }
                } finally {
                    current.wipe()
                }
            }
        }
    }

    suspend fun status(check: Boolean): CoordinationStatus {
        // A check asks the witness again even when confirmed (C2). A read that
        // cannot be had leaves the coordinator unconfirmed, and that is what is
        // said: Pending(refused = false), not Active (M1). Nothing the witness
        // said fences; the caller decides whether to keep what it showed, and
        // the rooms hold until a read succeeds.
        val gate = if (check) recheck() else open()
        return when (gate) {
            Gate.Ready -> CoordinationStatus.Active
            Gate.Pending -> CoordinationStatus.Pending(coordinator?.let { runCatching { it.refused() }.getOrDefault(false) } ?: false)
            Gate.NotEnrolled -> CoordinationStatus.NotEnrolled
            is Gate.Fenced -> CoordinationStatus.Fenced(gate.reason, runCatching { store.marker()?.subject }.getOrNull())
        }
    }

    /**
     * `clear()` of a coordinated persona: `installation_replaced`. While a
     * retiring duty stands, the state, seed and route stay until a signed
     * `retired` ends it. Either way the marker is fenced `cleared` and keeps
     * the subject, so the keeper is shown `bothyd witness retire --subject`;
     * re-enrolment then waits for [supersede] or the duty's signed `retired`.
     */
    suspend fun clear() {
        snapshot = null
        val marker = store.marker()
        when (val read = store.read()) {
            FileRead.Missing -> {
                drop()
                runCatching { store.deleteInnerKey() }
                // The marker proves a file existed: fence it, never drop it.
                if (marker != null && marker.state == Marker.State.Genesis) store.fenceMarker(MISSING_FILE)
            }
            FileRead.SealLost -> {
                // The seed is lost with the file: only the keeper's retire is left.
                drop()
                store.fenceMarker(MISSING_SEAL_KEY)
                store.delete()
                runCatching { store.deleteInnerKey() }
            }
            is FileRead.Present -> {
                val current = read.file
                val state = current.state
                if (state == null) {
                    // Prepared only: nothing was ever witnessed under it. An
                    // interrupted genesis's marker is superseded, its ids kept
                    // as tombstones, rather than left to fence a missing file.
                    if (marker?.state == Marker.State.Genesis) retireLocally(marker, marker.installation)
                    else { drop(); store.delete(); runCatching { store.deleteInnerKey() } }
                    return
                }
                val c = coordinator?.takeIf { revision == current.revision }
                    ?: guarded { witness.open(state, activeEntries(current), stagedEntries(current)) }.also {
                        drop()
                        coordinator = it
                        file = current
                        revision = current.revision
                    }
                guarded { c.installationReplaced() }
                store.fenceMarker(CLEARED)
                if (!c.retiring()) {
                    drop(); store.delete(); runCatching { store.deleteInnerKey() }
                    return
                }
                persist(current.next(state = guarded { c.state() }, active = emptyMap(), staged = null, cleared = true, sessions = emptyMap()))
                runCatching { store.sweepSnapshots(emptySet()) }
                runCatching { store.deleteInnerKey() }
                retiringDuty()
            }
        }
    }

    /**
     * The keeper confirms that the old subject was retired at the box. This is
     * the keeper's word, not proof: v1 (debug only) accepts it as the way out
     * after a clear or a fence whose box gives no signed `retired`. They name
     * [retiredSubject]; only then is the old installation (state, seed, route,
     * keys) deleted, its subject and installation kept as tombstones never to
     * be reused, and a fresh enrolment allowed. Refused unless the marker is
     * fenced and names exactly that subject (so a healthy persona must be
     * cleared first), and refused while a retiring duty stands: a witness
     * found behind is released only by its signed `retired` (§4.2, T34).
     */
    fun keeperConfirmsRetired(retiredSubject: String): Boolean {
        val marker = store.marker() ?: return false
        if (marker.state == Marker.State.Superseded) return marker.retired.any { it.subject == retiredSubject }
        if (!marker.fenced || marker.subject == null || marker.subject != retiredSubject) return false
        val present = (store.read() as? FileRead.Present)?.file
        val state = present?.state
        if (state != null) {
            val retiring = guarded {
                witness.open(state, activeEntries(present), stagedEntries(present)).use { it.retiring() }
            }
            if (retiring) return false
        }
        val installation = present?.installation?.toHex() ?: marker.installation
        retireLocally(marker, installation)
        return true
    }

    /** Deletes the old installation and keeps only its tombstone in the marker. */
    private fun retireLocally(marker: Marker, installation: String?) {
        drop()
        snapshot = null
        store.delete()
        runCatching { store.deleteInnerKey() }
        store.writeMarker(
            marker.copy(
                state = Marker.State.Superseded, reason = null, writer = null, digest = null,
                retired = marker.retired + Marker.Tombstone(marker.subject, installation ?: marker.installation),
            ),
        )
    }

    /** The retiring duty (§4.2): at every open and on the app's foreground timer, fenced or not. */
    suspend fun retiringDuty() {
        val c = coordinator ?: return
        if (!guarded { c.retiring() }) return
        val request = guarded { c.retiringRead() } ?: return
        var decision = guarded { c.onRetiringRead(read(request)) }
        persistState(c)
        if (decision == WitnessDecision.RetireDue) {
            val advance = guarded { c.retiringAdvance() } ?: return
            decision = guarded { c.onRetiring(advance(advance)) }
            persistState(c)
        }
        // A signed `retired` for a replaced installation is the box's own proof: the old state may go.
        if (decision is WitnessDecision.Retired && decision.dutyEnded && file?.cleared == true) {
            val marker = store.marker() ?: Marker(Marker.State.Fenced, CLEARED, null, null, null)
            retireLocally(marker, file?.installation?.toHex())
        }
    }

    /** Opens the persona if this process has not, then runs its duty. */
    suspend fun duty() {
        open()
        if (!justRead) retiringDuty()
    }

    // ---- inside ----

    private fun promotedPlain(): ByteArray? {
        val current = file ?: return null
        val sealed = current.active[RECORD_HEX] ?: return null
        return store.open(current, RECORD_ID, sealed)
    }

    private fun takeSnapshot(): Boolean {
        val plain = try { promotedPlain() } catch (_: SealLostException) { return false }
        try { snapshot = Snapshot(viewOf(plain), null) } finally { plain?.fill(0) }
        return true
    }

    private fun sealLost(): Gate.Fenced {
        drop()
        store.fenceMarker(MISSING_SEAL_KEY)
        return fenced(MISSING_SEAL_KEY)
    }

    private fun fenced(reason: String): Gate.Fenced {
        snapshot = Snapshot(null, reason)
        return Gate.Fenced(reason)
    }

    private fun persistState(c: WitnessCoordinator) {
        val current = file ?: return
        val state = guarded { c.state() }
        if (current.state != null && state.contentEquals(current.state)) return
        persist(current.next(state = state))
    }

    private fun persist(next: PersonaFile) {
        try {
            store.write(next)
        } catch (error: Exception) {
            drop()
            throw error
        }
        file = next
        revision = next.revision
    }

    private fun drop() {
        coordinator?.let { runCatching { it.close() } }
        coordinator = null
        revision = -1
        hashes.clear()
    }

    private fun activeEntries(file: PersonaFile): List<CoordEntry> = entries(file.active, file.sessions)
    private fun stagedEntries(file: PersonaFile): List<CoordEntry>? = file.staged?.let { entries(it, file.stagedSessions.orEmpty()) }

    /**
     * The covered objects as they really are on disk (§4.3 step 1): at open,
     * each session's snapshot file is read and hashed. A file definitively absent
     * is left out, so the core fences (`local-state-mismatch`, or
     * `stage-corrupt` for a candidate); a failed read is only unavailable.
     */
    private fun entries(objects: Map<String, ByteArray>, sessions: Map<String, Long>, fresh: Boolean = true): List<CoordEntry> = guarded {
        objects.toSortedMap().map { (id, sealed) -> CoordEntry.Vault(hex(id), witness.objectHash(sealed)) } +
            sessions.toSortedMap().mapNotNull { (id, generation) ->
                // Staging reuses the hashes this open computed; a snapshot written since is read back from disk.
                if (!fresh) hashes[id to generation]?.let { return@mapNotNull CoordEntry.Session(hex(id), generation, it.copyOf()) }
                store.readSnapshot(id, generation)?.let { sealed ->
                    val hash = witness.objectHash(sealed)
                    hashes[id to generation] = hash
                    CoordEntry.Session(hex(id), generation, hash)
                }
            }
    }

    private suspend fun read(request: ByteArray): WitnessAnswer = send { it.read(request) }
    private suspend fun advance(request: ByteArray): WitnessAnswer = send { it.advance(request) }

    private suspend fun send(call: suspend (WitnessChannel) -> WitnessAnswer): WitnessAnswer {
        val seed = file?.writerSeed?.copyOf()
        val channel = try { channels.forPersona(persona, seed, file?.witnessRoute) } finally { seed?.fill(0) } ?: return WitnessAnswer.Unavailable
        return try { call(channel) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { WitnessAnswer.Unavailable }
    }

    private inline fun <T> guarded(block: () -> T): T = try { block() } catch (error: Exception) {
        if (error is MlsVaultUnavailableException) throw error
        drop()
        throw MlsVaultUnavailableException(error)
    }

    companion object {
        const val MISSING_SEAL_KEY = "missing-seal-key"
        const val INSTALLATION_REPLACED = "installation-replaced"
        const val MISSING_FILE = "missing-file"
        const val CLEARED = "cleared"

        /**
         * A prepared file may be enrolled with no marker, after a supersede, or
         * after an interrupted genesis (a genesis marker beside a file whose
         * state was never persisted): its subject was never shown to the keeper.
         */
        internal fun mayEnrol(marker: Marker?, file: PersonaFile): Boolean = when {
            marker == null || marker.state == Marker.State.Superseded -> true
            marker.state == Marker.State.Genesis -> file.state == null
            else -> false
        }
        /**
         * The keeper's line. [installation] and [writer] come from the sealed
         * file; the unsealed marker must agree with them, or no line is shown.
         * Null too for a marker from before the digest was kept.
         */
        internal fun enrolLine(marker: Marker, installation: String, writer: String): String? {
            val subject = marker.subject ?: return null
            val digest = marker.digest ?: return null
            if (marker.installation != installation || marker.writer != writer) return null
            return "bothyd witness enrol --subject $subject --installation $installation --writer $writer --initial-digest $digest"
        }

        /** The keeper's line to retire [subject] at the box. */
        fun retireLine(subject: String): String = "bothyd witness retire --subject $subject"

        /** C4: the whole persona record is one vault entry. */
        val RECORD_ID: ByteArray = "persona".toByteArray(Charsets.US_ASCII)
        val RECORD_HEX: String = RECORD_ID.toHex()

        private fun hex(value: String): ByteArray = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}

/**
 * The durable session epoch for signing (the delegated decision of 3
 * October): a random 128-bit value in its own uncoordinated rollback-resistant
 * store, which keeps delete-before-commit. Journal entries carry it, so an
 * identical retry after a restart replays while a logout does not. A missing
 * or corrupt store fails safe: a fresh epoch makes every journalled entry stale.
 */
internal class SessionEpoch(private val storage: RoomStorage, private val random: SecureRandom) {
    fun load(): String {
        val existing = try { storage.read() } catch (_: Exception) { null }
        if (existing != null) {
            try {
                if (existing.size == SIZE) return existing.toHex()
            } finally {
                existing.fill(0)
            }
        }
        return renew().first
    }

    /** A fresh epoch, written durably first; the flag is false when it could not be. */
    fun renew(): Pair<String, Boolean> {
        val fresh = ByteArray(SIZE).also(random::nextBytes)
        try {
            val written = try {
                storage.write(fresh)
                true
            } catch (_: Exception) {
                // A corrupt store cannot be read before a write: replace it, so
                // the next start never finds the old epoch either.
                try { storage.reset(); storage.write(fresh); true } catch (_: Exception) { runCatching { storage.reset() }; false }
            }
            return fresh.toHex() to written
        } finally {
            fresh.fill(0)
        }
    }

    private companion object { const val SIZE = 16 }
}
