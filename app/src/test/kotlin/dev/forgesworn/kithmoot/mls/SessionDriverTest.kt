package dev.forgesworn.kithmoot.mls

import dev.forgesworn.kithmoot.account.ConsentDecision
import dev.forgesworn.kithmoot.account.ConsentPrompt
import dev.forgesworn.kithmoot.account.CoordinationStatus
import dev.forgesworn.kithmoot.account.EngineStep
import dev.forgesworn.kithmoot.account.FakeVaultWitness
import dev.forgesworn.kithmoot.account.FakeWitnessServer
import dev.forgesworn.kithmoot.account.Hosted
import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.account.MemoryCoordinatedStores
import dev.forgesworn.kithmoot.account.MlsVault
import dev.forgesworn.kithmoot.account.SessionHost
import dev.forgesworn.kithmoot.account.StepSnapshot
import dev.forgesworn.kithmoot.account.VaultContext
import dev.forgesworn.kithmoot.account.VaultCoordination
import dev.forgesworn.kithmoot.account.VaultResult
import dev.forgesworn.kithmoot.account.WitnessChannels
import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.relay.LinkJsonRequest
import dev.forgesworn.kithmoot.relay.LinkJsonResponse
import dev.forgesworn.kithmoot.relay.LinkJsonTransport
import dev.forgesworn.kithmoot.relay.LinkPathState
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CompletableFuture
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The driver loop (P3-03b-3a) against a model engine, a fake box behind the
 * real box client, and the real coordinated vault as the request signer, so
 * a box request made inside a step would deadlock on the persona lock and
 * time the test out.
 */
class SessionDriverTest {
    private val random = SecureRandom()
    private val principal = "dev.forgesworn.kithmoot"
    private val box = bytes(32)
    private val otherBox = bytes(32)
    private val installation = bytes(32)
    private val session = bytes(32)

    private lateinit var stores: MemoryCoordinatedStores
    private lateinit var server: FakeWitnessServer
    private lateinit var vault: MlsVault
    private lateinit var alice: LocalSigner
    private lateinit var ctx: VaultContext
    private lateinit var world: World
    private lateinit var fake: FakeBox
    private lateinit var host: SessionHost<ModelDriverSession>
    private lateinit var driver: SessionDriver<ModelDriverSession>
    private val events = mutableListOf<Any>()

    @BeforeTest fun setup() = runBlocking<Unit> {
        stores = MemoryCoordinatedStores()
        server = FakeWitnessServer(bytes(32))
        vault = MlsVault.coordinated(VaultCoordination(stores, WitnessChannels { _, _, _ -> server.channel }, FakeVaultWitness()))
        alice = LocalSigner(secret())
        ctx = vault.context(principal, alice.pubkey)
        val genesis = (vault.beginCoordination(alice.pubkey, bytes(32), server.key) as VaultResult.Ok).value
        server.enrol(genesis.subject, genesis.initialDigest)
        assertEquals(CoordinationStatus.Active, vault.coordinationStatus(alice.pubkey, check = true))
        assertIs<VaultResult.Ok<*>>(vault.enrol(ctx, alice, System.currentTimeMillis() / 1000 + 86_400))
        world = World()
        fake = FakeBox(box.toHex(), installation)
        host = SessionHost(vault) { _, plain, mark -> ModelDriverSession.open(world, plain, mark) }
        val client = VmlsBoxClient(fake, "route", box.toHex(), { vault.signBoxRequestV1(ctx, it, ConsentPrompt { ConsentDecision.Approve }) })
        driver = SessionDriver(host, client, box, { body -> String(body).takeIf { it.startsWith("{\"v\":1") }?.let { installation.copyOf() } }, { events.addAll(it) })
        assertIs<Hosted.Released<Unit>>(host.create(alice.pubkey) { ModelDriverSession(world, session, 0, 0).let { s -> s to EngineStep(s.next(), Unit) } })
    }

    private suspend fun round(): Round = withTimeout(10_000) { driver.round(alice.pubkey, session, 1_000) }

    // ---- capabilities ----

    @Test fun `no capabilities reply holds the round, so nothing is sent, fetched or told to the engine`() = runBlocking<Unit> {
        world.outbox += Outgoing(bytes(32), bytes(32), Destination.Leaf(box), bytes(64))
        fake.capabilities = null
        assertEquals(Round.Offline(BoxAnswer.Unreachable), round())
        assertEquals(listOf("GET /vmls/v1/capabilities"), fake.requests)
        assertTrue(world.calls.isEmpty())
        fake.capabilities = 403
        assertIs<Round.Offline>(round())
        assertTrue(world.calls.isEmpty())
    }

    @Test fun `the engine is given the box's installation on every reply, then ticks`() = runBlocking<Unit> {
        assertEquals(Round.Done(0, 0, 0), round())
        assertEquals(listOf("installation ${installation.toHex()}", "tick 1000"), world.calls)
    }

    // ---- the outbox ----

    @Test fun `records are deposited and then marked delivered in one witnessed step`() = runBlocking<Unit> {
        val a = Outgoing(bytes(32), bytes(32), Destination.Leaf(box), bytes(64))
        val b = Outgoing(bytes(32), bytes(32), Destination.Evidence(box), bytes(64))
        world.outbox += listOf(a, b)
        assertEquals(Round.Done(2, 0, 0), round())
        assertTrue(fake.records[a.mailbox.toHex()]!!.single().contentEquals(a.envelope))
        assertTrue(world.calls.contains("delivered ${a.recordId.toHex()},${b.recordId.toHex()}"))
        assertTrue(world.outbox.isEmpty())
    }

    @Test fun `a deposit with no answer stops the outbox and marks nothing`() = runBlocking<Unit> {
        world.outbox += Outgoing(bytes(32), bytes(32), Destination.Leaf(box), bytes(64))
        fake.downAfterCapabilities = true
        assertEquals(Round.Done(0, 0, 0, stalled = true), round())
        assertTrue(world.calls.none { it.startsWith("delivered") })
        assertEquals(1, world.outbox.size)
    }

    @Test fun `a commit slot's signed receipt goes to the engine at the attempt deposited`() = runBlocking<Unit> {
        val slot = bytes(32)
        world.outbox += Outgoing(bytes(32), slot, Destination.Slot(box, 7, 4), bytes(64))
        round()
        assertTrue(world.calls.any { it == "deposit_result 4 ${slot.toHex()}" }, world.calls.toString())
        // Not yet durable: no receipt, so no deposit_result; the status is read instead.
        world.outbox += Outgoing(bytes(32), bytes(32), Destination.Slot(box, 7, 5), bytes(64))
        fake.signSlots = false
        world.calls.clear()
        round()
        assertTrue(world.calls.none { it.startsWith("deposit_result") })
    }

    @Test fun `a Welcome stays in the outbox until the box shows it acknowledged, then the member is confirmed`() = runBlocking<Unit> {
        val pkg = bytes(32)
        val welcome = Outgoing(bytes(32), bytes(32), Destination.Welcome(pkg), bytes(64))
        world.outbox += welcome
        round()
        assertEquals(1, world.outbox.size)
        assertTrue(world.calls.none { it.startsWith("confirm") })
        fake.welcomeAcknowledged = true
        round()
        assertTrue(world.calls.contains("confirm ${pkg.toHex()}"))
        assertTrue(world.outbox.isEmpty())
        assertEquals(2, fake.records[welcome.mailbox.toHex()]!!.size + fake.duplicates)
    }

    @Test fun `a record for another box is held and counted, never sent`() = runBlocking<Unit> {
        world.outbox += Outgoing(bytes(32), bytes(32), Destination.Leaf(otherBox), bytes(64))
        assertEquals(Round.Done(0, 0, 1), round())
        assertTrue(fake.requests.none { it.startsWith("PUT") })
    }

    // ---- fetching ----

    @Test fun `fetched records are processed and acknowledged only as the engine's ack allows`() = runBlocking<Unit> {
        val mailbox = bytes(32)
        world.watch += Watched(mailbox, Watched.Kind.Mailbox(false), box)
        val now = fake.put(mailbox, bytes(50))
        val after = fake.put(mailbox, bytes(51))
        val keep = fake.put(mailbox, bytes(52))
        world.acks[Digests.sha256(now).toHex()] = AckRule.Now
        world.acks[Digests.sha256(after).toHex()] = AckRule.AfterStep
        world.acks[Digests.sha256(keep).toHex()] = AckRule.Keep
        assertEquals(Round.Done(0, 3, 0), round())
        assertEquals(setOf(Digests.sha256(now).toHex(), Digests.sha256(after).toHex()), fake.acked)
    }

    @Test fun `a step the witness cannot take stops the round, and its record is not acknowledged`() = runBlocking<Unit> {
        val mailbox = bytes(32)
        world.watch += Watched(mailbox, Watched.Kind.Mailbox(false), box)
        val first = fake.put(mailbox, bytes(50))
        val second = fake.put(mailbox, bytes(51))
        world.acks[Digests.sha256(first).toHex()] = AckRule.Now
        world.acks[Digests.sha256(second).toHex()] = AckRule.AfterStep
        world.downOnProcess = Digests.sha256(second).toHex()
        world.server = server
        assertEquals(Round.Held, round())
        // The held record is never acknowledged. Nor, here, is the first: with the persona
        // pending the vault signs nothing, so it is fetched again and answered as a duplicate.
        assertTrue(Digests.sha256(second).toHex() !in fake.acked)
        assertTrue(world.calls.count { it.startsWith("process") } == 2)
    }

    @Test fun `pages are followed to the end, up to the round's bound`() = runBlocking<Unit> {
        val mailbox = bytes(32)
        world.watch += Watched(mailbox, Watched.Kind.Mailbox(false), box)
        repeat(5) { fake.put(mailbox, bytes(40 + it)) }
        fake.pageSize = 2
        assertEquals(Round.Done(0, 5, 0), round())
    }

    @Test fun `a departed epoch's mailbox answered empty is reported drained, a current one never`() = runBlocking<Unit> {
        val retained = bytes(32); val own = bytes(32); val busy = bytes(32)
        world.watch += listOf(
            Watched(retained, Watched.Kind.Mailbox(true), box),
            Watched(own, Watched.Kind.Mailbox(false), box),
            Watched(busy, Watched.Kind.Mailbox(true), box),
        )
        world.acks[Digests.sha256(fake.put(busy, bytes(50))).toHex()] = AckRule.Keep
        round()
        assertTrue(world.calls.contains("drained ${retained.toHex()}"))
        assertTrue(world.calls.none { it == "drained ${own.toHex()}" || it == "drained ${busy.toHex()}" })
    }

    @Test fun `a Welcome mailbox's record is processed with the home box's installation`() = runBlocking<Unit> {
        val mailbox = bytes(32)
        world.watch += Watched(mailbox, Watched.Kind.Welcome, box)
        fake.put(mailbox, bytes(50))
        round()
        assertTrue(world.calls.any { it.startsWith("process ${mailbox.toHex()}") && it.endsWith("installation ${installation.toHex()}") }, world.calls.toString())
    }

    // ---- commit slots and receipts ----

    @Test fun `watched slots are read through their status, filled is processed, void and expired are reported, empty is nothing`() = runBlocking<Unit> {
        val filled = bytes(32); val void = bytes(32); val expired = bytes(32); val empty = bytes(32)
        world.watch += listOf(
            Watched(filled, Watched.Kind.Slot(1), box), Watched(void, Watched.Kind.Slot(2), box),
            Watched(expired, Watched.Kind.Slot(3), box), Watched(empty, Watched.Kind.Slot(4), box),
        )
        fake.slots[filled.toHex()] = Triple("filled", 1L, bytes(70))
        fake.slots[void.toHex()] = Triple("void", 9L, bytes(70))
        fake.slots[expired.toHex()] = Triple("expired", 3L, bytes(70))
        round()
        assertTrue(world.calls.any { it.startsWith("process ${filled.toHex()}") && it.contains("receipt") }, world.calls.toString())
        assertTrue(world.calls.contains("slot_status 2 Void"))
        assertTrue(world.calls.contains("slot_status 3 Expired"))
        assertTrue(world.calls.none { it.contains(empty.toHex()) || it == "slot_status 4 Void" })
    }

    @Test fun `an OrderingUnconfirmed asks the box for that slot's receipt`() = runBlocking<Unit> {
        val slot = bytes(32)
        world.unconfirmOnTick = slot to 6L
        fake.slots[slot.toHex()] = Triple("filled", 6L, bytes(70))
        round()
        assertTrue(world.calls.contains("observe_receipt ${slot.toHex()}"), world.calls.toString())
        assertTrue(events.isNotEmpty())
    }

    @Test fun `every request was signed by the vault, outside every step`() = runBlocking<Unit> {
        world.outbox += Outgoing(bytes(32), bytes(32), Destination.Leaf(box), bytes(64))
        world.watch += Watched(bytes(32), Watched.Kind.Mailbox(false), box)
        assertIs<Round.Done>(round())
        assertTrue(fake.requests.size >= 3)
        assertTrue(fake.authorizations.all { it.startsWith("Nostr ") })
    }

    // ---- phases and refusals (review of #165) ----

    @Test fun `a pending join is never given the installation, and still fetches its Welcome`() = runBlocking<Unit> {
        world.phase = Phase.PendingJoin
        world.epoch = null
        val welcome = bytes(32)
        world.watch += Watched(welcome, Watched.Kind.Welcome, box)
        fake.put(welcome, bytes(50))
        assertIs<Round.Done>(round())
        assertTrue(world.calls.none { it.startsWith("installation") })
        assertTrue(world.calls.any { it.startsWith("process ${welcome.toHex()}") })
    }

    @Test fun `removal, expiry and recoveries other than a gap drive nothing, a gap still fetches but deposits no commit`() = runBlocking<Unit> {
        for (phase in listOf(Phase.Removed, Phase.Expired, Phase.NeedsRecovery("RestoreFenced"), Phase.NeedsRecovery("Fork"))) {
            world.phase = phase
            assertEquals(Round.Stopped(phase), round())
        }
        world.phase = Phase.NeedsRecovery("Gap")
        val evidence = bytes(32)
        world.watch += Watched(evidence, Watched.Kind.Mailbox(false), box)
        fake.put(evidence, bytes(50))
        world.outbox += Outgoing(bytes(32), bytes(32), Destination.Slot(box, 7, 1), bytes(64))
        assertIs<Round.Done>(round())
        assertTrue(fake.requests.none { it.startsWith("PUT /vmls/v1/slots") })
        assertEquals(1, world.outbox.size)
        assertTrue(world.calls.any { it.startsWith("process ${evidence.toHex()}") })
    }

    @Test fun `an earlier epoch's commit record leaves the outbox without being deposited again`() = runBlocking<Unit> {
        val stale = Outgoing(bytes(32), bytes(32), Destination.Slot(box, 6, 2), bytes(64))
        world.outbox += stale
        assertEquals(1, (round() as Round.Done).delivered)
        assertTrue(fake.requests.none { it.startsWith("PUT /vmls/v1/slots") })
        assertTrue(world.outbox.isEmpty())
    }

    @Test fun `a refused deposit holds only its own record, no answer holds the rest, a gone Welcome leaves`() = runBlocking<Unit> {
        val refused = Outgoing(bytes(32), bytes(32), Destination.Leaf(box), bytes(64))
        val ok = Outgoing(bytes(32), bytes(32), Destination.Leaf(box), bytes(64))
        val gone = Outgoing(bytes(32), bytes(32), Destination.Welcome(bytes(32)), bytes(64))
        world.outbox += listOf(refused, ok, gone)
        fake.refuseDeposit[refused.mailbox.toHex()] = 403 to "authority"
        fake.refuseDeposit[gone.mailbox.toHex()] = 410 to "expired"
        val done = round() as Round.Done
        assertEquals(2, done.delivered)
        assertEquals(listOf(refused.recordId.toHex()), world.outbox.map { it.recordId.toHex() })
        // No answer at all: the box may have it, so the rest waits.
        val silent = Outgoing(bytes(32), bytes(32), Destination.Leaf(box), bytes(64))
        val after = Outgoing(bytes(32), bytes(32), Destination.Leaf(box), bytes(64))
        world.outbox.clear(); world.outbox += listOf(silent, after)
        fake.silent += silent.mailbox.toHex()
        val stalled = round() as Round.Done
        assertTrue(stalled.stalled)
        assertEquals(0, stalled.delivered)
        assertTrue(fake.records[after.mailbox.toHex()] == null)
    }

    @Test fun `an engine refusal changes nothing and the round goes on`() = runBlocking<Unit> {
        world.refuse["deposit_result"] = "UnexpectedSlot"
        val slot = Outgoing(bytes(32), bytes(32), Destination.Slot(box, 7, 3), bytes(64))
        val leaf = Outgoing(bytes(32), bytes(32), Destination.Leaf(box), bytes(64))
        world.outbox += listOf(slot, leaf)
        assertEquals(2, (round() as Round.Done).delivered)
        world.refuse["process"] = "WrongPhase"
        val mailbox = bytes(32)
        world.watch += Watched(mailbox, Watched.Kind.Mailbox(false), box)
        val env = fake.put(mailbox, bytes(50))
        assertIs<Round.Done>(round())
        assertTrue(Digests.sha256(env).toHex() !in fake.acked, "a refused record is left at the box")
    }

    @Test fun `an OrderingUnconfirmed query is kept until the box can answer it`() = runBlocking<Unit> {
        val slot = bytes(32)
        world.unconfirmOnTick = slot to 6L
        round()
        assertTrue(world.calls.none { it.startsWith("observe_receipt") })
        fake.slots[slot.toHex()] = Triple("filled", 6L, bytes(70))
        round()
        assertTrue(world.calls.contains("observe_receipt ${slot.toHex()}"))
        world.calls.clear()
        round()
        assertTrue(world.calls.none { it.startsWith("observe_receipt") })
    }

    private fun bytes(n: Int) = ByteArray(n).also(random::nextBytes)
    private fun secret(): ByteArray { while (true) { val k = bytes(32); if (runCatching { Schnorr.publicKey(k) }.isSuccess) return k } }
}

/** The model engine's state, shared by every session opened over it, as the engine's snapshot would carry. */
internal class World {
    val outbox = mutableListOf<Outgoing>()
    val watch = mutableListOf<Watched>()
    val calls = mutableListOf<String>()
    val acks = mutableMapOf<String, AckRule>()
    var unconfirmOnTick: Pair<ByteArray, Long>? = null
    var phase: Phase = Phase.Active
    var epoch: Long? = 7
    /** Engine calls refused with a code, by the call's first word. */
    val refuse = mutableMapOf<String, String>()
    /** The witness goes down while this record's step is being witnessed. */
    var downOnProcess: String? = null
    var server: FakeWitnessServer? = null
}

/** A model engine session: every mutation is a new generation; its state lives in [world]. */
internal class ModelDriverSession(private val world: World, val id: ByteArray, private var generation: Long, private var acked: Long) : DriverSession {
    override fun generation() = generation
    override fun commitAck(generation: Long, highWater: Long) { if (generation > acked) acked = generation }
    override fun close() {}

    fun next(): StepSnapshot {
        generation++
        return StepSnapshot(id.copyOf(), generation, ByteBuffer.allocate(40).put(id).putLong(generation).array())
    }

    private fun mutate(call: String): EngineStep<Effects> {
        world.calls += call
        world.refuse[call.substringBefore(' ')]?.let { return EngineStep(null, Effects(refused = it)) }
        return EngineStep(next(), Effects())
    }

    override fun phase() = world.phase
    override fun epoch() = world.epoch
    override fun outbox() = world.outbox.toList()
    override fun watchList() = world.watch.toList()
    override fun tick(now: Long): EngineStep<Effects> {
        world.calls += "tick $now"
        val unconfirmed = world.unconfirmOnTick?.let { listOf(it) }.orEmpty()
        world.unconfirmOnTick = null
        return EngineStep(next(), Effects(events = unconfirmed.map { "OrderingUnconfirmed" }, unconfirmed = unconfirmed))
    }
    override fun delivered(recordIds: List<ByteArray>): EngineStep<Effects> {
        val ids = recordIds.map { it.toHex() }
        world.outbox.removeAll { it.recordId.toHex() in ids }
        return mutate("delivered ${ids.joinToString(",")}")
    }
    override fun depositResult(now: Long, attempt: Long, signedReceipt: ByteArray) = mutate("deposit_result $attempt ${signedReceipt.copyOfRange(65, 97).toHex()}")
    override fun slotStatus(now: Long, attempt: Long, outcome: SlotOutcome, signedReceipt: ByteArray) = mutate("slot_status $attempt $outcome")
    override fun observeReceipt(now: Long, signedReceipt: ByteArray) = mutate("observe_receipt ${signedReceipt.copyOfRange(65, 97).toHex()}")
    override fun observeInstallation(now: Long, installation: ByteArray) = mutate("installation ${installation.toHex()}")
    override fun mailboxDrained(mailbox: ByteArray) = mutate("drained ${mailbox.toHex()}")
    override fun confirmMember(packageId: ByteArray) = mutate("confirm ${packageId.toHex()}")

    override fun process(now: Long, mailbox: ByteArray, envelope: ByteArray, signedReceipt: ByteArray?, installation: Pair<ByteArray, ByteArray>?): EngineStep<Processed> {
        val hash = Digests.sha256(envelope).toHex()
        world.calls += "process ${mailbox.toHex()}" + (if (signedReceipt != null) " receipt" else "") + (installation?.let { " installation ${it.second.toHex()}" } ?: "")
        if (world.downOnProcess == hash) world.server?.mode = FakeWitnessServer.Mode.Down
        world.refuse["process"]?.let { return EngineStep(null, Processed(Effects(refused = it), AckRule.Keep)) }
        return EngineStep(next(), Processed(Effects(), world.acks[hash] ?: AckRule.AfterStep))
    }

    companion object {
        fun open(world: World, plain: ByteArray, mark: Long): ModelDriverSession {
            val buffer = ByteBuffer.wrap(plain)
            val id = ByteArray(32).also(buffer::get)
            check(buffer.getLong() >= mark)
            return ModelDriverSession(world, id, mark, mark)
        }
    }
}

/** A box with Bothy's `/vmls/v1/` answers, in memory. Slot receipts have its layout and a placeholder signature. */
internal class FakeBox(private val node: String, private val installation: ByteArray) : LinkJsonTransport {
    val requests = mutableListOf<String>()
    val authorizations = mutableListOf<String>()
    val records = mutableMapOf<String, MutableList<ByteArray>>()
    val acked = mutableSetOf<String>()
    /** Slot hex -> (state, winning attempt, envelope). */
    val slots = mutableMapOf<String, Triple<String, Long, ByteArray>>()
    var duplicates = 0
    var capabilities: Int? = 200
    var downAfterCapabilities = false
    var signSlots = true
    var welcomeAcknowledged = false
    var pageSize = 64
    /** Mailbox hex -> (status, code) the box refuses a deposit there with. */
    val refuseDeposit = mutableMapOf<String, Pair<Int, String>>()
    /** Mailbox hex the box gives no answer for. */
    val silent = mutableSetOf<String>()
    private val path = LinkPathState("direct", null, "1.2.3.4:5", "test")
    private val b64 = Base64.getEncoder()

    fun put(mailbox: ByteArray, envelope: ByteArray): ByteArray { records.getOrPut(mailbox.toHex()) { mutableListOf() } += envelope; return envelope }

    override fun request(request: LinkJsonRequest): CompletableFuture<LinkJsonResponse> = CompletableFuture.supplyAsync {
        requests += "${request.method} ${request.path}"
        authorizations += request.authorization
        answer(request) ?: throw IllegalStateException("unreachable")
    }

    private fun reply(status: Int, body: String) = LinkJsonResponse(status, body.toByteArray(), path)

    private fun receipt(slot: String, attempt: Long, hash: ByteArray): String {
        val out = ByteArray(197)
        out[0] = 1
        node.chunked(2).map { it.toInt(16).toByte() }.toByteArray().copyInto(out, 1)
        installation.copyInto(out, 33)
        slot.chunked(2).map { it.toInt(16).toByte() }.toByteArray().copyInto(out, 65)
        for (i in 0 until 4) out[97 + i] = (attempt shr (24 - 8 * i)).toByte()
        hash.copyInto(out, 101)
        return b64.encodeToString(out)
    }

    private fun answer(request: LinkJsonRequest): LinkJsonResponse? {
        val parts = request.path.removePrefix("/vmls/v1/").split('/')
        if (parts[0] == "capabilities") {
            val status = capabilities ?: return null
            return reply(status, if (status == 200) """{"v":1,"security_contract":1,"slot_receipts":1,"fork_evidence":1,"restore_fence":1,"installation":"${installation.toHex()}"}""" else """{"v":1,"code":"authority","server_time":1}""")
        }
        if (downAfterCapabilities) return null
        return when {
            parts[0] == "mailboxes" && parts[1] in silent -> null
            parts[0] == "mailboxes" && parts[1] in refuseDeposit -> refuseDeposit.getValue(parts[1]).let { (st, code) -> reply(st, """{"v":1,"code":"$code","server_time":1}""") }
            parts[0] == "mailboxes" -> {
                val list = records.getOrPut(parts[1]) { mutableListOf() }
                val duplicate = list.any { it.contentEquals(request.body) }
                if (duplicate) duplicates++ else list += request.body
                val welcome = if (welcomeAcknowledged) ""","welcome":{"acknowledged":true}""" else ""
                reply(if (duplicate) 200 else 201, """{"v":1,"code":"${if (duplicate) "duplicate" else "stored"}","server_time":1,"receipt":"${Digests.sha256(request.body).toHex()}"$welcome}""")
            }
            parts[0] == "slots" && parts.size == 3 -> {
                val attempt = parts[2].toLong()
                slots.putIfAbsent(parts[1], Triple("filled", attempt, request.body))
                val hash = Digests.sha256(request.body)
                val signed = if (signSlots) ""","signed_receipt":"${receipt(parts[1], attempt, hash)}"""" else ""
                reply(201, """{"v":1,"code":"won","server_time":1,"attempt":$attempt,"receipt":"${hash.toHex()}"$signed}""")
            }
            parts[0] == "slots" -> {
                val (state, winner, env) = slots[parts[1]] ?: return reply(200, """{"v":1,"code":"empty","server_time":1}""")
                val hash = Digests.sha256(env)
                val envelope = if (state == "filled") ""","envelope":"${b64.encodeToString(env)}"""" else ""
                reply(200, """{"v":1,"code":"$state","server_time":1,"attempt":$winner,"receipt":"${hash.toHex()}","signed_receipt":"${receipt(parts[1], winner, hash)}"$envelope}""")
            }
            parts[0] == "fetch" -> {
                val json = Json.parseToJsonElement(String(request.body)).jsonObject
                val asked = json.getValue("mailboxes").jsonArray.map { it.jsonPrimitive.content }
                val start = (json["after"]?.jsonPrimitive?.content)?.toInt() ?: 0
                // The cursor is a position in the whole list, so acknowledging a page never shifts the next.
                val all = asked.flatMap { m -> records[m].orEmpty().map { m to it } }
                val window = all.drop(start).take(pageSize)
                val next = if (start + window.size < all.size) "\"${start + window.size}\"" else "null"
                val page = window.filter { Digests.sha256(it.second).toHex() !in acked }
                val items = page.joinToString(",") { (m, e) -> """{"mailbox":"$m","receipt":"${Digests.sha256(e).toHex()}","envelope":"${b64.encodeToString(e)}"}""" }
                reply(200, """{"v":1,"code":"ok","server_time":1,"records":[$items],"next":$next}""")
            }
            parts[0] == "ack" -> {
                val items = Json.parseToJsonElement(String(request.body)).jsonObject.getValue("records").jsonArray
                items.forEach { acked += (it as JsonObject).getValue("receipt").jsonPrimitive.content }
                reply(200, """{"v":1,"code":"deleted","server_time":1,"acked":${items.size}}""")
            }
            else -> reply(404, "")
        }
    }
}
