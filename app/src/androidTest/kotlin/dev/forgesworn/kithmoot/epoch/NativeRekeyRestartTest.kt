package dev.forgesworn.kithmoot.epoch

import android.os.Build
import android.os.Bundle
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.crypto.*
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.*
import dev.forgesworn.kithmoot.session.*
import dev.forgesworn.kithmoot.storage.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Two precise durable-source windows, killed by the external emulator driver.
 * All persistence/authority/session operations are real; only Nearby bytes and
 * a pause AFTER a production encrypted write are supplied by the fixture. */
class NativeRekeyRestartTest {
    private class Link(private val meshScope: String, private val scope: CoroutineScope) : RoomMeshLink {
        @Volatile private var receive: ((ByteArray, String) -> Unit)? = null
        @Volatile var peer: Link? = null
        @Volatile var available = true
        private val deliveries = ReentrantLock()
        private val generation = AtomicLong()
        val events = CopyOnWriteArrayList<NostrEvent>()
        private val queries = CopyOnWriteArrayList<JsonObject>()
        override fun subscribe(receive: (ByteArray, String) -> Unit): AutoCloseable {
            this.receive = receive
            return AutoCloseable { this.receive = null }
        }
        override fun offer(bytes: ByteArray, to: String?) {
            RoomMeshWire.decode(bytes)?.let { (kind, payload) ->
                payload["event"]?.let { events += NostrEvent.fromJson(it) }
                if (kind == RoomMeshWire.QUERY) payload.getValue("filters").jsonArray.forEach { queries += it.jsonObject }
            }
            val offeredGeneration = generation.get()
            val copy = bytes.clone()
            scope.launch {
                delay(1)
                // resetQueued must discard older offers, including a delayed
                // fixture delivery. The callback never runs inline in offer.
                deliveries.withLock { if (generation.get() == offeredGeneration) peer?.receive?.invoke(copy, "lab-rekey-peer") }
            }
        }
        fun inject(event: NostrEvent) = receive?.invoke(RoomMeshWire.encode(RoomMeshWire.EVENT,
            buildJsonObject { put("scope", meshScope); put("event", event.toJson()) }), "lab-rekey-peer")
        fun subscribedTo(kind: Int, room: String? = null) = queries.any { query ->
            query["kinds"]?.jsonArray?.any { it.jsonPrimitive.int == kind } == true &&
                (room == null || query["#d"]?.jsonArray?.any { it.jsonPrimitive.content == room } == true)
        }
        override suspend fun resetQueued() { deliveries.withLock { generation.incrementAndGet() } }
        override fun reachable() = available
        // RoomMeshTransport calls close under its lock. Never wait here for
        // a delivery callback that might be entering the other transport.
        override fun close() { available = false; generation.incrementAndGet(); receive = null }
    }

    /** No substituted bytes or write failure: pause only after the actual
     * AtomicFile commit. The paused worker still owns the real source lease. */
    private class CommittedPause(private val delegate: RoomStorage) : RoomStorage by delegate {
        @Volatile var checkpoint: ((JsonObject) -> Unit)? = null
        @Volatile var queued = false
        private val claimed = AtomicBoolean()
        val release = CountDownLatch(1)
        override fun write(value: ByteArray) {
            delegate.write(value)
            val report = checkpoint ?: return
            val record = Json.parseToJsonElement(value.toString(Charsets.UTF_8)).jsonObject
            val pending = record["pending"] as? JsonObject ?: return
            val events = pending.getValue("events").jsonArray.map(NostrEvent::fromJson)
            if (events.size != 1 || events.single().kind != KIND_ROOM_REKEY) return
            val hasQueued = events.single().id in pending.getValue("queued").jsonArray.map { it.jsonPrimitive.content }
            if (hasQueued != queued || !claimed.compareAndSet(false, true)) return
            report(record)
            check(release.await(120, TimeUnit.SECONDS)) { "External rekey SIGKILL did not arrive" }
        }
    }

    private class Rig(saved: SavedRoom? = null) {
        init { require(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Use the guarded disposable emulator driver" } }
        val app = ApplicationProvider.getApplicationContext<KithMootApplication>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val at = saved?.openedAt ?: System.currentTimeMillis() / 1000
        private val creation = if (saved == null) NativeKeeperCreation.fresh(at) else null
        val base = saved?.secret ?: requireNotNull(creation).roomSecret()
        val room = deriveRoom(base)
        private val keys = (1..5).map { seed -> ByteArray(32) { (seed + it).toByte() } }
        val owner = saved?.identity(System.currentTimeMillis() / 1000)
            ?: PrimaryIdentity.create(room.roomId, at + 3600, at, keys[0], keys[1])
        val member = PrimaryIdentity.create(room.roomId, at + 3600, at, keys[2], keys[3])
        val offlineKey = keys[4]
        val binding = saved?.nativeAuthority ?: NativeKeeperBinding(room.roomId, requireNotNull(creation).authority,
            owner.participant, owner.devicePubkey, RoomRoute.NEARBY, emptyList())
        val queueBinding = RoomRekeyBinding(room.roomId, binding.authority, owner.devicePubkey, binding.meshScope, emptyList(), RoomRoute.NEARBY)
        private val sourceAlias = "kithmoot.keeper-authority." + Digests.sha256(binding.owner.toByteArray()).toHex()
        private val queueAlias = "kithmoot.keeper-rekeys." + Digests.sha256(queueBinding.owner.toByteArray()).toHex()
        private val peerAlias = "kithmoot.lab.native-rekey-peer." + room.roomId
        val sourceStorage = EncryptedRoomStorage(app, sourceAlias, NativeKeeperJournal.MAX_FILE_BYTES)
        val pause = CommittedPause(sourceStorage)
        val queueVault = RoomRekeyVault(app, queueBinding)
        val checkpointStore = EncryptedRoomStorage(app, CHECKPOINT_ALIAS, 64 * 1024)
        private val peerStore = EncryptedRoomStorage(app, peerAlias)
        val receiver = app.roomEpochs
        val peerReceiver = EpochVault(peerStore)
        val link = Link(requireNotNull(binding.meshScope), scope)
        private val peerLink = Link(requireNotNull(binding.meshScope), scope)
        val mesh = RoomMeshTransport(requireNotNull(binding.meshScope), link)
        private val peerMesh = RoomMeshTransport(requireNotNull(binding.meshScope), peerLink)
        lateinit var savedRoom: SavedRoom
        lateinit var source: NativeKeeperJournal
        lateinit var ledger: RoomRekeyLedger
        lateinit var live: RoomSession
        lateinit var peer: RoomSession
        @Volatile private var selected = true
        private var controller: NativeKeeperController? = null

        init { if (saved != null) savedRoom = saved; link.peer = peerLink; peerLink.peer = link }

        private fun session(identity: RoomIdentity, transport: RoomTransport, vault: EpochVault): RoomSession {
            val stored = requireNotNull(vault.get(room.roomId))
            val initial = try { deriveEpoch(RoomEpoch(stored.currentEpoch, stored.currentSecret)) }
                finally { stored.currentSecret.fill(0) }
            return RoomSession(room, identity, transport, scope, authority = binding.authority,
                initialEpoch = initial, initialRemoved = stored.removed,
                epochGate = { event, notice ->
                    if (notice.closed || notice.secret == null) vault.terminal(room.roomId, notice.epoch - 1, notice, event.id, System.currentTimeMillis() / 1000)
                    else requireNotNull(vault.follow(room.roomId, notice, event.id, System.currentTimeMillis() / 1000))
                    EpochGateResult.COMMITTED
                }, timing = SessionTiming(announceJitterMs = 0))
        }

        suspend fun create() {
            assertNull(checkpointStore.read())
            val invitation = requireNotNull(creation).invitation()
            val url = try { encodeInvitationUrl("https://kithmoot.example/", invitation, emptyList()) }
                finally { invitation.bearer.fill(0) }
            receiver.initialise(room.roomId, binding.authority, base, at)
            source = NativeKeeperJournal.create(pause, binding, creation, owner.credential)
            savedRoom = SavedRoom.create(base, owner, url, emptyList(), "Native rekey process lab", at,
                null, binding.authority, route = RoomRoute.NEARBY).withNativeAuthority(source)
            app.savedRooms.saveNew(savedRoom)
            ledger = queueVault.open(initialise = true)
            source.recordCourierCreated(ledger)
            live = session(owner, mesh, receiver)
            live.holdKeeperStartup(); live.join()
            startController()
            await("fresh source Ready") { controller!!.state.value == NativeKeeperController.State.Ready(0, KeeperPhase.ACTIVE) }
            await("source request subscription") { link.subscribedTo(KIND_EPOCH_REQUEST) }
            val offlineCredential = member.enrol(Schnorr.publicKeyHex(offlineKey), room.roomId, at + 3600, at)
            for ((key, credential) in listOf(member.deviceSecretKey to member.credential, offlineKey to offlineCredential)) {
                var ask = encodeEpochRequest(room.roomId, binding.authority, room.roomKey, key, credential, System.currentTimeMillis() / 1000)
                link.inject(ask)
                if (credential == member.credential) {
                    await("unknown member refusal") { grants(key, ask.id).any { it == EpochGrant.Refused("unknown") } }
                    await("unknown member approval prompt") { member.participant in controller!!.unknownParticipants.value }
                    controller!!.approve(member.participant)
                    val refused = ask.id
                    ask = encodeEpochRequest(room.roomId, binding.authority, room.roomKey, key, credential, System.currentTimeMillis() / 1000)
                    assertNotEquals(refused, ask.id)
                    link.inject(ask)
                }
                await("qualified member device grant") { grants(key, ask.id).any { it is EpochGrant.Current && it.epoch == 0 } }
            }
            controller!!.approve(member.participant) // Actual serialized accounting barrier.
        }

        private fun grants(key: ByteArray, request: String) = link.events.mapNotNull {
            decodeEpochGrant(it, room.roomId, binding.authority, key, request, System.currentTimeMillis() / 1000)
        }

        suspend fun command() = controller!!.rekeyMembers()

        fun coldCourier(): JsonObject {
            val bytes = requireNotNull(EncryptedRoomStorage(app, queueAlias, RoomRekeyLedger.MAX_FILE_BYTES).read())
            return try { Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject } finally { bytes.fill(0) }
        }

        suspend fun recover() {
            // Missing authority/courier must refuse: never initialise on reopen.
            source = NativeKeeperVault.forSavedRoom(app, savedRoom).open()
            ledger = queueVault.open()
            live = session(owner, mesh, receiver)
            live.holdKeeperStartup(); live.join()
            peerReceiver.initialise(room.roomId, binding.authority, base, at)
            peer = session(member, peerMesh, peerReceiver)
            peer.join()
            await("recovering member rekey subscription") { peerLink.subscribedTo(KIND_ROOM_REKEY) }
            startController()
            await("source successor completion") { controller!!.state.value == NativeKeeperController.State.Ready(1, KeeperPhase.ACTIVE) }
            await("original successor reaches existing member") { (peer.epochState.value as? RoomEpochState.Active)?.epoch == 1 }
            val trafficRoom = source.snapshot().epochId
            await("owner successor chat subscription") { link.subscribedTo(KIND_CHAT, trafficRoom) }
            await("member successor chat subscription") { peerLink.subscribedTo(KIND_CHAT, trafficRoom) }
        }

        private suspend fun startController() {
            controller = NativeKeeperController.start(source, receiver, live, ledger,
                NativeKeeperEndpoints(queueBinding, mesh, null), scope, { selected })
        }

        suspend fun close() {
            selected = false; pause.checkpoint = null; pause.release.countDown()
            try { controller?.stop() ?: run {
                if (::source.isInitialized) source.close()
                if (::ledger.isInitialized) ledger.close()
            } } finally {
                try { if (::live.isInitialized) live.leave(); if (::peer.isInitialized) peer.leave() }
                finally {
                    mesh.close(); peerMesh.close(); scope.cancel()
                    NativeKeeperVault(app, binding).forget(); queueVault.forget()
                    receiver.forget(room.roomId); app.savedRooms.forget(room.roomId)
                    peerStore.reset(); checkpointStore.reset()
                    assertNull(receiver.get(room.roomId)); assertNull(app.savedRooms.get(room.roomId))
                    val aliases = listOf(sourceAlias, queueAlias, peerAlias, CHECKPOINT_ALIAS)
                    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                    assertTrue(aliases.all { !store.containsAlias(it) && listOf(".vault", ".vault.new", ".vault.bak").all { suffix ->
                        !File(app.noBackupFilesDir, it + suffix).exists() } })
                    creation?.close(); base.fill(0); room.roomKey.fill(0); keys.forEach { it.fill(0) }
                }
            }
        }
    }

    @Test fun a_prepare(): Unit = runBlocking {
        val mode = mode()
        val r = Rig()
        val ready = AtomicBoolean()
        try {
            r.create()
            r.pause.queued = mode == "after-handoff"
            r.pause.checkpoint = { record ->
                val pending = record.getValue("pending").jsonObject
                val original = NostrEvent.fromJson(pending.getValue("events").jsonArray.single())
                assertTrue(Events.verify(original))
                assertEquals(0, r.receiver.get(r.room.roomId)!!.currentEpoch)
                if (mode == "after-handoff") {
                    val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
                    while (r.ledger.status().entries.single().nearby.state != RekeyLaneState.OFFERED) {
                        check(System.nanoTime() < until) { "Actual original handoff was not recorded" }
                        Thread.sleep(10)
                    }
                    // Freeze the synthetic lane after its first durable offer
                    // so a slow external kill cannot advance retry accounting
                    // beyond the public checkpoint. The source owner stays live.
                    r.link.available = false
                    assertEquals(listOf(original), r.link.events.filter { it.kind == KIND_ROOM_REKEY }.distinct())
                } else {
                    assertTrue(r.ledger.status().entries.isEmpty())
                    assertTrue(r.link.events.none { it.kind == KIND_ROOM_REKEY })
                }
                val status = r.ledger.status()
                val row = status.entries.singleOrNull()
                val expected = buildJsonObject {
                    put("pid", Process.myPid()); put("mode", mode); put("room", r.room.roomId); put("pin", r.binding.pin)
                    put("source", publicSource(record)); put("original", original.toJson())
                    put("attempts", row?.nearby?.attempts ?: 0); put("debt", status.nearbyBytes)
                    put("added", row?.added?.let(::JsonPrimitive) ?: JsonNull)
                    put("expires", row?.expires?.let(::JsonPrimitive) ?: JsonNull)
                }
                val bytes = expected.toString().toByteArray(Charsets.UTF_8)
                try { r.checkpointStore.write(bytes) } finally { bytes.fill(0) }
                ready.set(true)
                InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                    putString("native_rekey_restart_pid", Process.myPid().toString())
                    putString("native_rekey_restart_checkpoint", "ready")
                    putString("native_rekey_restart_mode", mode)
                })
            }
            r.command() // Stops inside an actual durable write until external SIGKILL.
            error("The active transition unexpectedly returned instead of being killed")
        } finally { if (!ready.get()) r.close() }
    }

    @Test fun b_recover() = runBlocking {
        val mode = mode()
        check(InstrumentationRegistry.getArguments().getString("requireRestart") == "true") { "Use the external active-process driver" }
        val app = ApplicationProvider.getApplicationContext<KithMootApplication>()
        val checkpoint = EncryptedRoomStorage(app, CHECKPOINT_ALIAS, 64 * 1024)
        val bytes = requireNotNull(checkpoint.read())
        val expected = try { Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject } finally { bytes.fill(0) }
        require(expected.keys == setOf("pid", "mode", "room", "pin", "source", "original", "attempts", "debt", "added", "expires"))
        assertEquals(mode, expected.getValue("mode").jsonPrimitive.content)
        assertNotEquals(expected.getValue("pid").jsonPrimitive.int, Process.myPid())
        val saved = requireNotNull(app.savedRooms.get(expected.getValue("room").jsonPrimitive.content))
        assertEquals(expected.getValue("pin").jsonPrimitive.content, saved.nativeAuthority!!.pin)
        val r = Rig(saved)
        try {
            // No native owner, route activation, credential refresh or source
            // recreation has happened before this exact persisted comparison.
            val persisted = requireNotNull(r.sourceStorage.read())
            val source = try { Json.parseToJsonElement(persisted.toString(Charsets.UTF_8)).jsonObject }
                finally { persisted.fill(0) }
            assertEquals(expected.getValue("source"), publicSource(source))
            assertEquals(3, source.getValue("devices").jsonArray.size)
            assertTrue(source.getValue("courierReady").jsonPrimitive.boolean)
            val original = NostrEvent.fromJson(expected.getValue("original"))
            assertEquals(original, NostrEvent.fromJson(source.getValue("pending").jsonObject.getValue("events").jsonArray.single()))
            assertEquals(0, r.receiver.get(saved.id)!!.currentEpoch)
            val coldCourier = r.coldCourier()
            val deathAttempts = expected.getValue("attempts").jsonPrimitive.int
            assertEquals(expected.getValue("debt").jsonPrimitive.int, coldCourier.getValue("spends").jsonArray.sumOf {
                if (it.jsonObject.getValue("lane").jsonPrimitive.content == "NEARBY") it.jsonObject.getValue("bytes").jsonPrimitive.int else 0
            })
            if (mode == "after-handoff") {
                val row = coldCourier.getValue("entries").jsonArray.single().jsonObject
                assertEquals(original, NostrEvent.fromJson(row.getValue("event")))
                assertEquals(deathAttempts, row.getValue("NEARBY").jsonObject.getValue("attempts").jsonPrimitive.int)
                assertEquals("OFFERED", row.getValue("NEARBY").jsonObject.getValue("state").jsonPrimitive.content)
                assertEquals(expected.getValue("added"), row.getValue("added")); assertEquals(expected.getValue("expires"), row.getValue("expires"))
            } else { assertEquals(0, deathAttempts); assertTrue(coldCourier.getValue("entries").jsonArray.isEmpty()) }
            r.recover()
            assertEquals(1, r.source.snapshot().epoch)
            assertTrue(r.source.snapshot().pending.isEmpty())
            assertEquals(original.id, r.source.snapshot().epochCause)
            assertEquals(original.id, r.receiver.get(saved.id)!!.activationCause)
            assertEquals(original.id, r.peerReceiver.get(saved.id)!!.activationCause)
            assertEquals(setOf(r.owner.participant, r.member.participant), r.source.snapshot().members.toSet())
            val previous = deriveEpoch(RoomEpoch(0, r.base))
            try {
                val body = Json.parseToJsonElement(Nip44.decrypt(original.content, previous.key)).jsonObject
                assertEquals(setOf(r.owner.devicePubkey, r.member.devicePubkey, Schnorr.publicKeyHex(r.offlineKey)), body.getValue("keys").jsonObject.keys)
                val offline = requireNotNull(decodeRekeyEvent(original, saved.id, r.binding.authority, previous, r.offlineKey))
                val current = requireNotNull(r.receiver.get(saved.id))
                try { assertTrue(requireNotNull(offline.secret).contentEquals(current.currentSecret)) }
                finally { offline.secret?.fill(0); current.currentSecret.fill(0) }
            } finally { previous.key.fill(0) }
            assertEquals(listOf(original), r.link.events.filter { it.kind == KIND_ROOM_REKEY }.distinct())
            // Holding startup suppresses ordinary exports at predecessor epoch.
            assertTrue(r.link.events.none { it.kind in setOf(KIND_CHAT, KIND_ROSTER, KIND_SIGNAL_WRAP) && it.tagValue("d") == saved.id })
            val after = r.ledger.status()
            val retained = after.entries.single()
            assertEquals(original, retained.event)
            assertTrue(retained.nearby.attempts > deathAttempts)
            assertEquals(eventBytes(original) * retained.nearby.attempts, after.nearbyBytes)
            assertTrue(after.nearbyBytes >= expected.getValue("debt").jsonPrimitive.int)
            if (mode == "after-handoff") {
                assertEquals(expected.getValue("added").jsonPrimitive.long, retained.added)
                assertEquals(expected.getValue("expires").jsonPrimitive.long, retained.expires)
            }
            val newSource = requireNotNull(r.sourceStorage.read())
            try {
                val actual = Json.parseToJsonElement(newSource.toString(Charsets.UTF_8)).jsonObject
                assertEquals(publicSource(source).getValue("devices"), publicSource(actual).getValue("devices"))
                assertEquals(source.getValue("spends"), actual.getValue("spends"))
            } finally { newSource.fill(0) }
            r.live.sendChat("Native host after interrupted successor")
            r.peer.sendChat("Approved member after interrupted successor")
            await("fresh chat both ways after interrupted successor") {
                r.live.chat.value.count { it.body == "Approved member after interrupted successor" } == 1 &&
                    r.peer.chat.value.count { it.body == "Native host after interrupted successor" } == 1
            }
            InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                putString("native_rekey_recovery_pid", Process.myPid().toString())
                putString("native_rekey_recovery_mode", mode)
                putString("native_rekey_recovery_attempts_at_death", deathAttempts.toString())
                putString("native_rekey_recovery_attempts_after_reopen", retained.nearby.attempts.toString())
                putString("native_rekey_recovery_debt_at_death", expected.getValue("debt").toString())
                putString("native_rekey_recovery_debt_after_reopen", after.nearbyBytes.toString())
                putString("native_rekey_recovery_devices", "3")
            })
        } finally { r.close() }
    }

    companion object {
        private const val CHECKPOINT_ALIAS = "kithmoot.lab.native-rekey-restart"
        private fun mode(): String {
            require(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Use a disposable emulator" }
            return requireNotNull(InstrumentationRegistry.getArguments().getString("transitionMode"))
                .also { require(it in setOf("before-handoff", "after-handoff")) }
        }
        private fun eventBytes(event: NostrEvent) = event.toJson().toString().toByteArray(Charsets.UTF_8).size
        private fun publicSource(record: JsonObject) = buildJsonObject {
            for (key in listOf("room", "pin", "phase", "epoch", "at", "high", "revision", "members", "removed", "cause", "epochCause", "courierReady", "destruct", "spends"))
                record[key]?.let { put(key, it) }
            put("devices", JsonArray(record.getValue("devices").jsonArray.map { raw ->
                val device = raw.jsonObject
                buildJsonObject {
                    put("participant", device.getValue("participant")); put("device", device.getValue("device"))
                    put("credential", device.getValue("credential").jsonObject.getValue("id"))
                    put("verifiedAt", device.getValue("verifiedAt")); put("removed", device.getValue("removed"))
                }
            }))
            put("pending", record["pending"]?.let { pending ->
                if (pending is JsonObject) JsonObject(pending.filterKeys { it != "secret" }) else pending
            } ?: JsonNull)
        }
        private suspend fun await(stage: String, test: () -> Boolean) {
            try { withTimeout(30_000) { while (!test()) delay(10) } }
            catch (timeout: TimeoutCancellationException) { throw AssertionError("Native interrupted successor stage timed out: $stage", timeout) }
        }
    }
}
