package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.crypto.toHex

/**
 * One engine session as the host drives it. Debug builds implement it over
 * vmls-ffi's `VmlsSession`; the engine is debug-only until D1.
 */
interface HostedSession : AutoCloseable {
    /** The generation of the session's latest snapshot, acknowledged or not. */
    fun generation(): Long
    /** The engine's `commit_ack`: [generation]'s snapshot is sealed and witnessed, and the mark is [highWater]. */
    fun commitAck(generation: Long, highWater: Long)
}

/** Opens an engine session from its snapshot at the witnessed mark (`open_session`); wipe [plaintext] afterwards. */
fun interface SessionOpener<S : HostedSession> {
    fun open(session: ByteArray, plaintext: ByteArray, highWater: Long): S
}

/** A step's next snapshot, for the host to seal. The host wipes [plaintext]. */
class StepSnapshot(val session: ByteArray, val generation: Long, val plaintext: ByteArray)

/** What an engine call answered: its next snapshot, if it changed the session, and the caller's [value]. */
class EngineStep<R>(val snapshot: StepSnapshot?, val value: R)

/** How a hosted call ended. Only [Released] lets anything leave. */
sealed class Hosted<out R> {
    /** Witnessed and acknowledged: [value]'s effects may be released now. */
    data class Released<R>(val value: R) : Hosted<R>()
    /** The witness has not confirmed: nothing is released. A later call finishes what is staged. */
    data object Held : Hosted<Nothing>()
    data class Fenced(val reason: String) : Hosted<Nothing>()
    /** No such session in the witnessed manifest. */
    data object Unknown : Hosted<Nothing>()
}

/**
 * Runs MLS sessions under the persona's restore-witness coordinator
 * (P3-03b-3a, contract §4.3). Every call holds the persona's lock for the
 * whole step: open at the witnessed mark, the engine call, seal, stage,
 * witness, promote and `commit_ack`. A step whose snapshot is not promoted
 * is never acknowledged: its in-memory session is closed and the next call
 * reopens the witnessed one, so nothing unwitnessed is ever released and no
 * ratchet advances past the witness (offline, receive included).
 *
 * A step whose result was held is not lost: its records are in the snapshot's
 * outbox, which the session releases after the candidate is promoted. So the
 * driver sends from the outbox after every call, not from a step's result alone.
 *
 * Keep one host per vault: an open session is reused only while its snapshot
 * hash is still the witnessed one, but two hosts would each hold their own.
 * The persona lock is not reentrant, so a call must not use the vault for the
 * same persona.
 */
class SessionHost<S : HostedSession>(private val vault: MlsVault, private val opener: SessionOpener<S>) {
    /** An open session and the hash of the witnessed snapshot it stands for. */
    private class Open<S>(val session: S, val generation: Long, val hash: ByteArray)

    /**
     * Idle open sessions by persona and session id, changed only under
     * [guard]. A step checks its session out and puts it back when it ends,
     * so [closeAll] never closes one in use.
     */
    private val open = HashMap<String, Open<S>>()
    private val guard = Any()
    /** Raised by [closeAll]: a step that began before it caches nothing. */
    private var epoch = 0L

    /**
     * Runs [call] on [session] at its witnessed generation. A call that
     * changes the session is released only once its snapshot is witnessed.
     */
    suspend fun <R> step(persona: String, session: ByteArray, call: (S) -> EngineStep<R>): Hosted<R> = vault.underPersona(persona) { coord ->
        val began = synchronized(guard) { epoch }
        val id = sessionId(session)
        val key = key(persona, id)
        val (gate, marks) = coord.sessionMarks()
        when (gate) {
            Gate.Ready -> Unit
            is Gate.Fenced -> { closeAll(persona); return@underPersona Hosted.Fenced(gate.reason) }
            else -> return@underPersona Hosted.Held
        }
        val mark = marks?.get(id) ?: run { close(key); return@underPersona Hosted.Unknown }
        val handle = checkout(key, mark, coord.snapshotHash(id, mark)) ?: run {
            val (again, snapshot) = coord.sessionSnapshot(id)
            when (again) {
                Gate.Ready -> Unit
                is Gate.Fenced -> { closeAll(persona); return@underPersona Hosted.Fenced(again.reason) }
                else -> return@underPersona Hosted.Held
            }
            snapshot ?: return@underPersona Hosted.Unknown
            try {
                if (snapshot.generation != mark) throw MlsVaultUnavailableException(IllegalStateException("The session's mark moved"))
                opener.open(session.copyOf(), snapshot.plaintext, mark)
            } finally {
                snapshot.plaintext.fill(0)
            }
        }
        val markHash = coord.snapshotHash(id, mark)
        val step = try {
            if (markHash == null) throw MlsVaultUnavailableException(IllegalStateException("The snapshot's hash is unknown"))
            call(handle)
        } catch (error: Exception) {
            // A refused call leaves the engine as it was, but a fault may not: reopen next time.
            close(key, handle)
            throw error
        }
        commit(coord, persona, key, id, handle, step, previous = mark, previousHash = markHash!!, began = began)
    }

    /**
     * Adopts a session the engine has just made (a group created, or a
     * Welcome accepted): its first snapshot is witnessed before anything of
     * it is released.
     */
    suspend fun <R> create(persona: String, call: () -> Pair<S, EngineStep<R>>): Hosted<R> = vault.underPersona(persona) { coord ->
        val began = synchronized(guard) { epoch }
        when (val gate = coord.sessionMarks().first) {
            Gate.Ready -> Unit
            is Gate.Fenced -> return@underPersona Hosted.Fenced(gate.reason)
            else -> return@underPersona Hosted.Held
        }
        val (handle, step) = call()
        val snapshot = step.snapshot
        val id = try {
            if (snapshot == null) throw IllegalArgumentException("A new session has a first snapshot")
            sessionId(snapshot.session).also { id ->
                if (coord.sessionMarks().second?.containsKey(id) != false) throw MlsVaultUnavailableException(IllegalStateException("The session already exists"))
            }
        } catch (error: Exception) {
            runCatching { handle.close() }
            snapshot?.plaintext?.fill(0)
            throw error
        }
        val key = key(persona, id)
        // A handle left under this id (an earlier session dropped, then made again) is never this one.
        close(key)
        commit(coord, persona, key, id, handle, step, previous = 0, previousHash = null, began = began)
    }

    /** Takes [session] out of the witnessed manifest: the room is left or forgotten. */
    suspend fun drop(persona: String, session: ByteArray): Hosted<Unit> = vault.underPersona(persona) { coord ->
        val id = sessionId(session)
        close(key(persona, id))
        when (val outcome = coord.dropSession(id)) {
            CommitOutcome.Written -> Hosted.Released(Unit)
            CommitOutcome.Unchanged -> Hosted.Unknown
            CommitOutcome.Pending -> Hosted.Held
            is CommitOutcome.Fenced -> { closeAll(persona); Hosted.Fenced(outcome.reason) }
        }
    }

    /** Each session's witnessed generation, by session id in hex. */
    suspend fun sessions(persona: String): Hosted<Map<String, Long>> = vault.underPersona(persona) { coord ->
        val (gate, marks) = coord.sessionMarks()
        when (gate) {
            Gate.Ready -> Hosted.Released(marks.orEmpty())
            is Gate.Fenced -> { closeAll(persona); Hosted.Fenced(gate.reason) }
            else -> Hosted.Held
        }
    }

    /**
     * Closes every idle session, for a logout or a vault lock. A step already
     * running finishes, and then closes its session rather than keeping it.
     */
    fun closeAll() {
        val closing = synchronized(guard) {
            epoch++
            open.values.toList().also { open.clear() }
        }
        closing.forEach { runCatching { it.session.close() } }
    }

    private suspend fun <R> commit(
        coord: PersonaCoordination<EnrolledDevice>, persona: String, key: String, id: String,
        handle: S, step: EngineStep<R>, previous: Long, previousHash: ByteArray?, began: Long,
    ): Hosted<R> {
        val snapshot = step.snapshot ?: run {
            // Nothing changed: the session still stands for the witnessed snapshot it was opened at.
            if (previousHash != null) keep(began, key, Open(handle, previous, previousHash)) else close(key, handle)
            return Hosted.Released(step.value)
        }
        val outcome = try {
            if (!snapshot.session.contentEquals(sessionBytes(id)) || snapshot.generation <= previous || snapshot.generation != handle.generation()) {
                throw MlsVaultUnavailableException(IllegalStateException("The step's snapshot is not this session's next"))
            }
            coord.commitSession(id, snapshot.generation, snapshot.plaintext)
        } catch (error: Exception) {
            close(key, handle)
            throw error
        } finally {
            snapshot.plaintext.fill(0)
        }
        return when (outcome) {
            CommitOutcome.Written -> {
                try {
                    val witnessed = coord.sessionMarks().second?.get(id)
                    val hash = coord.snapshotHash(id, snapshot.generation)
                    if (witnessed != snapshot.generation || hash == null) {
                        throw MlsVaultUnavailableException(IllegalStateException("The witnessed generation is not the step's"))
                    }
                    handle.commitAck(snapshot.generation, snapshot.generation)
                    // After a logout the step is still witnessed and released, but its session is not kept.
                    keep(began, key, Open(handle, snapshot.generation, hash))
                } catch (error: Exception) {
                    close(key, handle)
                    throw error
                }
                Hosted.Released(step.value)
            }
            // Ahead of the witness in memory only: closed, and reopened from the witnessed snapshot.
            CommitOutcome.Pending, CommitOutcome.Unchanged -> { close(key, handle); Hosted.Held }
            is CommitOutcome.Fenced -> { close(key, handle); closeAll(persona); Hosted.Fenced(outcome.reason) }
        }
    }

    /**
     * Checks out the idle session under [key] if it still stands for the
     * witnessed snapshot at [mark] with [hash]; any other is closed.
     */
    private fun checkout(key: String, mark: Long, hash: ByteArray?): S? {
        val cached = synchronized(guard) { open.remove(key) } ?: return null
        val live = hash != null && cached.generation == mark && cached.hash.contentEquals(hash) &&
            runCatching { cached.session.generation() == mark }.getOrDefault(false)
        if (live) return cached.session
        runCatching { cached.session.close() }
        return null
    }

    /** Caches [entry] unless [closeAll] ran since [began], when it is closed instead. */
    private fun keep(began: Long, key: String, entry: Open<S>): Boolean {
        var kept = false
        val replaced = synchronized(guard) {
            if (epoch != began) return@synchronized null
            kept = true
            open.put(key, entry)
        }
        if (!kept) runCatching { entry.session.close() }
        if (replaced != null && replaced.session !== entry.session) runCatching { replaced.session.close() }
        return kept
    }

    private fun close(key: String, also: S? = null) {
        val removed = synchronized(guard) { open.remove(key) }
        removed?.let { runCatching { it.session.close() } }
        if (also != null && also !== removed?.session) runCatching { also.close() }
    }

    private fun closeAll(persona: String) {
        val closing = synchronized(guard) {
            open.keys.filter { it.startsWith("$persona:") }.mapNotNull { open.remove(it) }
        }
        closing.forEach { runCatching { it.session.close() } }
    }

    private companion object {
        fun key(persona: String, id: String) = "$persona:$id"

        fun sessionId(session: ByteArray): String {
            require(session.size == 32)
            return session.toHex()
        }

        fun sessionBytes(id: String): ByteArray = PersonaFile.hex32(id)
    }
}
