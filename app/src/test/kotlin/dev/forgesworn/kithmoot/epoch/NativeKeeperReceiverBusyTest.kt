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
class NativeKeeperReceiverBusyTest {
    // Local copy of the established real fixture: keep the accepted red/green
    // controller regression file completely immutable while extending coverage.
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


    private fun RoomSession.epochMutexForTest(): Mutex =
        RoomSession::class.java.getDeclaredField("epochMutex").apply { isAccessible = true }.get(this) as Mutex

    private fun held(r: Rig) {
        assertEquals(NativeKeeperController.State.Starting, r.controller!!.state.value)
        assertEquals(NativeHostingStatus.STARTING, r.controller!!.hosting.value.status)
        assertFalse(r.controller!!.hosting.value.canShareInvitation)
        assertTrue(r.controller!!.unknownParticipants.value.isEmpty())
        assertFails { r.live.sendChat("Incomplete receiver verification cannot release chat") }
    }

    @Test fun commandFirstBusyRefusesWithoutReplayingTheAuthorityOperation() = runTest {
        for (route in RoomRoute.entries) {
            val r = Rig(this, route); var locked = false
            try {
                r.start(); r.acknowledge(); runCurrent()
                val owner = r.controller!!.hosting.value.ownerGeneration
                val source = r.sourceStore.bytes!!.clone(); val receiver = r.receiverStore.bytes!!.clone()
                val courier = r.queueStore.bytes!!.clone()
                assertTrue(r.live.epochMutexForTest().tryLock()); locked = true
                assertFailsWith<NativeReceiverBusyException> { r.controller!!.approve(r.member.participant) }
                held(r)
                assertContentEquals(source, r.sourceStore.bytes); assertContentEquals(receiver, r.receiverStore.bytes)
                assertContentEquals(courier, r.queueStore.bytes)
                r.live.epochMutexForTest().unlock(); locked = false
                advanceTimeBy(5_000); runCurrent(); r.acknowledge(); runCurrent()
                assertIs<NativeKeeperController.State.Ready>(r.controller!!.state.value)
                assertEquals(owner, r.controller!!.hosting.value.ownerGeneration)
                assertFalse(r.member.participant in r.source.snapshot().members, "A refused approve must not replay on Retry")
                r.live.sendChat("Same owner after an explicitly refused command")
            } finally { if (locked) r.live.epochMutexForTest().unlock(); r.stop() }
        }
    }

    @Test fun incomingRequestFirstBusyCannotReserveOrPublishAGrant() = runTest {
        for (route in RoomRoute.entries) {
            val r = Rig(this, route); var locked = false
            try {
                r.start(); r.acknowledge(); runCurrent()
                val source = r.sourceStore.bytes!!.clone(); val receiver = r.receiverStore.bytes!!.clone()
                val courier = r.queueStore.bytes!!.clone()
                val lane = if (route.nearby) RekeyLane.NEARBY else RekeyLane.INTERNET
                val before = r.events(lane).filter { it.kind == KIND_EPOCH_GRANT }
                val request = encodeEpochRequest(r.room.roomId, r.binding.authority, r.room.roomKey,
                    r.member.deviceSecretKey, r.member.credential, currentTime / 1000)
                assertTrue(r.live.epochMutexForTest().tryLock()); locked = true
                r.inject(request, lane); runCurrent(); held(r)
                r.inject(request, lane); runCurrent(); held(r)
                assertContentEquals(source, r.sourceStore.bytes); assertContentEquals(receiver, r.receiverStore.bytes)
                assertContentEquals(courier, r.queueStore.bytes)
                assertEquals(before, r.events(lane).filter { it.kind == KIND_EPOCH_GRANT })
                r.live.epochMutexForTest().unlock(); locked = false
                advanceTimeBy(5_000); runCurrent(); r.acknowledge(); runCurrent()
                assertIs<NativeKeeperController.State.Ready>(r.controller!!.state.value)
                assertEquals(before, r.events(lane).filter { it.kind == KIND_EPOCH_GRANT })
                r.live.sendChat("A dropped incoming request cannot keep the verified owner failed")
                r.inject(encodeEpochRequest(r.room.roomId, r.binding.authority, r.room.roomKey,
                    r.member.deviceSecretKey, r.member.credential, currentTime / 1000), lane)
                runCurrent()
                assertEquals(listOf(r.member.participant), r.controller!!.unknownParticipants.value,
                    "Only a fresh request after full verification may become an approval")
            } finally { if (locked) r.live.epochMutexForTest().unlock(); r.stop() }
        }
    }

    @Test fun busyColdStartupCannotReturnAnUnverifiedController() = runTest {
        for (route in RoomRoute.entries) {
            val r = Rig(this, route); var locked = false
            try {
                val source = r.sourceStore.bytes!!.clone()
                r.live.holdKeeperStartup()
                assertTrue(r.live.epochMutexForTest().tryLock()); locked = true
                assertFailsWith<NativeReceiverBusyException> { r.start() }
                assertNull(r.controller)
                assertContentEquals(source, r.sourceStore.bytes)
                r.live.epochMutexForTest().unlock(); locked = false
                advanceTimeBy(10_000); runCurrent()
                assertFails { r.live.sendChat("Failed cold startup never publishes") }
            } finally { if (locked) r.live.epochMutexForTest().unlock(); r.stop() }
        }
    }

    @Test fun pendingReplacementBusyCannotReleaseChatOrAnswerDuringPartialRecovery() = runTest {
        val r = Rig(this, RoomRoute.NEARBY); var locked = false
        try {
            r.enableReplacement(); r.start(); r.link.up = false
            r.controller!!.replaceObservedInvitation(r.controller!!.hosting.value); runCurrent()
            val pending = assertNotNull(r.source.snapshot().replacement)
            val expected = r.controller!!.hosting.value
            assertTrue(expected.canRetry)
            val owner = expected.ownerGeneration
            assertTrue(r.live.epochMutexForTest().tryLock()); locked = true
            assertFailsWith<NativeReceiverBusyException> { r.controller!!.retryObservedPending(expected) }
            held(r)
            assertFails { r.controller!!.retryObservedPending(expected) }
            held(r)
            r.live.epochMutexForTest().unlock(); locked = false
            advanceTimeBy(5_000); runCurrent()
            assertIs<NativeKeeperController.State.Pending>(r.controller!!.state.value)
            assertFails { r.live.sendChat("Partial replacement cannot release an earlier verification hold") }
            val source = r.sourceStore.bytes!!.clone(); val receiver = r.receiverStore.bytes!!.clone()
            val request = encodeEpochRequest(r.room.roomId, r.binding.authority, r.room.roomKey,
                r.owner.deviceSecretKey, r.owner.credential, currentTime / 1000)
            val before = r.events(RekeyLane.NEARBY).filter { it.kind == KIND_EPOCH_GRANT }
            r.link.up = true // Exercise the held guard, not an unavailable-endpoint refusal.
            r.inject(request, RekeyLane.NEARBY); runCurrent()
            assertContentEquals(source, r.sourceStore.bytes); assertContentEquals(receiver, r.receiverStore.bytes)
            assertEquals(before, r.events(RekeyLane.NEARBY).filter { it.kind == KIND_EPOCH_GRANT })
            r.link.up = true
            r.controller!!.retryObservedPending(r.controller!!.hosting.value); runCurrent()
            assertEquals(NativeKeeperController.State.Ready(0, KeeperPhase.ACTIVE), r.controller!!.state.value)
            assertEquals(owner, r.controller!!.hosting.value.ownerGeneration)
            assertEquals(pending.welcome, r.source.welcome().id)
            assertEquals(listOf(pending.retirement), r.source.snapshot().retirementOriginals)
            assertEquals(1, r.source.snapshot().invitationGeneration)
            assertNull(r.source.snapshot().replacement)
            assertEquals(KeeperPhase.ACTIVE, r.source.verifyReceiver(r.vault, r.live))
            r.live.sendChat("Only full matching replacement recovery releases chat")
        } finally { if (locked) r.live.epochMutexForTest().unlock(); r.stop() }
    }

    @Test fun withdrawalDuringPendingRecoveryBusyCannotReviveTheOriginalOwner() = runTest {
        val r = Rig(this, RoomRoute.NEARBY); var locked = false
        try {
            r.enableReplacement(); r.start(); r.link.up = false
            r.controller!!.replaceObservedInvitation(r.controller!!.hosting.value); runCurrent()
            val expected = r.controller!!.hosting.value
            assertTrue(expected.canRetry)
            assertTrue(r.live.epochMutexForTest().tryLock()); locked = true
            assertFailsWith<NativeReceiverBusyException> { r.controller!!.retryObservedPending(expected) }
            held(r)
            val source = r.sourceStore.bytes!!.clone(); val receiver = r.receiverStore.bytes!!.clone()
            r.selected = false; r.controller!!.close(); runCurrent()
            r.live.epochMutexForTest().unlock(); locked = false
            r.link.up = true
            advanceTimeBy(10_000); runCurrent()
            assertFalse(r.controller!!.state.value is NativeKeeperController.State.Ready)
            assertFalse(r.controller!!.hosting.value.canShareInvitation)
            assertContentEquals(source, r.sourceStore.bytes); assertContentEquals(receiver, r.receiverStore.bytes)
            assertFails { r.controller!!.retryObservedPending(expected) }
            assertFails { r.live.sendChat("A withdrawn pending owner cannot release traffic") }
        } finally { if (locked) r.live.epochMutexForTest().unlock(); r.stop() }
    }

    private suspend fun TestScope.ledgerFailureCannotBecomeReady(afterHold: Boolean) {
        for (route in RoomRoute.entries) {
            val r = Rig(this, route); var locked = false
            try {
                r.start(); r.acknowledge(); runCurrent()
                assertTrue(r.live.epochMutexForTest().tryLock()); locked = true
                if (afterHold) { r.controller!!.retry(); runCurrent(); held(r) }
                r.queueStore.fail = true
                assertFails { r.ledger.status() }
                assertTrue(r.ledger.persistenceFailed())
                if (!afterHold) { r.controller!!.retry(); runCurrent() }
                r.live.epochMutexForTest().unlock(); locked = false
                advanceTimeBy(5_000); runCurrent()
                assertEquals(NativeKeeperController.State.Failed, r.controller!!.state.value)
                assertFalse(r.controller!!.hosting.value.canShareInvitation)
                assertFails { r.live.sendChat("Durability failure cannot recover to Ready") }
            } finally { if (locked) r.live.epochMutexForTest().unlock(); r.stop() }
        }
    }

    @Test fun ledgerFailureBeforeFirstBusyStillFailsTheOwner() = runTest {
        ledgerFailureCannotBecomeReady(afterHold = false)
    }

    @Test fun ledgerFailureWhileHeldCannotBeHiddenBySuccessfulLaterVerification() = runTest {
        ledgerFailureCannotBecomeReady(afterHold = true)
    }
}
