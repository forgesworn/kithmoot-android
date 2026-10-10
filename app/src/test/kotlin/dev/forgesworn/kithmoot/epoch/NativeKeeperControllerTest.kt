package dev.forgesworn.kithmoot.epoch

import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.*
import dev.forgesworn.kithmoot.session.*
import dev.forgesworn.kithmoot.storage.RoomStorage
import dev.forgesworn.kithmoot.support.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class NativeKeeperControllerTest {
    private class Store : RoomStorage {
        var bytes: ByteArray? = null; var fail = false
        var onWrite: () -> Unit = {}
        override fun read() = bytes?.clone()
        override fun write(value: ByteArray) { if (fail) error("Disk unavailable"); onWrite(); bytes = value.clone() }
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
    private class Rig(val test: TestScope, val route: RoomRoute) {
        val creation = NativeKeeperCreation.fresh(0)
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
        fun newSession() = test.session(room, owner, FakeRelay(), authority = binding.authority, transport = transport,
            initialEpoch = vault.get(room.roomId)!!.let { deriveEpoch(RoomEpoch(it.currentEpoch, it.currentSecret)) },
            initialRemoved = vault.get(room.roomId)!!.removed, onEpochBlocked = { whenHeld() },
            epochGate = { event, notice ->
                if (notice.closed) vault.terminal(room.roomId, notice.epoch - 1, notice, event.id, test.currentTime / 1000)
                else assertNotNull(vault.follow(room.roomId, notice, event.id, test.currentTime / 1000))
                EpochGateResult.COMMITTED
            })
        var source = NativeKeeperJournal.create(sourceStore, binding, creation, owner.credential) { test.currentTime / 1000 }
        var ledger = RoomRekeyLedger(queueStore, queueBinding, { test.currentTime }, true)
        var selected = true
        var controller: NativeKeeperController? = null
        suspend fun start(parent: CoroutineScope = test.backgroundScope) {
            internet?.start(); test.runCurrent(); sockets.openAll(); test.runCurrent()
            live.join(); test.runCurrent()
            controller = NativeKeeperController.start(source, vault, live, ledger, NativeKeeperEndpoints(queueBinding, mesh, internet),
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

    @Test fun eachSelectedModeAdoptsTheOriginalLocallyWithoutEchoAndOnlyOffersOnItsOwnLanes() = runTest {
        for (route in RoomRoute.entries) {
            val r = Rig(this, route)
            try {
                r.start(); val controller = assertNotNull(r.controller)
                assertEquals(NativeKeeperController.State.Ready(0, KeeperPhase.ACTIVE), controller.state.value)
                controller.rekey(listOf(r.owner.credential)); runCurrent()
                assertEquals(NativeKeeperController.State.Ready(1, KeeperPhase.ACTIVE), controller.state.value)
                assertEquals(1, r.vault.get(r.room.roomId)!!.currentEpoch)
                val original = r.ledger.status().entries.single().event
                assertEquals(original.id, r.source.snapshot().epochCause)
                assertEquals(original.id, r.vault.get(r.room.roomId)!!.activationCause)
                assertEquals(if (route.nearby) 1 else 0, r.events(RekeyLane.NEARBY).count { it.id == original.id })
                assertEquals(if (route.internet) 1 else 0, r.events(RekeyLane.INTERNET).count { it.id == original.id })
                r.acknowledge(); runCurrent()
                println("NATIVE_KEEPER_CONTROLLER_MEASUREMENT " + buildJsonObject {
                    put("case", "selected-mode-original-local-adoption"); put("route", route.stored); put("sourceEpoch", 1)
                    put("receiverCause", original.id); put("relayEchoes", 0); put("participantReceipt", false)
                })
            } finally { r.stop() }
        }
    }

    @Test fun observedMemberCommandsRejectForeignStaleUnavailableAndInvalidConfirmationsWithoutHoldingOrWriting() {
        for (route in RoomRoute.entries) runTest {
            val r = Rig(this, route)
            try {
                r.start(); r.acknowledge(); runCurrent(); val controller = assertNotNull(r.controller)
                val expected = controller.hosting.value
                assertEquals(r.source.snapshot().revision, expected.revision)
                assertTrue(expected.canChangeMembers)
                var holds = 0; r.whenHeld = { holds++ }
                suspend fun refused(observation: NativeHostingState, removed: List<String> = emptyList()) {
                    val source = r.sourceStore.bytes?.clone(); val receiver = r.receiverStore.bytes?.clone()
                    val courier = r.queueStore.bytes?.clone()
                    val nearby = r.events(RekeyLane.NEARBY).toList()
                    val internet = r.events(RekeyLane.INTERNET).toList()
                    assertFails { controller.rekeyObservedMembers(observation, removed) }; runCurrent()
                    assertContentEquals(source, r.sourceStore.bytes)
                    assertContentEquals(receiver, r.receiverStore.bytes)
                    assertContentEquals(courier, r.queueStore.bytes)
                    assertEquals(nearby, r.events(RekeyLane.NEARBY)); assertEquals(internet, r.events(RekeyLane.INTERNET))
                    assertEquals(0, holds)
                    assertEquals(NativeKeeperController.State.Ready(0, KeeperPhase.ACTIVE), controller.state.value)
                }
                for (observation in listOf(
                    expected.copy(binding = expected.binding.copy(pin = "0".repeat(64))),
                    expected.copy(binding = expected.binding.copy(device = "0".repeat(64))),
                    expected.copy(epoch = 1), expected.copy(revision = expected.revision!! + 1),
                    expected.copy(revision = null), expected.copy(lifecycle = NativeHostingLifecycle.CLOSED),
                    expected.copy(pendingOriginals = listOf("0".repeat(64))),
                ) + NativeHostingStatus.entries.filter { it != NativeHostingStatus.READY }.map { expected.copy(status = it) })
                    refused(observation)
                refused(expected, listOf(r.owner.participant))
                refused(expected, listOf(r.member.participant))
                val lane = if (route.nearby) RekeyLane.NEARBY else RekeyLane.INTERNET
                r.inject(encodeEpochRequest(r.room.roomId, r.binding.authority, r.room.roomKey,
                    r.member.deviceSecretKey, r.member.credential, currentTime / 1000), lane)
                runCurrent(); r.acknowledge(); runCurrent()
                assertNotEquals(expected.revision, controller.hosting.value.revision)
                refused(expected)
                r.live.sendChat("a refused confirmation leaves verified traffic working")
            } finally { r.stop() }
        }
    }

    @Test fun anObservedConfirmationCannotBeReplayedAfterItsSuccessorCommits() = runTest {
        val r = Rig(this, RoomRoute.NEARBY)
        try {
            r.start(); val controller = assertNotNull(r.controller)
            val expected = controller.hosting.value
            controller.rekeyObservedMembers(expected); runCurrent()
            assertEquals(1, controller.hosting.value.epoch)
            val source = r.sourceStore.bytes!!.clone(); val courier = r.queueStore.bytes!!.clone()
            val originals = r.events(RekeyLane.NEARBY).filter { it.kind == KIND_ROOM_REKEY }
            assertEquals(1, originals.size)
            assertFails { controller.rekeyObservedMembers(expected) }; runCurrent()
            assertContentEquals(source, r.sourceStore.bytes); assertContentEquals(courier, r.queueStore.bytes)
            assertEquals(originals, r.events(RekeyLane.NEARBY).filter { it.kind == KIND_ROOM_REKEY })
            assertEquals(1, r.vault.get(r.room.roomId)!!.currentEpoch)
            r.live.sendChat("confirmed successor remains usable")
        } finally { r.stop() }
    }

    @Test fun hostingObservationUsesActualSourceMembershipEvenWhenTheMemberIsOffline() {
        for (route in RoomRoute.entries) runTest {
            val r = Rig(this, route)
            try {
                r.start(); val controller = assertNotNull(r.controller)
                val first = controller.hosting.value
                assertEquals(r.binding.pin, first.binding.pin)
                assertEquals(route, first.binding.route)
                assertEquals(NativeHostingStatus.READY, first.status)
                assertEquals(0, first.epoch)
                assertEquals(r.source.snapshot().revision, first.revision)
                assertEquals(listOf(r.owner.participant), first.approved)
                val lane = if (route.nearby) RekeyLane.NEARBY else RekeyLane.INTERNET
                r.inject(encodeEpochRequest(r.room.roomId, r.binding.authority, r.room.roomKey,
                    r.member.deviceSecretKey, r.member.credential, currentTime / 1000), lane)
                runCurrent()
                assertEquals(listOf(r.owner.participant), controller.hosting.value.approved)
                controller.approve(r.member.participant); runCurrent()
                assertTrue(controller.hosting.value.approved.contains(r.member.participant))
                assertFalse(r.live.hasParticipant(r.member.participant))
                assertFailsWith<UnsupportedOperationException> {
                    (controller.hosting.value.approved as MutableList<String>).clear()
                }
                controller.rekeyMembers(removed = listOf(r.member.participant)); runCurrent()
                val next = controller.hosting.value
                assertEquals(NativeHostingStatus.READY, next.status)
                assertEquals(NativeHostingLifecycle.ACTIVE, next.lifecycle)
                assertEquals(1, next.epoch)
                assertEquals(listOf(r.owner.participant), next.approved)
                assertEquals(listOf(r.member.participant), next.removed)
                assertTrue(next.pendingOriginals.isEmpty()); assertFalse(next.canRetry)
                assertEquals(listOf(r.owner.participant), first.approved)
            } finally { r.stop() }
        }
    }

    @Test fun withdrawingDuringASourceWriteSynchronouslyPausesAndCannotRepublishReady() = runTest {
        val r = Rig(this, RoomRoute.NEARBY)
        try {
            r.start(); val controller = assertNotNull(r.controller)
            r.inject(encodeEpochRequest(r.room.roomId, r.binding.authority, r.room.roomKey,
                r.member.deviceSecretKey, r.member.credential, currentTime / 1000), RekeyLane.NEARBY)
            runCurrent()
            var observed = false
            r.sourceStore.onWrite = {
                controller.close()
                assertEquals(NativeHostingStatus.SUSPENDED, controller.hosting.value.status)
                assertFalse(controller.hosting.value.canRetry)
                observed = true
            }
            assertFails { controller.approve(r.member.participant) }
            runCurrent()
            assertTrue(observed)
            assertEquals(NativeHostingStatus.SUSPENDED, controller.hosting.value.status)
            assertFalse(controller.hosting.value.canRetry)
        } finally { r.sourceStore.onWrite = {}; r.stop() }
    }

    @Test fun failedReceiverAdoptionKeepsOriginalRecoveryMetadataWithoutClaimingReady() = runTest {
        val r = Rig(this, RoomRoute.NEARBY)
        try {
            r.start(); val controller = assertNotNull(r.controller)
            r.receiverStore.fail = true
            assertFails { controller.rekeyMembers() }; runCurrent()
            val observed = controller.hosting.value
            assertEquals(NativeHostingStatus.FAILED, observed.status)
            assertEquals(0, observed.epoch)
            val persisted = Json.parseToJsonElement(assertNotNull(r.queueStore.bytes).decodeToString()).jsonObject
            val original = NostrEvent.fromJson(persisted.getValue("entries").jsonArray.single().jsonObject.getValue("event"))
            assertEquals(listOf(original.id), observed.pendingOriginals)
            assertFalse(observed.canRetry)
            controller.close()
            assertEquals(NativeHostingStatus.FAILED, controller.hosting.value.status)
        } finally { r.receiverStore.fail = false; r.stop() }
    }

    @Test fun sourceDerivedAudienceRetainsBothOfflineMemberDevicesAfterCacheExpiryOnEveryRoute() {
        for (route in RoomRoute.entries) runTest {
            val r = Rig(this, route)
            val offlineKey = Fixtures.key(7)
            val credential = r.member.enrol(dev.forgesworn.kithmoot.crypto.Schnorr.publicKeyHex(offlineKey),
                r.room.roomId, Fixtures.CREDENTIAL_EXPIRY, 0)
            val lane = if (route.nearby) RekeyLane.NEARBY else RekeyLane.INTERNET
            try {
                r.start()
                val originals = mutableListOf<String>()
                for ((key, proof) in listOf(r.member.deviceSecretKey to r.member.credential, offlineKey to credential)) {
                    val ask = encodeEpochRequest(r.room.roomId, r.binding.authority, r.room.roomKey, key, proof, currentTime / 1000)
                    originals += ask.id; r.inject(ask, lane); runCurrent()
                    if (proof == r.member.credential) r.controller!!.approve(r.member.participant)
                }
                advanceTimeBy((EPOCH_MAX_AGE_SECONDS + 1) * 1000); runCurrent()
                r.inject(encodeEpochRequest(r.room.roomId, r.binding.authority, r.room.roomKey,
                    r.owner.deviceSecretKey, r.owner.credential, currentTime / 1000), lane); runCurrent()
                val persisted = Json.parseToJsonElement(r.sourceStore.bytes!!.decodeToString()).jsonObject
                assertEquals(3, persisted.getValue("devices").jsonArray.size)
                assertTrue(persisted.getValue("answers").jsonArray.none {
                    it.jsonObject.getValue("request").jsonObject.getValue("id").jsonPrimitive.content in originals
                })
                r.controller!!.rekeyObservedMembers(r.controller!!.hosting.value); runCurrent()
                assertEquals(NativeKeeperController.State.Ready(1, KeeperPhase.ACTIVE), r.controller!!.state.value)
                val original = r.ledger.status().entries.single().event
                assertTrue(Events.verify(original))
                assertEquals(original.id, r.source.snapshot().epochCause)
                assertEquals(original.id, r.vault.get(r.room.roomId)!!.activationCause)
                val previous = deriveEpoch(RoomEpoch(0, r.secret))
                try {
                    val body = Json.parseToJsonElement(dev.forgesworn.kithmoot.crypto.Nip44.decrypt(original.content, previous.key)).jsonObject
                    assertEquals(setOf(r.owner.devicePubkey, r.member.devicePubkey,
                        dev.forgesworn.kithmoot.crypto.Schnorr.publicKeyHex(offlineKey)), body.getValue("keys").jsonObject.keys)
                    for (key in listOf(r.owner.deviceSecretKey, r.member.deviceSecretKey, offlineKey)) {
                        val notice = assertNotNull(decodeRekeyEvent(original, r.room.roomId, r.binding.authority, previous, key))
                        try { assertTrue(assertNotNull(notice.secret).contentEquals(r.vault.get(r.room.roomId)!!.currentSecret)) }
                        finally { notice.secret?.fill(0) }
                    }
                } finally { previous.key.fill(0) }
                println("NATIVE_MEMBER_COMMAND_MEASUREMENT route=${route.stored} cached_member_requests=0 qualified_devices=3 successor_seals=3")
            } finally { offlineKey.fill(0); r.stop() }
        }
    }

    @Test fun memberRemovalDropsAllOfItsQualifiedDeviceSealsAndRetainsTheirTombstones() = runTest {
        val r = Rig(this, RoomRoute.NEARBY)
        val offlineKey = Fixtures.key(7)
        try {
            r.start()
            val offline = r.member.enrol(dev.forgesworn.kithmoot.crypto.Schnorr.publicKeyHex(offlineKey),
                r.room.roomId, Fixtures.CREDENTIAL_EXPIRY, 0)
            for ((key, proof) in listOf(r.member.deviceSecretKey to r.member.credential, offlineKey to offline)) {
                r.inject(encodeEpochRequest(r.room.roomId, r.binding.authority, r.room.roomKey, key, proof, 0), RekeyLane.NEARBY)
                runCurrent(); if (proof == r.member.credential) r.controller!!.approve(r.member.participant)
            }
            r.controller!!.rekeyObservedMembers(r.controller!!.hosting.value, removed = listOf(r.member.participant)); runCurrent()
            assertEquals(listOf(r.owner.participant), r.source.snapshot().members)
            assertEquals(listOf(r.member.participant), r.source.snapshot().removed)
            val devices = Json.parseToJsonElement(r.sourceStore.bytes!!.decodeToString()).jsonObject.getValue("devices").jsonArray
            assertEquals(2, devices.count { it.jsonObject.getValue("removed").jsonPrimitive.boolean })
            val original = r.ledger.status().entries.single().event
            val previous = deriveEpoch(RoomEpoch(0, r.secret))
            try {
                val body = Json.parseToJsonElement(dev.forgesworn.kithmoot.crypto.Nip44.decrypt(original.content, previous.key)).jsonObject
                assertEquals(setOf(r.owner.devicePubkey), body.getValue("keys").jsonObject.keys)
                for (key in listOf(r.member.deviceSecretKey, offlineKey)) {
                    val notice = assertNotNull(decodeRekeyEvent(original, r.room.roomId, r.binding.authority, previous, key))
                    assertNull(notice.secret); assertEquals(listOf(r.member.participant), notice.removed)
                }
            } finally { previous.key.fill(0) }
            assertEquals(NativeKeeperController.State.Ready(1, KeeperPhase.ACTIVE), r.controller!!.state.value)
            r.live.sendChat("Remaining owner still uses the actual successor"); runCurrent()
            assertEquals(1, r.live.chat.value.size)
        } finally { offlineKey.fill(0); r.stop() }
    }

    @Test fun invalidRekeyCommandsLeaveActualPairedChatActiveWithoutSourceOrCourierWrites() = runTest {
        val r = Rig(this, RoomRoute.NEARBY)
        val peerLink = Link(RoomNearbyDiscovery.scope(r.room.roomId))
        val peerMesh = RoomMeshTransport(RoomNearbyDiscovery.scope(r.room.roomId), peerLink) { currentTime / 1000 }
        val peer = session(r.room, r.member, FakeRelay(), authority = r.binding.authority, transport = peerMesh)
        r.link.onOffer = { bytes -> backgroundScope.launch { peerLink.receive?.invoke(bytes, "owner") } }
        peerLink.onOffer = { bytes -> backgroundScope.launch { r.link.receive?.invoke(bytes, "peer") } }
        try {
            r.start(); peer.join(); runCurrent()
            val ask = encodeEpochRequest(r.room.roomId, r.binding.authority, r.room.roomKey,
                r.member.deviceSecretKey, r.member.credential, currentTime / 1000)
            r.inject(ask, RekeyLane.NEARBY); runCurrent(); r.controller!!.approve(r.member.participant)
            val source = r.sourceStore.bytes!!.clone(); val queue = r.queueStore.bytes!!.clone()
            var holds = 0; r.whenHeld = { holds++ }
            assertFails { r.controller!!.rekey(listOf(r.owner.credential)) }
            assertFails { r.controller!!.rekey(listOf(r.owner.credential, r.member.credential, r.member.credential)) }
            assertFails { r.controller!!.rekey(listOf(r.owner.credential, r.member.credential), destruct = true) }
            assertFails { r.controller!!.rekey(listOf(r.owner.credential), removed = listOf(r.member.participant), scheduled = true) }
            assertFails { r.controller!!.rekeyMembers(removed = listOf(r.owner.participant)) }
            assertFails { r.controller!!.rekeyMembers(removed = listOf("ab".repeat(32))) }
            assertFails { r.controller!!.rekeyMembers(destruct = true) }
            assertFails { r.controller!!.rekeyMembers(removed = listOf(r.member.participant), scheduled = true) }
            runCurrent()
            assertEquals(0, holds); assertTrue(source.contentEquals(r.sourceStore.bytes)); assertTrue(queue.contentEquals(r.queueStore.bytes))
            assertEquals(NativeKeeperController.State.Ready(0, KeeperPhase.ACTIVE), r.controller!!.state.value)
            r.live.sendChat("Owner after rejected command"); peer.sendChat("Peer after rejected command"); runCurrent()
            assertEquals(2, r.live.chat.value.size); assertEquals(2, peer.chat.value.size)
            assertEquals(r.live.chat.value.map { it.body }.toSet(), peer.chat.value.map { it.body }.toSet())
        } finally { peer.leave(); peerMesh.close(); r.stop() }
    }

    @Test fun credentialExpiryDuringTheActualHoldResumesOnlyTheUnchangedReceiverAndSource() = runTest { expiryDuringHold(false) }
    @Test fun memberCommandExpiryDuringTheActualHoldRestoresActualPairedChat() = runTest { expiryDuringHold(true) }

    private suspend fun TestScope.expiryDuringHold(sourceAudience: Boolean) {
        val r = Rig(this, RoomRoute.NEARBY)
        val short = PrimaryIdentity.create(r.room.roomId, 1, 0, Fixtures.key(1), Fixtures.key(2))
        val peerLink = Link(RoomNearbyDiscovery.scope(r.room.roomId))
        val peerMesh = RoomMeshTransport(RoomNearbyDiscovery.scope(r.room.roomId), peerLink) { currentTime / 1000 }
        val peer = session(r.room, r.member, FakeRelay(), authority = r.binding.authority, transport = peerMesh)
        r.link.onOffer = { bytes -> backgroundScope.launch { peerLink.receive?.invoke(bytes, "owner") } }
        peerLink.onOffer = { bytes -> backgroundScope.launch { r.link.receive?.invoke(bytes, "peer") } }
        try {
            r.start(); peer.join(); runCurrent()
            val ask = encodeEpochRequest(r.room.roomId, r.binding.authority, r.room.roomKey,
                short.deviceSecretKey, short.credential, 0)
            r.inject(ask, RekeyLane.NEARBY); runCurrent(); r.controller!!.approve(short.participant)
            val before = r.sourceStore.bytes!!.clone()
            r.whenHeld = { advanceTimeBy(2_000) }
            assertFailsWith<NativeRekeyRefusedException> {
                if (sourceAudience) r.controller!!.rekeyMembers()
                else r.controller!!.rekey(listOf(r.owner.credential, short.credential))
            }
            runCurrent()
            assertTrue(before.contentEquals(r.sourceStore.bytes)); assertTrue(r.source.snapshot().pending.isEmpty())
            assertEquals(NativeKeeperController.State.Ready(0, KeeperPhase.ACTIVE), r.controller!!.state.value)
            assertIs<RoomEpochState.Active>(r.live.epochState.value)
            assertEquals(0, r.vault.get(r.room.roomId)!!.currentEpoch)
            r.live.sendChat("Actual unchanged receiver resumed after expiry"); runCurrent()
            peer.sendChat("Live peer still chats after refused rekey"); runCurrent()
            assertEquals(2, r.live.chat.value.size); assertEquals(2, peer.chat.value.size)
            assertEquals(r.live.chat.value.map { it.body }.toSet(), peer.chat.value.map { it.body }.toSet())
            println("NATIVE_REKEY_PREFLIGHT_MEASUREMENT expired_during_hold=true unchanged_source=true epoch=0 fresh_rows_each=2")
        } finally { peer.leave(); peerMesh.close(); r.stop() }
    }

    @Test fun aCompleteOversizedAudienceNeverHoldsActualPairedChatOrSignsAPendingNotice() = runTest {
        val r = Rig(this, RoomRoute.MIXED)
        val peerLink = Link(RoomNearbyDiscovery.scope(r.room.roomId))
        val peerMesh = RoomMeshTransport(RoomNearbyDiscovery.scope(r.room.roomId), peerLink) { currentTime / 1000 }
        val peer = session(r.room, r.member, FakeRelay(), authority = r.binding.authority, transport = peerMesh)
        r.link.onOffer = { bytes -> backgroundScope.launch { peerLink.receive?.invoke(bytes, "owner") } }
        peerLink.onOffer = { bytes -> backgroundScope.launch { r.link.receive?.invoke(bytes, "peer") } }
        fun key(seed: Int) = ByteArray(32).apply { this[30] = (seed ushr 8).toByte(); this[31] = seed.toByte() }
        try {
            r.start(); peer.join(); runCurrent()
            val presented = mutableListOf(r.member)
            for (seed in 10..135) presented += PrimaryIdentity.create(r.room.roomId, 10_000, 0, key(seed), key(seed + 256))
            for (who in presented) {
                advanceTimeBy(11_000)
                r.inject(encodeEpochRequest(r.room.roomId, r.binding.authority, r.room.roomKey,
                    who.deviceSecretKey, who.credential, currentTime / 1000), RekeyLane.INTERNET)
                runCurrent(); r.controller!!.approve(who.participant); r.acknowledge(); runCurrent()
            }
            val gone = presented.drop(1).take(96).map { it.participant }
            val eligible = listOf(r.owner.credential) + presented.filter { it.participant !in gone }.map { it.credential }
            assertEquals(128, r.source.snapshot().members.size); assertEquals(32, eligible.size)
            val source = r.sourceStore.bytes!!.clone(); val queue = r.queueStore.bytes!!.clone()
            var held = 0; r.whenHeld = { held++ }
            assertFails { r.controller!!.rekey(eligible, removed = gone) }; runCurrent()
            assertFails { r.controller!!.rekeyMembers(removed = gone) }; runCurrent()
            assertEquals(0, held); assertTrue(source.contentEquals(r.sourceStore.bytes)); assertTrue(queue.contentEquals(r.queueStore.bytes))
            assertEquals(0, r.events(RekeyLane.INTERNET).count { it.kind == KIND_ROOM_REKEY })
            assertEquals(0, r.events(RekeyLane.NEARBY).count { it.kind == KIND_ROOM_REKEY })
            assertEquals(NativeKeeperController.State.Ready(0, KeeperPhase.ACTIVE), r.controller!!.state.value)
            r.live.sendChat("Owner after oversized complete audience"); peer.sendChat("Peer after oversized complete audience"); runCurrent()
            assertEquals(2, r.live.chat.value.size); assertEquals(2, peer.chat.value.size)
            assertEquals(r.live.chat.value.map { it.body }.toSet(), peer.chat.value.map { it.body }.toSet())
            println("NATIVE_REKEY_PREFLIGHT_MEASUREMENT oversized_complete_audience=true retained_devices=128 seals=32 removals=96 held=0 fresh_rows_each=2")
        } finally { peer.leave(); peerMesh.close(); r.stop() }
    }

    @Test fun foregroundWithdrawalDuringTheHoldCannotResumeAnUncommittedProposal() = runTest {
        val r = Rig(this, RoomRoute.NEARBY)
        try {
            r.start(); val source = r.sourceStore.bytes!!.clone(); val queue = r.queueStore.bytes!!.clone()
            r.whenHeld = { r.selected = false }
            assertFails { r.controller!!.rekey(listOf(r.owner.credential)) }; runCurrent()
            assertEquals(NativeKeeperController.State.Failed, r.controller!!.state.value)
            assertTrue(source.contentEquals(r.sourceStore.bytes)); assertTrue(queue.contentEquals(r.queueStore.bytes))
            assertFails { r.live.sendChat("The withdrawn owner remains held") }
            assertEquals(0, r.events(RekeyLane.NEARBY).count { it.kind == KIND_ROOM_REKEY })
        } finally { r.stop() }
    }

    @Test fun aCorruptActualReceiverDuringTheHoldCannotBeResumedByATypedRefusal() = runTest {
        val r = Rig(this, RoomRoute.NEARBY)
        val short = PrimaryIdentity.create(r.room.roomId, 1, 0, Fixtures.key(1), Fixtures.key(2))
        try {
            r.start()
            val ask = encodeEpochRequest(r.room.roomId, r.binding.authority, r.room.roomKey,
                short.deviceSecretKey, short.credential, 0)
            r.inject(ask, RekeyLane.NEARBY); runCurrent(); r.controller!!.approve(short.participant)
            val source = r.sourceStore.bytes!!.clone(); val queue = r.queueStore.bytes!!.clone()
            r.whenHeld = { advanceTimeBy(2_000); r.receiverStore.bytes = byteArrayOf(0) }
            assertFails { r.controller!!.rekey(listOf(r.owner.credential, short.credential)) }; runCurrent()
            assertEquals(NativeKeeperController.State.Failed, r.controller!!.state.value)
            assertTrue(source.contentEquals(r.sourceStore.bytes)); assertTrue(queue.contentEquals(r.queueStore.bytes))
            assertEquals(JsonNull, Json.parseToJsonElement(r.sourceStore.bytes!!.decodeToString()).jsonObject["pending"])
            assertTrue(r.receiverStore.bytes!!.contentEquals(byteArrayOf(0)))
            assertFails { r.live.sendChat("A corrupt actual receiver remains held") }
        } finally { r.stop() }
    }

    @Test fun refusalAfterAScheduledEpochPreservesTheExactLiveActiveState() = runTest {
        val r = Rig(this, RoomRoute.NEARBY)
        val short = PrimaryIdentity.create(r.room.roomId, 1, 0, Fixtures.key(1), Fixtures.key(2))
        try {
            r.start(); r.controller!!.rekey(listOf(r.owner.credential), scheduled = true); runCurrent()
            val previous = assertIs<RoomEpochState.Active>(r.live.epochState.value)
            assertTrue(previous.scheduled)
            val ask = encodeEpochRequest(r.room.roomId, r.binding.authority, r.room.roomKey,
                short.deviceSecretKey, short.credential, 0)
            r.inject(ask, RekeyLane.NEARBY); runCurrent(); r.controller!!.approve(short.participant)
            val before = r.sourceStore.bytes!!.clone()
            r.whenHeld = { advanceTimeBy(2_000) }
            assertFailsWith<NativeRekeyRefusedException> { r.controller!!.rekey(listOf(r.owner.credential, short.credential)) }
            runCurrent()
            assertEquals(previous, r.live.epochState.value)
            assertEquals(NativeKeeperController.State.Ready(1, KeeperPhase.ACTIVE), r.controller!!.state.value)
            assertTrue(before.contentEquals(r.sourceStore.bytes))
            r.live.sendChat("Scheduled epoch metadata survives the refused command"); runCurrent()
            assertEquals(1, r.live.chat.value.size)
        } finally { r.stop() }
    }

    @Test fun nearbyRequestsUseActualSubscriptionsCachedOriginalAnswersAndPersistedApproval() = runTest {
        val r = Rig(this, RoomRoute.NEARBY)
        try {
            r.start(); val controller = assertNotNull(r.controller)
            val context = LivePersistentContext(r.invitation, r.room.roomId)
            val requester = Fixtures.key(31)
            val challenge = encodeLivePersistentRequest(context, requester, currentTime / 1000)
            r.inject(challenge, RekeyLane.NEARBY); runCurrent()
            val first = r.events(RekeyLane.NEARBY).single { it.kind == KIND_INVITATION_GRANT }
            assertNotNull(decodeLivePersistentAnswer(first, context, challenge, requester, currentTime / 1000))
            advanceTimeBy(1_000); r.inject(challenge, RekeyLane.NEARBY); runCurrent()
            val answers = r.events(RekeyLane.NEARBY).filter { it.kind == KIND_INVITATION_GRANT }
            assertEquals(listOf(first, first), answers)
            val request = encodeEpochRequest(r.room.roomId, r.binding.authority, r.room.roomKey,
                r.member.deviceSecretKey, r.member.credential, currentTime / 1000)
            r.inject(request, RekeyLane.NEARBY); runCurrent()
            assertEquals("unknown", (assertNotNull(decodeEpochGrant(r.events(RekeyLane.NEARBY).last { it.kind == KIND_EPOCH_GRANT },
                r.room.roomId, r.binding.authority, r.member.deviceSecretKey, request.id, currentTime / 1000)) as EpochGrant.Refused).reason)
            controller.approve(r.member.participant)
            advanceTimeBy(1_000)
            val fresh = encodeEpochRequest(r.room.roomId, r.binding.authority, r.room.roomKey,
                r.member.deviceSecretKey, r.member.credential, currentTime / 1000)
            r.inject(fresh, RekeyLane.NEARBY); runCurrent()
            assertEquals(0, (assertNotNull(decodeEpochGrant(r.events(RekeyLane.NEARBY).last { it.kind == KIND_EPOCH_GRANT },
                r.room.roomId, r.binding.authority, r.member.deviceSecretKey, fresh.id, currentTime / 1000)) as EpochGrant.Current).epoch)
            val before = r.source.snapshot()
            r.selected = false
            advanceTimeBy(1_000); r.inject(challenge, RekeyLane.NEARBY); runCurrent()
            assertEquals(before.copy(suspended = true), r.source.snapshot())
            controller.stop()
            assertEquals(NativeKeeperController.State.Suspended, controller.state.value)
            assertFails { controller.approve(r.member.participant) }
        } finally { r.stop() }
    }

    @Test fun actualInternetRequestSubscriptionAllowsOnlyThreeSpacedOriginalRetriesAndStillDeduplicatesChat() = runTest {
        val r = Rig(this, RoomRoute.INTERNET)
        try {
            r.start()
            val request = encodeLivePersistentRequest(LivePersistentContext(r.invitation, r.room.roomId), Fixtures.key(31), 0)
            for (index in 0..3) {
                if (index > 0) advanceTimeBy(1_000)
                r.inject(request, RekeyLane.INTERNET); r.inject(request, RekeyLane.INTERNET); runCurrent()
                val answers = r.events(RekeyLane.INTERNET).filter { it.kind == KIND_INVITATION_GRANT }
                assertEquals(minOf(index + 1, 3), answers.size)
                assertEquals(1, answers.map { it.id }.distinct().size)
                r.acknowledge(); runCurrent()
            }
            val pool = requireNotNull(r.internet)
            assertFails { pool.subscribeKeeperRequests(listOf(Filter(kinds = listOf(KIND_CHAT)))) }
            val received = mutableListOf<NostrEvent>()
            val normal = backgroundScope.launch { pool.subscribe(listOf(Filter(kinds = listOf(KIND_CHAT)))).collect { received += it } }
            runCurrent()
            val chat = Events.sign(Fixtures.key(2), KIND_CHAT, currentTime / 1000, emptyList(), "opaque")
            val socket = r.sockets.opened.last(); val subscription = socket.requestedSubscriptions().last()
            repeat(2) { socket.deliverEvent(subscription, chat); runCurrent(); advanceTimeBy(1_000) }
            assertEquals(listOf(chat), received); normal.cancelAndJoin()
        } finally { r.stop() }
    }

    @Test fun receiverWriteFailureSuspendsTheOwnerAndReopenResumesOneOriginalWithoutAnotherSignature() = runTest {
        val r = Rig(this, RoomRoute.NEARBY)
        try {
            r.start(); r.receiverStore.fail = true
            assertFails { r.controller!!.rekey(listOf(r.owner.credential)) }
            runCurrent(); r.controller!!.stop()
            val saved = Json.parseToJsonElement(r.sourceStore.bytes!!.decodeToString()).jsonObject
            val original = saved.getValue("pending").jsonObject.getValue("events").jsonArray.single().let(NostrEvent::fromJson)
            assertEquals(0, r.vault.get(r.room.roomId)!!.currentEpoch)
            assertFails { r.live.sendChat("A failed source adoption keeps chat held") }
            r.receiverStore.fail = false
            r.source = NativeKeeperJournal.open(r.sourceStore, r.binding) { currentTime / 1000 }
            r.ledger = RoomRekeyLedger(r.queueStore, r.queueBinding, { currentTime })
            r.controller = NativeKeeperController.start(r.source, r.vault, r.live, r.ledger,
                NativeKeeperEndpoints(r.queueBinding, r.mesh, null), backgroundScope, { r.selected }, StandardTestDispatcher(testScheduler))
            runCurrent()
            assertEquals(NativeKeeperController.State.Ready(1, KeeperPhase.ACTIVE), r.controller!!.state.value)
            assertEquals(original.id, r.source.snapshot().epochCause)
            assertTrue(r.source.snapshot().pending.isEmpty())
            assertEquals(original, r.ledger.status().entries.single().event)
            assertTrue(r.events(RekeyLane.NEARBY).filter { it.kind == KIND_ROOM_REKEY }.all { it == original })
            println("NATIVE_KEEPER_CONTROLLER_MEASUREMENT " + buildJsonObject {
                put("case", "receiver-write-failure-controller-reopen"); put("original", original.id); put("sourceEpoch", 1)
                put("freshSignatureAfterReopen", false); put("processDeath", false)
            })
        } finally { r.stop() }
    }

    @Test fun closureRetirementLeavesViaTheGuardedControlPathAndNoNewAuthorityOperationCanRun() = runTest {
        val r = Rig(this, RoomRoute.NEARBY)
        try {
            r.start(); r.controller!!.rekey(emptyList(), closed = true); runCurrent()
            assertEquals(NativeKeeperController.State.Closed(1), r.controller!!.state.value)
            assertEquals(RoomEpochState.Closed(1), r.live.epochState.value)
            assertEquals(1, r.events(RekeyLane.NEARBY).count { it.kind == KIND_INVITATION_RETIREMENT })
            assertEquals(1, r.events(RekeyLane.NEARBY).count { it.kind == KIND_ROOM_REKEY })
            assertFails { r.controller!!.approve(r.member.participant) }
            assertFails { r.controller!!.retire() }
            assertFails { r.live.sendChat("Closed") }
            assertTrue(r.source.snapshot().pending.isEmpty())
            assertEquals(NativeKeeperController.State.Closed(1), r.controller!!.state.value)
        } finally { r.stop() }
    }

    @Test fun sourceDerivedDestructiveClosureKeepsTheActualTerminalStateAndOriginalRetirement() = runTest {
        val r = Rig(this, RoomRoute.NEARBY)
        try {
            r.start(); r.controller!!.rekeyMembers(closed = true, destruct = true); runCurrent()
            assertEquals(NativeKeeperController.State.Closed(1), r.controller!!.state.value)
            assertEquals(RoomEpochState.Closed(1), r.live.epochState.value)
            assertTrue(Json.parseToJsonElement(r.sourceStore.bytes!!.decodeToString()).jsonObject.getValue("destruct").jsonPrimitive.boolean)
            assertEquals(1, r.events(RekeyLane.NEARBY).count { it.kind == KIND_INVITATION_RETIREMENT })
            assertEquals(1, r.events(RekeyLane.NEARBY).count { it.kind == KIND_ROOM_REKEY })
            assertEquals(2, r.events(RekeyLane.NEARBY).filter { it.kind in listOf(KIND_INVITATION_RETIREMENT, KIND_ROOM_REKEY) }.distinctBy { it.id }.size)
            assertTrue(r.source.snapshot().pending.isEmpty())
            assertFails { r.controller!!.rekeyMembers() }
            assertFails { r.live.sendChat("Closed native member command") }
        } finally { r.stop() }
    }

    @Test fun coldSourceAndActualSessionReplacementRecoversTheOfflineOriginalToTheSamePeerWithFreshChatBothWays() = runTest {
        val r = Rig(this, RoomRoute.NEARBY)
        val peerLink = Link(RoomNearbyDiscovery.scope(r.room.roomId))
        val peerMesh = RoomMeshTransport(RoomNearbyDiscovery.scope(r.room.roomId), peerLink) { currentTime / 1000 }
        val peerVault = EpochVault(Store()).also { it.initialise(r.room.roomId, r.binding.authority, r.secret, 0) }
        val peer = session(r.room, r.member, FakeRelay(), authority = r.binding.authority, transport = peerMesh,
            epochGate = { event, notice -> assertNotNull(peerVault.follow(r.room.roomId, notice, event.id, currentTime / 1000)); EpochGateResult.COMMITTED })
        r.link.onOffer = { bytes -> backgroundScope.launch { peerLink.receive?.invoke(bytes, "fixture-owner") } }
        peerLink.onOffer = { bytes -> backgroundScope.launch { r.link.receive?.invoke(bytes, "fixture-peer") } }
        try {
            r.start(); peer.join(); runCurrent()
            val ask = encodeEpochRequest(r.room.roomId, r.binding.authority, r.room.roomKey,
                r.member.deviceSecretKey, r.member.credential, currentTime / 1000)
            r.inject(ask, RekeyLane.NEARBY); runCurrent()
            assertEquals(listOf(r.member.participant), r.source.unknownParticipants())
            r.controller!!.approve(r.member.participant)
            r.link.up = false
            r.controller!!.rekey(listOf(r.owner.credential, r.member.credential)); runCurrent()
            val original = r.ledger.status().entries.single().event
            assertEquals(0, peer.epochKeys().epoch)
            assertEquals(0, r.ledger.status().entries.single().nearby.attempts)
            r.controller!!.stop(); r.live.leave()
            r.source = NativeKeeperJournal.open(r.sourceStore, r.binding) { currentTime / 1000 }
            r.ledger = RoomRekeyLedger(r.queueStore, r.queueBinding, { currentTime })
            r.live = r.newSession(); r.live.join(); runCurrent()
            r.controller = NativeKeeperController.start(r.source, r.vault, r.live, r.ledger,
                NativeKeeperEndpoints(r.queueBinding, r.mesh, null), backgroundScope, { r.selected }, StandardTestDispatcher(testScheduler))
            runCurrent(); assertEquals(0, peer.epochKeys().epoch)
            r.link.up = true; advanceTimeBy(5_000); runCurrent()
            assertEquals(1, peer.epochKeys().epoch)
            assertEquals(original, r.ledger.status().entries.single().event)
            r.live.sendChat("Owner after cold recovery"); peer.sendChat("Same peer after missed notice"); runCurrent()
            assertEquals(2, peer.chat.value.size); assertEquals(2, r.live.chat.value.size)
            assertEquals(peer.chat.value.map { it.body }.toSet(), r.live.chat.value.map { it.body }.toSet())
            println("NATIVE_KEEPER_CONTROLLER_MEASUREMENT " + buildJsonObject {
                put("case", "offline-original-actual-controller-session-reopen-same-peer"); put("original", original.id)
                put("memberRejoined", false); put("sourceEpoch", 1); put("peerEpoch", 1); put("freshRowsEach", 2)
                put("attemptsBeforeReopen", 0); put("attemptsAfterRecovery", r.ledger.status().entries.single().nearby.attempts)
                put("participantReceipt", false); put("processDeath", false)
            })
        } finally { peer.leave(); peerMesh.close(); r.stop() }
    }

    @Test fun parentCancellationReleasesBothJournalsAndCachedAnswerRetryKeepsTheOriginalAndDebt() = runTest {
        val r = Rig(this, RoomRoute.INTERNET)
        val parent = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
        try {
            r.start(parent)
            val request = encodeLivePersistentRequest(LivePersistentContext(r.invitation, r.room.roomId), Fixtures.key(31), 0)
            r.inject(request, RekeyLane.INTERNET); runCurrent()
            val original = r.events(RekeyLane.INTERNET).single { it.kind == KIND_INVITATION_GRANT }
            val charged = r.source.snapshot().internetBytes
            assertTrue(charged > 0)
            // No relay OK: the handoff is still ambiguous when the foreground owner dies.
            parent.cancel(); r.controller!!.stop(); runCurrent()
            assertEquals(NativeKeeperController.State.Suspended, r.controller!!.state.value)
            assertTrue(r.internet!!.reachable(), "The controller borrows and does not tear down the caller's route")
            r.inject(request, RekeyLane.INTERNET); runCurrent()
            assertEquals(1, r.events(RekeyLane.INTERNET).count { it.kind == KIND_INVITATION_GRANT })
            r.source = NativeKeeperJournal.open(r.sourceStore, r.binding) { currentTime / 1000 }
            r.ledger = RoomRekeyLedger(r.queueStore, r.queueBinding, { currentTime })
            assertTrue(r.source.snapshot().suspended)
            assertEquals(charged, r.source.snapshot().internetBytes)
            r.start()
            r.inject(request, RekeyLane.INTERNET); runCurrent()
            assertEquals(listOf(original, original), r.events(RekeyLane.INTERNET).filter { it.kind == KIND_INVITATION_GRANT })
            assertEquals(charged * 2, r.source.snapshot().internetBytes)
            r.acknowledge(); runCurrent()
            println("NATIVE_KEEPER_CONTROLLER_MEASUREMENT " + buildJsonObject {
                put("case", "parent-cancellation-original-answer-reopen"); put("answer", original.id)
                put("offers", 2); put("internetDebtBefore", charged); put("internetDebtAfter", charged * 2)
                put("lateExportsWhileStopped", 0); put("routesBorrowed", true); put("processDeath", false)
            })
        } finally { parent.cancel(); r.stop() }
    }

    @Test fun duplicateControllerStartupCannotCloseTheOriginalOwnersJournals() = runTest {
        val r = Rig(this, RoomRoute.NEARBY)
        try {
            r.start()
            assertFails {
                NativeKeeperController.start(r.source, r.vault, r.live, r.ledger,
                    NativeKeeperEndpoints(r.queueBinding, r.mesh, null), backgroundScope, { true }, StandardTestDispatcher(testScheduler))
            }
            assertEquals(NativeKeeperController.State.Ready(0, KeeperPhase.ACTIVE), r.controller!!.state.value)
            assertFalse(r.source.snapshot().suspended)
            assertFalse(r.ledger.status().suspended)
            r.controller!!.rekey(listOf(r.owner.credential)); runCurrent()
            assertEquals(NativeKeeperController.State.Ready(1, KeeperPhase.ACTIVE), r.controller!!.state.value)
            assertEquals(1, r.vault.get(r.room.roomId)!!.currentEpoch)
        } finally { r.stop() }
    }
}
