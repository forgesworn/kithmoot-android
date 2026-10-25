package dev.forgesworn.kithmoot.mls

import dev.forgesworn.kithmoot.account.*
import dev.forgesworn.kithmoot.crypto.*
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.*
import dev.forgesworn.kithmoot.storage.RoomStorage
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import kotlin.test.*

class KeeperInboxWorkflowTest {
    private class Memory : RoomStorage { private var value: ByteArray? = null; override fun read() = value?.copyOf(); override fun write(value: ByteArray) { this.value = value.copyOf() }; override fun reset() { value = null } }
    private class Fixture {
        var clock = 1_900_000_000L
        val account = LocalSigner(Digests.sha256("workflow-account".toByteArray()))
        val otherKeeper = LocalSigner(Digests.sha256("workflow-other-keeper".toByteArray()))
        val stores = MemoryCoordinatedStores(); val witness = FakeWitnessServer(Digests.sha256("workflow-witness".toByteArray()))
        val vault = MlsVault.coordinated(VaultCoordination(stores, WitnessChannels { _, _, _ -> witness.channel }, FakeVaultWitness()), now = { clock })
        val ctx = vault.context(VmlsRuntime.PRINCIPAL, account.pubkey)
        val quiet = AtomicBoolean(false); var live = true; var accept = true; var closeWithoutEventAck = false
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val route = VmlsBoxRoute(account.pubkey, "42".repeat(32), "workflow-route", "Synthetic workflow Bothy")
        val roomStore = VmlsRoomStore(Memory()).apply { putRoute(route) }
        val rootKinds = mutableListOf<Int>(); val wire = mutableListOf<NostrEvent>(); val exact = mutableListOf<String>()
        var opens = 0; var publicIo = 0; var signCalls = 0; var duringSign: suspend (Int) -> Unit = {}
        lateinit var device: EnrolledDevice
        val signer = object : ParticipantSigner by account {
            override suspend fun sign(kind: Int, createdAt: Long, tags: List<List<String>>, content: String): NostrEvent {
                signCalls++; rootKinds += kind; duringSign(kind)
                return account.sign(kind, createdAt, tags, content)
            }
        }
        val linkVault = LinkTransportVault(Memory()).apply {
            val card = ByteArray(126).also { "FSL1".toByteArray().copyInto(it); it[4] = 1; it.fill(0x42, 5, 37) }
            upsert(StoredLinkRoute(route.routeId, card, ByteArray(32) { 7 }, 1UL, 1UL))
        }
        val manager = LinkTransportManager(linkVault, object : LinkTransportRuntime {
            override fun start(state: LinkTransportState): LinkTransportSession = object : LinkTransportSession {
                override fun open(url: String, routeId: String, listener: RelaySocketListener): LinkTransportSocket {
                    require(url == LinkRelayAddress.canonicalForNode(route.box) && routeId == route.routeId); opens++
                    listener.onOpen(); listener.onMessage("[\"AUTH\",\"${"ab".repeat(32)}\"]")
                    return object : LinkTransportSocket {
                        override fun send(text: String) {
                            val f = Json.parseToJsonElement(text).jsonArray; val e = NostrEvent.fromJson(f[1]); assertTrue(Events.verify(e))
                            wire += e
                            if (f[0].jsonPrimitive.content == "AUTH") {
                                assertEquals(22242, e.kind); assertEquals("", e.content)
                                listener.onMessage("[\"OK\",\"${e.id}\",true,\"\"]")
                            } else {
                                assertEquals("EVENT", f[0].jsonPrimitive.content); exact += text.removePrefix("[\"EVENT\",").removeSuffix("]")
                                if (closeWithoutEventAck) listener.onClosed("lost ACK")
                                else listener.onMessage("[\"OK\",\"${e.id}\",$accept,\"\"]")
                            }
                        }
                        override fun disconnect() { listener.onClosed("closed") }; override fun dispose() {}
                    }
                }
                override fun request(request: LinkJsonRequest): LinkJsonResponse = error("No private HTTP/public fallback")
                override fun pair(routeId: String, card: ByteArray, pairingSecret: ByteArray, expiresAt: ULong): StoredLinkRoute = error("No pairing during test")
                override fun upsert(route: StoredLinkRoute) {}; override fun retire(routeId: String) {}; override fun finalize(routeId: String) {}; override fun remove(routeId: String) {}; override fun stop() {}
            }
        })
        val channel = VmlsRevocationChannel(vault, { _, _ -> publicIo++; error("Public sentinel") }, { error("Directory sentinel") }, { !quiet.get() }, { clock })
        val runtime = VmlsRuntime(vault, manager, roomStore, VmlsGrantLedger(Memory()), VmlsInviteStore(Memory()),
            { publicIo++; error("Public carrier sentinel") }, { null }, quiet, scope, now = { clock })
        suspend fun initialise() {
            val g = assertIs<VaultResult.Ok<CoordinationGenesis>>(vault.beginCoordination(account.pubkey, Digests.sha256("workflow-subject".toByteArray()), witness.key)).value
            witness.enrol(g.subject, g.initialDigest); assertEquals(CoordinationStatus.Active, vault.coordinationStatus(account.pubkey, check = true))
            device = assertIs<VaultResult.Ok<EnrolledDevice>>(vault.enrol(ctx, account, clock + 86400)).value
        }
        fun request() = VmlsRevocationRequest(account.pubkey, otherKeeper.pubkey, "33".repeat(32), listOf("44".repeat(32)), listOf(route.box), clock, clock + 86400)
        suspend fun memberPermission(): String = otherKeeper.sign(24242, clock, listOf(listOf("t", "keeper-inbox-deposit"), listOf("server", LinkRelayAddress.canonicalForNode(route.box)),
            listOf("device", device.device), listOf("grant", "56".repeat(16)), listOf("expiration", (clock + 3600).toString()), listOf("status", "active")), "").toCompactJson()
        suspend fun prepare() = runtime.prepareKeeperPermission(signer, route.box, "78".repeat(32), 7) { live }
        suspend fun records() = runtime.permissionRecords(account.pubkey)
        fun close() { scope.cancel(); manager.close() }
    }

    @Test fun `runtime invitation preview default gate holds every operation before vault or carriers`() = runBlocking<Unit> {
        assertFalse(dev.forgesworn.kithmoot.BuildConfig.EXPERIMENTAL_INVITATION_MLS, "Run this composition gate case with the default build setting")
        val f = Fixture()
        try {
            val id = "11".repeat(32)
            val operations: List<suspend () -> Unit> = listOf(
                { f.runtime.createInvitationRoom(f.account.pubkey, f.route.box, "Synthetic") },
                { f.runtime.issuePrivateInvitation(f.account.pubkey, id, ExperimentalInvitationTarget(id, id, id, f.route.box, f.clock + 60)) },
                { f.runtime.reviewPrivateInvitation(f.account.pubkey, id, "AA==") },
                { f.runtime.acceptPrivateInvitation(f.account.pubkey, id, "AA==", "Synthetic") },
                { f.runtime.exportPrivateInvitationResponse(f.account.pubkey, id) },
                { f.runtime.importPrivateInvitationResponse(f.account.pubkey, id, "AA==", "AA==") },
                { f.runtime.exportInvitationRegistrationRequest(f.account.pubkey, id, "AA==", "AA==") },
                { f.runtime.reviewInvitationRegistrationRequest("AA==") },
                { f.runtime.registerInvitationRequest(f.account.pubkey, "AA==") },
                { f.runtime.addPrivateInvitationMember(f.account.pubkey, id, "AA==", "AA==", "AA==") },
                { f.runtime.removeInvitationMember(f.account.pubkey, id, id) },
            )
            for (operation in operations) assertFailsWith<IllegalStateException> { operation() }
            assertEquals(0, f.opens); assertEquals(0, f.publicIo); assertEquals(0, f.signCalls)
            assertTrue(f.roomStore.rooms().isEmpty())
            assertNull(f.runtime.invitationConsent.value)
        } finally { f.close() }
    }

    @Test fun `shared signed vector imports exact active fields and rejects revoked as sender permission`() {
        val raw = checkNotNull(javaClass.getResourceAsStream("/keeper-inbox-permission-vector.json")).bufferedReader().use { it.readText() }
        val v = Json.parseToJsonElement(raw).jsonObject
        val activeJson = v.getValue("active").toString(); val e = KeeperInboxPermissionJson.read(activeJson)
        assertEquals(v.getValue("active"), e.toJson()); assertTrue(Events.verify(e))
        val route = VmlsBoxRoute(v.getValue("keeper").jsonPrimitive.content, v.getValue("node").jsonPrimitive.content, "vector-route", "Synthetic vector")
        assertEquals(e, KeeperInboxPermissionJson.preview(activeJson, route, route.persona, v.getValue("device").jsonPrimitive.content, v.getValue("now").jsonPrimitive.long))
        val stored = KeeperPermissionRecord(route, activeJson, v.getValue("revoked").toString(), KeeperPermissionStatus.INSTALLED)
        stored.validate()
        val restored = RevocationBook.decode(RevocationBook(keeperPermissions = linkedMapOf(e.id to stored)).encode()).keeperPermissions.getValue(e.id)
        assertEquals(activeJson, restored.activeJson, "Export material retains the exact prepared JSON bytes")
        assertEquals(v.getValue("active"), KeeperInboxPermissionJson.read(restored.activeJson).toJson())
        assertFailsWith<IllegalArgumentException> { KeeperInboxPermissionJson.preview(v.getValue("revoked").toString(), route, route.persona, v.getValue("device").jsonPrimitive.content, v.getValue("now").jsonPrimitive.long) }
        assertFailsWith<IllegalArgumentException> { KeeperInboxPermissionJson.read(" ".repeat(8193)) }
    }

    @Test fun `runtime sends retained private request through fresh real typed consent and direct device AUTH`() = runBlocking<Unit> {
        val f = Fixture(); f.initialise()
        try {
            val key = f.channel.retain(f.ctx, f.request()); assertFalse(f.runtime.requestOutbox(f.account.pubkey).single().second.attempted)
            val pending = async { f.runtime.sendPrivateRequest(f.account.pubkey, f.signer, key, f.route.box, f.memberPermission(), 0) { f.live } }
            val consent = withTimeout(5000) { f.runtime.keeperConsent.filterNotNull().first() }
            assertEquals(f.device.device, consent.device); assertNotEquals(f.request().device, consent.device)
            assertEquals(f.otherKeeper.pubkey, consent.keeper); assertEquals(f.route.box, consent.box)
            f.runtime.answerKeeperConsent(consent, ConsentDecision.Approve)
            assertTrue(pending.await().contains("ACKed"))
            assertEquals(listOf(22242, 1059), f.wire.map { it.kind }); assertEquals(f.device.device, f.wire.first().pubkey)
            assertEquals(listOf(13), f.rootKinds); assertEquals(0, f.publicIo)
            val retained = f.runtime.requestOutbox(f.account.pubkey).single().second
            assertTrue(retained.sent); assertEquals(1, retained.revision); assertFalse(retained.earlierPublicAttempt)
            assertFailsWith<IllegalArgumentException> { f.runtime.sendPrivateRequest(f.account.pubkey, f.signer, key, f.route.box, f.memberPermission(), 0) { true } }
        } finally { f.close() }
    }

    @Test fun `runtime consent denial and actual cancellation preserve unconfirmed intent with no AUTH or public IO`() = runBlocking<Unit> {
        val f = Fixture(); f.initialise()
        try {
            val key = f.channel.retain(f.ctx, f.request())
            val first = async { f.runtime.sendPrivateRequest(f.account.pubkey, f.signer, key, f.route.box, f.memberPermission(), 0) { f.live } }
            val consent = withTimeout(5000) { f.runtime.keeperConsent.filterNotNull().first() }
            f.runtime.answerKeeperConsent(consent, ConsentDecision.Deny)
            assertTrue(first.await().contains("unconfirmed")); assertTrue(f.wire.isEmpty()); assertEquals(0, f.publicIo)
            val revision = f.runtime.requestOutbox(f.account.pubkey).single().second.revision
            val cancel = async { f.runtime.sendPrivateRequest(f.account.pubkey, f.signer, key, f.route.box, f.memberPermission(), revision) { f.live } }
            withTimeout(5000) { f.runtime.keeperConsent.filterNotNull().first() }
            cancel.cancelAndJoin(); assertNull(f.runtime.keeperConsent.value)
            assertFalse(f.runtime.requestOutbox(f.account.pubkey).single().second.sent)
        } finally { f.close() }
    }

    @Test fun `keeper active plus terminal pair is protected before exact account-authenticated install and export`() = runBlocking<Unit> {
        val f = Fixture(); f.initialise()
        try {
            val prepared = f.prepare(); assertEquals(listOf(24242, 24242), f.rootKinds); assertEquals(0, f.opens)
            assertEquals(prepared, f.records().single()); assertEquals(prepared.active.createdAt + 1, prepared.revoked.createdAt)
            assertEquals(prepared.active.tagValue("expiration"), prepared.revoked.tagValue("expiration"))
            assertEquals(32, prepared.active.tagValue("grant")!!.length)
            assertFailsWith<IllegalArgumentException> { f.runtime.exportKeeperPermission(f.account.pubkey, prepared.id) { true } }
            val installed = f.runtime.publishKeeperPermission(f.signer, prepared.id, 0, false) { f.live }
            assertEquals(KeeperPermissionStatus.INSTALLED, installed.status)
            assertEquals(listOf(22242, 24242), f.wire.map { it.kind }); assertEquals(f.account.pubkey, f.wire.first().pubkey)
            assertEquals(prepared.activeJson, f.exact.single())
            assertEquals(prepared.activeJson, f.runtime.exportKeeperPermission(f.account.pubkey, prepared.id) { true })
            assertEquals(0, f.publicIo)
        } finally { f.close() }
    }

    @Test fun `unknown install and terminal revoke retry exact persisted bytes without new permission signing`() = runBlocking<Unit> {
        val f = Fixture(); f.initialise()
        try {
            val prepared = f.prepare(); f.accept = false
            val unknown = f.runtime.publishKeeperPermission(f.signer, prepared.id, 0, false) { true }
            assertEquals(KeeperPermissionStatus.INSTALL_UNKNOWN, unknown.status)
            assertFailsWith<IllegalArgumentException> { f.runtime.exportKeeperPermission(f.account.pubkey, prepared.id) { true } }
            f.accept = true
            val installed = f.runtime.publishKeeperPermission(f.signer, prepared.id, unknown.revision, false) { true }
            assertEquals(listOf(prepared.activeJson, prepared.activeJson), f.exact)
            f.accept = false
            val revoking = f.runtime.publishKeeperPermission(f.signer, prepared.id, installed.revision, true) { true }
            assertEquals(KeeperPermissionStatus.REVOKE_UNKNOWN, revoking.status)
            assertFailsWith<IllegalArgumentException> { f.runtime.publishKeeperPermission(f.signer, prepared.id, revoking.revision, false) { true } }
            f.accept = true
            val revoked = f.runtime.publishKeeperPermission(f.signer, prepared.id, revoking.revision, true) { true }
            assertEquals(KeeperPermissionStatus.REVOKED, revoked.status)
            assertEquals(listOf(prepared.revokedJson, prepared.revokedJson), f.exact.takeLast(2))
            assertEquals(2, f.rootKinds.count { it == 24242 }); assertEquals(0, f.publicIo)
        } finally { f.close() }
    }

    @Test fun `keeper permission expiry route loss and context loss never export or send stale material`() = runBlocking<Unit> {
        val f = Fixture(); f.initialise()
        try {
            val prepared = f.prepare(); f.manager.remove(f.route.routeId)
            assertFailsWith<IllegalStateException> { f.runtime.publishKeeperPermission(f.signer, prepared.id, 0, false) { true } }
            assertEquals(0, f.opens)
            val restoredCard = ByteArray(126).also { "FSL1".toByteArray().copyInto(it); it[4] = 1; it.fill(0x42, 5, 37) }
            f.manager.upsert(StoredLinkRoute(f.route.routeId, restoredCard, ByteArray(32) { 7 }, 1UL, 1UL))
            f.clock = prepared.expiresAt
            assertFailsWith<IllegalArgumentException> { f.runtime.publishKeeperPermission(f.signer, prepared.id, 0, true) { true } }
            assertEquals(KeeperPermissionStatus.PREPARED, f.records().single().status)
        } finally { f.close() }
    }

    @Test fun `invalid clock reaches neither account signature and cancellation never persists a partial pair`() = runBlocking<Unit> {
        val f = Fixture(); f.initialise()
        try {
            for (at in listOf(-1L, Long.MAX_VALUE, 9_007_199_254_740_991L)) {
                assertFailsWith<IllegalArgumentException> { KeeperPermissionRecord.prepare(f.route, f.signer, "78".repeat(32), 86400, { at }, { true }, java.security.SecureRandom()) }
            }
            assertTrue(f.rootKinds.isEmpty())
            f.duringSign = { if (f.signCalls == 2) throw CancellationException("cancelled approval") }
            assertFailsWith<CancellationException> { f.prepare() }; assertTrue(f.records().isEmpty())
        } finally { f.close() }
    }

    @Test fun `control lease rejects a same-public-key signer replacement after a delayed account approval`() = runBlocking<Unit> {
        val f = Fixture(); f.initialise()
        try {
            var latest: ParticipantSigner? = f.signer
            val lease = KeeperWorkflowLease(f.account.pubkey, f.signer, { latest }, { f.live })
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            f.duringSign = { if (it == 24242) { entered.complete(Unit); release.await() } }
            val pending = async { runCatching { f.runtime.prepareKeeperPermission(f.signer, f.route.box, "78".repeat(32), 7, lease::current) } }
            entered.await()
            latest = object : ParticipantSigner by f.signer {}
            assertEquals(f.signer.pubkey, latest!!.pubkey); assertFalse(lease.current())
            release.complete(Unit)
            assertIs<IllegalStateException>(pending.await().exceptionOrNull())
            assertEquals(1, f.signCalls); assertTrue(f.records().isEmpty()); assertEquals(0, f.opens)
        } finally { f.close() }
    }

    @Test fun `version three custody cannot be downgraded to a writer that discards terminal state`() = runBlocking<Unit> {
        val f = Fixture(); f.initialise()
        try {
            val record = f.prepare()
            val bytes = assertIs<VaultResult.Ok<ByteArray?>>(f.vault.revocationRequests(f.ctx)).value!!
            val o = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            assertEquals(3, o.getValue("version").jsonPrimitive.int)
            assertEquals(record, RevocationBook.decode(bytes).keeperPermissions.getValue(record.id))
            for (field in listOf("keeperPermissions", "publicAttempts")) assertFailsWith<IllegalArgumentException> {
                RevocationBook.decode(JsonObject(o - field).toString().encodeToByteArray())
            }
            for (version in listOf(1, 2)) assertFailsWith<IllegalArgumentException> {
                RevocationBook.decode(JsonObject(o + ("version" to JsonPrimitive(version))).toString().encodeToByteArray())
            }
            // d0d725c9's version gate runs before any permissive field handling.
            assertFailsWith<IllegalArgumentException> { require(o.getValue("version").jsonPrimitive.int in 1..2) }
        } finally { f.close() }
    }
}
