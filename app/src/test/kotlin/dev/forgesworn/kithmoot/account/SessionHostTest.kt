package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.crypto.toHex
import java.nio.ByteBuffer
import java.security.SecureRandom
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * MLS sessions under the restore-witness coordinator (P3-03b-3a), against the
 * Kotlin model of the core, a fake witness with bothy's semantics, and a
 * model engine session whose snapshot is its id, generation and a counter.
 */
class SessionHostTest {
    private val random = SecureRandom()

    private lateinit var stores: MemoryCoordinatedStores
    private lateinit var server: FakeWitnessServer
    private lateinit var witness: FakeVaultWitness
    private lateinit var vault: MlsVault
    private lateinit var host: SessionHost<ModelSession>
    private lateinit var opened: MutableList<ModelSession>
    private val persona = bytes(32).toHex()
    private val subject = bytes(32)
    private val session = bytes(32)
    private val id = session.toHex()

    @BeforeTest fun setup() {
        stores = MemoryCoordinatedStores()
        server = FakeWitnessServer(bytes(32))
        witness = FakeVaultWitness()
        opened = mutableListOf()
        vault = vault()
        host = host(vault)
    }

    private fun vault(on: MemoryCoordinatedStores = stores) =
        MlsVault.coordinated(VaultCoordination(on, WitnessChannels { _, _, _ -> server.channel }, witness))

    private fun host(v: MlsVault) = SessionHost(v) { s, plain, mark -> ModelSession.open(s, plain, mark).also { opened += it } }

    private suspend fun enrolAtBox(v: MlsVault = vault) {
        val genesis = (v.beginCoordination(persona, subject, server.key) as VaultResult.Ok).value
        server.enrol(genesis.subject, genesis.initialDigest)
        assertEquals(CoordinationStatus.Active, v.coordinationStatus(persona, check = true))
    }

    private suspend fun created(h: SessionHost<ModelSession> = host): Hosted<String> =
        h.create(persona) { ModelSession.fresh(session).let { it to EngineStep(it.next(), "created") } }

    private suspend fun bump(h: SessionHost<ModelSession> = host): Hosted<Long> =
        h.step(persona, session) { s -> EngineStep(s.next(), s.counter) }

    private fun seq() = server.subjects.getValue(subject.toHex()).seq
    private fun marks(h: SessionHost<ModelSession> = host) = runBlocking { (h.sessions(persona) as Hosted.Released).value }
    private fun files() = stores.snapshotNames().filter { ".snap.$id." in it }

    // ---- witnessed before released ----

    @Test fun `a new session is witnessed before it is released, and acknowledged at its first generation`() = runBlocking<Unit> {
        enrolAtBox()
        assertEquals(Hosted.Released("created"), created())
        assertEquals(1L, seq())
        assertEquals(mapOf(id to 1L), marks())
        assertEquals(1L, opened.lastOrNull()?.acked ?: ModelSession.lastFresh!!.acked)
        assertEquals(1, files().size)
    }

    @Test fun `each step advances the witness once, acknowledges only then, and sweeps the old snapshot`() = runBlocking<Unit> {
        enrolAtBox(); created()
        assertEquals(Hosted.Released(1L), bump())
        assertEquals(Hosted.Released(2L), bump())
        assertEquals(3L, seq())
        assertEquals(mapOf(id to 3L), marks())
        assertEquals(listOf("${stores.coordinatedName()}.snap.$id.3"), files())
        assertEquals(3L, ModelSession.lastFresh!!.acked)
    }

    @Test fun `a call that changes nothing is released without a witness round trip`() = runBlocking<Unit> {
        enrolAtBox(); created()
        val advances = server.advances
        assertEquals(Hosted.Released(7), host.step(persona, session) { EngineStep(null, 7) })
        assertEquals(advances, server.advances)
    }

    @Test fun `an unknown session is answered as unknown, and nothing is written`() = runBlocking<Unit> {
        enrolAtBox()
        assertEquals(Hosted.Unknown, bump())
        assertEquals(0L, seq())
    }

    @Test fun `before genesis nothing is created`() = runBlocking<Unit> {
        assertEquals(Hosted.Held, created())
        assertTrue(stores.snapshotNames().isEmpty())
    }

    // ---- held: offline, lost answers ----

    @Test fun `with the witness down a step is held, never acknowledged, and its session is closed`() = runBlocking<Unit> {
        enrolAtBox(); created()
        val live = ModelSession.lastFresh!!
        server.mode = FakeWitnessServer.Mode.Down
        assertEquals(Hosted.Held, bump())
        assertEquals(1L, live.acked)
        assertTrue(live.closed)
        // Offline, no further step runs, receive included: the persona is pending.
        assertEquals(Hosted.Held, bump())
        assertEquals(1L, seq())
    }

    @Test fun `a held step is promoted first, and the next step runs on the witnessed snapshot it left`() = runBlocking<Unit> {
        enrolAtBox(); created()
        server.mode = FakeWitnessServer.Mode.LoseAnswer
        assertEquals(Hosted.Held, bump())
        assertEquals(2L, seq())
        server.mode = FakeWitnessServer.Mode.Up
        // The held step (counter 0 -> 1) is promoted; this one moves it to 2.
        assertEquals(Hosted.Released(2L), bump())
        assertEquals(3L, seq())
        assertEquals(mapOf(id to 3L), marks())
        assertEquals(2L, opened.last().openedAt)
    }

    @Test fun `a held new session appears once the witness confirms it`() = runBlocking<Unit> {
        enrolAtBox()
        server.mode = FakeWitnessServer.Mode.LoseAnswer
        assertEquals(Hosted.Held, created())
        server.mode = FakeWitnessServer.Mode.Up
        assertEquals(mapOf(id to 1L), marks())
        assertEquals(Hosted.Released(1L), bump())
    }

    @Test fun `a restart with a staged step finishes exactly that candidate`() = runBlocking<Unit> {
        enrolAtBox(); created()
        server.mode = FakeWitnessServer.Mode.Down
        assertEquals(Hosted.Held, bump())
        server.mode = FakeWitnessServer.Mode.Up
        val restarted = host(vault())
        assertEquals(Hosted.Released(2L), bump(restarted))
        assertEquals(3L, seq())
    }

    @Test fun `a snapshot write that fails stages nothing, and the session reopens at its witnessed generation`() = runBlocking<Unit> {
        enrolAtBox(); created()
        stores.failNextSnapshotWrite = true
        assertFailsWith<MlsVaultUnavailableException> { bump() }
        assertEquals(1L, seq())
        // The failed step's counter never became current.
        assertEquals(Hosted.Released(1L), bump())
        assertEquals(1L, opened.last().openedAt)
    }

    @Test fun `an engine refusal changes nothing and the next step reopens`() = runBlocking<Unit> {
        enrolAtBox(); created()
        assertFailsWith<IllegalStateException> { host.step<Unit>(persona, session) { error("refused") } }
        assertEquals(Hosted.Released(1L), bump())
        assertEquals(2L, seq())
    }

    @Test fun `a step whose generation does not advance is refused before anything is written`() = runBlocking<Unit> {
        enrolAtBox(); created()
        assertFailsWith<MlsVaultUnavailableException> {
            host.step(persona, session) { s -> EngineStep(StepSnapshot(session, 1, s.encode()), Unit) }
        }
        assertEquals(1L, seq())
    }

    // ---- fences ----

    @Test fun `a missing snapshot file fences the persona at open`() = runBlocking<Unit> {
        enrolAtBox(); created(); bump()
        stores.removeSnapshot(files().single())
        assertEquals(Hosted.Fenced("local-state-mismatch"), bump(host(vault())))
    }

    @Test fun `a missing staged snapshot fences as a corrupt stage`() = runBlocking<Unit> {
        enrolAtBox(); created()
        server.mode = FakeWitnessServer.Mode.Down
        assertEquals(Hosted.Held, bump())
        stores.removeSnapshot("${stores.coordinatedName()}.snap.$id.2")
        server.mode = FakeWitnessServer.Mode.Up
        assertEquals(Hosted.Fenced("stage-corrupt"), bump(host(vault())))
    }

    @Test fun `a snapshot replaced on disk after open is never opened, and the next open fences`() = runBlocking<Unit> {
        enrolAtBox(); created(); bump()
        host.closeAll()
        val name = files().single()
        val other = stores.snapshotBytes(name)!!.also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        stores.putSnapshot(name, other)
        assertFailsWith<MlsVaultUnavailableException> { bump() }
        assertEquals(Hosted.Fenced("local-state-mismatch"), bump())
    }

    @Test fun `a restored profile copy is fenced once the original stepped on`() = runBlocking<Unit> {
        enrolAtBox(); created()
        val copy = stores.copy()
        assertEquals(Hosted.Released(1L), bump())
        val restored = host(vault(copy))
        assertEquals(Hosted.Fenced("witness-mismatch"), restored.step(persona, session) { s -> EngineStep(s.next(), s.counter) })
    }

    @Test fun `a transient snapshot read at open is unavailable, never a fence`() = runBlocking<Unit> {
        enrolAtBox(); created(); bump()
        val restarted = host(vault())
        stores.failNextSnapshotRead = true
        assertFailsWith<MlsVaultUnavailableException> { bump(restarted) }
        assertEquals(Hosted.Released(2L), bump(restarted))
    }

    // ---- the persona record and dropping ----

    @Test fun `a record write keeps the sessions, and dropping a session sweeps its files`() = runBlocking<Unit> {
        enrolAtBox(); created(); bump()
        val ctx = vault.context("dev.forgesworn.kithmoot", persona)
        assertIs<VaultResult.Ok<Unit>>(vault.revokeCredential(ctx, bytes(32).toHex()))
        assertEquals(mapOf(id to 2L), marks())
        assertEquals(Hosted.Released(Unit), host.drop(persona, session))
        assertEquals(emptyMap(), marks())
        assertTrue(files().isEmpty())
        assertEquals(Hosted.Unknown, host.drop(persona, session))
    }

    @Test fun `a format 1 persona file still opens, with no sessions`() {
        val file = PersonaFile.fresh(persona, bytes(32), 5, bytes(32))
        val v2 = file.encode()
        // Format 1 is format 2 without the trailing empty session list.
        val v1 = byteArrayOf(1) + v2.copyOfRange(1, v2.size - 4)
        val decoded = PersonaFile.decode(v1, persona)
        assertEquals(emptyMap(), decoded.sessions)
        assertNull(decoded.stagedSessions)
        assertTrue(decoded.encode().contentEquals(v2))
    }

    private fun bytes(n: Int) = ByteArray(n).also(random::nextBytes)
}

/** A model engine session: its snapshot is (id, generation, counter), and it refuses rollback as the engine does. */
class ModelSession private constructor(val id: ByteArray, var generation: Long, var acked: Long, var counter: Long, val openedAt: Long) : HostedSession {
    var closed = false

    override fun generation(): Long = generation

    override fun commitAck(generation: Long, highWater: Long) {
        check(!closed)
        require(generation in 1..this.generation && highWater >= generation)
        if (generation > acked) acked = generation
    }

    /** One mutation: the counter moves, and a new generation's snapshot comes out unacknowledged. */
    fun next(): StepSnapshot {
        check(!closed && acked == generation) { "AwaitingCommitAck" }
        counter++
        generation++
        return StepSnapshot(id.copyOf(), generation, encode())
    }

    fun encode(): ByteArray = ByteBuffer.allocate(48).put(id).putLong(generation).putLong(counter).array()

    override fun close() { closed = true }

    companion object {
        var lastFresh: ModelSession? = null

        /** A session the engine has just made: generation 0, its first step makes generation 1. */
        fun fresh(id: ByteArray): ModelSession = ModelSession(id.copyOf(), 0, 0, -1, 0).also { lastFresh = it }

        fun open(id: ByteArray, plain: ByteArray, highWater: Long): ModelSession {
            val buffer = ByteBuffer.wrap(plain)
            val stored = ByteArray(32).also(buffer::get)
            require(stored.contentEquals(id))
            val generation = buffer.getLong()
            check(generation >= highWater) { "Rollback" }
            return ModelSession(id, generation, highWater, buffer.getLong(), generation).also { lastFresh = it }
        }
    }
}
