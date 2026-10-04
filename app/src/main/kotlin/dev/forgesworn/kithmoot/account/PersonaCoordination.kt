package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.relay.StoredLinkRoute
import dev.forgesworn.kithmoot.storage.RoomStorage
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException

/** Where a persona's witness traffic goes: null while it may send none (C7), so covered writes hold. */
fun interface WitnessChannels {
    fun forPersona(persona: String, route: StoredLinkRoute?): WitnessChannel?
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
        // A marker fence is terminal evidence; only a cleared file is still opened, for its duty.
        if (marker?.fenced == true && marker.reason != CLEARED) { drop(); return fenced(marker.reason ?: MISSING_SEAL_KEY) }
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
        val c = guarded { witness.open(state, entries(current.active), current.staged?.let(::entries)) }
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

    private suspend fun readWitness(c: WitnessCoordinator): Gate {
        if (c.fenced() != null) return fenced(c.fenced()!!)
        if (c.confirmed()) return Gate.Ready
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
        val promotion = guarded { c.promote() }
        promotion.use {
            val next = current.next(state = it.state, active = staged, staged = null)
            persist(next)
            try {
                c.promoted(it)
            } catch (_: WitnessCoordinatorException) {
                // Persisted, but it no longer fits: reopen from what is stored.
                drop()
                return Gate.Pending
            }
        }
        if (!takeSnapshot()) return sealLost()
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
        val current = file ?: return CommitOutcome.Pending
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
        val staged = try {
            c.stage(entries(candidate))
        } catch (error: WitnessCoordinatorException) {
            if (error.code == "SequenceExhausted") {
                persistState(c)
                return CommitOutcome.Fenced(c.fenced() ?: "sequence-exhausted").also { fenced(it.reason) }
            }
            drop()
            throw MlsVaultUnavailableException(error)
        }
        val request = staged.use {
            persist(current.next(state = it.state, staged = candidate))
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

    // ---- genesis, status, clear and the retiring duty ----

    /**
     * Mints and persists the persona's own installation id before any pairing
     * (PR 5 adds the seed). Null while a marker stands that was not
     * superseded: an earlier installation's subject may still be live at the box.
     */
    fun prepare(): ByteArray? {
        val marker = store.marker()
        when (val read = store.read()) {
            is FileRead.Present -> return read.file.takeIf { it.state == null && mayEnrol(marker, it) }?.installation?.copyOf()
            FileRead.SealLost -> return null
            FileRead.Missing -> if (marker != null && marker.state != Marker.State.Superseded) return null
        }
        drop()
        val fresh = PersonaFile.fresh(persona, CoordinatedPersonaStore.random32(random), random.nextLong() ushr 2)
        persist(fresh)
        return fresh.installation.copyOf()
    }

    /**
     * Genesis over the **empty** persona, for the keeper's explicit enrolment:
     * device enrolment is then the first witnessed advance. Refuses an
     * installation that already has a genesis.
     */
    fun genesis(subject: ByteArray, witnessKey: ByteArray): CoordinationGenesis? {
        require(subject.size == 32 && witnessKey.size == 32)
        prepare() ?: return null
        val current = (store.read() as? FileRead.Present)?.file ?: throw MlsVaultUnavailableException(IllegalStateException("The persona was not prepared"))
        if (current.state != null) return null
        val marker = store.marker()
        // An interrupted genesis's subject was never shown to the keeper; it is still never reused.
        val retired = marker?.retired.orEmpty() +
            listOfNotNull(marker?.takeIf { it.state == Marker.State.Genesis }?.let { Marker.Tombstone(it.subject, it.installation) })
        val installation = current.installation.toHex()
        if (retired.any { it.subject == subject.toHex() || it.installation == installation }) return null
        drop()
        val genesis = guarded { witness.genesis(subject, current.installation, witnessKey, emptyList()) }
        store.newInnerKey()
        // The marker first: from here a missing file is never a fresh start.
        store.writeMarker(Marker(persona, Marker.State.Genesis, null, subject.toHex(), null, installation, retired))
        persist(current.next(state = genesis.state))
        drop()
        snapshot = null
        return CoordinationGenesis(subject.toHex(), current.installation.toHex(), genesis.digest.toHex())
    }

    suspend fun status(check: Boolean): CoordinationStatus {
        val gate = if (check) ready() else open()
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
                    // Prepared only: nothing was ever witnessed under it.
                    drop(); store.delete(); runCatching { store.deleteInnerKey() }
                    return
                }
                val c = coordinator?.takeIf { revision == current.revision }
                    ?: guarded { witness.open(state, entries(current.active), current.staged?.let(::entries)) }.also {
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
                persist(current.next(state = guarded { c.state() }, active = emptyMap(), staged = null, cleared = true))
                runCatching { store.deleteInnerKey() }
                retiringDuty()
            }
        }
    }

    /**
     * The keeper's way out after a clear or a fence: they confirm, by naming
     * it, that [retiredSubject] was retired at the box. Only then is the old
     * installation (state, seed, route, keys) deleted, its subject and
     * installation recorded as tombstones never to be reused, and a fresh
     * enrolment allowed. Refused unless the marker is fenced and names exactly
     * that subject, so a healthy persona must be cleared first.
     */
    fun supersede(retiredSubject: String): Boolean {
        val marker = store.marker() ?: return false
        if (marker.state == Marker.State.Superseded) return marker.retired.any { it.subject == retiredSubject }
        if (!marker.fenced || marker.subject == null || marker.subject != retiredSubject) return false
        val installation = (store.read() as? FileRead.Present)?.file?.installation?.toHex() ?: marker.installation
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
                state = Marker.State.Superseded, reason = null, writer = null,
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
            val marker = store.marker() ?: Marker(persona, Marker.State.Fenced, CLEARED, null, null, null)
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
    }

    private fun entries(objects: Map<String, ByteArray>): List<CoordEntry> = guarded {
        objects.toSortedMap().map { (id, sealed) -> CoordEntry.Vault(hex(id), witness.objectHash(sealed)) }
    }

    private suspend fun read(request: ByteArray): WitnessAnswer = send { it.read(request) }
    private suspend fun advance(request: ByteArray): WitnessAnswer = send { it.advance(request) }

    private suspend fun send(call: suspend (WitnessChannel) -> WitnessAnswer): WitnessAnswer {
        val channel = channels.forPersona(persona, file?.witnessRoute) ?: return WitnessAnswer.Unavailable
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

        /** An interrupted genesis may be retried: the marker names exactly this prepared installation. */
        internal fun mayEnrol(marker: Marker?, file: PersonaFile): Boolean = when {
            marker == null || marker.state == Marker.State.Superseded -> true
            marker.state == Marker.State.Genesis -> marker.installation == file.installation.toHex()
            else -> false
        }
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
