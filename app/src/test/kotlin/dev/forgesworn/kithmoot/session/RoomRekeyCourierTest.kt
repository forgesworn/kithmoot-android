package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.epoch.*
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.*
import dev.forgesworn.kithmoot.storage.RoomStorage
import dev.forgesworn.kithmoot.storage.SavedRoom
import dev.forgesworn.kithmoot.support.FakeRelay
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class RoomRekeyCourierTest {
    private class Store : RoomStorage {
        var bytes: ByteArray? = null
        var fail = false
        var onWrite: (() -> Unit)? = null
        override fun read() = bytes?.clone()
        override fun write(value: ByteArray) {
            if (fail) error("disk full")
            bytes = value.clone(); onWrite?.invoke()
        }
        override fun reset() = error("Cannot reset courier retry debt")
    }
    private class Bus {
        val links = mutableListOf<Link>()
        inner class Link : RoomMeshLink {
            var listener: ((ByteArray, String) -> Unit)? = null
            var closed = false
            val offers = mutableListOf<ByteArray>()
            override fun subscribe(receive: (ByteArray, String) -> Unit): AutoCloseable {
                listener = receive; return AutoCloseable { listener = null }
            }
            override fun offer(bytes: ByteArray, to: String?) {
                check(!closed); offers += bytes.clone()
                links.filter { it !== this }.forEach { it.listener?.invoke(bytes.clone(), "fixture-peer") }
            }
            override suspend fun resetQueued() = Unit
            override fun reachable() = !closed
            override fun close() { closed = true; listener = null }
        }
        fun link() = Link().also { links += it }
    }
    private inner class Rig(val test: TestScope) : AutoCloseable {
        val room = Fixtures.room()
        val aliceIdentity = Fixtures.primary(room, 1, 2)
        val bobIdentity = Fixtures.primary(room, 3, 4)
        val ownerIdentity = Fixtures.primary(room, 5, 6)
        val authoritySecret = Fixtures.key(41)
        val authority = Schnorr.publicKeyHex(authoritySecret)
        val scopeId = RoomNearbyDiscovery.scope(room.roomId)
        val bus = Bus()
        val aliceLink = bus.link(); val ownerLink = bus.link()
        val aliceMesh = RoomMeshTransport(scopeId, aliceLink) { test.currentTime / 1000 }
        val ownerMesh = RoomMeshTransport(scopeId, ownerLink) { test.currentTime / 1000 }
        val relay = FakeRelay()
        var loseOk = false
        var generationOffset = 0L
        var beforeDispatch: (() -> Unit)? = null
        val internet = object : RoomTransport by relay.transport() {
            override fun describe() = listOf("wss://fixture.invalid/")
            override fun publicationGeneration() = relay.transport().publicationGeneration() + generationOffset
            override suspend fun publishConfirmedGuarded(event: NostrEvent, generation: Long,
                stillAllowed: () -> Boolean, timeoutMs: Long): Boolean {
                beforeDispatch?.invoke()
                if (!stillAllowed() || generation != publicationGeneration()) return false
                if (loseOk) { relay.publish(event); throw PublicationUnconfirmedException() }
                return relay.transport().publishConfirmedGuarded(event, generation, stillAllowed, timeoutMs)
            }
        }
        val hybrid = HybridRoomTransport(ownerMesh, internet) { test.currentTime / 1000 }
        val store = Store()
        val binding = RoomRekeyBinding(room.roomId, authority, ownerIdentity.devicePubkey, scopeId, internet.describe())
        var selected = true
        fun open(initialise: Boolean = true) = RoomRekeyLedger(store, binding, { test.currentTime }, initialise)
        fun start(ledger: RoomRekeyLedger, parent: CoroutineScope = test.backgroundScope) =
            RoomRekeyCourier.start(ledger, ownerMesh, internet, parent, { selected }, StandardTestDispatcher(test.testScheduler))
        fun notice(at: Long = test.currentTime / 1000) = encodeRekeyEvent(room.roomId, authoritySecret,
            deriveEpoch(RoomEpoch(0, ByteArray(32) { 7 })), RoomEpoch(1, ByteArray(32) { 44 }),
            listOf(aliceIdentity.devicePubkey, bobIdentity.devicePubkey, ownerIdentity.devicePubkey),
            emptyList(), at, commit = true)
        override fun close() { aliceMesh.close(); ownerMesh.close() }
    }

    @Test fun `a missed real rekey recovers after courier reopen without recipient rejoin and fresh chat crosses both ways`() = runTest {
        val rig = Rig(this)
        val alice = session(rig.room, rig.aliceIdentity, FakeRelay(), transport = rig.aliceMesh, authority = rig.authority,
            epochGate = { _, _ -> EpochGateResult.COMMITTED })
        val bob = session(rig.room, rig.bobIdentity, rig.relay, authority = rig.authority,
            epochGate = { _, _ -> EpochGateResult.COMMITTED })
        val owner = session(rig.room, rig.ownerIdentity, FakeRelay(), transport = rig.hybrid, authority = rig.authority,
            epochGate = { _, _ -> EpochGateResult.COMMITTED })
        val forwardingStore = Store()
        val saved = SavedRoom.create(ByteArray(32) { 7 }, rig.ownerIdentity,
            encodeJoinUrl("https://fixture.invalid/j/", ByteArray(32) { 7 }, rig.internet.describe()),
            rig.internet.describe(), "Private fixture", 0, null, null, route = RoomRoute.MIXED)
        val forwardingBinding = RoomForwardingBinding(rig.room.roomId, rig.ownerIdentity.participant,
            rig.ownerIdentity.devicePubkey, rig.scopeId, rig.internet.describe(),
            setOf(rig.aliceIdentity.participant, rig.bobIdentity.participant))
        val chatLedger = RoomForwardingLedger(forwardingStore, forwardingBinding, { currentTime }, true)
        var courier: RoomRekeyCourier? = null
        var forwarder: RoomChatForwarder? = null
        try {
            alice.join(); bob.join(); owner.join(); runCurrent()
            forwarder = RoomChatForwarder.start(saved, LinkConsentVault(Store()), owner, rig.ownerMesh, rig.internet,
                chatLedger, backgroundScope, { rig.selected }, StandardTestDispatcher(testScheduler))
            rig.relay.reachable = false; runCurrent()
            alice.sendChat("Old ciphertext stays on its epoch"); runCurrent(); forwarder.pump(); runCurrent()
            val old = chatLedger.status().entries.single().event
            val first = rig.open(); courier = rig.start(first)
            val original = rig.notice(); courier.admit(original); runCurrent(); courier.pump(); runCurrent()
            assertEquals(1, alice.epochKeys().epoch); assertEquals(1, owner.epochKeys().epoch)
            assertEquals(0, bob.epochKeys().epoch, "The Internet member actually missed the notice")
            assertEquals(0, rig.relay.countOfKind(KIND_ROOM_REKEY))
            val before = first.status(); assertEquals(RekeyLaneState.OFFERED, before.entries.single().nearby.state)
            assertEquals(RekeyLaneState.WAITING, before.entries.single().internet.state)
            courier.close(); courier = null; runCurrent()
            val restored = rig.open(initialise = false)
            assertEquals(before.copy(suspended = true), restored.status())
            rig.relay.reachable = true
            advanceTimeBy(5000); runCurrent()
            assertEquals(0, bob.epochKeys().epoch); assertEquals(0, rig.relay.countOfKind(KIND_ROOM_REKEY))
            assertEquals(before.nearbyBytes, restored.status().nearbyBytes)
            courier = rig.start(restored); runCurrent(); courier.pump(); runCurrent()
            assertEquals(1, bob.epochKeys().epoch)
            assertEquals(deriveEpoch(RoomEpoch(1, ByteArray(32) { 44 })).id, bob.epochKeys().id)
            assertEquals(listOf(original), rig.relay.published.filter { it.kind == KIND_ROOM_REKEY })
            assertEquals(original, restored.status().entries.single().event)
            assertEquals(before.entries.single().expires, restored.status().entries.single().expires)
            assertEquals(RekeyLaneState.ACCEPTED, restored.status().entries.single().internet.state)
            forwarder.pump(); runCurrent()
            assertTrue(chatLedger.status().entries.first().moved)
            assertTrue(rig.relay.published.none { it.id == old.id })
            alice.sendChat("New epoch from nearby"); runCurrent(); forwarder.pump(); runCurrent()
            bob.sendChat("New epoch from internet"); runCurrent(); forwarder.pump(); runCurrent()
            val fresh = listOf("New epoch from nearby", "New epoch from internet")
            assertEquals(fresh, bob.chat.value.map { it.body })
            for (member in listOf(alice, owner)) assertEquals(fresh, member.chat.value.takeLast(2).map { it.body })
            assertEquals(3, chatLedger.status().entries.size)
            assertTrue(chatLedger.status().entries.all { it.event.kind == KIND_CHAT })
            assertFalse(rig.ownerMesh.receivedEventConfirmsPublication(original.id))
            val after = restored.status()
            println("REKEY_COURIER_MEASUREMENT " + buildJsonObject {
                put("case", "missed-rekey-reopen"); put("noticeId", original.id)
                put("noticeBytes", original.toCompactJson().toByteArray(Charsets.UTF_8).size)
                put("suspendedReopenMs", 5000); put("recipientEpochBefore", 0); put("recipientEpochAfter", bob.epochKeys().epoch)
                put("recipientRejoined", false); put("noticeInternetOffers", rig.relay.countOfKind(KIND_ROOM_REKEY))
                put("nearbyAttemptsBefore", before.entries.single().nearby.attempts)
                put("nearbyAttemptsAfter", after.entries.single().nearby.attempts)
                put("internetAttemptsAfter", after.entries.single().internet.attempts)
                put("nearbyDebtBefore", before.nearbyBytes); put("nearbyDebtAfter", after.nearbyBytes)
                put("internetDebtAfter", after.internetBytes)
                put("originalExpiresBefore", before.entries.single().expires); put("originalExpiresAfter", after.entries.single().expires)
                put("staleInternetOffers", rig.relay.published.count { it.id == old.id })
                put("freshRecipientRows", bob.chat.value.size)
            })
        } finally {
            courier?.close(); forwarder?.close(); chatLedger.close()
            alice.leave(); bob.leave(); owner.leave(); rig.close()
        }
    }

    @Test fun `lost Internet OK retries the identical original after reopen with preserved delay and debt`() = runTest {
        val rig = Rig(this)
        try {
            rig.loseOk = true
            val first = rig.open(); var courier = rig.start(first)
            val original = rig.notice(); courier.admit(original); runCurrent()
            val before = first.status()
            assertEquals(RekeyLaneState.UNKNOWN, before.entries.single().internet.state)
            assertEquals(listOf(original), rig.relay.published)
            courier.close(); runCurrent()
            rig.loseOk = false
            val restored = rig.open(initialise = false); courier = rig.start(restored); runCurrent()
            assertEquals(1, rig.relay.published.size)
            advanceTimeBy(5000); runCurrent(); courier.pump(); runCurrent()
            assertEquals(listOf(original, original), rig.relay.published)
            assertEquals(before.internetBytes * 2, restored.status().internetBytes)
            assertEquals(RekeyLaneState.ACCEPTED, restored.status().entries.single().internet.state)
            assertEquals(RekeyLaneState.OFFERED, restored.status().entries.single().nearby.state)
            println("REKEY_COURIER_MEASUREMENT " + buildJsonObject {
                put("case", "lost-ok-reopen"); put("noticeId", original.id)
                put("internetAttemptsBefore", before.entries.single().internet.attempts)
                put("internetAttemptsAfter", restored.status().entries.single().internet.attempts)
                put("internetDebtBefore", before.internetBytes); put("internetDebtAfter", restored.status().internetBytes)
                put("internetOffers", rig.relay.published.size); put("identicalOriginals", rig.relay.published.all { it == original })
            })
            courier.close()
        } finally { rig.close() }
    }

    @Test fun `withdrawal during the reservation commits debt but sends nothing`() = runTest {
        val rig = Rig(this)
        try {
            val ledger = rig.open(); val courier = rig.start(ledger)
            rig.store.onWrite = {
                if (rig.store.bytes!!.toString(Charsets.UTF_8).contains("UNKNOWN")) rig.selected = false
            }
            courier.admit(rig.notice()); runCurrent()
            assertTrue(rig.ownerLink.offers.isEmpty()); assertTrue(rig.relay.published.isEmpty())
            assertEquals(RekeyLaneState.UNKNOWN, ledger.status().entries.single().nearby.state)
            assertTrue(ledger.status().nearbyBytes > 0); assertEquals(0, ledger.status().internetBytes)
            courier.close()
        } finally { rig.close() }
    }

    @Test fun `changed transport generation at the final dispatch barrier keeps the reservation uncertain`() = runTest {
        val rig = Rig(this)
        try {
            val ledger = rig.open(); val courier = rig.start(ledger)
            val original = rig.notice(); courier.admit(original)
            rig.beforeDispatch = { rig.generationOffset++ }
            runCurrent()
            assertTrue(rig.selected)
            assertTrue(rig.relay.published.isEmpty())
            assertEquals(RekeyLaneState.UNKNOWN, ledger.status().entries.single().internet.state)
            assertTrue(ledger.status().internetBytes > 0)
            courier.close()
        } finally { rig.close() }
    }

    @Test fun `parent cancellation releases journal ownership and preserves caller owned routes`() = runTest {
        val rig = Rig(this)
        try {
            val parentJob = Job()
            val parent = CoroutineScope(backgroundScope.coroutineContext + parentJob)
            val courier = rig.start(rig.open(), parent)
            parentJob.cancel(); runCurrent()
            assertFalse(rig.ownerLink.closed)
            RoomRekeyLedger(rig.store, rig.binding, { currentTime }).use { box -> assertTrue(box.status().suspended) }
            assertFails { courier.admit(rig.notice()) }
            courier.close()
        } finally { rig.close() }
    }

    @Test fun `a second pump owner cannot reuse a live ledger or leak a child job`() = runTest {
        val rig = Rig(this)
        val parentJob = Job()
        val parent = CoroutineScope(backgroundScope.coroutineContext + parentJob)
        try {
            val ledger = rig.open(); val first = rig.start(ledger, parent)
            try {
                assertEquals(1, parentJob.children.count())
                assertFailsWith<IllegalStateException> { rig.start(ledger, parent) }
                assertEquals(1, parentJob.children.count())
                first.admit(rig.notice()); runCurrent()
                assertEquals(1, rig.relay.countOfKind(KIND_ROOM_REKEY))
                assertEquals(1, ledger.status().entries.single().internet.attempts)
            } finally { first.close() }
        } finally { parentJob.cancel(); rig.close() }
    }

    @Test fun `wrong route and inactive owners refuse start before any admission or handoff`() = runTest {
        val rig = Rig(this)
        try {
            rig.open().use { ledger ->
                val otherMesh = RoomMeshTransport("88".repeat(32), rig.bus.link()) { currentTime / 1000 }
                try {
                    assertFailsWith<IllegalArgumentException> {
                        RoomRekeyCourier.start(ledger, otherMesh, rig.internet, backgroundScope, { true })
                    }
                    val wrong = object : RoomTransport by rig.internet { override fun describe() = listOf("wss://other.invalid/") }
                    assertFailsWith<IllegalArgumentException> {
                        RoomRekeyCourier.start(ledger, rig.ownerMesh, wrong, backgroundScope, { true })
                    }
                    assertFailsWith<IllegalArgumentException> { rig.start(ledger, CoroutineScope(Job().apply { cancel() })) }
                    rig.selected = false; assertFailsWith<IllegalArgumentException> { rig.start(ledger) }
                    assertTrue(ledger.status().suspended); assertTrue(rig.ownerLink.offers.isEmpty())
                } finally { otherMesh.close() }
            }
        } finally { rig.close() }
    }

    @Test fun `decorator delegates ordinary traffic and incoming controls but cannot bypass root admission`() = runTest {
        val rig = Rig(this)
        try {
            val ledger = rig.open(); val courier = rig.start(ledger)
            val decorated = courier.transport(rig.internet)
            val incoming = mutableListOf<NostrEvent>()
            val reader = backgroundScope.launch { decorated.subscribe(listOf(Filter(kinds = listOf(KIND_ROOM_REKEY)))).collect { incoming += it } }
            runCurrent()
            val original = rig.notice(); rig.relay.publish(original); runCurrent()
            assertEquals(listOf(original), incoming); assertTrue(ledger.status().entries.isEmpty())
            val ordinary = Events.sign(Fixtures.key(2), KIND_CHAT, 0, emptyList(), "opaque", ByteArray(32))
            decorated.publish(ordinary); assertEquals(ordinary, rig.relay.published.last())
            assertFailsWith<IllegalArgumentException> { decorated.publishConfirmed(original) }
            assertFailsWith<IllegalArgumentException> { decorated.publishConfirmedGuarded(original, 0, { true }) }
            assertFailsWith<IllegalArgumentException> { decorated.publishRecovery(original) }
            assertTrue(ledger.status().entries.isEmpty())
            decorated.publish(original); assertEquals(original, ledger.status().entries.single().event)
            runCurrent(); courier.close(); reader.cancel()
        } finally { rig.close() }
    }

    @Test fun `failed durable admission stops the owner before any outgoing bytes`() = runTest {
        val rig = Rig(this)
        try {
            val courier = rig.start(rig.open()); rig.store.fail = true
            assertFails { courier.admit(rig.notice()) }; runCurrent()
            assertTrue(courier.isFailed()); assertTrue(rig.ownerLink.offers.isEmpty()); assertTrue(rig.relay.published.isEmpty())
            rig.store.fail = false
            rig.open(initialise = false).use { assertTrue(it.status().entries.isEmpty()) }
            courier.close()
        } finally { rig.close() }
    }
}
