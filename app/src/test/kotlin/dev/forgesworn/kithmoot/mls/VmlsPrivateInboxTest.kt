package dev.forgesworn.kithmoot.mls

import dev.forgesworn.kithmoot.account.*
import dev.forgesworn.kithmoot.crypto.*
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.*
import dev.forgesworn.kithmoot.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.*
import java.security.SecureRandom
import java.util.UUID
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class VmlsPrivateInboxTest {
    private class Fixture(val scope: TestScope, val budgets: VmlsPrivateInboxBudgets = VmlsPrivateInboxBudgets()) {
        var clock = 1_900_000_000L
        val keeper = LocalSigner(Digests.sha256("private-keeper".toByteArray()))
        val member = LocalSigner(Digests.sha256("private-member".toByteArray()))
        val route = VmlsBoxRoute(keeper.pubkey, "cd".repeat(32), "saved-route", "Synthetic Bothy")
        var selected = route
        var permitted = true
        var trafficAllowed = true
        var onSign: suspend () -> Unit = {}
        var authority = true
        var eose = true
        var cap = 64
        var opens = 0
        var closed = 0
        var decryptions = 0
        var publicIo = 0
        var onDecrypt: suspend (Int) -> Unit = {}
        var onOpen: suspend () -> Unit = {}
        var onQuery: suspend () -> Unit = {}
        val frames = mutableListOf<JsonArray>()
        val events = mutableListOf<NostrEvent>()
        val vault = MlsVault(object : MlsVaultStores {
            override val lockName = UUID.randomUUID().toString()
            private val data = mutableMapOf<String, MemoryStorage>()
            override fun open(name: String, aad: ByteArray): RoomStorage = data.getOrPut(name + aad.toHex()) { MemoryStorage() }
        }, now = { clock })
        val ctx = vault.context("private-test", keeper.pubkey)
        val signer = object : ParticipantSigner by keeper {
            override suspend fun sign(kind: Int, createdAt: Long, tags: List<List<String>>, content: String): NostrEvent =
                keeper.sign(kind, createdAt, tags, content).also { onSign() }
            override suspend fun nip44Decrypt(peer: String, payload: String): String {
                decryptions++; onDecrypt(decryptions)
                return keeper.nip44Decrypt(peer, payload)
            }
        }
        fun channel() = VmlsRevocationChannel(vault, { _, _ -> publicIo++; error("public IO sentinel") },
            { listOf("wss://public-directory.invalid/") }, { trafficAllowed }, { clock })
        fun consent() = VmlsPrivateInboxConsent(route) { permitted && selected == route }
        val reader = VmlsPrivateInboxReader({ url, id, listener, current ->
            assertEquals(LinkRelayAddress.canonicalForNode(selected.box), url)
            assertEquals(selected.routeId, id)
            check(current()); opens++
            var active = true
            scope.backgroundScope.launch {
                onOpen()
                if (current() && active) { listener.onOpen(); listener.onMessage("[\"AUTH\",\"private-challenge\"]") }
            }
            object : RelaySocket {
                override fun send(text: String) {
                    check(current()); val frame = Json.parseToJsonElement(text).jsonArray; frames += frame
                    when (frame[0].jsonPrimitive.content) {
                        "AUTH" -> {
                            val auth = NostrEvent.fromJson(frame[1])
                            assertEquals(keeper.pubkey, auth.pubkey)
                            assertEquals(listOf(listOf("relay", url), listOf("challenge", "private-challenge")), auth.tags)
                            scope.backgroundScope.launch { listener.onMessage("[\"OK\",\"${auth.id}\",$authority,\"\"]") }
                        }
                        "REQ" -> {
                            val subscription = frame[1].jsonPrimitive.content
                            val filter = frame[2].jsonObject
                            assertEquals(listOf(1059), filter.getValue("kinds").jsonArray.map { it.jsonPrimitive.int })
                            assertEquals(listOf(keeper.pubkey), filter.getValue("#p").jsonArray.map { it.jsonPrimitive.content })
                            val since = filter.getValue("since").jsonPrimitive.long
                            val until = filter.getValue("until").jsonPrimitive.long
                            scope.backgroundScope.launch {
                                onQuery()
                                events.filter { it.createdAt in since..until }.sortedByDescending { it.createdAt }.take(cap).forEach {
                                    listener.onMessage("[\"EVENT\",\"$subscription\",${it.toCompactJson()}]")
                                }
                                listener.onMessage(if (eose) "[\"EOSE\",\"$subscription\"]" else "[\"CLOSED\",\"$subscription\",\"restricted: incomplete\"]")
                            }
                        }
                        "EVENT" -> error("private inbox must never publish")
                    }
                }
                override fun close() { if (active) { active = false; closed++; listener.onClosed("closed") } }
            }
        }, { clock }, budgets)
        suspend fun wrap(at: Long = clock): NostrEvent {
            val request = VmlsRevocationRequest(member.pubkey, keeper.pubkey, "ab".repeat(32), listOf("ef".repeat(32)), listOf(route.box), at, at + 86400)
            return VmlsRequestEnvelope.wrap(request, member, { true }, object : SecureRandom() { override fun nextInt(bound: Int) = 0 })
        }
        suspend fun noise(at: Long) = member.sign(1059, at, listOf(listOf("p", keeper.pubkey), listOf("x", "unsupported")), "opaque")
        suspend fun poll(mode: VmlsInboxMode = VmlsInboxMode.LATEST, consent: VmlsPrivateInboxConsent = consent(),
            authorise: suspend (VmlsRevocationRequest) -> Boolean = { true }) =
            channel().pollPrivate(ctx, signer, consent, mode, reader, authorise)
        fun untils() = frames.filter { it[0].jsonPrimitive.content == "REQ" }.map { it[2].jsonObject.getValue("until").jsonPrimitive.long }
    }

    @Test fun `supported signer approval beyond five seconds leaves a separate full stored-page window`() = runTest {
        val f = Fixture(this); f.events += f.wrap(f.clock - 1)
        f.onSign = { delay(6_000) }
        f.onQuery = { delay(4_000) }
        val result = f.poll()
        assertTrue(result.complete); assertEquals(1, result.book.inbox.size)
        assertEquals(10_000, testScheduler.currentTime)
        assertEquals(listOf("AUTH", "REQ", "CLOSE"), f.frames.map { it[0].jsonPrimitive.content })
        assertEquals(f.opens, f.closed); assertEquals(0, f.publicIo)
    }

    @Test fun `authentication phase expiry closes the socket without a query or late AUTH`() = runTest {
        val f = Fixture(this, VmlsPrivateInboxBudgets(connectMillis = 100, authenticationMillis = 100, pageMillis = 50))
        f.onSign = { delay(200) }
        val result = f.poll()
        assertFalse(result.complete); assertEquals(100, testScheduler.currentTime)
        advanceTimeBy(200); runCurrent()
        assertTrue(f.frames.isEmpty()); assertEquals(f.opens, f.closed)
        assertEquals(0, f.decryptions); assertEquals(0, f.publicIo)
    }

    @Test fun `withdrawal during delayed external AUTH ignores its late reply and never sends AUTH or REQ`() = runTest {
        val f = Fixture(this); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.onSign = { entered.complete(Unit); withContext(NonCancellable) { release.await() } }
        val pending = async { runCatching { f.poll() } }
        entered.await(); f.permitted = false
        advanceTimeBy(26); runCurrent()
        assertEquals(f.opens, f.closed); assertTrue(f.frames.isEmpty())
        release.complete(Unit)
        assertTrue(pending.await().exceptionOrNull() is IllegalStateException)
        assertTrue(f.frames.isEmpty()); assertEquals(0, f.decryptions); assertEquals(0, f.publicIo)
        assertTrue(testScheduler.currentTime < 100)
    }

    @Test fun `a journal write queued on the vault lock rechecks operation consent before mutation`() = runTest {
        val lock = Mutex(locked = true)
        val values = mutableMapOf<String, MemoryStorage>()
        val vault = MlsVault(object : MlsVaultStores {
            override val lockName = UUID.randomUUID().toString()
            override fun open(name: String, aad: ByteArray): RoomStorage = values.getOrPut(name + aad.toHex()) { MemoryStorage() }
        }, lock = lock)
        val ctx = vault.context("private-test", "12".repeat(32))
        var allowed = true
        val pending = async { vault.keepRevocationRequests(ctx, RevocationBook().encode()) { allowed } }
        runCurrent(); allowed = false; lock.unlock()
        assertEquals(VaultResult.Refused(VaultRefusal.Stale), pending.await())
        assertNull((vault.revocationRequests(ctx) as VaultResult.Ok).value)
    }

    @Test fun `only saved Link receives keeper AUTH then recipient REQ and private factory cannot fall back`() = runTest {
        val f = Fixture(this); f.events += f.wrap(f.clock - 1)
        val result = f.poll()
        assertTrue(result.complete); assertEquals(1, result.book.inbox.size)
        assertEquals(2, f.decryptions); assertEquals(0, f.publicIo)
        assertEquals(listOf("AUTH", "REQ", "CLOSE"), f.frames.map { it[0].jsonPrimitive.content })
        assertEquals(f.opens, f.closed)
        assertTrue(result.book.scanUntil.isEmpty()); assertTrue(result.book.privateScanUntil.containsKey(privateCursorKey(f.route)))
        f.clock += 60
        assertFailsWith<IllegalStateException> { f.channel().poll(f.ctx, f.signer) { true } }
        assertEquals(1, f.publicIo, "positive control catches public directory IO")
    }

    @Test fun `wrong account or replaced route refuses before opening`() = runTest {
        val f = Fixture(this)
        assertFailsWith<IllegalStateException> { f.channel().pollPrivate(f.ctx, f.member, f.consent(), VmlsInboxMode.LATEST, f.reader) { true } }
        val permission = f.consent(); f.selected = f.route.copy(routeId = "replacement")
        assertFailsWith<IllegalStateException> { f.poll(consent = permission) }
        assertEquals(0, f.opens); assertEquals(0, f.decryptions); assertEquals(0, f.publicIo)
    }

    @Test fun `pairing without matching AUTH OK grants no read and closes finite page`() = runTest {
        val f = Fixture(this); f.authority = false; f.events += f.wrap()
        val result = f.poll()
        assertFalse(result.complete); assertEquals(0, f.decryptions)
        assertTrue(f.untils().isEmpty()); assertEquals(f.opens, f.closed); assertEquals(0, f.publicIo)
    }

    @Test fun `withdrawal during a delayed Link open releases no AUTH or query`() = runTest {
        val f = Fixture(this); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.onOpen = { entered.complete(Unit); release.await() }
        val pending = async { runCatching { f.poll() } }; entered.await(); f.permitted = false; release.complete(Unit)
        assertTrue(pending.await().exceptionOrNull() is IllegalStateException)
        assertTrue(f.frames.isEmpty()); assertEquals(f.opens, f.closed); assertEquals(0, f.publicIo)
    }

    @Test fun `consent withdrawn in an AUTH reply releases no AUTH frame or recipient query`() = runTest {
        val f = Fixture(this); f.onSign = { f.permitted = false }
        assertFailsWith<IllegalStateException> { f.poll() }
        assertTrue(f.frames.isEmpty()); assertEquals(0, f.decryptions); assertEquals(f.opens, f.closed)
    }

    @Test fun `quiet mode entered during decryption holds without another layer`() = runTest {
        val f = Fixture(this); f.events += f.wrap(f.clock - 1)
        f.onDecrypt = { f.trafficAllowed = false }
        assertFailsWith<IllegalStateException> { f.poll() }
        assertEquals(1, f.decryptions); assertEquals(0, f.publicIo)
    }

    @Test fun `a new saved route gets a separate persona box route cursor`() = runTest {
        val f = Fixture(this); f.events += f.noise(f.clock - 10)
        val initial = f.poll(VmlsInboxMode.RETAINED).book.privateScanUntil.toMap()
        f.clock += 60; f.selected = f.route.copy(box = "12".repeat(32), routeId = "new-route")
        val consent = VmlsPrivateInboxConsent(f.selected) { f.permitted }
        val result = f.channel().pollPrivate(f.ctx, f.signer, consent, VmlsInboxMode.LATEST, f.reader) { true }
        assertEquals(initial.getValue(privateCursorKey(f.route)), result.book.privateScanUntil[privateCursorKey(f.route)])
        assertEquals(f.clock, result.book.privateScanUntil[privateCursorKey(f.selected)])
        assertEquals(2, result.book.privateScanUntil.size); assertEquals(0, f.publicIo)
    }

    @Test fun `withdrawal during either decrypt layer stops later layers wraps and prompt commits`() = runTest {
        for (layer in 1..2) {
            val f = Fixture(this); f.events += f.wrap(f.clock - 1); f.events += f.wrap(f.clock - 2)
            f.onDecrypt = { if (it == layer) f.permitted = false }
            assertFailsWith<IllegalStateException> { f.poll() }
            assertEquals(layer, f.decryptions)
            val book = f.channel().entries(f.ctx)
            assertEquals(1, book.seen.size); assertTrue(book.inbox.isEmpty()); assertEquals(0, f.publicIo)
        }
    }

    @Test fun `consent loss during ledger lookup commits no prompt`() = runTest {
        val f = Fixture(this); f.events += f.wrap(f.clock - 1)
        assertFailsWith<IllegalStateException> { f.poll { f.permitted = false; true } }
        assertTrue(f.channel().entries(f.ctx).inbox.isEmpty()); assertEquals(2, f.decryptions)
    }

    @Test fun `session ends during decryption without second layer or prompt`() = runTest {
        val f = Fixture(this); f.events += f.wrap(f.clock - 1)
        f.onDecrypt = { f.vault.bump() }
        assertFailsWith<IllegalStateException> { f.poll() }
        assertEquals(1, f.decryptions); assertEquals(0, f.publicIo)
    }

    @Test fun `incomplete page retains cursor and performs no decryptions`() = runTest {
        val f = Fixture(this); f.events += f.wrap(f.clock - 1); f.eose = false
        val result = f.poll(VmlsInboxMode.RETAINED)
        assertFalse(result.complete); assertEquals(f.clock, result.book.privateScanUntil.getValue(privateCursorKey(f.route)))
        assertEquals(0, f.decryptions); assertEquals(f.opens, f.closed)
    }

    @Test fun `signed unsupported paging reaches older request across reconstruction while forged boundary cannot advance`() = runTest {
        val f = Fixture(this); val initial = f.clock
        repeat(64) { f.events += f.noise(initial - it) }
        val good = f.wrap(initial - 100); f.events += good; f.events += good.copy(createdAt = initial - 200)
        val first = f.poll(VmlsInboxMode.RETAINED)
        assertTrue(first.complete); assertEquals(initial - 63, first.book.privateScanUntil.getValue(privateCursorKey(f.route)))
        assertEquals(0, f.decryptions)
        f.clock += 60
        val second = f.poll(VmlsInboxMode.RETAINED)
        assertTrue(second.complete); assertEquals(good.createdAt, second.book.privateScanUntil.getValue(privateCursorKey(f.route)))
        assertEquals(1, second.book.inbox.size); assertEquals(2, f.decryptions); assertEquals(0, f.publicIo)
    }

    @Test fun `latest reaches later request after empty or held head without changing retained progress`() = runTest {
        for (held in listOf(false, true)) {
            val f = Fixture(this); val initial = f.clock
            if (held) { f.cap = 1; f.events += f.noise(initial) }
            assertFalse(f.poll().complete)
            f.clock++; f.events += f.wrap(); f.clock += 60
            val latest = f.poll()
            assertEquals(1, latest.book.inbox.size)
            assertEquals(initial, latest.book.privateScanUntil.getValue(privateCursorKey(f.route)))
            assertTrue(f.poll(VmlsInboxMode.RETAINED).limited)
            f.clock += 60; assertFalse(f.poll(VmlsInboxMode.RETAINED).complete)
            assertEquals(listOf(initial, f.clock - 60, initial), f.untils())
        }
    }

    @Test fun `eight wrap shared allowance survives reconstruction and private public mode switching`() = runTest {
        val f = Fixture(this)
        repeat(9) { f.events += f.wrap(f.clock - it - 1) }
        assertFalse(f.poll(VmlsInboxMode.RETAINED).complete); assertEquals(16, f.decryptions)
        assertTrue(f.poll().limited)
        assertTrue(f.channel().pollPublic(f.ctx, f.signer, authorise = { true }).limited)
        assertEquals(0, f.publicIo)
        f.clock += 60
        assertTrue(f.poll(VmlsInboxMode.RETAINED).complete); assertEquals(18, f.decryptions)
    }

    @Test fun `retained same-second capped and expired boundaries hold without skipping or resetting`() = runTest {
        val f = Fixture(this); val initial = f.clock; f.cap = 1
        f.events += f.noise(initial); f.events += f.wrap(initial - 1)
        assertFalse(f.poll(VmlsInboxMode.RETAINED).complete)
        f.clock += 60; assertFalse(f.poll(VmlsInboxMode.RETAINED).complete)
        assertEquals(listOf(initial, initial), f.untils())
        f.clock += 10 * 86400
        val expired = f.poll(VmlsInboxMode.RETAINED)
        assertFalse(expired.complete); assertEquals(initial, expired.book.privateScanUntil.getValue(privateCursorKey(f.route)))
        assertEquals(2, f.opens)
    }
}
