package dev.forgesworn.kithmoot.epoch

import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.*
import dev.forgesworn.kithmoot.session.*
import dev.forgesworn.kithmoot.storage.RoomStorage
import dev.forgesworn.kithmoot.storage.RoomRepository
import dev.forgesworn.kithmoot.storage.SavedRoom
import dev.forgesworn.kithmoot.support.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class NativeKeeperBusyCompletionTest {
    // Keep both accepted regression files frozen. This local fixture exercises
    // real journal/session/transport state and actual storage failure.
    private class Store : RoomStorage {
        var bytes: ByteArray? = null; var fail = false
        var writes = 0
        var onWrite: () -> Unit = {}
        override fun read() = bytes?.clone()
        override fun write(value: ByteArray) { if (fail) error("Disk unavailable"); onWrite(); bytes = value.clone(); writes += 1 }
        override fun reset() = error("Do not reset retry or authority debt")
    }

    private class Link(private val scope: String) : RoomMeshLink {
        var receive: ((ByteArray, String) -> Unit)? = null
        val offered = mutableListOf<ByteArray>()
        var onOffer: ((ByteArray) -> Unit)? = null
        var up = true
        override fun subscribe(receive: (ByteArray, String) -> Unit): AutoCloseable {
            this.receive = receive
            return AutoCloseable { this.receive = null }
        }
        override fun offer(bytes: ByteArray, to: String?) { offered += bytes.clone(); if (up) onOffer?.invoke(bytes.clone()) }
        override suspend fun resetQueued() = Unit
        override fun reachable() = up
        override fun close() { up = false }
        fun inject(event: NostrEvent) { receive?.invoke(RoomMeshWire.encode(RoomMeshWire.EVENT,
            buildJsonObject { put("scope", scope); put("event", event.toJson()) }), "fixture-peer") }
        fun events() = offered.mapNotNull { RoomMeshWire.decode(it)?.second?.get("event")?.jsonObject?.let(NostrEvent::fromJson) }
    }
    private class Rig(val test: TestScope, val route: RoomRoute, createdAt: Long = 0) {
        val creation = NativeKeeperCreation.fresh(createdAt)
        val secret = creation.roomSecret(); val invitation = creation.invitation()
        val room = deriveRoom(secret); val owner = Fixtures.primary(room, 5, 6); val member = Fixtures.primary(room, 1, 2)
        val binding = NativeKeeperBinding(room.roomId, creation.authority, owner.participant, owner.devicePubkey,
            route, if (route.internet) listOf("wss://fixture.invalid/") else emptyList())
        val queueBinding = RoomRekeyBinding(room.roomId, binding.authority, owner.devicePubkey, binding.meshScope, binding.relays, route)
        val sourceStore = Store(); val receiverStore = Store(); val queueStore = Store()
        val vault = EpochVault(receiverStore).also { it.initialise(room.roomId, binding.authority, secret, 0) }
        val link = Link(RoomNearbyDiscovery.scope(room.roomId))
        val mesh = if (route.nearby) RoomMeshTransport(RoomNearbyDiscovery.scope(room.roomId), link) { test.currentTime / 1000 } else null
        val sockets = FakeSocketFactory()
        val internet = if (route.internet) RelayPool(binding.relays, sockets, test.backgroundScope, now = { test.currentTime }) else null
        val transport: RoomTransport = when (route) {
            RoomRoute.NEARBY -> requireNotNull(mesh)
            RoomRoute.INTERNET -> requireNotNull(internet)
            RoomRoute.MIXED -> HybridRoomTransport(requireNotNull(mesh), requireNotNull(internet)) { test.currentTime / 1000 }
        }
        var live = newSession()
        var whenHeld: () -> Unit = {}
        var beforeFollowing: suspend () -> Unit = {}
        fun newSession() = test.session(room, owner, FakeRelay(), authority = binding.authority, transport = transport,
            initialEpoch = vault.get(room.roomId)!!.let { deriveEpoch(RoomEpoch(it.currentEpoch, it.currentSecret)) },
            initialRemoved = vault.get(room.roomId)!!.removed, onEpochBlocked = { whenHeld() },
            epochGate = { event, notice ->
                beforeFollowing()
                if (notice.closed) vault.terminal(room.roomId, notice.epoch - 1, notice, event.id, test.currentTime / 1000)
                else assertNotNull(vault.follow(room.roomId, notice, event.id, test.currentTime / 1000))
                EpochGateResult.COMMITTED
            })
        var source = NativeKeeperJournal.create(sourceStore, binding, creation, owner.credential) { test.currentTime / 1000 }
        var ledger = RoomRekeyLedger(queueStore, queueBinding, { test.currentTime }, true)
        var selected = true
        var controller: NativeKeeperController? = null
        val indexStore = Store()
        val index = RoomRepository(indexStore)
        var replacementIndexEnabled = false
        fun enableReplacement() {
            source.recordCourierCreated(ledger)
            index.save(SavedRoom.create(secret, owner, encodeInvitationUrl("https://fixture.invalid/join", invitation, binding.relays),
                binding.relays, "Generation fixture", 0, host = null, authority = binding.authority, route = route).withNativeAuthority(source))
            replacementIndexEnabled = true
        }
        suspend fun start(parent: CoroutineScope = test.backgroundScope) {
            internet?.start(); test.runCurrent(); sockets.openAll(); test.runCurrent()
            live.join(); test.runCurrent()
            controller = if (replacementIndexEnabled) NativeKeeperController.startForRoom(source, vault, live, ledger,
                NativeKeeperEndpoints(queueBinding, mesh, internet), parent, { selected }, index, StandardTestDispatcher(test.testScheduler))
            else NativeKeeperController.start(source, vault, live, ledger, NativeKeeperEndpoints(queueBinding, mesh, internet),
                parent, { selected }, StandardTestDispatcher(test.testScheduler))
            test.runCurrent()
        }
        fun events(lane: RekeyLane): List<NostrEvent> = if (lane == RekeyLane.NEARBY) link.events()
            else sockets.opened.flatMap { it.publishedFrames() }.map { NostrEvent.fromJson(Json.parseToJsonElement(it).jsonArray[1]) }
        fun inject(event: NostrEvent, lane: RekeyLane) {
            if (lane == RekeyLane.NEARBY) link.inject(event)
            else sockets.opened.last().let { socket -> socket.requestedSubscriptions().forEach { socket.deliverEvent(it, event) } }
        }
        fun acknowledge() { events(RekeyLane.INTERNET).forEach { sockets.opened.last().deliverOk(it.id, true) } }
        suspend fun stop() {
            controller?.stop() ?: run { source.close(); ledger.close() }
            live.leave(); mesh?.close(); internet?.stop(); secret.fill(0); invitation.bearer.fill(0)
        }
    }



    private fun RoomSession.completionEpochMutex(): Mutex =
        RoomSession::class.java.getDeclaredField("epochMutex").apply { isAccessible = true }.get(this) as Mutex

    private suspend fun TestScope.assertFatalCallerCompletes(r: Rig, operation: suspend () -> Unit) {
        val source = r.sourceStore.bytes!!.clone(); val receiver = r.receiverStore.bytes!!.clone()
        val courier = r.queueStore.bytes!!.clone(); val index = r.indexStore.bytes?.clone()
        val mutex = r.live.completionEpochMutex()
        var locked = false
        var caller: Deferred<Result<Unit>>? = null
        try {
            assertTrue(mutex.tryLock()); locked = true
            r.queueStore.fail = true
            assertFails { r.ledger.status() }
            assertTrue(r.ledger.persistenceFailed(), "Actual custody write must fail before the current BUSY work")
            // A sibling of the private controller owner, as with viewModelScope.
            caller = backgroundScope.async { runCatching { operation() } }
            runCurrent()
            assertEquals(NativeKeeperController.State.Failed, r.controller!!.state.value)
            assertTrue(caller.isCompleted,
                "Fatal custody failure must complete the in-flight caller without external cancellation")
            val failure = assertIs<IllegalStateException>(caller.await().exceptionOrNull())
            assertFalse(failure is NativeReceiverBusyException, "The fatal health cause must reach the refused caller")
            assertTrue(failure.message.orEmpty().contains("persistence or courier failed"))
            assertFalse(r.controller!!.hosting.value.canShareInvitation)
            assertFails { r.live.sendChat("Fatal owner failure cannot release startup traffic") }
            assertContentEquals(source, r.sourceStore.bytes); assertContentEquals(receiver, r.receiverStore.bytes)
            assertContentEquals(courier, r.queueStore.bytes); assertContentEquals(index, r.indexStore.bytes)
            mutex.unlock(); locked = false
            advanceTimeBy(10_000); runCurrent()
            assertEquals(NativeKeeperController.State.Failed, r.controller!!.state.value)
            assertFails { r.live.sendChat("Unlock cannot restore a failed owner") }
            assertContentEquals(source, r.sourceStore.bytes); assertContentEquals(receiver, r.receiverStore.bytes)
            assertContentEquals(courier, r.queueStore.bytes); assertContentEquals(index, r.indexStore.bytes)
            r.controller!!.stop()
        } finally {
            if (locked) mutex.unlock()
            // On red, fail at isCompleted rather than await forever; cleanup is
            // deliberately AFTER the assertion and is never completion evidence.
            caller?.cancelAndJoin()
        }
    }

    private suspend fun TestScope.failedApprovalCallerCompletes(route: RoomRoute) {
        val r = Rig(this, route)
        try {
            r.start(); r.acknowledge(); runCurrent()
            val lane = if (route.nearby) RekeyLane.NEARBY else RekeyLane.INTERNET
            r.inject(encodeEpochRequest(r.room.roomId, r.binding.authority, r.room.roomKey,
                r.member.deviceSecretKey, r.member.credential, currentTime / 1000), lane)
            runCurrent(); r.acknowledge(); runCurrent()
            assertEquals(listOf(r.member.participant), r.controller!!.unknownParticipants.value,
                "The otherwise-actionable approval must reach the first-BUSY command boundary")
            assertFalse(r.member.participant in r.source.snapshot().members)
            assertFatalCallerCompletes(r) { r.controller!!.approve(r.member.participant) }
        } finally { r.stop() }
    }

    @Test fun callerCompletion_fatalFirstBusyApprovalNearbyCompletes() = runTest {
        failedApprovalCallerCompletes(RoomRoute.NEARBY)
    }

    @Test fun callerCompletion_fatalFirstBusyApprovalInternetCompletes() = runTest {
        failedApprovalCallerCompletes(RoomRoute.INTERNET)
    }

    @Test fun callerCompletion_fatalFirstBusyApprovalMixedCompletes() = runTest {
        failedApprovalCallerCompletes(RoomRoute.MIXED)
    }

    @Test fun callerCompletion_fatalFirstBusyPendingReplacementCompletes() = runTest {
        val r = Rig(this, RoomRoute.NEARBY)
        try {
            r.enableReplacement(); r.start(); r.link.up = false
            r.controller!!.replaceObservedInvitation(r.controller!!.hosting.value); runCurrent()
            assertNotNull(r.source.snapshot().replacement)
            val expected = r.controller!!.hosting.value
            assertTrue(expected.canRetry)
            assertIs<NativeKeeperController.State.Pending>(r.controller!!.state.value)
            assertFatalCallerCompletes(r) { r.controller!!.retryObservedPending(expected); Unit }
        } finally { r.stop() }
    }
}
