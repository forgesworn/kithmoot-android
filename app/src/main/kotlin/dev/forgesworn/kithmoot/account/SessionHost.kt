package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.crypto.toHex
import java.util.concurrent.ConcurrentHashMap

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
 */
class SessionHost<S : HostedSession>(private val vault: MlsVault, private val opener: SessionOpener<S>) {
    /** Open sessions by persona and session id, each at its witnessed generation. */
    private val open = ConcurrentHashMap<String, S>()

    /**
     * Runs [call] on [session] at its witnessed generation. A call that
     * changes the session is released only once its snapshot is witnessed.
     */
    suspend fun <R> step(persona: String, session: ByteArray, call: (S) -> EngineStep<R>): Hosted<R> = vault.underPersona(persona) { coord ->
        val id = sessionId(session)
        val key = key(persona, id)
        val (gate, marks) = coord.sessionMarks()
        when (gate) {
            Gate.Ready -> Unit
            is Gate.Fenced -> { closeAll(persona); return@underPersona Hosted.Fenced(gate.reason) }
            else -> return@underPersona Hosted.Held
        }
        val mark = marks?.get(id) ?: run { close(key); return@underPersona Hosted.Unknown }
        val handle = open[key]?.takeIf { it.generation() == mark } ?: run {
            close(key)
            val (again, snapshot) = coord.sessionSnapshot(id)
            when (again) {
                Gate.Ready -> Unit
                is Gate.Fenced -> { closeAll(persona); return@underPersona Hosted.Fenced(again.reason) }
                else -> return@underPersona Hosted.Held
            }
            snapshot ?: return@underPersona Hosted.Unknown
            if (snapshot.generation != mark) throw MlsVaultUnavailableException(IllegalStateException("The session's mark moved"))
            try { opener.open(session.copyOf(), snapshot.plaintext, mark) } finally { snapshot.plaintext.fill(0) }.also { open[key] = it }
        }
        val step = try { call(handle) } catch (error: Exception) {
            // A refused call leaves the engine as it was, but a fault may not: reopen next time.
            close(key)
            throw error
        }
        commit(coord, persona, key, id, handle, step, previous = mark)
    }

    /**
     * Adopts a session the engine has just made (a group created, or a
     * Welcome accepted): its first snapshot is witnessed before anything of
     * it is released.
     */
    suspend fun <R> create(persona: String, call: () -> Pair<S, EngineStep<R>>): Hosted<R> = vault.underPersona(persona) { coord ->
        when (val gate = coord.sessionMarks().first) {
            Gate.Ready -> Unit
            is Gate.Fenced -> return@underPersona Hosted.Fenced(gate.reason)
            else -> return@underPersona Hosted.Held
        }
        val (handle, step) = call()
        val snapshot = step.snapshot ?: run { handle.close(); throw IllegalArgumentException("A new session has a first snapshot") }
        val id = sessionId(snapshot.session)
        val key = key(persona, id)
        if (coord.sessionMarks().second?.containsKey(id) != false) {
            handle.close()
            snapshot.plaintext.fill(0)
            throw MlsVaultUnavailableException(IllegalStateException("The session already exists"))
        }
        commit(coord, persona, key, id, handle, step, previous = 0)
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

    /** Closes every open session, for a logout or a vault lock. */
    fun closeAll() {
        for (key in open.keys.toList()) close(key)
    }

    private suspend fun <R> commit(
        coord: PersonaCoordination<EnrolledDevice>, persona: String, key: String, id: String,
        handle: S, step: EngineStep<R>, previous: Long,
    ): Hosted<R> {
        val snapshot = step.snapshot ?: return Hosted.Released(step.value)
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
                val witnessed = coord.sessionMarks().second?.get(id)
                if (witnessed != snapshot.generation) {
                    close(key, handle)
                    throw MlsVaultUnavailableException(IllegalStateException("The witnessed generation is not the step's"))
                }
                try { handle.commitAck(snapshot.generation, snapshot.generation) } catch (error: Exception) { close(key, handle); throw error }
                open[key] = handle
                Hosted.Released(step.value)
            }
            // Ahead of the witness in memory only: closed, and reopened from the witnessed snapshot.
            CommitOutcome.Pending, CommitOutcome.Unchanged -> { close(key, handle); Hosted.Held }
            is CommitOutcome.Fenced -> { close(key, handle); closeAll(persona); Hosted.Fenced(outcome.reason) }
        }
    }

    private fun close(key: String, also: S? = null) {
        open.remove(key)?.let { runCatching { it.close() } }
        if (also != null) runCatching { also.close() }
    }

    private fun closeAll(persona: String) {
        for (key in open.keys.filter { it.startsWith("$persona:") }) close(key)
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
