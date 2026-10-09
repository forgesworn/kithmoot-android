package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.encodeJoinUrl
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.protocol.RoomEpoch
import dev.forgesworn.kithmoot.protocol.deriveEpoch
import dev.forgesworn.kithmoot.protocol.encodeRekeyEvent
import dev.forgesworn.kithmoot.relay.*
import dev.forgesworn.kithmoot.storage.RoomStorage
import dev.forgesworn.kithmoot.storage.SavedRoom
import dev.forgesworn.kithmoot.support.FakeRelay
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.flow.filter
import kotlin.test.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeoutException

@OptIn(ExperimentalCoroutinesApi::class)
class RoomChatForwarderTest {
    @Test fun `a transport dispatch guard defers instead of waiting for an announcing session`() = runTest {
        val room = Fixtures.room()
        val owner = Fixtures.primary(room, 5, 6)
        val peer = Fixtures.primary(room, 3, 4)
        val relay = FakeRelay()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pause = AtomicBoolean(false)
        val live = RoomSession(room, owner, relay.transport(), backgroundScope, timing = Fixtures.QUIET,
            now = {
                if (pause.compareAndSet(true, false)) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
                0
            })
        val binding = RoomForwardingBinding(room.roomId, owner.participant, owner.devicePubkey,
            RoomNearbyDiscovery.scope(room.roomId), listOf("wss://fixture.invalid/"), setOf(peer.participant))
        val event = encodeChatEvent("guarded original", peer.participant, peer.credential,
            room.roomId, room.roomKey, peer.deviceSecretKey, 0)
        val workers = Executors.newFixedThreadPool(2) { task -> Thread(task, "authority-lock-regression").apply { isDaemon = true } }
        try {
            live.join()
            assertEquals(ForwardingVerdict.CURRENT, live.forwardingVerdict(event, binding, 0))
            pause.set(true)
            val announce = workers.submit { live.announce() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val guard = workers.submit<ForwardingVerdict> { live.forwardingVerdict(event, binding, 0, waitForState = false) }
            // The transport may already hold its own dispatch lock. Waiting for
            // an announcement's state lock here would invert that lock order.
            assertEquals(ForwardingVerdict.WAITING, guard.get(2, TimeUnit.SECONDS))
            val checking = CountDownLatch(1)
            val observation = workers.submit<ForwardingVerdict> {
                checking.countDown()
                live.forwardingVerdict(event, binding, 0)
            }
            assertTrue(checking.await(5, TimeUnit.SECONDS))
            assertFailsWith<TimeoutException> { observation.get(100, TimeUnit.MILLISECONDS) }
            release.countDown()
            announce.get(5, TimeUnit.SECONDS)
            assertEquals(ForwardingVerdict.CURRENT, observation.get(5, TimeUnit.SECONDS),
                "An ordinary incoming message waits for valid authority rather than being refused on contention")
            assertEquals(ForwardingVerdict.CURRENT, live.forwardingVerdict(event, binding, 0))
        } finally {
            release.countDown()
            workers.shutdown()
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
            live.leave()
        }
    }

    private class Store : RoomStorage {
        var value: ByteArray? = null
        var onWrite: (() -> Unit)? = null
        override fun read() = value?.clone()
        override fun write(value: ByteArray) { this.value = value.clone(); onWrite?.invoke() }
        override fun reset() { value = null }
    }
    private class Bus {
        val links = mutableListOf<Link>()
        inner class Link : RoomMeshLink {
            var listener: ((ByteArray, String) -> Unit)? = null
            val offered = mutableListOf<ByteArray>()
            override fun subscribe(receive: (ByteArray, String) -> Unit): AutoCloseable {
                listener = receive; return AutoCloseable { listener = null }
            }
            override fun offer(bytes: ByteArray, to: String?) {
                offered += bytes.clone()
                links.filter { it !== this }.forEach { it.listener?.invoke(bytes.clone(), "fixture-peer") }
            }
            override suspend fun resetQueued() = Unit
            override fun reachable() = true
            override fun close() { listener = null }
        }
        fun link() = Link().also { links += it }
    }
    private inner class Rig(val test: TestScope, removed: List<String> = emptyList(), rekey: Boolean = false) {
        val room = Fixtures.room()
        val aliceIdentity = Fixtures.primary(room, 1, 2)
        val bobIdentity = Fixtures.primary(room, 3, 4)
        val ownerIdentity = Fixtures.primary(room, 5, 6)
        val bus = Bus()
        val scopeId = RoomNearbyDiscovery.scope(room.roomId)
        val aliceMesh = RoomMeshTransport(scopeId, bus.link()) { test.currentTime / 1000 }
        val ownerMesh = RoomMeshTransport(scopeId, bus.link()) { test.currentTime / 1000 }
        val relay = FakeRelay()
        var loseOk = false
        var muteOwnerEcho = false
        val internet = object : RoomTransport by relay.transport() {
            override fun describe() = listOf("wss://fixture.invalid/")
            override fun subscribe(filters: List<Filter>) = relay.transport().subscribe(filters)
                .filter { !muteOwnerEcho || it.kind != KIND_CHAT }
            override suspend fun publishConfirmedGuarded(event: NostrEvent, generation: Long,
                stillAllowed: () -> Boolean, timeoutMs: Long): Boolean {
                if (!loseOk) return relay.transport().publishConfirmedGuarded(event, generation, stillAllowed, timeoutMs)
                check(generation == publicationGeneration() && stillAllowed())
                relay.publish(event)
                throw PublicationUnconfirmedException()
            }
        }
        val authoritySecret = Fixtures.key(41)
        val authority = if (rekey) Schnorr.publicKeyHex(authoritySecret) else null
        val alice = test.session(room, aliceIdentity, FakeRelay(), transport = aliceMesh, authority = authority,
            epochGate = if (rekey) { _, _ -> EpochGateResult.COMMITTED } else null)
        val bob = test.session(room, bobIdentity, relay, authority = authority,
            epochGate = if (rekey) { _, _ -> EpochGateResult.COMMITTED } else null)
        val hybrid = HybridRoomTransport(ownerMesh, internet) { test.currentTime / 1000 }
        val owner = test.session(room, ownerIdentity, FakeRelay(),
            transport = hybrid, initialRemoved = removed, authority = authority,
            epochGate = if (rekey) { _, _ -> EpochGateResult.COMMITTED } else null)
        val store = Store()
        val consents = LinkConsentVault(Store())
        var selected = true
        val saved = SavedRoom.create(ByteArray(32) { 7 }, ownerIdentity,
            encodeJoinUrl("https://fixture.invalid/j/", ByteArray(32) { 7 }, internet.describe()),
            internet.describe(), "Private fixture", 0, null, null, route = RoomRoute.MIXED)
        val binding = RoomForwardingBinding(room.roomId, ownerIdentity.participant, ownerIdentity.devicePubkey,
            scopeId, internet.describe(), setOf(aliceIdentity.participant, bobIdentity.participant))
        suspend fun join() { alice.join(); bob.join(); owner.join(); test.runCurrent() }
        fun start(initialise: Boolean = true): Pair<RoomChatForwarder, RoomForwardingLedger> {
            val ledger = RoomForwardingLedger(store, binding, { test.currentTime }, initialise)
            val forwarder = RoomChatForwarder.start(saved, consents, owner, ownerMesh, internet,
                ledger, test.backgroundScope, { selected }, StandardTestDispatcher(test.testScheduler))
            return forwarder to ledger
        }
        fun close() { alice.leave(); bob.leave(); owner.leave(); aliceMesh.close(); ownerMesh.close() }
    }

    @Test fun `disabled sharing keeps actual nearby and internet participants separate`() = runTest {
        val rig = Rig(this); rig.join()
        rig.alice.sendChat("Only nearby"); runCurrent()
        assertEquals(listOf("Only nearby"), rig.owner.chat.value.map { it.body })
        assertTrue(rig.bob.chat.value.isEmpty()); assertEquals(0, rig.relay.countOfKind(1460))
        rig.close()
    }

    @Test fun `an empty saved selection cannot start an exporting owner`() = runTest {
        val rig = Rig(this); rig.join()
        val empty = RoomForwardingBinding(rig.binding.room, rig.binding.participant, rig.binding.device,
            rig.binding.meshScope, rig.binding.relays, emptySet())
        RoomForwardingLedger(rig.store, empty, { currentTime }, true).use { ledger ->
            assertFailsWith<IllegalArgumentException> {
                RoomChatForwarder.start(rig.saved, rig.consents, rig.owner, rig.ownerMesh, rig.internet,
                    ledger, backgroundScope, { true }, StandardTestDispatcher(testScheduler))
            }
            assertTrue(ledger.status().suspended)
            rig.alice.sendChat("No approved people"); runCurrent()
            assertTrue(rig.bob.chat.value.isEmpty())
        }
        rig.close()
    }

    @Test fun `explicit owner carries original signed chat both ways across disjoint RoomSessions`() = runTest {
        val rig = Rig(this); rig.join(); val (forwarder, ledger) = rig.start(); runCurrent()
        rig.alice.sendChat("From nearby"); runCurrent(); forwarder.pump(); runCurrent()
        rig.bob.sendChat("From internet"); runCurrent(); forwarder.pump(); runCurrent()
        val texts = listOf("From nearby", "From internet")
        assertEquals(texts, rig.alice.chat.value.map { it.body }); assertEquals(texts, rig.bob.chat.value.map { it.body })
        assertEquals(texts, rig.owner.chat.value.map { it.body })
        val first = rig.relay.published.first { it.kind == KIND_CHAT }
        assertEquals(first, ledger.status().entries.first().event)
        assertEquals(1, rig.relay.published.count { it.id == first.id })
        rig.relay.publish(first); runCurrent(); forwarder.pump(); runCurrent()
        assertEquals(2, rig.alice.chat.value.size); assertEquals(2, rig.bob.chat.value.size)
        val reverse = ledger.status().entries.last()
        assertEquals(ForwardingLaneState.SEEN, reverse.internet.state)
        assertEquals(ForwardingLaneState.OFFERED, reverse.nearby.state)
        assertFalse(rig.ownerMesh.receivedEventConfirmsPublication(reverse.event.id))
        forwarder.close(); rig.close()
    }

    @Test fun `outage queue survives owner reopen and exports only the original event`() = runTest {
        val rig = Rig(this); rig.join(); rig.relay.reachable = false
        val (first, ledger) = rig.start(); runCurrent()
        rig.alice.sendChat("Wait for internet"); runCurrent(); first.pump(); runCurrent()
        val retained = ledger.status().entries.single().event
        assertTrue(rig.bob.chat.value.isEmpty()); first.close(); runCurrent()
        rig.relay.reachable = true
        val (second, restored) = rig.start(initialise = false); runCurrent(); second.pump(); runCurrent()
        assertEquals(listOf("Wait for internet"), rig.bob.chat.value.map { it.body })
        assertEquals(retained, restored.status().entries.single().event)
        assertEquals(listOf(retained), rig.relay.published.filter { it.kind == KIND_CHAT })
        second.close(); rig.close()
    }

    @Test fun `foreground withdrawal during durable reservation stops actual handoff`() = runTest {
        val rig = Rig(this); rig.join(); val (forwarder, ledger) = rig.start(); runCurrent()
        rig.alice.sendChat("Stay queued"); runCurrent()
        rig.store.onWrite = {
            if (rig.store.value!!.toString(Charsets.UTF_8).contains("UNKNOWN")) rig.selected = false
        }
        forwarder.pump(); runCurrent()
        assertEquals(ForwardingLaneState.UNKNOWN, ledger.status().entries.single().internet.state)
        assertTrue(rig.bob.chat.value.isEmpty()); assertEquals(0, rig.relay.countOfKind(KIND_CHAT))
        forwarder.close(); rig.close()
    }

    @Test fun `leaving the live session strands queued exports despite a saved sharing identity`() = runTest {
        val rig = Rig(this); rig.join(); rig.relay.reachable = false
        val (forwarder, ledger) = rig.start(); runCurrent()
        rig.alice.sendChat("No export after leaving"); runCurrent()
        rig.owner.leave(); rig.relay.reachable = true; forwarder.pump(); runCurrent()
        assertTrue(ledger.status().entries.single().moved)
        assertTrue(rig.bob.chat.value.isEmpty()); forwarder.close(); rig.close()
    }

    @Test fun `saved internet mode and mismatched relay ownership refuse sharing before subscriptions`() = runTest {
        val rig = Rig(this); rig.join()
        RoomForwardingLedger(rig.store, rig.binding, { currentTime }, true).use { ledger ->
            assertFailsWith<IllegalArgumentException> {
                RoomChatForwarder.start(rig.saved.withRoute(RoomRoute.INTERNET), rig.consents, rig.owner,
                    rig.ownerMesh, rig.internet, ledger, backgroundScope, { true }, StandardTestDispatcher(testScheduler))
            }
            val wrong = object : RoomTransport by rig.internet { override fun describe() = listOf("wss://other.invalid/") }
            assertFailsWith<IllegalArgumentException> {
                RoomChatForwarder.start(rig.saved, rig.consents, rig.owner, rig.ownerMesh, wrong,
                    ledger, backgroundScope, { true }, StandardTestDispatcher(testScheduler))
            }
            assertTrue(ledger.status().suspended)
        }
        rig.close()
    }

    @Test fun `lost relay OK across owner reopen retries the original event without another room row`() = runTest {
        val rig = Rig(this); rig.join(); rig.loseOk = true; rig.muteOwnerEcho = true
        val (first, ledger) = rig.start(); runCurrent()
        rig.alice.sendChat("An uncertain relay handoff"); runCurrent(); first.pump(); runCurrent()
        val original = ledger.status().entries.single().event
        assertEquals(ForwardingLaneState.UNKNOWN, ledger.status().entries.single().internet.state)
        assertEquals(1, rig.bob.chat.value.size); first.close(); runCurrent()
        rig.loseOk = false
        val (second, restored) = rig.start(initialise = false); runCurrent()
        assertEquals(ForwardingLaneState.UNKNOWN, restored.status().entries.single().internet.state)
        advanceTimeBy(5001); runCurrent(); second.pump(); runCurrent()
        assertEquals(listOf(original, original), rig.relay.published.filter { it.kind == KIND_CHAT })
        assertEquals(1, rig.bob.chat.value.size)
        assertEquals(ForwardingLaneState.ACCEPTED, restored.status().entries.single().internet.state)
        second.close(); rig.close()
    }

    @Test fun `overlapping owners preserve one chat row with bounded identical signed exports`() = runTest {
        val rig = Rig(this); rig.join()
        val identity = Fixtures.primary(rig.room, 7, 8)
        val mesh = RoomMeshTransport(rig.scopeId, rig.bus.link()) { currentTime / 1000 }
        val session = session(rig.room, identity, FakeRelay(), transport = HybridRoomTransport(mesh, rig.internet))
        session.join(); runCurrent()
        val saved = SavedRoom.create(ByteArray(32) { 7 }, identity, rig.saved.joinUrl, rig.internet.describe(),
            "Second explicit owner", 0, null, null, route = RoomRoute.MIXED)
        val binding = RoomForwardingBinding(rig.room.roomId, identity.participant, identity.devicePubkey,
            rig.scopeId, rig.internet.describe(), rig.binding.senders)
        val ledger = RoomForwardingLedger(Store(), binding, { currentTime }, true)
        val second = RoomChatForwarder.start(saved, rig.consents, session, mesh, rig.internet, ledger,
            backgroundScope, { true }, StandardTestDispatcher(testScheduler))
        val (first, _) = rig.start(); runCurrent()
        rig.alice.sendChat("Two authorised carriers"); runCurrent()
        val a = async { first.pump() }; val b = async { second.pump() }; a.await(); b.await(); runCurrent()
        rig.bob.sendChat("Still one conversation"); runCurrent(); first.pump(); second.pump(); runCurrent()
        val expected = listOf("Two authorised carriers", "Still one conversation")
        for (member in listOf(rig.alice, rig.bob, rig.owner, session)) assertEquals(expected, member.chat.value.map { it.body })
        val event = rig.relay.published.first { it.kind == KIND_CHAT }
        assertTrue(rig.relay.published.count { it.id == event.id } in 1..2)
        first.close(); second.close(); session.leave(); mesh.close(); rig.close()
    }

    @Test fun `current removal floor refuses a valid chat from an approved removed author`() = runTest {
        val author = Fixtures.primary(Fixtures.room(), 1, 2).participant
        val rig = Rig(this, removed = listOf(author)); rig.join()
        val (forwarder, ledger) = rig.start(); runCurrent()
        rig.alice.sendChat("Removed author"); runCurrent(); forwarder.pump(); runCurrent()
        assertTrue(ledger.status().entries.isEmpty()); assertTrue(rig.bob.chat.value.isEmpty())
        forwarder.close(); rig.close()
    }

    @Test fun `a valid room member outside the explicit author list cannot acquire a forwarding record`() = runTest {
        val rig = Rig(this); rig.join(); val (forwarder, ledger) = rig.start(); runCurrent()
        val identity = Fixtures.primary(rig.room, 9, 10)
        val mesh = RoomMeshTransport(rig.scopeId, rig.bus.link()) { currentTime / 1000 }
        val extra = session(rig.room, identity, FakeRelay(), transport = mesh)
        extra.join(); runCurrent(); extra.sendChat("Not approved for export"); runCurrent(); forwarder.pump(); runCurrent()
        assertEquals(listOf("Not approved for export"), rig.owner.chat.value.map { it.body })
        assertTrue(ledger.status().entries.isEmpty()); assertTrue(rig.bob.chat.value.isEmpty())
        forwarder.close(); extra.leave(); mesh.close(); rig.close()
    }

    @Test fun `any Bothy consent refuses sharing without changing the suspended ledger`() = runTest {
        val rig = Rig(this); rig.join()
        rig.consents.put(LinkConsent(rig.ownerIdentity.participant, rig.room.roomId, "aa".repeat(32), "route-1",
            "ws://aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaq/events", rig.internet.describe(), LinkConsentState.PENDING))
        RoomForwardingLedger(rig.store, rig.binding, { currentTime }, true).use { ledger ->
            assertFailsWith<IllegalArgumentException> {
                RoomChatForwarder.start(rig.saved, rig.consents, rig.owner, rig.ownerMesh, rig.internet,
                    ledger, backgroundScope, { true }, StandardTestDispatcher(testScheduler))
            }
            assertTrue(ledger.status().suspended); assertTrue(ledger.status().entries.isEmpty())
        }
        rig.close()
    }

    @Test fun `a real committed rekey strands old ciphertext and renews current chat subscriptions`() = runTest {
        val rig = Rig(this, rekey = true); rig.join(); rig.relay.reachable = false
        val (forwarder, ledger) = rig.start(); runCurrent()
        rig.alice.sendChat("Before the key change"); runCurrent(); forwarder.pump(); runCurrent()
        val old = ledger.status().entries.single().event
        advanceTimeBy(1000); runCurrent()
        val next = RoomEpoch(1, ByteArray(32) { 44 })
        val notice = encodeRekeyEvent(rig.room.roomId, rig.authoritySecret,
            deriveEpoch(RoomEpoch(0, ByteArray(32) { 7 })), next,
            listOf(rig.aliceIdentity.devicePubkey, rig.bobIdentity.devicePubkey, rig.ownerIdentity.devicePubkey),
            emptyList(), 1, commit = true)
        // The qualified keeper originates its own notice on both lanes. The
        // sharing owner never forwards an arbitrary received control event.
        rig.hybrid.publish(notice); runCurrent()
        assertEquals(1, rig.owner.epochKeys().epoch); assertEquals(1, rig.alice.epochKeys().epoch)
        assertEquals(1, rig.bob.epochKeys().epoch)
        rig.relay.reachable = true; forwarder.pump(); runCurrent()
        assertTrue(ledger.status().entries.single().moved)
        assertTrue(rig.relay.published.none { it.id == old.id })
        rig.alice.sendChat("After the key change"); runCurrent(); forwarder.pump(); runCurrent()
        assertEquals(listOf("After the key change"), rig.bob.chat.value.map { it.body })
        assertEquals(2, ledger.status().entries.size)
        assertTrue(ledger.status().entries.all { it.event.kind == KIND_CHAT })
        forwarder.close(); rig.close()
    }
}
