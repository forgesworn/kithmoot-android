package dev.forgesworn.kithmoot.epoch

import dev.forgesworn.kithmoot.crypto.Schnorr
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
class NativeKeeperEndpointsTest {
    private class Store : RoomStorage {
        var bytes: ByteArray? = null; var fail = false
        override fun read() = bytes?.clone()
        override fun write(value: ByteArray) { if (fail) error("disk full"); bytes = value.clone() }
        override fun reset() = error("Do not reset an authority or retry budget")
    }
    private class Link : RoomMeshLink {
        val offers = mutableListOf<ByteArray>(); var closed = false
        var reset: CompletableDeferred<Unit>? = null
        override fun subscribe(receive: (ByteArray, String) -> Unit) = AutoCloseable {}
        override fun offer(bytes: ByteArray, to: String?) { check(!closed); offers += bytes.clone() }
        override suspend fun resetQueued() { reset?.await() }
        override fun reachable() = !closed
        override fun close() { closed = true }
    }
    private val room = Fixtures.room()
    private val rootKey = Fixtures.key(41)
    private val root = Schnorr.publicKeyHex(rootKey)
    private val owner = Fixtures.primary(room, 5, 6)
    private val scope = RoomNearbyDiscovery.scope(room.roomId)
    private fun binding(route: RoomRoute) = RoomRekeyBinding(room.roomId, root, owner.devicePubkey,
        if (route.nearby) scope else null, if (route.internet) listOf("wss://fixture.invalid/") else emptyList(), route)
    private fun notice(closed: Boolean = false) = encodeRekeyEvent(room.roomId, rootKey,
        deriveEpoch(RoomEpoch(0, ByteArray(32) { 7 })), RoomEpoch(1, ByteArray(32) { 44 }),
        if (closed) emptyList() else listOf(owner.devicePubkey), emptyList(), 0, closed = closed, commit = true)
    private fun pool(test: TestScope, sockets: FakeSocketFactory) = RelayPool(listOf("wss://fixture.invalid/"),
        sockets, test.backgroundScope, now = { test.currentTime })

    @Test fun singleLaneBindingNeverReservesOrRestoresDebtForAnAbsentLaneAndCannotGetNewCreditByEditingMode() {
        for (route in listOf(RoomRoute.NEARBY, RoomRoute.INTERNET)) {
            val b = binding(route); val store = Store(); val e = notice()
            val selected = if (route.nearby) RekeyLane.NEARBY else RekeyLane.INTERNET
            val absent = if (route.nearby) RekeyLane.INTERNET else RekeyLane.NEARBY
            RoomRekeyLedger(store, b, { 0 }, true).use { ledger ->
                ledger.bind { true }; ledger.admit(e)
                assertNull(ledger.reserve(e.id, absent))
                val send = assertNotNull(ledger.reserve(e.id, selected))
                assertFalse(ledger.canHandoff(send.copy(lane = absent)))
                assertFails { ledger.offered(send.copy(lane = absent)) }
            }
            val original = store.bytes!!.clone()
            assertEquals(b.owner, binding(RoomRoute.MIXED).owner)
            assertFails { RoomRekeyLedger(store, binding(RoomRoute.MIXED), { 0 }) }
            assertContentEquals(original, store.bytes)
            val root = Json.parseToJsonElement(original.decodeToString()).jsonObject
            val mutated = JsonObject(root + ("spends" to JsonArray(root.getValue("spends").jsonArray.map {
                JsonObject(it.jsonObject + ("lane" to JsonPrimitive(absent.name)))
            })))
            store.bytes = mutated.toString().toByteArray()
            assertFails { RoomRekeyLedger(store, b, { 0 }) }
            store.bytes = original
            RoomRekeyLedger(store, b, { 0 }).use { ledger ->
                assertTrue(ledger.status().suspended); ledger.bind { true }
                assertNull(ledger.reserve(e.id, absent))
            }
        }
        assertEquals(binding(RoomRoute.MIXED).pin, RoomRekeyBinding(room.roomId, root, owner.devicePubkey, scope,
            listOf("wss://fixture.invalid/")).pin, "Existing mixed pins remain identical")
    }

    @Test fun endpointsRejectMissingExtraWrongScopeAndWrongInternetSelection() = runTest {
        val mesh = RoomMeshTransport(scope, Link()) { 0 }
        val otherMesh = RoomMeshTransport("11".repeat(32), Link()) { 0 }
        val internet = pool(this, FakeSocketFactory())
        try {
            assertFails { NativeKeeperEndpoints(binding(RoomRoute.NEARBY), null, null) }
            assertFails { NativeKeeperEndpoints(binding(RoomRoute.NEARBY), mesh, internet) }
            assertFails { NativeKeeperEndpoints(binding(RoomRoute.MIXED), otherMesh, internet) }
            val changed = RoomRekeyBinding(room.roomId, root, owner.devicePubkey, null,
                listOf("wss://other.invalid/"), RoomRoute.INTERNET)
            assertFails { NativeKeeperEndpoints(changed, null, internet) }
            NativeKeeperEndpoints(binding(RoomRoute.NEARBY), mesh, null)
            NativeKeeperEndpoints(binding(RoomRoute.INTERNET), null, internet)
        } finally { mesh.close(); otherMesh.close(); internet.stop() }
    }

    @Test fun nearbyOriginalAuthorityControlWaitsForResetAndDeniesChatStaleGenerationsAndWithdrawal() = runTest {
        val link = Link(); val mesh = RoomMeshTransport(scope, link) { 0 }; val e = notice()
        val endpoints = NativeKeeperEndpoints(binding(RoomRoute.NEARBY), mesh, null)
        try {
            val old = endpoints.generation(RekeyLane.NEARBY)
            link.reset = CompletableDeferred()
            val barrier = async { mesh.beginRekey() }; runCurrent()
            assertFalse(endpoints.ready(RekeyLane.NEARBY))
            assertFalse(endpoints.offer(e, RekeyLane.NEARBY, mesh.publicationGeneration(), { true }, 5000))
            assertTrue(link.offers.isEmpty()); link.reset!!.complete(Unit); barrier.await()
            assertTrue(endpoints.ready(RekeyLane.NEARBY)); assertFalse(mesh.reachable())
            assertFails { mesh.publish(e) }
            assertFalse(endpoints.offer(e, RekeyLane.NEARBY, old, { true }, 5000))
            assertFalse(endpoints.offer(e, RekeyLane.NEARBY, mesh.publicationGeneration(), { false }, 5000))
            assertFails { endpoints.offer(e.copy(kind = KIND_CHAT), RekeyLane.NEARBY, mesh.publicationGeneration(), { true }, 5000) }
            assertFailsWith<PublicationUnconfirmedException> {
                endpoints.offer(e, RekeyLane.NEARBY, mesh.publicationGeneration(), { true }, 5000)
            }
            assertEquals(1, link.offers.size); assertFalse(mesh.receivedEventConfirmsPublication(e.id))
            mesh.close(); assertFalse(endpoints.ready(RekeyLane.NEARBY))
            assertFalse(endpoints.offer(e, RekeyLane.NEARBY, mesh.publicationGeneration(), { true }, 5000))
        } finally { mesh.close() }
    }

    @Test fun actualRelayPoolConfirmsOnlyTheSelectedOriginalControlWhileOrdinaryPublicationIsBlocked() = runTest {
        val sockets = FakeSocketFactory(); val internet = pool(this, sockets); val e = notice(closed = true)
        val endpoints = NativeKeeperEndpoints(binding(RoomRoute.INTERNET), null, internet)
        try {
            internet.start(); runCurrent(); sockets.openAll(); runCurrent()
            val old = internet.publicationGeneration(); internet.beginRekey()
            assertTrue(endpoints.ready(RekeyLane.INTERNET))
            assertFails { internet.publish(e) }
            assertFails { endpoints.offer(e, RekeyLane.INTERNET, old, { true }, 5000) }
            assertFails { endpoints.offer(e, RekeyLane.INTERNET, internet.publicationGeneration(), { false }, 5000) }
            assertFails { endpoints.offer(e.copy(kind = KIND_CHAT), RekeyLane.INTERNET, internet.publicationGeneration(), { true }, 5000) }
            val send = async { endpoints.offer(e, RekeyLane.INTERNET, internet.publicationGeneration(), { true }, 5000) }
            runCurrent(); assertEquals(1, sockets.opened.single().publishedFrames().size)
            sockets.opened.single().deliverOk(e.id, true); runCurrent(); assertTrue(send.await())
            assertFails { internet.publish(e) }
        } finally { internet.stop() }
    }

    @Test fun stoppedAndReopenedRelayPoolCannotReleaseAnOldControlGeneration() = runTest {
        val sockets = FakeSocketFactory(); val internet = pool(this, sockets); val e = notice()
        try {
            internet.start(); runCurrent(); sockets.openAll(); runCurrent()
            val old = internet.publicationGeneration(); internet.stop(); runCurrent()
            assertFalse(internet.keeperControlReady()); assertTrue(internet.publicationGeneration() > old)
            internet.start(); runCurrent(); sockets.openAll(); runCurrent()
            assertFails { internet.publishKeeperControlGuarded(e, old, { true }, 5000) }
            assertTrue(sockets.opened.all { it.publishedFrames().isEmpty() })
        } finally { internet.stop() }
    }

    @Test fun authorityCourierUsesExactlyEachSelectedModeAndRetriesTheOriginalClosingNoticeUnderTheChatBarrier() = runTest {
        for (route in RoomRoute.entries) {
            val sockets = FakeSocketFactory(); val internet = if (route.internet) pool(this, sockets) else null
            val link = Link(); val mesh = if (route.nearby) RoomMeshTransport(scope, link) { currentTime / 1000 } else null
            val b = binding(route); val store = Store(); val ledger = RoomRekeyLedger(store, b, { currentTime }, true)
            var selected = true
            var courier: RoomRekeyCourier? = null
            try {
                internet?.start(); runCurrent(); sockets.openAll(); runCurrent()
                internet?.beginRekey(); mesh?.beginRekey()
                val endpoints = NativeKeeperEndpoints(b, mesh, internet)
                courier = RoomRekeyCourier.startAuthority(ledger, endpoints, backgroundScope, { selected }, StandardTestDispatcher(testScheduler))
                val e = notice(closed = true); courier.admit(e); runCurrent()
                if (internet != null) { sockets.opened.single().deliverOk(e.id, true); runCurrent() }
                val first = ledger.status(); val row = first.entries.single()
                assertEquals(if (route.nearby) RekeyLaneState.OFFERED else RekeyLaneState.WAITING, row.nearby.state)
                assertEquals(if (route.internet) RekeyLaneState.ACCEPTED else RekeyLaneState.WAITING, row.internet.state)
                assertEquals(if (route.nearby) 1 else 0, link.offers.size)
                assertEquals(if (route.internet) 1 else 0, sockets.opened.sumOf { it.publishedFrames().size })
                selected = false; advanceTimeBy(5000); runCurrent(); courier.pump(); runCurrent()
                assertEquals(first.copy(suspended = true, high = currentTime), ledger.status())
                selected = true; courier.pump(); runCurrent()
                if (route.nearby) {
                    assertEquals(2, link.offers.size)
                    val ids = link.offers.map { RoomMeshWire.decode(it)!!.second.getValue("event").jsonObject.let(NostrEvent::fromJson).id }
                    assertEquals(listOf(e.id, e.id), ids)
                    assertEquals(first.nearbyBytes * 2, ledger.status().nearbyBytes)
                }
                println("NATIVE_KEEPER_ENDPOINT_MEASUREMENT " + buildJsonObject {
                    put("case", "closing-courier-selected-mode"); put("route", route.stored); put("noticeId", e.id)
                    put("nearbyAttempts", ledger.status().entries.single().nearby.attempts)
                    put("internetAttempts", ledger.status().entries.single().internet.attempts)
                    put("internetAccepted", ledger.status().entries.single().internet.state == RekeyLaneState.ACCEPTED)
                    put("nearbyReceipt", false); put("ordinaryChatBlocked", true)
                })
            } finally { courier?.close() ?: ledger.close(); mesh?.close(); internet?.stop() }
        }
    }

    @Test fun localSourceAdoptsItsOriginalThroughTheActualReceiverWithoutAnyInternetEchoAndKeepsPendingOnFailure() = runTest {
        val creation = NativeKeeperCreation.fresh(0); val secret = creation.roomSecret(); val derived = deriveRoom(secret)
        val owner = Fixtures.primary(derived, 5, 6)
        val sourceBinding = NativeKeeperBinding(derived.roomId, creation.authority, owner.participant, owner.devicePubkey,
            RoomRoute.INTERNET, listOf("wss://fixture.invalid/"))
        val sourceStore = Store(); val receiverStore = Store(); val vault = EpochVault(receiverStore)
        vault.initialise(derived.roomId, creation.authority, secret, 0)
        val sockets = FakeSocketFactory(); val internet = pool(this, sockets)
        val live = session(derived, owner, FakeRelay(), authority = creation.authority, transport = internet,
            epochGate = { event, notice -> assertNotNull(vault.follow(derived.roomId, notice, event.id, 0)); EpochGateResult.COMMITTED })
        val journal = NativeKeeperJournal.create(sourceStore, sourceBinding, creation, owner.credential) { 0 }
        val queueBinding = RoomRekeyBinding(derived.roomId, sourceBinding.authority, owner.devicePubkey, null,
            sourceBinding.relays, RoomRoute.INTERNET)
        val queue = RoomRekeyLedger(Store(), queueBinding, { currentTime }, true)
        var courier: RoomRekeyCourier? = null
        try {
            internet.start(); runCurrent(); sockets.openAll(); runCurrent(); live.join(); runCurrent()
            journal.bind { true }
            courier = RoomRekeyCourier.startAuthority(queue, NativeKeeperEndpoints(queueBinding, null, internet),
                backgroundScope, { true }, StandardTestDispatcher(testScheduler))
            live.holdKeeperTransition(derived.roomId, sourceBinding.authority, owner.participant, owner.devicePubkey)
            assertFails { live.sendChat("Old chat cannot leave during the source transaction") }
            val original = journal.prepareRekey(listOf(owner.credential)).single()
            courier.admit(original); journal.queued(original)
            // The fake socket sends no EVENT echoes. Adoption cannot depend on the courier/network.
            receiverStore.fail = true
            assertFails { live.applyKeeperRekey(original, owner.participant, owner.devicePubkey) }
            assertEquals(listOf(original), journal.snapshot().pending); assertEquals(0, vault.get(derived.roomId)!!.currentEpoch)
            assertFails { live.sendChat("A failed receiver keeps chat held") }
            receiverStore.fail = false
            live.applyKeeperRekey(original, owner.participant, owner.devicePubkey)
            assertEquals(1, live.epochKeys().epoch); assertEquals(original.id, vault.get(derived.roomId)!!.activationCause)
            assertTrue(journal.completePending(vault, live)); assertEquals(1, journal.epoch().epoch)
            assertTrue(journal.snapshot().pending.isEmpty())
            live.sendChat("Fresh local chat after the receiver barrier"); runCurrent()
            val rootFrames = sockets.opened.single().publishedFrames().count { it.contains(original.id) }
            assertEquals(1, rootFrames); sockets.opened.single().deliverOk(original.id, true); runCurrent()
            assertEquals(RekeyLaneState.ACCEPTED, queue.status().entries.single().internet.state)
            println("NATIVE_KEEPER_ENDPOINT_MEASUREMENT " + buildJsonObject {
                put("case", "local-original-no-relay-echo"); put("noticeId", original.id)
                put("sourceEpoch", journal.epoch().epoch); put("receiverEpoch", live.epochKeys().epoch)
                put("receiverCause", vault.get(derived.roomId)!!.activationCause); put("internetEchoes", 0)
                put("receiverWriteFailureRefused", true); put("originalInternetOffers", rootFrames)
            })
        } finally { courier?.close() ?: queue.close(); journal.close(); live.leave(); internet.stop(); secret.fill(0) }
    }

    @Test fun originalClosureAndRetirementFinishOnTheSelectedControlPathAfterTheLocalSessionStops() = runTest {
        val creation = NativeKeeperCreation.fresh(0); val secret = creation.roomSecret(); val derived = deriveRoom(secret)
        val owner = Fixtures.primary(derived, 5, 6)
        val sourceBinding = NativeKeeperBinding(derived.roomId, creation.authority, owner.participant, owner.devicePubkey,
            RoomRoute.INTERNET, listOf("wss://fixture.invalid/"))
        val vault = EpochVault(Store()); vault.initialise(derived.roomId, creation.authority, secret, 0)
        val sockets = FakeSocketFactory(); val internet = pool(this, sockets)
        val live = session(derived, owner, FakeRelay(), authority = creation.authority, transport = internet,
            epochGate = { event, notice -> vault.terminal(derived.roomId, 0, notice, event.id, 0); EpochGateResult.COMMITTED })
        val journal = NativeKeeperJournal.create(Store(), sourceBinding, creation, owner.credential) { 0 }
        val queueBinding = RoomRekeyBinding(derived.roomId, creation.authority, owner.devicePubkey, null,
            sourceBinding.relays, RoomRoute.INTERNET)
        val queue = RoomRekeyLedger(Store(), queueBinding, { currentTime }, true)
        val endpoints = NativeKeeperEndpoints(queueBinding, null, internet)
        var courier: RoomRekeyCourier? = null
        try {
            internet.start(); runCurrent(); sockets.openAll(); runCurrent(); live.join(); runCurrent(); journal.bind { true }
            courier = RoomRekeyCourier.startAuthority(queue, endpoints, backgroundScope, { true }, StandardTestDispatcher(testScheduler))
            live.holdKeeperTransition(derived.roomId, creation.authority, owner.participant, owner.devicePubkey)
            val originals = journal.prepareRekey(emptyList(), closed = true)
            val rootNotice = originals.single { it.kind == KIND_ROOM_REKEY }
            val retirement = originals.single { it.kind == KIND_INVITATION_RETIREMENT }
            courier.admit(rootNotice); journal.queued(rootNotice)
            live.applyKeeperRekey(rootNotice, owner.participant, owner.devicePubkey)
            assertEquals(RoomEpochState.Closed(1), live.epochState.value)
            assertFails { live.sendChat("Closed rooms cannot chat") }
            assertFails { journal.completePending(vault, live) }
            val send = assertNotNull(journal.reservePending(retirement.id, RekeyLane.INTERNET))
            val offer = async { endpoints.offer(send.event, send.lane, endpoints.generation(send.lane),
                { journal.canHandoff(send) }, 5000) }
            runCurrent()
            val socket = sockets.opened.single()
            assertEquals(1, socket.publishedFrames().count { it.contains(retirement.id) })
            assertEquals(1, socket.publishedFrames().count { it.contains(rootNotice.id) })
            socket.deliverOk(retirement.id, true); socket.deliverOk(rootNotice.id, true); runCurrent()
            assertTrue(offer.await()); journal.offered(send)
            assertTrue(journal.completePending(vault, live)); assertEquals(KeeperPhase.CLOSED, journal.snapshot().phase)
            assertEquals(rootNotice.id, vault.get(derived.roomId)!!.terminalCause)
            assertEquals(RekeyLaneState.ACCEPTED, queue.status().entries.single().internet.state)
            assertFails { internet.publish(rootNotice) }
            println("NATIVE_KEEPER_ENDPOINT_MEASUREMENT " + buildJsonObject {
                put("case", "closed-local-session-original-control"); put("rootNoticeId", rootNotice.id)
                put("retirementId", retirement.id); put("sourcePhase", journal.snapshot().phase.name)
                put("receiverEpoch", vault.get(derived.roomId)!!.currentEpoch); put("liveClosedEpoch", 1)
                put("ordinaryChatBlocked", true); put("participantReceipt", false)
            })
        } finally { courier?.close() ?: queue.close(); journal.close(); live.leave(); internet.stop(); secret.fill(0) }
    }

}
