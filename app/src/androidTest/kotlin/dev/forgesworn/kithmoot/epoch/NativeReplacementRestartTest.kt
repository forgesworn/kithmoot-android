package dev.forgesworn.kithmoot.epoch

import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import android.os.Process
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.crypto.*
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.*
import dev.forgesworn.kithmoot.session.*
import dev.forgesworn.kithmoot.storage.*
import dev.forgesworn.kithmoot.ui.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Five actual source/index commits held until the guarded external SIGKILL. */
class NativeReplacementRestartTest {
    @get:Rule val compose = createComposeRule()

    private class Link(private val scopeName: String, private val scope: CoroutineScope) : RoomMeshLink {
        @Volatile var receive: ((ByteArray, String) -> Unit)? = null
        @Volatile var peer: Link? = null
        @Volatile var available = true
        val events = CopyOnWriteArrayList<NostrEvent>()
        val queries = CopyOnWriteArrayList<JsonObject>()
        override fun subscribe(receive: (ByteArray, String) -> Unit): AutoCloseable {
            this.receive = receive
            return AutoCloseable { this.receive = null }
        }
        override fun offer(bytes: ByteArray, to: String?) {
            RoomMeshWire.decode(bytes)?.let { (kind, body) ->
                body["event"]?.let { events += NostrEvent.fromJson(it) }
                if (kind == RoomMeshWire.QUERY) body.getValue("filters").jsonArray.forEach { queries += it.jsonObject }
            }
            val copy = bytes.copyOf()
            scope.launch { delay(1); peer?.receive?.invoke(copy, "replacement-lab-peer") }
        }
        fun inject(event: NostrEvent) = receive?.invoke(RoomMeshWire.encode(RoomMeshWire.EVENT,
            buildJsonObject { put("scope", scopeName); put("event", event.toJson()) }), "replacement-lab-peer")
        override suspend fun resetQueued() = Unit
        override fun reachable() = available
        override fun close() { available = false; receive = null }
    }

    /** Real storage commits first. Only its return is held by the test. */
    private class Pause(private val storage: RoomStorage) : RoomStorage by storage {
        @Volatile var checkpoint: ((JsonObject) -> Unit)? = null
        var matches: (JsonObject) -> Boolean = { false }
        private val claimed = AtomicBoolean()
        val release = CountDownLatch(1)
        override fun write(value: ByteArray) {
            storage.write(value)
            val report = checkpoint ?: return
            val record = Json.parseToJsonElement(value.toString(Charsets.UTF_8)).jsonObject
            if (!matches(record) || !claimed.compareAndSet(false, true)) return
            try { report(record) }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Throwable) { throw IllegalStateException("Committed replacement checkpoint failed", failure) }
            check(release.await(120, TimeUnit.SECONDS)) { "External replacement SIGKILL did not arrive" }
        }
    }

    private class Preparation(val f: NativeHostFixture) {
        val app = f.app
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val at = System.currentTimeMillis() / 1000
        private val creation = NativeKeeperCreation.fresh(at)
        val base = creation.roomSecret()
        val room = deriveRoom(base)
        private val keys = listOf(5, 6, 7, 8).map { seed -> ByteArray(32).apply { this[31] = seed.toByte() } }
        val owner = PrimaryIdentity.create(room.roomId, at + 3600, at, keys[0], keys[1])
        val binding = NativeKeeperBinding(room.roomId, creation.authority, owner.participant, owner.devicePubkey, RoomRoute.NEARBY, emptyList())
        val q = RoomRekeyBinding(room.roomId, binding.authority, owner.devicePubkey, binding.meshScope, emptyList(), RoomRoute.NEARBY)
        val sourceStorage = EncryptedRoomStorage(app, sourceAlias(binding), NativeKeeperJournal.MAX_FILE_BYTES)
        val pause = Pause(sourceStorage)
        private val indexField = RoomRepository::class.java.getDeclaredField("storage").apply { isAccessible = true }
        private val originalIndex = synchronized(app.savedRooms) { indexField.get(app.savedRooms) as RoomStorage }
        val indexPause = Pause(originalIndex)
        private var indexWrapped = false
        lateinit var oldRequest: NostrEvent
        val queueVault = RoomRekeyVault(app, q)
        val checkpoint = EncryptedRoomStorage(app, CHECKPOINT, 128 * 1024)
        val peerStore = EncryptedRoomStorage(app, peerAlias(room.roomId))
        val peerReceiver = EpochVault(peerStore)
        val link = Link(requireNotNull(binding.meshScope), scope)
        private val peerLink = Link(requireNotNull(binding.meshScope), scope)
        private val mesh = RoomMeshTransport(requireNotNull(binding.meshScope), link)
        private val peerMesh = RoomMeshTransport(requireNotNull(binding.meshScope), peerLink)
        lateinit var saved: SavedRoom
        lateinit var source: NativeKeeperJournal
        lateinit var ledger: RoomRekeyLedger
        lateinit var live: RoomSession
        lateinit var peer: RoomSession
        lateinit var member: PrimaryIdentity
        lateinit var controller: NativeKeeperController
        @Volatile private var selected = true

        suspend fun create() {
            assertNull(checkpoint.read())
            synchronized(app.savedRooms) {
                assertSame(originalIndex, indexField.get(app.savedRooms))
                indexField.set(app.savedRooms, indexPause); indexWrapped = true
            }
            val invitation = creation.invitation()
            val url = try { encodeInvitationUrl("https://kithmoot.invalid/", invitation, emptyList()) }
                finally { invitation.bearer.fill(0) }
            app.roomEpochs.initialise(room.roomId, binding.authority, base, at)
            peerReceiver.initialise(room.roomId, binding.authority, base, at)
            source = NativeKeeperJournal.create(pause, binding, creation, owner.credential)
            saved = SavedRoom.create(base, owner, url, emptyList(), "Native replacement process lab", at,
                null, binding.authority, route = RoomRoute.NEARBY).withNativeAuthority(source)
            app.savedRooms.saveNew(saved)
            ledger = queueVault.open(initialise = true); source.recordCourierCreated(ledger)
            link.peer = peerLink; peerLink.peer = link
            live = RoomSession(room, owner, mesh, scope, authority = binding.authority,
                epochGate = { event, notice ->
                    requireNotNull(app.roomEpochs.follow(room.roomId, notice, event.id, System.currentTimeMillis() / 1000))
                    EpochGateResult.COMMITTED
                }, timing = SessionTiming(announceJitterMs = 0))
            live.holdKeeperStartup(); live.join()
            controller = NativeKeeperController.startForRoom(source, app.roomEpochs, live, ledger,
                NativeKeeperEndpoints(q, mesh, null), scope, { selected }, app.savedRooms)
            NativeHostFixture.await("retirement source subscriptions ready") {
                link.queries.any { it["kinds"]?.jsonArray?.any { k -> k.jsonPrimitive.int == KIND_EPOCH_REQUEST } == true }
            }
            member = f.identity(saved, at)
            val offline = member.enrol(Schnorr.publicKeyHex(keys[2]), room.roomId, at + 3600, at)
            for ((key, credential) in listOf(member.deviceSecretKey to member.credential, keys[2] to offline)) {
                var ask = encodeEpochRequest(room.roomId, binding.authority, room.roomKey, key, credential, at)
                link.inject(ask)
                if (credential == member.credential) {
                    NativeHostFixture.await("retirement unknown member is observed") { member.participant in controller.unknownParticipants.value }
                    controller.approve(member.participant)
                    val old = ask.id
                    ask = encodeEpochRequest(room.roomId, binding.authority, room.roomKey, key, credential, System.currentTimeMillis() / 1000)
                    assertNotEquals(old, ask.id); link.inject(ask)
                }
                NativeHostFixture.await("retirement device is durably qualified") {
                    link.events.any { decodeEpochGrant(it, room.roomId, binding.authority, key, ask.id,
                        System.currentTimeMillis() / 1000) is EpochGrant.Current }
                }
            }
            peer = RoomSession(room, member, peerMesh, scope, authority = binding.authority,
                requireFreshEpoch = true, expectedEpoch = 0,
                epochGate = { event, notice ->
                    check(!notice.closed)
                    requireNotNull(peerReceiver.follow(room.roomId, notice, event.id, System.currentTimeMillis() / 1000))
                    EpochGateResult.COMMITTED
                }, timing = SessionTiming(announceJitterMs = 0))
            peer.join()
            live.sendChat("host before retirement kill"); peer.sendChat("member before retirement kill")
            NativeHostFixture.await("approved retirement peer chats before checkpoint") {
                peer.chat.value.any { it.body == "host before retirement kill" } && live.chat.value.any { it.body == "member before retirement kill" }
            }
            assertEquals(3, source.snapshot().deviceCount)
            controller.rekeyMembers()
            NativeHostFixture.await("prior real rekey reaches both durable receivers") {
                controller.state.value == NativeKeeperController.State.Ready(1, KeeperPhase.ACTIVE) &&
                    peer.epochState.value is RoomEpochState.Active &&
                    receiver(peerReceiver, saved.id).getValue("epoch").jsonPrimitive.int == 1
            }
            val invitationCopy = requireNotNull(saved.invitation).invitation
            oldRequest = try { encodeLivePersistentRequest(LivePersistentContext(invitationCopy, saved.id),
                keys[3], System.currentTimeMillis() / 1000) } finally { invitationCopy.bearer.fill(0) }
            link.inject(oldRequest)
            NativeHostFixture.await("old actual invitation answer is retained and offered") {
                f.source(saved).getValue("answers").jsonArray.any {
                    val entry = it.jsonObject
                    entry.getValue("request").jsonObject.getValue("id") == JsonPrimitive(oldRequest.id) &&
                        entry.getValue("handed").jsonPrimitive.boolean
                }
            }
            controller.approve(member.participant) // Await the same serialized worker before arming.
            NativeHostFixture.await("replacement command observation is ready") { controller.hosting.value.canReplaceInvitation }
            assertEquals(1, receiver(app.roomEpochs, saved.id).getValue("epoch").jsonPrimitive.int)
            assertEquals(3, source.snapshot().deviceCount)
        }

        suspend fun close() {
            selected = false
            pause.checkpoint = null; indexPause.checkpoint = null
            pause.release.countDown(); indexPause.release.countDown()
            try { if (::controller.isInitialized) controller.stop() else {
                if (::source.isInitialized) source.close(); if (::ledger.isInitialized) ledger.close()
            } } finally {
                try { if (::live.isInitialized) live.leave(); if (::peer.isInitialized) peer.leave() }
                finally {
                    mesh.close(); peerMesh.close(); scope.cancel()
                    if (indexWrapped) synchronized(app.savedRooms) {
                        assertSame(indexPause, indexField.get(app.savedRooms))
                        indexField.set(app.savedRooms, originalIndex); indexWrapped = false
                    }
                    NativeKeeperVault(app, binding).forget(); queueVault.forget()
                    app.roomEpochs.forget(room.roomId); app.savedRooms.forget(room.roomId)
                    peerStore.reset(); checkpoint.reset()
                    creation.close(); base.fill(0); room.roomKey.fill(0); keys.forEach { it.fill(0) }
                    f.close()
                }
            }
        }
    }

    @Test fun a_prepare(): Unit = runBlocking {
        val mode = mode(); val f = NativeHostFixture(); val r = Preparation(f)
        val ready = AtomicBoolean()
        var failure: Throwable? = null
        try {
            r.create()
            val prior = f.source(r.saved)
            val priorDebt = nearbyDebt(prior)
            val priorSpends = prior.getValue("spends").jsonArray
            val oldCache = prior.getValue("answers").jsonArray.single {
                it.jsonObject.getValue("request").jsonObject.getValue("id") == JsonPrimitive(r.oldRequest.id)
            }.jsonObject
            val report: (JsonObject) -> Unit = {
                r.link.available = false
                val record = f.source(r.saved)
                val pending = record.getValue("replacement").jsonObject
                val notice = NostrEvent.fromJson(pending.getValue("retirement"))
                val welcome = NostrEvent.fromJson(pending.getValue("proposed").jsonObject.getValue("welcome"))
                assertTrue(Events.verify(notice)); assertTrue(Events.verify(welcome))
                assertEquals(KIND_INVITATION_RETIREMENT, notice.kind)
                assertEquals(KIND_GROUP_INVITATION, welcome.kind)
                assertEquals(1, record.getValue("epoch").jsonPrimitive.int)
                assertEquals(3, record.getValue("devices").jsonArray.size)
                assertEquals(traffic(prior), traffic(record))
                assertEquals(0, record.getValue("activeInvitation").jsonObject.getValue("generation").jsonPrimitive.int)
                assertEquals(1, pending.getValue("proposed").jsonObject.getValue("generation").jsonPrimitive.int)
                assertTrue(priorSpends.all { original ->
                    record.getValue("spends").jsonArray.count { it == original } >= priorSpends.count { it == original }
                })
                assertEquals(JsonObject(oldCache + ("generation" to JsonPrimitive(0))), record.getValue("answers").jsonArray.single {
                    it.jsonObject.getValue("request").jsonObject.getValue("id") == JsonPrimitive(r.oldRequest.id)
                })
                val attempts = attempts(record)
                assertEquals(if (mode == MODES[0]) 0 else 1, attempts)
                assertEquals(eventBytes(notice) * attempts, nearbyDebt(record) - priorDebt)
                val offered = mode in MODES.drop(2)
                assertEquals(if (offered) listOf(notice) else emptyList<NostrEvent>(),
                    r.link.events.filter { event -> event.kind == KIND_INVITATION_RETIREMENT })
                assertTrue(r.link.events.none { event -> event.kind == KIND_GROUP_INVITATION })
                assertEquals(if (offered) 1 else 0, total(pending.getValue("offered")))
                val stage = if (mode == MODES[3]) "NOTICE_ARCHIVED" else if (mode == MODES[4]) "INDEX_VERIFIED" else "ORIGINALS_RETAINED"
                assertEquals(stage, pending.getValue("stage").jsonPrimitive.content)
                val actualIndex = f.committed("kithmoot.rooms.v1", 4 * 1024 * 1024)
                val actual = SavedRoom.decode(actualIndex.getValue("rooms").jsonArray.single {
                    it.jsonObject.getValue("id") == JsonPrimitive(r.saved.id)
                }.jsonObject)
                assertEquals(pending.getValue(if (mode in MODES.drop(3)) "proposedReference" else "previousReference"), actual.json.getValue("nativeAuthority"))
                assertEquals(digest(pending.getValue(if (mode in MODES.drop(3)) "proposedJoinUrl" else "previousJoinUrl").jsonPrimitive.content), digest(actual.joinUrl))
                assertFalse(actual.retired)
                val proposedId = actualInvitationId(pending.getValue("proposed").jsonObject)
                assertTrue(r.link.queries.none { query -> query["#d"]?.jsonArray?.contains(JsonPrimitive(proposedId)) == true })
                val courier = f.courier(r.saved)
                val expected = buildJsonObject {
                    put("pid", Process.myPid()); put("mode", mode); put("room", r.saved.id); put("peerAt", r.at)
                    put("sourceDigest", digest(record.toString())); put("traffic", traffic(prior))
                    put("indexDigest", digest(actualIndex.toString()))
                    put("reference", actual.json.getValue("nativeAuthority")); put("invitationDigest", digest(actual.joinUrl))
                    put("previousReference", pending.getValue("previousReference")); put("proposedReference", pending.getValue("proposedReference"))
                    put("oldRequest", r.oldRequest.toJson()); put("oldAnswer", oldCache.getValue("answer"))
                    put("notice", notice.toJson()); put("welcome", welcome.toJson()); put("proposedId", proposedId)
                    put("priorNearbyDebt", priorDebt); put("priorSpends", priorSpends)
                    put("receiver", receiver(f.app.roomEpochs, r.saved.id)); put("peerReceiver", receiver(r.peerReceiver, r.saved.id))
                    put("courier", JsonObject(courier.filterKeys { key -> key != "high" })); put("courierHigh", courier.getValue("high"))
                }
                val bytes = expected.toString().toByteArray(Charsets.UTF_8)
                try { r.checkpoint.write(bytes) } finally { bytes.fill(0) }
                ready.set(true)
                InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                    putString("native_replacement_restart_pid", Process.myPid().toString())
                    putString("native_replacement_restart_checkpoint", "ready")
                    putString("native_replacement_restart_mode", mode)
                })
            }
            if (mode == MODES[3]) {
                r.indexPause.matches = { record -> record.getValue("rooms").jsonArray.any {
                    it.jsonObject["nativeAuthority"]?.jsonObject?.get("generation") == JsonPrimitive(1)
                } }
                r.indexPause.checkpoint = report
            } else {
                r.pause.matches = { record ->
                    val pending = record["replacement"] as? JsonObject
                    pending != null && if (mode == MODES[4]) pending.getValue("stage") == JsonPrimitive("INDEX_VERIFIED")
                    else pending.getValue("stage") == JsonPrimitive("ORIGINALS_RETAINED") &&
                        total(pending.getValue("attempts")) == (if (mode == MODES[0]) 0 else 1) &&
                        total(pending.getValue("offered")) == (if (mode == MODES[2]) 1 else 0)
                }
                r.pause.checkpoint = report
            }
            r.controller.replaceObservedInvitation(r.controller.hosting.value)
            error("The committed replacement returned without an external kill")
        } catch (error: Throwable) { failure = error; throw error }
        finally { if (!ready.get()) cleanup(failure) { r.close() } }
    }

    @Test fun b_recover(): Unit = runBlocking {
        val mode = mode()
        check(InstrumentationRegistry.getArguments().getString("requireRestart") == "true")
        val f = NativeHostFixture()
        val checkpoint = EncryptedRoomStorage(f.app, CHECKPOINT, 128 * 1024)
        val bytes = requireNotNull(checkpoint.read())
        val expected = try { Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject } finally { bytes.fill(0) }
        assertEquals(mode, expected.getValue("mode").jsonPrimitive.content)
        assertNotEquals(expected.getValue("pid").jsonPrimitive.int, Process.myPid())
        val saved = requireNotNull(f.app.savedRooms.get(expected.getValue("room").jsonPrimitive.content))
        f.savedRoom = saved
        val peerStore = EncryptedRoomStorage(f.app, peerAlias(saved.id))
        val peerReceiver = EpochVault(peerStore)
        var failure: Throwable? = null
        var result: Bundle? = null
        var oldInvitation: RoomInvitation? = null
        try {
            // No entry, route, credential refresh or recovery write precedes these cold checks.
            val cold = f.source(saved)
            val pendingIndex = cold.getValue("replacement").jsonObject
            val previousInvitationDigest = digest(pendingIndex.getValue("previousJoinUrl").jsonPrimitive.content)
            val proposedInvitationDigest = digest(pendingIndex.getValue("proposedJoinUrl").jsonPrimitive.content)
            assertNotEquals(previousInvitationDigest, proposedInvitationDigest)
            assertEquals(if (mode in MODES.drop(3)) proposedInvitationDigest else previousInvitationDigest,
                expected.getValue("invitationDigest").jsonPrimitive.content)
            oldInvitation = requireNotNull(decodeInvitationUrl(cold.getValue("replacement").jsonObject
                .getValue("previousJoinUrl").jsonPrimitive.content)).invitation
            assertEquals(expected.getValue("sourceDigest").jsonPrimitive.content, digest(cold.toString()))
            assertEquals(expected.getValue("indexDigest").jsonPrimitive.content, digest(f.committed("kithmoot.rooms.v1", 4 * 1024 * 1024).toString()))
            assertEquals(expected.getValue("reference"), saved.json.getValue("nativeAuthority"))
            assertEquals(expected.getValue("invitationDigest").jsonPrimitive.content, digest(saved.joinUrl))
            assertEquals(expected.getValue("traffic"), traffic(cold))
            assertEquals(expected.getValue("receiver"), receiver(f.app.roomEpochs, saved.id))
            assertEquals(expected.getValue("peerReceiver"), receiver(peerReceiver, saved.id))
            val courier = f.courier(saved)
            assertEquals(expected.getValue("courier"), JsonObject(courier.filterKeys { it != "high" }))
            assertTrue(courier.getValue("high").jsonPrimitive.long >= expected.getValue("courierHigh").jsonPrimitive.long)
            val notice = NostrEvent.fromJson(expected.getValue("notice"))
            val welcome = NostrEvent.fromJson(expected.getValue("welcome"))
            assertEquals(notice, original(cold)); assertEquals(welcome, proposedWelcome(cold))
            val priorDebt = expected.getValue("priorNearbyDebt").jsonPrimitive.int
            val deathAttempts = attempts(cold); val deathDebt = nearbyDebt(cold) - priorDebt
            f.startModel(); compose.showNativeHost(f)
            val beforeHome = digest(f.source(saved).toString())
            assertNull(f.model.inviteLinkFor(saved.id))
            assertEquals(beforeHome, digest(f.source(saved).toString()))
            f.main { f.setNearbyAvailable(false); f.model.reopenRoom(saved.id) }; f.opened()
            if (mode in MODES.take(2)) {
                f.awaitHost("cold same originals remain pending with no lane") {
                    f.model.room.value.nativeHosting?.replacementGeneration == 1 && !f.model.room.value.nativeHostingBusy
                }
                assertFalse(f.model.room.value.canShareInvitation); assertTrue(f.model.room.value.joinUrl.isEmpty())
                assertNull(f.model.inviteLinkFor(saved.id))
                assertEquals(deathAttempts, attempts(f.source(saved)))
                assertEquals(deathDebt, nearbyDebt(f.source(saved)) - priorDebt)
            }
            f.main { f.setNearbyAvailable(true) }
            f.awaitHost("replacement recovery selected lane returns") { f.model.room.value.nearby?.writablePeers == 1 }
            if (mode in MODES.take(2)) {
                compose.onNodeWithContentDescription("Room details").performClick()
                compose.onNodeWithText("Recover saved update").performScrollTo().performClick()
                compose.onNodeWithText("Recover update").performClick()
                compose.onNodeWithText("Done").performClick()
            }
            f.awaitHost("same replacement completes with the real next reference and installed collector") {
                !f.model.room.value.nativeHostingBusy && f.model.room.value.nativeHosting?.invitationGeneration == 1 &&
                    f.model.room.value.canShareInvitation
            }
            val completed = f.source(saved)
            val automaticAttempts = attempts(completed)
            assertEquals(deathAttempts + if (mode in MODES.take(2)) 1 else 0, automaticAttempts)
            assertEquals(eventBytes(notice) * automaticAttempts, nearbyDebt(completed) - priorDebt)
            assertEquals(notice, original(completed)); assertEquals(welcome, proposedWelcome(completed))
            assertEquals(JsonNull, completed.getValue("replacement"))
            assertEquals(1, completed.getValue("activeInvitation").jsonObject.getValue("generation").jsonPrimitive.int)
            assertEquals(expected.getValue("traffic"), traffic(completed))
            assertEquals(expected.getValue("receiver"), receiver(f.app.roomEpochs, saved.id))
            assertEquals(if (mode in MODES.take(2)) listOf(notice) else emptyList<NostrEvent>(),
                f.phoneEvents.filter { it.kind == KIND_INVITATION_RETIREMENT })
            assertTrue(f.phoneEvents.none { it.kind == KIND_GROUP_INVITATION })
            val actual = requireNotNull(f.app.savedRooms.get(saved.id))
            assertEquals(expected.getValue("proposedReference"), actual.json.getValue("nativeAuthority"))
            assertNotEquals(previousInvitationDigest, digest(actual.joinUrl))
            assertEquals(proposedInvitationDigest, digest(actual.joinUrl))
            assertEquals(digest(actual.joinUrl.substringAfter('#')), digest(f.model.room.value.joinUrl.substringAfter('#')))
            assertEquals(digest(actual.joinUrl.substringAfter('#')), digest(requireNotNull(f.model.inviteLinkFor(saved.id)).substringAfter('#')))
            val beforeOldRequest = f.phoneEvents.count { it.kind == KIND_INVITATION_GRANT }
            val oldRequester = ByteArray(32).apply { this[31] = 8 }
            val freshOldRequest = try { encodeLivePersistentRequest(LivePersistentContext(requireNotNull(oldInvitation), saved.id),
                oldRequester, System.currentTimeMillis() / 1000) } finally { oldRequester.fill(0) }
            f.injectPhone(saved, freshOldRequest)
            delay(250)
            assertEquals(beforeOldRequest, f.phoneEvents.count { it.kind == KIND_INVITATION_GRANT })
            val oldOwner = requireNotNull(f.model.room.value.nativeHosting)
            f.main { f.model.leave() }
            NativeHostFixture.await("replacement foreground closes for second owner") {
                f.model.stage.value == Stage.START && !f.model.start.value.busy && f.radios.all { it.closed }
            }
            f.main { f.model.reopenRoom(saved.id) }; f.opened()
            f.awaitHost("same completed generation reopens") { f.model.room.value.nativeHosting?.canReplaceInvitation == true }
            assertNotEquals(oldOwner.ownerGeneration, f.model.room.value.nativeHosting!!.ownerGeneration)
            val beforeStale = digest(f.source(saved).toString())
            f.main { f.model.replaceNativeInvitation(oldOwner) }
            f.awaitHost("stale replacement owner refuses without writing") {
                f.model.room.value.notice == "Room hosting changed. Open the confirmation again."
            }
            assertEquals(beforeStale, digest(f.source(saved).toString()))
            val peerAt = expected.getValue("peerAt").jsonPrimitive.long
            val recoveredPeer = f.rejoinApproved(actual, peerAt, peerReceiver)
            assertEquals(RoomEpochState.Active(1, deriveEpochId(1, peerReceiver, saved.id)), recoveredPeer.epochState.value)
            recoveredPeer.leave()
            // Fresh challenge against the NEXT invitation and actual installed
            // collector, then the existing approved device's current-epoch desk.
            val peer = f.join(actual, peerAt)
            assertEquals(RoomEpochState.Active(1, deriveEpochId(1, peerReceiver, saved.id)), peer.epochState.value)
            assertTrue(f.model.room.value.letInAsks.isEmpty())
            f.main { f.model.sendChat("host after replacement SIGKILL") }; peer.sendChat("member after replacement SIGKILL")
            f.awaitHost("approved epoch-one chat survives killed replacement both ways") {
                peer.chat.value.any { it.body == "host after replacement SIGKILL" } &&
                    f.model.room.value.chat.any { it.body == "member after replacement SIGKILL" }
            }
            assertEquals(0, f.server.requestCount)
            result = Bundle().apply {
                putString("native_replacement_recovery_pid", Process.myPid().toString())
                putString("native_replacement_recovery_mode", mode)
                putString("native_replacement_recovery_original_id", notice.id)
                putString("native_replacement_recovery_original_created_at", notice.createdAt.toString())
                putString("native_replacement_recovery_welcome_id", welcome.id)
                putString("native_replacement_recovery_welcome_created_at", welcome.createdAt.toString())
                putString("native_replacement_recovery_original_bytes", eventBytes(notice).toString())
                putString("native_replacement_recovery_attempts_at_death", deathAttempts.toString())
                putString("native_replacement_recovery_attempts_after_completion", automaticAttempts.toString())
                putString("native_replacement_recovery_debt_at_death", deathDebt.toString())
                putString("native_replacement_recovery_debt_after_completion", (nearbyDebt(completed) - priorDebt).toString())
                putString("native_replacement_recovery_devices", "3")
                putString("native_replacement_recovery_same_epoch", "1")
                putString("native_replacement_recovery_invitation_generation", "1")
                putString("native_replacement_recovery_internet_requests", "0")
                putString("native_replacement_recovery_cleanup_verified", "true")
            }
        } catch (error: Throwable) { failure = error; throw error }
        finally { cleanup(failure) {
            oldInvitation?.bearer?.fill(0)
            try { f.close() } finally { peerStore.reset(); checkpoint.reset() }
            assertNull(peerStore.read()); assertNull(checkpoint.read())
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            for (alias in listOf(CHECKPOINT, peerAlias(saved.id), sourceAlias(requireNotNull(saved.nativeAuthority)),
                courierAlias(requireNotNull(saved.nativeAuthority)))) {
                assertFalse(keyStore.containsAlias(alias))
                assertTrue(listOf(".vault", ".vault.new", ".vault.bak").all { !File(f.app.noBackupFilesDir, alias + it).exists() })
            }
        } }
        InstrumentationRegistry.getInstrumentation().sendStatus(2, requireNotNull(result))
    }

    /** A second external driver uses a_prepare unchanged, then this fresh-process
     * matrix. Every row starts from identical real committed ciphertexts. */
    @Test fun b_refuse_pending_stores(): Unit = runBlocking {
        val mode = mode()
        check(InstrumentationRegistry.getArguments().getString("requireRestart") == "true")
        val f = NativeHostFixture()
        val checkpoint = EncryptedRoomStorage(f.app, CHECKPOINT, 128 * 1024)
        val bytes = requireNotNull(checkpoint.read())
        val expected = try { Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject }
            finally { bytes.fill(0) }
        val originals = mutableMapOf<File, ByteArray>()
        val rows = mutableListOf<String>()
        var saved: SavedRoom? = null
        var primary: Throwable? = null
        try {
            assertEquals(mode, expected.getValue("mode").jsonPrimitive.content)
            assertNotEquals(expected.getValue("pid").jsonPrimitive.int, Process.myPid())
            val room = expected.getValue("room").jsonPrimitive.content
            val selected = requireNotNull(f.app.savedRooms.get(room)).also { saved = it }
            // The guarded runner owns a disposable profile. Never restore or
            // reset a shared receiver containing somebody else's room.
            assertEquals(listOf(room), f.app.savedRooms.list().map { it.id })
            val binding = requireNotNull(selected.nativeAuthority)
            val directory = f.app.noBackupFilesDir
            val targets = mapOf("SOURCE" to sourceAlias(binding), "RECEIVER" to "kithmoot.epoch.v1",
                "COURIER" to courierAlias(binding), "INDEX" to "kithmoot.rooms.v1")
            val limits = mapOf("SOURCE" to NativeKeeperJournal.MAX_FILE_BYTES + 64,
                "RECEIVER" to 1024 * 1024 + 512, "COURIER" to RoomRekeyLedger.MAX_FILE_BYTES + 64,
                "INDEX" to 4 * 1024 * 1024 + 64)
            for ((target, alias) in targets) {
                val file = File(directory, "$alias.vault")
                assertTrue(file.isFile)
                check(file.length() in 1..limits.getValue(target).toLong())
                assertFalse(File(file.path + ".new").exists()); assertFalse(File(file.path + ".bak").exists())
            }
            originals.putAll(ciphertexts(directory))
            val keys = aliases()
            val cold = f.source(selected)
            val index = f.committed("kithmoot.rooms.v1", 4 * 1024 * 1024)
            val pending = cold.getValue("replacement").jsonObject
            val notice = original(cold); val welcome = proposedWelcome(cold)
            val stage = if (mode == MODES[3]) "NOTICE_ARCHIVED" else if (mode == MODES[4]) "INDEX_VERIFIED" else "ORIGINALS_RETAINED"
            val deathAttempts = if (mode == MODES[0]) 0 else 1
            val offered = if (mode in MODES.drop(2)) 1 else 0
            val indexGeneration = if (mode in MODES.drop(3)) 1 else 0
            assertEquals(expected.getValue("sourceDigest"), JsonPrimitive(digest(cold.toString())))
            assertEquals(expected.getValue("indexDigest"), JsonPrimitive(digest(index.toString())))
            assertEquals(expected.getValue("reference"), selected.json.getValue("nativeAuthority"))
            assertEquals(expected.getValue("invitationDigest"), JsonPrimitive(digest(selected.joinUrl)))
            assertEquals(expected.getValue("notice"), notice.toJson()); assertEquals(expected.getValue("welcome"), welcome.toJson())
            assertTrue(Events.verify(notice)); assertTrue(Events.verify(welcome))
            assertEquals(KIND_INVITATION_RETIREMENT, notice.kind); assertEquals(KIND_GROUP_INVITATION, welcome.kind)
            assertEquals(stage, pending.getValue("stage").jsonPrimitive.content)
            assertEquals(0, cold.getValue("activeInvitation").jsonObject.getValue("generation").jsonPrimitive.int)
            assertEquals(1, pending.getValue("proposed").jsonObject.getValue("generation").jsonPrimitive.int)
            assertEquals(indexGeneration, NativeKeeperReference.generation(selected.json.getValue("nativeAuthority")))
            assertEquals(1, cold.getValue("epoch").jsonPrimitive.int)
            assertEquals(3, cold.getValue("devices").jsonArray.size)
            assertEquals(expected.getValue("traffic"), traffic(cold))
            assertEquals(deathAttempts, attempts(cold)); assertEquals(offered, total(pending.getValue("offered")))
            val debt = nearbyDebt(cold) - expected.getValue("priorNearbyDebt").jsonPrimitive.int
            assertEquals(eventBytes(notice) * deathAttempts, debt)
            val cached = cold.getValue("answers").jsonArray.single {
                it.jsonObject.getValue("request").jsonObject.getValue("id") == expected.getValue("oldRequest").jsonObject.getValue("id")
            }.jsonObject
            assertEquals(expected.getValue("oldAnswer"), cached.getValue("answer"))
            assertTrue(expected.getValue("priorSpends").jsonArray.all { original ->
                cold.getValue("spends").jsonArray.count { it == original } >= expected.getValue("priorSpends").jsonArray.count { it == original }
            })
            assertEquals(expected.getValue("receiver"), receiver(f.app.roomEpochs, room))
            assertEquals(expected.getValue("peerReceiver"), receiver(EpochVault(EncryptedRoomStorage(f.app, peerAlias(room))), room))
            val courier = f.courier(selected)
            assertEquals(expected.getValue("courier"), JsonObject(courier.filterKeys { it != "high" }))
            assertTrue(courier.getValue("high").jsonPrimitive.long >= expected.getValue("courierHigh").jsonPrimitive.long)
            val faults = targets.keys.flatMap { target -> listOf(target to "MISSING", target to "CORRUPT") } +
                listOf("INDEX" to "OWNER_DEVICE", "INDEX" to "ROUTE_PINS", "INDEX" to "INVITATION")
            for ((target, fault) in faults) {
                // Every preceding attempt and foreground owner has closed.
                originals.forEach { (file, value) -> file.writeBytes(value) }
                assertEquals(keys, aliases())
                val damagedFile = File(directory, targets.getValue(target) + ".vault")
                if (fault == "MISSING") assertTrue(damagedFile.delete())
                else if (fault == "CORRUPT") {
                    val damaged = originals.getValue(damagedFile).copyOf()
                    try { damaged[damaged.lastIndex] = (damaged.last().toInt() xor 1).toByte(); damagedFile.writeBytes(damaged) }
                    finally { damaged.fill(0) }
                } else {
                    val hostile = hostileIndex(selected, fault)
                    // This must pass real SavedRoom validation, then be sealed
                    // by the real Android store. Ordinary repository save guards
                    // are never altered to permit this hostile lab input.
                    SavedRoom.decode(hostile)
                    val value = JsonObject(index + ("rooms" to JsonArray(listOf(hostile)))).toString().toByteArray(Charsets.UTF_8)
                    try { EncryptedRoomStorage(f.app, "kithmoot.rooms.v1").write(value) }
                    finally { value.fill(0) }
                }
                val afterFault = ciphertexts(directory)
                val keysAfterFault = aliases()
                var foreground: NativeHostFixture? = null
                var rowFailure: Throwable? = null
                try {
                    val rooms = RoomRepository(EncryptedRoomStorage(f.app, "kithmoot.rooms.v1"))
                    val receiver = EpochVault(RollbackResistantRoomStorage(f.app, "kithmoot.epoch.v1", 1024 * 1024))
                    var entry: NativeKeeperEntry? = null
                    var refused = false
                    try {
                        entry = NativeKeeperEntry.openForRoom(selected, receiver, rooms,
                            { NativeKeeperVault.forSavedRoom(f.app, selected).openForEntry() },
                            { q, initialise ->
                                assertFalse("Marked pending courier must never initialise", initialise)
                                RoomRekeyVault(f.app, q).open(initialise)
                            })
                    } catch (_: Exception) { refused = true }
                    finally { entry?.stop() }
                    assertTrue("Cold pending $mode $target $fault must refuse before routes", refused)
                    if (target == "INDEX" && fault in setOf("MISSING", "CORRUPT")) {
                        assertTrue("Actual index read must fail before room selection",
                            runCatching { requireNotNull(rooms.get(room)) }.isFailure)
                    } else {
                        // New ViewModel/fixture per row; never reuse a recovered
                        // owner, receiver, courier or source between faults.
                        foreground = NativeHostFixture()
                        val owner = foreground
                        owner.startModel()
                        assertEquals(0, owner.server.requestCount)
                        owner.main { owner.model.reopenRoom(room) }
                        NativeHostFixture.await("pending fault refuses foreground before routes") {
                            !owner.model.start.value.busy && owner.model.start.value.error != null
                        }
                        assertEquals(Stage.START, owner.model.stage.value)
                        assertEquals("Refusal must precede even the route factory", 0, owner.nearbyLinkCreations)
                        assertTrue(owner.radios.isEmpty()); assertTrue(owner.phoneEvents.isEmpty())
                        assertTrue(owner.relayWrites.isEmpty()); assertEquals(0, owner.server.requestCount)
                    }
                    NativeKeeperJournal.withInactiveOwner(binding.owner) { Unit }
                    val q = RoomRekeyBinding(binding.room, binding.authority, binding.device, binding.meshScope, binding.relays, binding.route)
                    RoomRekeyLedger.withInactiveOwner(q.owner) { Unit }
                    if (target != "SOURCE") assertEquals(cold, f.source(selected))
                    assertEquals(keysAfterFault, aliases())
                    assertCiphertexts(afterFault, directory)
                    rows += "NATIVE_PENDING_STORE_REFUSAL window=$mode stage=$stage target=$target fault=$fault " +
                        "originalId=${notice.id} originalCreatedAt=${notice.createdAt} welcomeId=${welcome.id} welcomeCreatedAt=${welcome.createdAt} " +
                        "originalBytes=${eventBytes(notice)} attempts=$deathAttempts offered=$offered chargedBytes=$debt " +
                        "epoch=1 devices=3 sourceGeneration=0 proposedGeneration=1 indexGeneration=$indexGeneration " +
                        "newRadios=0 newSubscriptions=0 newOffers=0 relayRequests=0 filesUnchanged=true keysUnchanged=true"
                } catch (error: Throwable) { rowFailure = error; throw error }
                finally { cleanup(rowFailure) {
                    try { foreground?.close(); assertEquals(keysAfterFault, aliases()); assertCiphertexts(afterFault, directory) }
                    finally { afterFault.values.forEach { it.fill(0) } }
                } }
            }
            assertEquals(11, rows.size)
        } catch (error: Throwable) { primary = error; throw error }
        finally { cleanup(primary) {
            try {
                originals.forEach { (file, value) -> file.writeBytes(value) }
                saved?.let { selected ->
                    val binding = requireNotNull(selected.nativeAuthority)
                    NativeKeeperVault(f.app, binding).forget()
                    val q = RoomRekeyBinding(binding.room, binding.authority, binding.device, binding.meshScope, binding.relays, binding.route)
                    RoomRekeyVault(f.app, q).forget()
                    f.app.roomEpochs.forget(selected.id); f.app.savedRooms.forget(selected.id)
                    EncryptedRoomStorage(f.app, peerAlias(selected.id)).reset()
                    assertNull(f.app.roomEpochs.get(selected.id)); assertNull(f.app.savedRooms.get(selected.id))
                    assertTrue(f.app.savedRooms.list().isEmpty())
                    RollbackResistantRoomStorage(f.app, "kithmoot.epoch.v1", 1024 * 1024).reset()
                    EncryptedRoomStorage(f.app, "kithmoot.epoch-history.v1").reset()
                    val owned = listOf(sourceAlias(binding), courierAlias(binding), peerAlias(selected.id), CHECKPOINT,
                        "kithmoot.epoch.v1", "kithmoot.epoch-history.v1")
                    checkpoint.reset()
                    assertTrue(aliases().none { key -> owned.any { key == it || key.startsWith("$it.entry.") } })
                    for (alias in owned) for (suffix in listOf(".vault", ".vault.new", ".vault.bak"))
                        assertFalse(File(f.app.noBackupFilesDir, alias + suffix).exists())
                }
            } finally {
                try { f.close() } finally { originals.values.forEach { it.fill(0) } }
            }
        } }
        // Numeric rows reach the actual instrument result stream only after
        // all eleven refusals, closed leases and stage-wide deletion succeed.
        // Pretty-mode am instrument emits the stream alone for a combined
        // bundle. This post-cleanup control measures that on the real runner;
        // distinct probe fields can never substitute for the required proof.
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString(Instrumentation.REPORT_KEY_STREAMRESULT, "\nNATIVE_PENDING_REPORT_CONTROL shape=combined\n")
            putString("native_pending_refusal_probe_pid", Process.myPid().toString())
            putString("native_pending_refusal_probe_mode", mode)
        })
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("native_pending_refusal_recovery_pid", Process.myPid().toString())
            putString("native_pending_refusal_recovery_mode", mode)
        })
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString(Instrumentation.REPORT_KEY_STREAMRESULT, "\n" + rows.joinToString("\n") { "$it cleanupVerified=true" } + "\n")
        })
    }

    private fun hostileIndex(saved: SavedRoom, fault: String): JsonObject {
        val binding = requireNotNull(saved.nativeAuthority)
        val generation = NativeKeeperReference.generation(saved.json.getValue("nativeAuthority"))
        return when (fault) {
            "OWNER_DEVICE" -> {
                val identity = saved.json.getValue("identity").jsonObject.toMutableMap()
                val participant = ByteArray(32).apply { this[31] = 9 }
                val device = ByteArray(32).apply { this[31] = 10 }
                try {
                    identity["participantKey"] = JsonPrimitive(participant.toHex()); identity["deviceKey"] = JsonPrimitive(device.toHex())
                    val changed = NativeKeeperBinding(binding.room, binding.authority, Schnorr.publicKeyHex(participant),
                        Schnorr.publicKeyHex(device), binding.route, binding.relays)
                    JsonObject(saved.json + mapOf("identity" to JsonObject(identity), "nativeAuthority" to
                        NativeKeeperReference.encode(changed, saved.json.getValue("nativeAuthority").jsonObject.getValue("invitation").jsonPrimitive.content, generation)))
                } finally { participant.fill(0); device.fill(0) }
            }
            "ROUTE_PINS" -> {
                val relays = listOf("wss://pending-refusal.invalid/")
                val changed = NativeKeeperBinding(binding.room, binding.authority, binding.participant, binding.device, RoomRoute.MIXED, relays)
                JsonObject(saved.json + mapOf("route" to JsonPrimitive(RoomRoute.MIXED.stored), "relays" to JsonArray(relays.map(::JsonPrimitive)),
                    "nativeAuthority" to NativeKeeperReference.encode(changed,
                        saved.json.getValue("nativeAuthority").jsonObject.getValue("invitation").jsonPrimitive.content, generation)))
            }
            "INVITATION" -> {
                val invitation = requireNotNull(saved.invitation).invitation
                try {
                    invitation.bearer[0] = (invitation.bearer[0].toInt() xor 1).toByte()
                    JsonObject(saved.json + mapOf("joinUrl" to JsonPrimitive(encodeInvitationUrl("https://kithmoot.invalid/", invitation, saved.relays)),
                        "nativeAuthority" to NativeKeeperReference.encode(binding, deriveInvitationId(invitation), generation)))
                } finally { invitation.bearer.fill(0) }
            }
            else -> error("Unknown hostile pending index fault")
        }
    }

    private fun aliases() = KeyStore.getInstance("AndroidKeyStore").run { load(null); aliases().toList().toSet() }
    private fun ciphertexts(directory: File): Map<File, ByteArray> {
        val result = mutableMapOf<File, ByteArray>()
        try {
            for (file in requireNotNull(directory.listFiles())) {
                check(file.isFile && file.length() <= 32L * 1024 * 1024)
                check(!file.name.endsWith(".new") && !file.name.endsWith(".bak"))
                result[file] = file.readBytes()
            }
            return result
        } catch (error: Throwable) { result.values.forEach { it.fill(0) }; throw error }
    }
    private fun assertCiphertexts(expected: Map<File, ByteArray>, directory: File) {
        assertEquals(expected.keys, requireNotNull(directory.listFiles()).toSet())
        for ((file, value) in expected) {
            val actual = file.readBytes()
            try { assertTrue("Cold refusal changed retained ciphertext", value.contentEquals(actual)) }
            finally { actual.fill(0) }
        }
    }

    companion object {
        private const val CHECKPOINT = "kithmoot.lab.native-replacement-restart"
        private val MODES = listOf("committed-source-before-offer", "charged-original-before-offer", "offered-before-index",
            "index-committed-before-source-acknowledgement", "reference-installed-before-subscription-switch")
        private fun mode(): String {
            require(Build.HARDWARE in setOf("ranchu", "goldfish"))
            return requireNotNull(InstrumentationRegistry.getArguments().getString("transitionMode")).also { require(it in MODES) }
        }
        private fun sourceAlias(b: NativeKeeperBinding) = "kithmoot.keeper-authority." + digest(b.owner)
        private fun courierAlias(b: NativeKeeperBinding): String {
            val q = RoomRekeyBinding(b.room, b.authority, b.device, b.meshScope, b.relays, b.route)
            return "kithmoot.keeper-rekeys." + digest(q.owner)
        }
        private fun peerAlias(room: String) = "kithmoot.lab.native-replacement-peer.$room"
        private fun digest(value: String) = Digests.sha256(value.toByteArray(Charsets.UTF_8)).toHex()
        private fun eventBytes(event: NostrEvent) = event.toCompactJson().toByteArray(Charsets.UTF_8).size
        private fun total(value: JsonElement) = value.jsonObject.values.sumOf { it.jsonPrimitive.int }
        private fun original(record: JsonObject): NostrEvent = NostrEvent.fromJson(
            (record["replacement"] as? JsonObject)?.getValue("retirement")
                ?: record.getValue("retirements").jsonArray.single().jsonObject.getValue("event"))
        private fun proposedWelcome(record: JsonObject): NostrEvent = NostrEvent.fromJson(
            ((record["replacement"] as? JsonObject)?.getValue("proposed")?.jsonObject
                ?: record.getValue("activeInvitation").jsonObject).getValue("welcome"))
        private fun attempts(record: JsonObject) = total(
            (record["replacement"] as? JsonObject)?.getValue("attempts")
                ?: record.getValue("retirements").jsonArray.single().jsonObject.getValue("attempts"))
        private fun actualInvitationId(invitation: JsonObject) = NostrEvent.fromJson(invitation.getValue("welcome"))
            .tags.single { it[0] == "d" }[1]
        private fun nearbyDebt(record: JsonObject) = record.getValue("spends").jsonArray.sumOf {
            if (it.jsonObject.getValue("lane") == JsonPrimitive("NEARBY")) it.jsonObject.getValue("bytes").jsonPrimitive.int else 0
        }
        private fun traffic(record: JsonObject) = buildJsonObject {
            for (key in listOf("base", "signer", "secret")) put(key + "Digest", digest(record.getValue(key).jsonPrimitive.content))
            for (key in listOf("pin", "epoch", "epochCause", "members", "removed")) put(key, record.getValue(key))
            put("devices", JsonArray(record.getValue("devices").jsonArray.map {
                JsonObject(it.jsonObject.filterKeys { key -> key in setOf("participant", "device", "removed") })
            }))
        }
        private fun deriveEpochId(epoch: Int, vault: EpochVault, room: String): String {
            val stored = requireNotNull(vault.get(room))
            try { return deriveEpoch(RoomEpoch(epoch, stored.currentSecret)).id }
            finally { stored.currentSecret.fill(0); stored.pending?.secret?.fill(0) }
        }
        private fun receiver(vault: EpochVault, room: String): JsonObject {
            val value = requireNotNull(vault.get(room))
            try { return buildJsonObject {
                put("epoch", value.currentEpoch); put("phase", value.phase.name)
                put("secretDigest", digest(value.currentSecret.toHex()))
                put("cause", value.activationCause?.let(::JsonPrimitive) ?: JsonNull)
                put("terminalCause", value.terminalCause?.let(::JsonPrimitive) ?: JsonNull)
                put("pending", value.pending != null)
            } } finally { value.currentSecret.fill(0); value.pending?.secret?.fill(0) }
        }
        private suspend fun cleanup(primary: Throwable?, action: suspend () -> Unit) = withContext(NonCancellable) {
            try { action() } catch (secondary: Throwable) { if (primary == null) throw secondary else primary.addSuppressed(secondary) }
        }
    }
}
