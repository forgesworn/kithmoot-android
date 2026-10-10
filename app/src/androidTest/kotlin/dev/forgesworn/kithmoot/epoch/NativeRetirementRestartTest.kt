package dev.forgesworn.kithmoot.epoch

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

/** Four source commits held live until the guarded external SIGKILL. */
class NativeRetirementRestartTest {
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
            scope.launch { delay(1); peer?.receive?.invoke(copy, "retirement-lab-peer") }
        }
        fun inject(event: NostrEvent) = receive?.invoke(RoomMeshWire.encode(RoomMeshWire.EVENT,
            buildJsonObject { put("scope", scopeName); put("event", event.toJson()) }), "retirement-lab-peer")
        override suspend fun resetQueued() = Unit
        override fun reachable() = available
        override fun close() { available = false; receive = null }
    }

    /** Delegate unchanged bytes, then pause after the real AtomicFile commit. */
    private class Pause(private val storage: RoomStorage) : RoomStorage by storage {
        @Volatile var checkpoint: ((JsonObject) -> Unit)? = null
        lateinit var mode: String
        private val claimed = AtomicBoolean()
        val release = CountDownLatch(1)
        override fun write(value: ByteArray) {
            storage.write(value)
            val report = checkpoint ?: return
            val record = Json.parseToJsonElement(value.toString(Charsets.UTF_8)).jsonObject
            if (record.getValue("phase").jsonPrimitive.content != "RETIRED") return
            val pending = record["pending"] as? JsonObject
            val archive = record.getValue("retirements").jsonArray
            val matches = if (mode == "archive-before-hint") pending == null && archive.size == 1
                else pending?.let {
                    val events = it.getValue("events").jsonArray.map(NostrEvent::fromJson)
                    events.size == 1 && events.single().kind == KIND_INVITATION_RETIREMENT && archive.isEmpty() &&
                        total(it.getValue("attempts")) == (if (mode == "pending-original") 0 else 1) &&
                        total(it.getValue("offered")) == (if (mode == "offered-before-archive") 1 else 0)
                } == true
            if (!matches || !claimed.compareAndSet(false, true)) return
            try { report(record) }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Throwable) {
                // Surface checkpoint assertions through the worker's awaited
                // command rather than losing them in a supervisor child.
                throw IllegalStateException("Committed retirement checkpoint failed", failure)
            }
            check(release.await(120, TimeUnit.SECONDS)) { "External retirement SIGKILL did not arrive" }
        }
    }

    private class Preparation(val f: NativeHostFixture) {
        val app = f.app
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val at = System.currentTimeMillis() / 1000
        private val creation = NativeKeeperCreation.fresh(at)
        val base = creation.roomSecret()
        val room = deriveRoom(base)
        private val keys = listOf(5, 6, 7).map { seed -> ByteArray(32).apply { this[31] = seed.toByte() } }
        val owner = PrimaryIdentity.create(room.roomId, at + 3600, at, keys[0], keys[1])
        val binding = NativeKeeperBinding(room.roomId, creation.authority, owner.participant, owner.devicePubkey, RoomRoute.NEARBY, emptyList())
        val q = RoomRekeyBinding(room.roomId, binding.authority, owner.devicePubkey, binding.meshScope, emptyList(), RoomRoute.NEARBY)
        val sourceStorage = EncryptedRoomStorage(app, sourceAlias(binding), NativeKeeperJournal.MAX_FILE_BYTES)
        val pause = Pause(sourceStorage)
        val queueVault = RoomRekeyVault(app, q)
        val checkpoint = EncryptedRoomStorage(app, CHECKPOINT, 64 * 1024)
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
            val invitation = creation.invitation()
            val url = try { encodeInvitationUrl("https://kithmoot.example/", invitation, emptyList()) }
                finally { invitation.bearer.fill(0) }
            app.roomEpochs.initialise(room.roomId, binding.authority, base, at)
            peerReceiver.initialise(room.roomId, binding.authority, base, at)
            source = NativeKeeperJournal.create(pause, binding, creation, owner.credential)
            saved = SavedRoom.create(base, owner, url, emptyList(), "Native retirement process lab", at,
                null, binding.authority, route = RoomRoute.NEARBY).withNativeAuthority(source)
            app.savedRooms.saveNew(saved)
            ledger = queueVault.open(initialise = true); source.recordCourierCreated(ledger)
            link.peer = peerLink; peerLink.peer = link
            live = RoomSession(room, owner, mesh, scope, authority = binding.authority,
                timing = SessionTiming(announceJitterMs = 0))
            live.holdKeeperStartup(); live.join()
            controller = NativeKeeperController.start(source, app.roomEpochs, live, ledger,
                NativeKeeperEndpoints(q, mesh, null), scope, { selected })
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
        }

        suspend fun close() {
            selected = false; pause.checkpoint = null; pause.release.countDown()
            try { if (::controller.isInitialized) controller.stop() else {
                if (::source.isInitialized) source.close(); if (::ledger.isInitialized) ledger.close()
            } } finally {
                try { if (::live.isInitialized) live.leave(); if (::peer.isInitialized) peer.leave() }
                finally {
                    mesh.close(); peerMesh.close(); scope.cancel()
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
            r.create(); r.pause.mode = mode
            val priorDebt = nearbyDebt(f.source(r.saved))
            r.pause.checkpoint = { record ->
                r.link.available = false
                assertEquals(publicSource(record), publicSource(f.source(r.saved)))
                assertTrue(runCatching { NativeKeeperVault.forSavedRoom(f.app, r.saved).open().close() }.isFailure)
                val original = original(record)
                assertTrue(Events.verify(original)); assertEquals(KIND_INVITATION_RETIREMENT, original.kind)
                assertEquals(0, record.getValue("epoch").jsonPrimitive.int)
                assertEquals(3, record.getValue("devices").jsonArray.size)
                assertFalse(f.app.savedRooms.get(r.saved.id)!!.retired)
                val attempts = attemptCount(record)
                assertEquals(if (mode == "pending-original") 0 else 1, attempts)
                assertEquals(eventBytes(original) * attempts, (nearbyDebt(record) - priorDebt))
                assertEquals(if (mode in setOf("offered-before-archive", "archive-before-hint")) listOf(original) else emptyList<NostrEvent>(),
                    r.link.events.filter { it.kind == KIND_INVITATION_RETIREMENT })
                val courier = f.courier(r.saved)
                assertTrue(courier.getValue("entries").jsonArray.isEmpty()); assertTrue(courier.getValue("spends").jsonArray.isEmpty())
                val expected = buildJsonObject {
                    put("pid", Process.myPid()); put("mode", mode); put("room", r.saved.id); put("peerAt", r.at)
                    put("priorNearbyDebt", priorDebt)
                    put("reference", r.saved.json.getValue("nativeAuthority")); put("invitationDigest", digest(r.saved.joinUrl))
                    put("source", publicSource(record)); put("original", original.toJson())
                    put("receiver", receiver(f.app.roomEpochs, r.saved.id)); put("peerReceiver", receiver(r.peerReceiver, r.saved.id))
                    put("courier", JsonObject(courier.filterKeys { it != "high" })); put("courierHigh", courier.getValue("high"))
                }
                val bytes = expected.toString().toByteArray(Charsets.UTF_8)
                try { r.checkpoint.write(bytes) } finally { bytes.fill(0) }
                ready.set(true)
                InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                    putString("native_retirement_restart_pid", Process.myPid().toString())
                    putString("native_retirement_restart_checkpoint", "ready")
                    putString("native_retirement_restart_mode", mode)
                })
            }
            r.controller.retire()
            error("The committed retirement returned without an external kill")
        } catch (error: Throwable) { failure = error; throw error }
        finally { if (!ready.get()) cleanup(failure) { r.close() } }
    }

    @Test fun b_recover(): Unit = runBlocking {
        val mode = mode()
        check(InstrumentationRegistry.getArguments().getString("requireRestart") == "true")
        val f = NativeHostFixture()
        val checkpoint = EncryptedRoomStorage(f.app, CHECKPOINT, 64 * 1024)
        val checkpointBytes = requireNotNull(checkpoint.read())
        val expected = try { Json.parseToJsonElement(checkpointBytes.toString(Charsets.UTF_8)).jsonObject }
            finally { checkpointBytes.fill(0) }
        assertEquals(mode, expected.getValue("mode").jsonPrimitive.content)
        assertNotEquals(expected.getValue("pid").jsonPrimitive.int, Process.myPid())
        val saved = requireNotNull(f.app.savedRooms.get(expected.getValue("room").jsonPrimitive.content))
        f.savedRoom = saved
        val peerStore = EncryptedRoomStorage(f.app, peerAlias(saved.id))
        val peerReceiver = EpochVault(peerStore)
        var failure: Throwable? = null
        try {
            // All cold comparisons precede ViewModel, route activation and credential refresh.
            assertEquals(expected.getValue("reference"), saved.json.getValue("nativeAuthority"))
            assertEquals(expected.getValue("invitationDigest").jsonPrimitive.content, digest(saved.joinUrl))
            assertFalse(saved.retired)
            val cold = f.source(saved)
            assertEquals(expected.getValue("source"), publicSource(cold))
            assertEquals(expected.getValue("receiver"), receiver(f.app.roomEpochs, saved.id))
            assertEquals(expected.getValue("peerReceiver"), receiver(peerReceiver, saved.id))
            val courier = f.courier(saved)
            assertEquals(expected.getValue("courier"), JsonObject(courier.filterKeys { it != "high" }))
            assertTrue(courier.getValue("high").jsonPrimitive.long >= expected.getValue("courierHigh").jsonPrimitive.long)
            val original = NostrEvent.fromJson(expected.getValue("original"))
            assertEquals(original, original(cold))
            val priorDebt = expected.getValue("priorNearbyDebt").jsonPrimitive.int
            val deathAttempts = attemptCount(cold); val deathDebt = nearbyDebt(cold) - priorDebt
            f.startModel(); compose.showNativeHost(f)
            val beforeHome = digest(f.source(saved).toString())
            assertNull(f.model.inviteLinkFor(saved.id))
            assertEquals(beforeHome, digest(f.source(saved).toString()))
            assertFalse(f.app.savedRooms.get(saved.id)!!.retired)
            f.main { f.setNearbyAvailable(false); f.model.reopenRoom(saved.id) }; f.opened()
            f.awaitHost("retired source governs sharing before saved hint repair") {
                f.model.room.value.joinUrl.isEmpty() && f.app.savedRooms.get(saved.id)?.retired == true &&
                    f.model.room.value.nativeHosting?.let { it.canRetry || it.canResendRetirement } == true
            }
            assertFalse(f.model.room.value.canShareInvitation); assertTrue(f.model.room.value.letInAsks.isEmpty())
            if (mode in setOf("pending-original", "reserved-before-offer")) {
                assertTrue(f.model.room.value.nativeHosting!!.canRetry)
                assertEquals(original, original(f.source(saved)))
                assertEquals(deathAttempts, attemptCount(f.source(saved)))
                assertEquals(deathDebt, nearbyDebt(f.source(saved)) - priorDebt)
                compose.onNodeWithContentDescription("Room details").performClick()
                compose.onNodeWithText("Recover saved update").performScrollTo().performClick()
                f.main { f.setNearbyAvailable(true) }
                f.awaitHost("retirement recovery's original lane returns") { f.model.room.value.nearby?.writablePeers == 1 }
                compose.onNodeWithText("Recover update").performClick()
                compose.onNodeWithText("Done").performClick()
            }
            f.awaitHost("actual source completes its same retirement original") {
                f.model.room.value.nativeHosting?.canResendRetirement == true && !f.model.room.value.nativeHostingBusy
            }
            val completed = f.source(saved)
            assertEquals(original, original(completed)); assertEquals(JsonNull, completed.getValue("pending"))
            val automaticAttempts = attemptCount(completed)
            assertEquals(deathAttempts + if (mode in setOf("pending-original", "reserved-before-offer")) 1 else 0, automaticAttempts)
            val completionDebt = nearbyDebt(completed) - priorDebt
            assertEquals(eventBytes(original) * automaticAttempts, completionDebt)
            assertEquals(if (mode in setOf("pending-original", "reserved-before-offer")) listOf(original) else emptyList<NostrEvent>(),
                f.phoneEvents.filter { it.kind == KIND_INVITATION_RETIREMENT })
            assertEquals(expected.getValue("receiver"), receiver(f.app.roomEpochs, saved.id))
            val afterCourier = f.courier(saved)
            assertEquals(expected.getValue("courier"), JsonObject(afterCourier.filterKeys { it != "high" }))
            assertEquals(bindings(cold), bindings(completed))
            val oldOwner = requireNotNull(f.model.room.value.nativeHosting)
            f.main { f.model.leave() }
            NativeHostFixture.await("retired foreground owner closes") {
                f.model.stage.value == Stage.START && !f.model.start.value.busy && f.radios.all { it.closed }
            }
            f.main { f.setNearbyAvailable(false); f.model.reopenRoom(saved.id) }; f.opened()
            f.awaitHost("new foreground generation observes archive without automatic resend") { f.model.room.value.nativeHosting?.canResendRetirement == true }
            assertNotEquals(oldOwner.ownerGeneration, f.model.room.value.nativeHosting!!.ownerGeneration)
            val beforeStale = digest(f.source(saved).toString())
            f.main { f.model.resendNativeRetirement(oldOwner, original.id) }
            f.awaitHost("old foreground callback refuses") { f.model.room.value.notice == "Room hosting changed. Open the confirmation again." }
            assertEquals(beforeStale, digest(f.source(saved).toString()))
            assertEquals(automaticAttempts, attemptCount(f.source(saved)))
            f.main { f.setNearbyAvailable(true) }
            f.awaitHost("approved peer recovery lane returns") { f.model.room.value.nearby?.writablePeers == 1 }
            val peer = f.rejoinApproved(saved, expected.getValue("peerAt").jsonPrimitive.long, peerReceiver)
            assertEquals(RoomEpochState.Active(0, saved.id), peer.epochState.value)
            assertTrue(f.model.room.value.letInAsks.isEmpty())
            f.main { f.model.sendChat("host after retirement SIGKILL") }; peer.sendChat("member after retirement SIGKILL")
            f.awaitHost("approved epoch-zero chat both ways after killed retirement") {
                peer.chat.value.any { it.body == "host after retirement SIGKILL" } &&
                    f.model.room.value.chat.any { it.body == "member after retirement SIGKILL" }
            }
            compose.onNodeWithContentDescription("Room details").performClick()
            val beforeExplicitDebt = nearbyDebt(f.source(saved))
            compose.onNodeWithTag("native-resend-${original.id}").performScrollTo().performClick()
            compose.onNodeWithText("Resend notice").performClick()
            f.awaitHost("explicit archive resend consumes only its next permanent attempt") {
                !f.model.room.value.nativeHostingBusy && attemptCount(f.source(saved)) == automaticAttempts + 1
            }
            val after = f.source(saved)
            assertEquals(original, original(after))
            assertEquals(beforeExplicitDebt + eventBytes(original), nearbyDebt(after))
            assertEquals(saved.json.getValue("nativeAuthority"), f.app.savedRooms.get(saved.id)!!.json.getValue("nativeAuthority"))
            assertEquals(digest(saved.joinUrl), digest(f.app.savedRooms.get(saved.id)!!.joinUrl))
            assertEquals(0, f.server.requestCount)
            InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                putString("native_retirement_recovery_pid", Process.myPid().toString()); putString("native_retirement_recovery_mode", mode)
                putString("native_retirement_recovery_original_id", original.id)
                putString("native_retirement_recovery_original_created_at", original.createdAt.toString())
                putString("native_retirement_recovery_attempts_at_death", deathAttempts.toString())
                putString("native_retirement_recovery_attempts_after_completion", automaticAttempts.toString())
                putString("native_retirement_recovery_attempts_after_explicit_resend", attemptCount(after).toString())
                putString("native_retirement_recovery_debt_at_death", deathDebt.toString())
                putString("native_retirement_recovery_debt_after_completion", completionDebt.toString())
                putString("native_retirement_recovery_debt_after_explicit_resend", (completionDebt + nearbyDebt(after) - beforeExplicitDebt).toString())
                putString("native_retirement_recovery_devices", "3"); putString("native_retirement_recovery_same_epoch", "0")
            })
        } catch (error: Throwable) { failure = error; throw error }
        finally { cleanup(failure) {
            try { f.close() } finally { peerStore.reset(); checkpoint.reset() }
            assertNull(peerStore.read()); assertNull(checkpoint.read())
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            for (alias in listOf(CHECKPOINT, peerAlias(saved.id))) {
                assertFalse(keyStore.containsAlias(alias))
                assertTrue(listOf(".vault", ".vault.new", ".vault.bak").all { !File(f.app.noBackupFilesDir, alias + it).exists() })
            }
        } }
    }

    companion object {
        private const val CHECKPOINT = "kithmoot.lab.native-retirement-restart"
        private fun mode(): String {
            require(Build.HARDWARE in setOf("ranchu", "goldfish"))
            return requireNotNull(InstrumentationRegistry.getArguments().getString("transitionMode")).also {
                require(it in setOf("pending-original", "reserved-before-offer", "offered-before-archive", "archive-before-hint"))
            }
        }
        private fun sourceAlias(b: NativeKeeperBinding) = "kithmoot.keeper-authority." + digest(b.owner)
        private fun peerAlias(room: String) = "kithmoot.lab.native-retirement-peer.$room"
        private fun digest(value: String) = Digests.sha256(value.toByteArray(Charsets.UTF_8)).toHex()
        private fun eventBytes(event: NostrEvent) = event.toCompactJson().toByteArray(Charsets.UTF_8).size
        private fun total(value: JsonElement) = value.jsonObject.values.sumOf { it.jsonPrimitive.int }
        private fun original(record: JsonObject): NostrEvent = NostrEvent.fromJson(
            (record["pending"] as? JsonObject)?.getValue("events")?.jsonArray?.single()
                ?: record.getValue("retirements").jsonArray.single().jsonObject.getValue("event"))
        private fun attemptCount(record: JsonObject) = total(
            (record["pending"] as? JsonObject)?.getValue("attempts")
                ?: record.getValue("retirements").jsonArray.single().jsonObject.getValue("attempts"))
        private fun nearbyDebt(record: JsonObject) = record.getValue("spends").jsonArray.sumOf {
            if (it.jsonObject.getValue("lane") == JsonPrimitive("NEARBY")) it.jsonObject.getValue("bytes").jsonPrimitive.int else 0
        }
        private fun bindings(record: JsonObject) = JsonArray(record.getValue("devices").jsonArray.map {
            JsonObject(it.jsonObject.filterKeys { key -> key in setOf("participant", "device", "removed") })
        })
        private fun publicSource(record: JsonObject) = buildJsonObject {
            for (key in listOf("room", "pin", "phase", "epoch", "revision", "high", "members", "removed", "cause", "epochCause", "courierReady", "spends", "retirements"))
                record[key]?.let { put(key, it) }
            put("devices", JsonArray(record.getValue("devices").jsonArray.map {
                val device = it.jsonObject
                JsonObject(device.filterKeys { key -> key != "credential" } +
                    ("credential" to device.getValue("credential").jsonObject.getValue("id")))
            }))
            put("pending", (record["pending"] as? JsonObject)?.let { JsonObject(it.filterKeys { key -> key != "secret" }) } ?: JsonNull)
        }
        private fun receiver(vault: EpochVault, room: String): JsonObject {
            val value = requireNotNull(vault.get(room))
            try { return buildJsonObject {
                put("epoch", value.currentEpoch); put("phase", value.phase.name)
                put("cause", value.activationCause?.let(::JsonPrimitive) ?: JsonNull)
                put("terminalCause", value.terminalCause?.let(::JsonPrimitive) ?: JsonNull)
                put("pending", value.pending != null)
            } } finally { value.currentSecret.fill(0); value.pending?.secret?.fill(0) }
        }
        private suspend fun cleanup(primary: Throwable?, action: suspend () -> Unit) {
            try { action() } catch (secondary: Throwable) { if (primary == null) throw secondary else primary.addSuppressed(secondary) }
        }
    }
}
