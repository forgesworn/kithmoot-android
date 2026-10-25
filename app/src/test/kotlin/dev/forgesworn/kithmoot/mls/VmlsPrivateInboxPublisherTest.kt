package dev.forgesworn.kithmoot.mls

import dev.forgesworn.kithmoot.account.*
import dev.forgesworn.kithmoot.crypto.*
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class VmlsPrivateInboxPublisherTest {
    private class Fixture(val scope: TestScope, budgets: VmlsPrivatePublishBudgets = VmlsPrivatePublishBudgets()) {
        var clock = 1_900_000_000L
        val stores = MemoryCoordinatedStores()
        val server = FakeWitnessServer(Digests.sha256("publisher-witness".toByteArray()))
        var afterAdvance: () -> Unit = {}
        val vault = MlsVault.coordinated(VaultCoordination(stores, WitnessChannels { _, _, _ -> object : WitnessChannel {
            override suspend fun read(request: ByteArray) = server.channel.read(request)
            override suspend fun advance(request: ByteArray): WitnessAnswer = server.channel.advance(request).also { afterAdvance() }
        } }, FakeVaultWitness()), now = { clock })
        val member = LocalSigner(Digests.sha256("publisher-member".toByteArray()))
        val keeper = LocalSigner(Digests.sha256("publisher-keeper".toByteArray()))
        val ctx = vault.context("private-publisher-test", member.pubkey)
        lateinit var device: EnrolledDevice
        val route = VmlsBoxRoute(member.pubkey, "cd".repeat(32), "selected-saved-route", "Synthetic box")
        lateinit var target: VmlsPrivateInboxTarget
        var routeAvailable = true
        var active = true
        var allowed = true
        var authAccepted = true
        var eventAccepted = true
        var wrongAuth = false
        var wrongEvent = false
        var openDelay = 0L
        var authDelay = 0L
        var eventDelay = 0L
        var challenge = "ab".repeat(32)
        var opens = 0
        var closes = 0
        var publicIo = 0
        var encryptions = 0
        val rootKinds = mutableListOf<Int>()
        val frames = mutableListOf<JsonArray>()
        val offered = CompletableDeferred<Unit>()
        var onEncrypt: suspend () -> Unit = {}
        var beforeAuthAck: suspend () -> Unit = {}
        var beforeEventAck: suspend () -> Unit = {}
        var preplayedEventId: String? = null
        val signer = object : ParticipantSigner by member {
            override suspend fun nip44Encrypt(peer: String, plaintext: String): String {
                encryptions++; onEncrypt(); return member.nip44Encrypt(peer, plaintext)
            }
            override suspend fun sign(kind: Int, createdAt: Long, tags: List<List<String>>, content: String): NostrEvent {
                rootKinds += kind
                check(kind != 22242) { "The root signer must never receive AUTH" }
                return member.sign(kind, createdAt, tags, content)
            }
        }
        val channel = VmlsRevocationChannel(vault, { _, auth ->
            publicIo++; assertNull(auth)
            object : VmlsCarrier {
                override suspend fun publish(event: NostrEvent) = true
                override fun subscribe(filters: List<Filter>): Flow<NostrEvent> = flow {
                    emit(keeper.sign(KIND_DM_RELAYS, clock, listOf(listOf("relay", "wss://explicit-public.invalid")), ""))
                }
                override fun close() {}
            }
        }, { listOf("wss://directory-sentinel.invalid") }, { allowed }, { clock })
        val publisher = VmlsPrivateInboxPublisher({ url, routeId, listener, current ->
            assertEquals(LinkRelayAddress.canonicalForNode(route.box), url)
            assertEquals(route.routeId, routeId)
            check(current()); opens++
            var closed = false
            scope.backgroundScope.launch {
                delay(openDelay)
                if (!closed && current()) { listener.onOpen(); listener.onMessage("[\"AUTH\",\"$challenge\"]") }
            }
            object : RelaySocket {
                override fun send(text: String) {
                    check(!closed && current())
                    val frame = Json.parseToJsonElement(text).jsonArray; frames += frame
                    when (frame[0].jsonPrimitive.content) {
                        "AUTH" -> {
                            val auth = NostrEvent.fromJson(frame[1]); assertTrue(Events.verify(auth))
                            assertEquals(device.device, auth.pubkey)
                            assertEquals(22242, auth.kind); assertEquals("", auth.content)
                            assertEquals(listOf(listOf("relay", url), listOf("challenge", challenge)), auth.tags)
                            scope.backgroundScope.launch {
                                delay(authDelay); beforeAuthAck()
                                listener.onMessage("[\"OK\",\"${if (wrongAuth) "00".repeat(32) else auth.id}\",$authAccepted,\"\"]")
                                preplayedEventId?.let { listener.onMessage("[\"OK\",\"$it\",true,\"\"]") }
                            }
                        }
                        "EVENT" -> {
                            val event = NostrEvent.fromJson(frame[1]); offered.complete(Unit)
                            assertTrue(Events.verify(event)); assertEquals(1059, event.kind)
                            assertEquals(listOf(listOf("p", keeper.pubkey)), event.tags)
                            scope.backgroundScope.launch {
                                delay(eventDelay); beforeEventAck()
                                listener.onMessage("[\"OK\",\"${if (wrongEvent) "00".repeat(32) else event.id}\",$eventAccepted,\"\"]")
                            }
                        }
                        else -> error("A private sender only sends AUTH and one EVENT")
                    }
                }
                override fun close() { if (!closed) { closed = true; closes++; listener.onClosed("closed") } }
            }
        }, { clock }, budgets, { url, id -> routeAvailable && url == LinkRelayAddress.canonicalForNode(route.box) && id == route.routeId })
        suspend fun initialise(credentialSeconds: Long = 86400) {
            val genesis = assertIs<VaultResult.Ok<CoordinationGenesis>>(vault.beginCoordination(member.pubkey, Digests.sha256("publisher-subject".toByteArray()), server.key)).value
            server.enrol(genesis.subject, genesis.initialDigest)
            assertEquals(CoordinationStatus.Active, vault.coordinationStatus(member.pubkey, check = true))
            device = assertIs<VaultResult.Ok<EnrolledDevice>>(vault.enrol(ctx, member, clock + credentialSeconds)).value
            target = VmlsPrivateInboxTarget(route, keeper.pubkey, permission())
        }
        suspend fun permission(transmitter: String = device.device, grant: String = "ef".repeat(16)) = keeper.sign(24242, clock,
            listOf(listOf("t", "keeper-inbox-deposit"), listOf("server", LinkRelayAddress.canonicalForNode(route.box)),
                listOf("device", transmitter), listOf("grant", grant), listOf("expiration", (clock + 3600).toString()), listOf("status", "active")), "")
        fun request() = VmlsRevocationRequest(member.pubkey, keeper.pubkey, "12".repeat(32), listOf("34".repeat(32)), listOf(route.box), clock, clock + 86400)
        suspend fun send(prompt: KeeperInboxConsentPrompt = KeeperInboxConsentPrompt { ConsentDecision.Approve }, resend: Boolean = false) =
            channel.sendPrivately(ctx, signer, request(), target, prompt, publisher, { active }, resend)
        suspend fun entry() = channel.entries(ctx).outbox.values.single()
        fun commands() = frames.map { it[0].jsonPrimitive.content }
    }

    @Test fun `protected exact private intent precedes root crypto and device AUTH OK precedes one private EVENT ACK`() = runTest {
        val f = Fixture(this); f.initialise()
        f.onEncrypt = {
            val saved = RevocationBook.decode(assertIs<VaultResult.Ok<ByteArray?>>(f.vault.revocationRequests(f.ctx)).value).outbox.values.single()
            assertFalse(saved.sent); assertFalse(saved.earlierPublicAttempt)
            assertEquals(VmlsPrivateRequestDestination(f.route.routeId, LinkRelayAddress.canonicalForNode(f.route.box), f.route.box,
                f.keeper.pubkey, f.target.permission.id, f.device.device), saved.privateDestination)
            assertEquals(0, f.opens)
        }
        assertTrue(f.send())
        assertEquals(listOf("AUTH", "EVENT"), f.commands()); assertEquals(listOf(13), f.rootKinds)
        assertEquals(0, f.publicIo); assertEquals(1, f.opens); assertEquals(1, f.closes)
        val entry = f.entry(); assertTrue(entry.sent); assertNotNull(entry.privateEventId)
        assertEquals(NostrEvent.fromJson(f.frames.last()[1]).id, entry.privateEventId)
        assertEquals(entry, RevocationBook.decode(RevocationBook(outbox = linkedMapOf(RevocationBook.key(entry.request) to entry)).encode()).outbox.values.single())
        assertTrue(f.send()); assertEquals(1, f.opens, "Only matching private confirmation may satisfy an ordinary repeat")
    }

    @Test fun `negative or mismatched AUTH OK never publishes and cannot use public fallback`() = runTest {
        for (wrong in listOf(false, true)) {
            val f = Fixture(this); f.initialise(); f.authAccepted = wrong; f.wrongAuth = wrong
            assertFalse(f.send()); assertEquals(listOf("AUTH"), f.commands())
            assertFalse(f.entry().sent); assertNull(f.entry().privateEventId)
            assertEquals(0, f.publicIo); assertEquals(f.opens, f.closes)
        }
    }

    @Test fun `late wrong or negative EVENT acknowledgement never confirms private journal`() = runTest {
        for (mode in listOf("late", "wrong", "negative")) {
            val f = Fixture(this, VmlsPrivatePublishBudgets(100, 100, 50)); f.initialise()
            f.eventDelay = if (mode == "late") 100 else 0; f.wrongEvent = mode == "wrong"; f.eventAccepted = mode != "negative"
            assertFalse(f.send()); assertFalse(f.entry().sent); assertNull(f.entry().privateEventId)
            assertEquals(listOf("AUTH", "EVENT"), f.commands()); assertEquals(f.opens, f.closes)
            advanceTimeBy(200); runCurrent(); assertFalse(f.entry().sent); assertEquals(0, f.publicIo)
        }
    }

    @Test fun `open authentication and fresh acknowledgement each have separate finite budgets`() = runTest {
        val f = Fixture(this, VmlsPrivatePublishBudgets(100, 100, 50)); f.initialise()
        f.authDelay = 80; f.eventDelay = 40
        assertTrue(f.send()); assertEquals(120, testScheduler.currentTime)
        val late = Fixture(this, VmlsPrivatePublishBudgets(100, 100, 50)); late.initialise(); late.openDelay = 101
        assertFalse(late.send()); assertTrue(late.frames.isEmpty()); assertEquals(late.opens, late.closes)
        val auth = Fixture(this, VmlsPrivatePublishBudgets(100, 100, 50)); auth.initialise(); auth.authDelay = 101
        assertFalse(auth.send()); assertEquals(listOf("AUTH"), auth.commands()); assertEquals(auth.opens, auth.closes)
    }

    @Test fun `route withdrawal during prompt and quiet transition after AUTH refuse before EVENT`() = runTest {
        val f = Fixture(this); f.initialise()
        assertFailsWith<IllegalStateException> { f.send(KeeperInboxConsentPrompt { f.active = false; ConsentDecision.Approve }) }
        assertTrue(f.frames.isEmpty()); assertFalse(f.entry().sent); assertEquals(f.opens, f.closes)
        val g = Fixture(this); g.initialise(); g.beforeAuthAck = { g.allowed = false }
        assertFailsWith<IllegalStateException> { g.send() }
        assertEquals(listOf("AUTH"), g.commands()); g.allowed = true; assertFalse(g.entry().sent); assertEquals(0, g.publicIo)
    }

    @Test fun `original credential revoked or naturally expired before AUTH OK emits no EVENT`() = runTest {
        val revoked = Fixture(this); revoked.initialise()
        revoked.beforeAuthAck = { assertIs<VaultResult.Ok<Unit>>(revoked.vault.revokeCredential(revoked.ctx, revoked.device.credentialId)) }
        assertFalse(revoked.send()); assertEquals(listOf("AUTH"), revoked.commands()); assertFalse(revoked.entry().sent)
        val expired = Fixture(this); expired.initialise(credentialSeconds = 2)
        expired.beforeAuthAck = { expired.clock += 2 }
        assertFailsWith<IllegalStateException> { expired.send() }; assertEquals(listOf("AUTH"), expired.commands()); assertFalse(expired.entry().sent)
        assertEquals(revoked.opens, revoked.closes); assertEquals(expired.opens, expired.closes)
        assertEquals(0, revoked.publicIo + expired.publicIo)
    }

    @Test fun `actual cancellation during prompt or acknowledgement closes connection and never confirms`() = runTest {
        val f = Fixture(this); f.initialise()
        val prompt = async { f.send(KeeperInboxConsentPrompt { currentCoroutineContext().cancel(); ConsentDecision.Approve }) }
        assertFailsWith<CancellationException> { prompt.await() }
        assertTrue(f.frames.isEmpty()); assertFalse(f.entry().sent); assertEquals(f.opens, f.closes)
        val g = Fixture(this); g.initialise(); g.eventDelay = 1000
        val waiting = async { g.send() }; g.offered.await(); waiting.cancelAndJoin()
        assertFalse(g.entry().sent); assertEquals(listOf("AUTH", "EVENT"), g.commands()); assertEquals(g.opens, g.closes)
    }

    @Test fun `wrong current device or failed witnessed reservation blocks root crypto and private socket`() = runTest {
        val f = Fixture(this); f.initialise(); f.target = f.target.copy(permission = f.permission(f.keeper.pubkey))
        assertFailsWith<IllegalStateException> { f.send() }
        assertEquals(0, f.encryptions); assertEquals(0, f.opens); assertEquals(0, f.publicIo)
        val g = Fixture(this); g.initialise(); g.server.mode = FakeWitnessServer.Mode.LoseAnswer
        assertFailsWith<IllegalStateException> { g.send() }
        assertEquals(0, g.encryptions); assertEquals(0, g.opens); assertEquals(0, g.publicIo)
    }

    @Test fun `public ACK cannot satisfy private intent and explicit switch retains public history`() = runTest {
        val f = Fixture(this); f.initialise()
        assertTrue(f.channel.send(f.ctx, f.signer, f.request()))
        val publicCrypto = f.encryptions; val publicIo = f.publicIo
        assertFailsWith<IllegalStateException> { f.send() }
        assertEquals(publicCrypto, f.encryptions); assertEquals(0, f.opens); assertEquals(publicIo, f.publicIo)
        assertTrue(f.send(resend = true)); assertEquals(1, f.opens)
        assertTrue(f.entry().earlierPublicAttempt); assertNotNull(f.entry().privateDestination); assertNotNull(f.entry().privateEventId)
        assertFailsWith<IllegalStateException> { f.channel.send(f.ctx, f.signer, f.request()) }
        assertEquals(publicIo, f.publicIo)
        assertTrue(f.channel.send(f.ctx, f.signer, f.request(), resend = true))
        assertNull(f.entry().privateDestination); assertTrue(f.entry().earlierPublicAttempt)
    }

    @Test fun `private destination is frozen and only explicit fresh retry can switch permission`() = runTest {
        val f = Fixture(this); f.initialise(); assertTrue(f.send())
        val first = f.entry().privateDestination
        f.target = f.target.copy(permission = f.permission(grant = "56".repeat(16)))
        assertFailsWith<IllegalStateException> { f.send() }
        assertEquals(first, f.entry().privateDestination); assertEquals(1, f.opens)
        assertTrue(f.send(resend = true)); assertEquals(f.target.permission.id, f.entry().privateDestination!!.permissionId)
        assertFalse(f.entry().earlierPublicAttempt); assertEquals(2, f.opens)
    }

    @Test fun `preplayed EVENT OK cannot become a fresh post EVENT acknowledgement or forge a receipt`() = runTest {
        val f = Fixture(this); f.initialise()
        val event = VmlsRequestEnvelope.wrap(f.request(), f.member, { true })
        f.preplayedEventId = event.id
        val destination = VmlsPrivateRequestDestination(f.route.routeId, LinkRelayAddress.canonicalForNode(f.route.box), f.route.box,
            f.keeper.pubkey, f.target.permission.id, f.device.device)
        assertNull(f.publisher.publish(f.ctx, f.vault, destination, f.target.permission, KeeperInboxConsentPrompt { ConsentDecision.Approve }, event) { true })
        assertFalse(f.publisher.accepts(destination, event, VmlsPrivatePublishReceipt(event.id)))
        assertEquals(listOf("AUTH", "EVENT"), f.commands()); assertEquals(f.opens, f.closes)
    }

    @Test fun `legacy public outbox history stays public through an explicit private retry`() = runTest {
        val f = Fixture(this); f.initialise()
        val r = f.request(); val key = RevocationBook.key(r)
        val legacy = Json.parseToJsonElement(RevocationBook(outbox = linkedMapOf(key to RevocationEntry(r, sent = true))).encode().decodeToString()).jsonObject
        val entries = legacy.getValue("outbox").jsonObject
        val entry = entries.getValue(key).jsonObject
        val bytes = JsonObject((legacy - "publicAttempts") + ("version" to JsonPrimitive(1)) +
            ("outbox" to JsonObject(entries + (key to JsonObject(entry - "earlierPublicAttempt"))))).toString().encodeToByteArray()
        assertIs<VaultResult.Ok<Unit>>(f.vault.keepRevocationRequests(f.ctx, bytes))
        assertTrue(f.entry().earlierPublicAttempt)
        assertFailsWith<IllegalStateException> { f.send() }
        assertEquals(0, f.encryptions); assertEquals(0, f.opens)
        assertTrue(f.send(resend = true)); assertTrue(f.entry().earlierPublicAttempt)
        val incomplete = f.entry().copy(privateEventId = null)
        assertFailsWith<IllegalArgumentException> { RevocationBook.decode(RevocationBook(outbox = linkedMapOf(key to incomplete)).encode()) }
    }

    @Test fun `changed private request body requires explicit retry and caller hint mutation cannot rebind its reservation`() = runTest {
        val f = Fixture(this); f.initialise(); assertTrue(f.send())
        val changed = f.request().copy(sessions = listOf("78".repeat(32)))
        assertFailsWith<IllegalStateException> { f.channel.sendPrivately(f.ctx, f.signer, changed, f.target,
            KeeperInboxConsentPrompt { ConsentDecision.Approve }, f.publisher, { true }) }
        assertEquals(1, f.opens)
        val g = Fixture(this); g.initialise()
        val sessions = mutableListOf("34".repeat(32))
        val request = g.request().copy(sessions = sessions)
        g.onEncrypt = { sessions[0] = "78".repeat(32) }
        assertTrue(g.channel.sendPrivately(g.ctx, g.signer, request, g.target, KeeperInboxConsentPrompt { ConsentDecision.Approve }, g.publisher, { true }))
        assertEquals(listOf("34".repeat(32)), g.entry().request.sessions)
    }

    @Test fun `queued private caller input is bounded and snapshotted before waiting for channel mutex`() = runTest {
        val f = Fixture(this); f.initialise()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val first = async { f.send(KeeperInboxConsentPrompt { entered.complete(Unit); release.await(); ConsentDecision.Approve }) }
        entered.await()
        val sessions = mutableListOf("34".repeat(32))
        val tags = f.target.permission.tags.map { it.toMutableList() }.toMutableList()
        val secondRequest = f.request().copy(device = "90".repeat(32), sessions = sessions)
        val secondTarget = f.target.copy(permission = f.target.permission.copy(tags = tags))
        val second = async { f.channel.sendPrivately(f.ctx, f.signer, secondRequest, secondTarget,
            KeeperInboxConsentPrompt { ConsentDecision.Approve }, f.publisher, { true }) }
        runCurrent(); assertFalse(second.isCompleted)
        sessions[0] = "78".repeat(32); tags[1][1] = "ws://mutated.invalid/events"
        release.complete(Unit); assertTrue(first.await()); assertTrue(second.await())
        val saved = f.channel.entries(f.ctx).outbox.getValue(RevocationBook.key(secondRequest))
        assertEquals(listOf("34".repeat(32)), saved.request.sessions)
        assertEquals(f.target.permission.id, saved.privateDestination!!.permissionId)
    }

    @Test fun `uncertain retained private attempt holds until explicit fresh retry`() = runTest {
        val f = Fixture(this); f.initialise(); f.authAccepted = false
        assertFalse(f.send()); val frames = f.frames.size
        f.authAccepted = true
        assertFalse(f.send()); assertEquals(frames, f.frames.size); assertEquals(1, f.opens)
        assertTrue(f.send(resend = true)); assertEquals(2, f.opens); assertTrue(f.entry().sent)
    }

    @Test fun `public coordinate marker survives expiration and remains bounded without blocking private sends`() = runTest {
        val f = Fixture(this); f.initialise(); assertTrue(f.channel.send(f.ctx, f.signer, f.request()))
        val coordinate = RevocationBook.key(f.request())
        f.clock += 86400
        f.device = assertIs<VaultResult.Ok<EnrolledDevice>>(f.vault.enrol(f.ctx, f.member, f.clock + 86400, replace = true)).value
        f.target = f.target.copy(permission = f.permission())
        assertTrue(f.send()); assertTrue(f.entry().earlierPublicAttempt)
        assertTrue(coordinate in f.channel.entries(f.ctx).publicAttempts)
        val g = Fixture(this); g.initialise()
        val markers = (1..128).mapTo(linkedSetOf()) { "${g.member.pubkey}:${it.toString(16).padStart(64, '0')}:${g.keeper.pubkey}" }
        assertIs<VaultResult.Ok<Unit>>(g.vault.keepRevocationRequests(g.ctx, RevocationBook(publicAttempts = markers).encode()))
        assertFailsWith<IllegalStateException> { g.channel.send(g.ctx, g.signer, g.request()) }
        assertEquals(0, g.encryptions); assertEquals(0, g.publicIo)
        assertTrue(g.send()); assertFalse(g.entry().earlierPublicAttempt); assertEquals(128, g.channel.entries(g.ctx).publicAttempts.size)
    }

    @Test fun `wall clock rollback while EVENT ACK waits cannot confirm channel or standalone publisher`() = runTest {
        val f = Fixture(this); f.initialise(); f.beforeEventAck = { f.clock-- }
        assertFailsWith<IllegalStateException> { f.send() }
        assertFalse(f.entry().sent); assertEquals(listOf("AUTH", "EVENT"), f.commands())
        val g = Fixture(this); g.initialise(); g.beforeEventAck = { g.clock-- }
        val event = VmlsRequestEnvelope.wrap(g.request(), g.member, { true })
        val destination = VmlsPrivateRequestDestination(g.route.routeId, LinkRelayAddress.canonicalForNode(g.route.box), g.route.box,
            g.keeper.pubkey, g.target.permission.id, g.device.device)
        assertNull(g.publisher.publish(g.ctx, g.vault, destination, g.target.permission, KeeperInboxConsentPrompt { ConsentDecision.Approve }, event) { true })
        assertEquals(g.opens, g.closes)
    }

    @Test fun `credential expiring during reservation promotion blocks payload crypto and Link startup`() = runTest {
        val f = Fixture(this); f.initialise(credentialSeconds = 2)
        f.afterAdvance = { f.clock += 2; f.afterAdvance = {} }
        assertFailsWith<IllegalStateException> { f.send() }
        assertEquals(0, f.encryptions); assertTrue(f.rootKinds.isEmpty()); assertEquals(0, f.opens); assertEquals(0, f.publicIo)
        assertFalse(f.entry().sent, "The witnessed reservation remains unconfirmed")
    }

    @Test fun `actual route pin loss during reservation prompt or EVENT ACK blocks crypto or confirmation`() = runTest {
        val reserved = Fixture(this); reserved.initialise()
        reserved.afterAdvance = { reserved.routeAvailable = false; reserved.afterAdvance = {} }
        assertFailsWith<IllegalStateException> { reserved.send() }
        assertEquals(0, reserved.encryptions); assertEquals(0, reserved.opens)
        assertFalse(reserved.entry().sent)
        val prompting = Fixture(this); prompting.initialise()
        assertFailsWith<IllegalStateException> { prompting.send(KeeperInboxConsentPrompt {
            prompting.routeAvailable = false; ConsentDecision.Approve
        }) }
        assertTrue(prompting.frames.isEmpty()); assertFalse(prompting.entry().sent)
        val ack = Fixture(this); ack.initialise(); ack.beforeEventAck = { ack.routeAvailable = false }
        assertFailsWith<IllegalStateException> { ack.send() }
        assertFalse(ack.entry().sent); assertEquals(listOf("AUTH", "EVENT"), ack.commands())
        assertEquals(ack.opens, ack.closes); assertEquals(0, reserved.publicIo + prompting.publicIo + ack.publicIo)
    }

    @Test fun `private and public attempts share serialization while private prompt waits`() = runTest {
        val f = Fixture(this); f.initialise()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val privateSend = async { f.send(KeeperInboxConsentPrompt { entered.complete(Unit); release.await(); ConsentDecision.Approve }) }
        entered.await()
        val publicSend = async { runCatching { f.channel.send(f.ctx, f.signer, f.request()) } }
        runCurrent(); assertFalse(publicSend.isCompleted); assertEquals(0, f.publicIo)
        release.complete(Unit); assertTrue(privateSend.await())
        assertIs<IllegalStateException>(publicSend.await().exceptionOrNull()); assertEquals(0, f.publicIo)
    }
}
