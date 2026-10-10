package dev.forgesworn.kithmoot.epoch

import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.*
import dev.forgesworn.kithmoot.session.*
import dev.forgesworn.kithmoot.storage.*
import dev.forgesworn.kithmoot.support.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Test
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class NativeKeeperEntryTest {
    private class Store : RoomStorage {
        var bytes: ByteArray? = null
        var ambiguous = false
        override fun read() = bytes?.clone()
        override fun write(value: ByteArray) { bytes = value.clone(); if (ambiguous) error("Commit return lost") }
        override fun reset() { bytes = null }
    }
    private class Link : RoomMeshLink {
        val offered = mutableListOf<ByteArray>()
        var receive: ((ByteArray, String) -> Unit)? = null
        var onOffer: ((ByteArray) -> Unit)? = null
        override fun subscribe(receive: (ByteArray, String) -> Unit): AutoCloseable {
            this.receive = receive; return AutoCloseable { this.receive = null }
        }
        override fun offer(bytes: ByteArray, to: String?) { offered += bytes.clone(); onOffer?.invoke(bytes.clone()) }
        override fun reachable() = true
        override suspend fun resetQueued() = Unit
        override fun close() = Unit
    }
    private class Rig(val test: TestScope, val route: RoomRoute) {
        val relays = if (route.internet) listOf("wss://fixture.invalid/") else emptyList()
        val at = test.currentTime / 1000
        val creation = NativeKeeperCreation.fresh(at, roomRelays = relays.takeIf { route.internet })
        val base = creation.roomSecret(); val invitation = creation.invitation(); val room = deriveRoom(base)
        val who = PrimaryIdentity.create(room.roomId, at + 100_000, at,
            participantSecretKey = Fixtures.key(5), deviceSecretKey = Fixtures.key(6))
        val binding = NativeKeeperBinding(room.roomId, creation.authority, who.participant, who.devicePubkey, route, relays)
        val sourceStore = Store(); val queueStore = Store(); val receiverStore = Store()
        var vault = EpochVault(receiverStore)
        val saved = NativeKeeperJournal.create(sourceStore, binding, creation, who.credential) { test.currentTime / 1000 }.use { source ->
            SavedRoom.create(base, who, encodeInvitationUrl("https://fixture.invalid/join", invitation, relays), relays,
                "Nearby fixture", at, host = null, authority = binding.authority, route = route).withNativeAuthority(source)
        }
        val initialisations = mutableListOf<Boolean>()
        var source: NativeKeeperJournal? = null
        fun entry() = NativeKeeperEntry.open(saved, vault,
            { NativeKeeperJournal.open(sourceStore, binding) { test.currentTime / 1000 }.also { source = it } },
            { q, initialise -> initialisations += initialise; RoomRekeyLedger(queueStore, q, { test.currentTime }, initialise) })
    }

    @Test fun actualHeldJoinPublishesNoOrdinaryEventsUntilSourceReceiverSessionAgreementInEveryMode() = runTest {
        for (route in RoomRoute.entries) {
            val r = Rig(this, route); val entry = r.entry()
            val link = Link(); val mesh = if (route.nearby) RoomMeshTransport(requireNotNull(r.binding.meshScope), link) { currentTime / 1000 } else null
            val sockets = FakeSocketFactory()
            val pool = if (route.internet) RelayPool(r.relays, sockets, backgroundScope, now = { currentTime }) else null
            val transport: RoomTransport = when (route) {
                RoomRoute.NEARBY -> requireNotNull(mesh)
                RoomRoute.INTERNET -> requireNotNull(pool)
                RoomRoute.MIXED -> HybridRoomTransport(requireNotNull(mesh), requireNotNull(pool))
            }
            val live = session(r.room, r.who, FakeRelay(), authority = r.binding.authority, transport = transport,
                epochGate = { _, _ -> EpochGateResult.COMMITTED }, epochProbe = true)
            try {
                pool?.start(); runCurrent(); sockets.openAll(); runCurrent()
                live.holdKeeperStartup(); live.join(); runCurrent()
                assertEquals(0, link.offered.count { RoomMeshWire.decode(it)?.first == RoomMeshWire.EVENT })
                assertTrue(link.offered.all { RoomMeshWire.decode(it)?.first == RoomMeshWire.QUERY })
                assertTrue(sockets.opened.all { it.publishedFrames().isEmpty() })
                assertFails { live.announce() }
                assertFails { live.sendChat("held before verification") }
                val q = RoomRekeyBinding(r.binding.room, r.binding.authority, r.binding.device, r.binding.meshScope, r.relays, route)
                val controller = entry.start(live, NativeKeeperEndpoints(q, mesh, pool), backgroundScope, { true }, StandardTestDispatcher(testScheduler))
                assertEquals(NativeKeeperController.State.Ready(0, KeeperPhase.ACTIVE), controller.state.value)
                runCurrent()
                assertTrue(r.source!!.courierReady())
                live.announce()
                live.sendChat("verified owner")
                if (route.nearby) assertTrue(link.offered.any { RoomMeshWire.decode(it)?.first == RoomMeshWire.EVENT })
                if (route.internet) assertTrue(sockets.opened.any { it.publishedFrames().isNotEmpty() })
                entry.close(); assertFails { live.announce() }
                assertFails { live.sendChat("held after withdrawal") }
                println("NATIVE_KEEPER_ENTRY_MEASUREMENT route=${route.stored} pre-owner-ordinary-exports=0 source-marker=true withdrawn-chat-held=true")
            } finally { entry.stop(); live.leave(); mesh?.close(); pool?.stop() }
        }
    }

    @Test fun actualNativeHostAdmitsAfterLostGrantAndExplicitApprovalThenChatsBothWays() = runTest {
        val r = Rig(this, RoomRoute.NEARBY); val entry = r.entry()
        val hostLink = Link(); val peerLink = Link(); var lost = false
        hostLink.onOffer = { bytes ->
            val event = (RoomMeshWire.decode(bytes)?.second?.get("event") as? kotlinx.serialization.json.JsonObject)?.let(NostrEvent::fromJson)
            if (event?.kind == KIND_INVITATION_GRANT && !lost) lost = true
            else backgroundScope.launch { peerLink.receive?.invoke(bytes, "host") }
        }
        peerLink.onOffer = { bytes -> backgroundScope.launch { hostLink.receive?.invoke(bytes, "peer") } }
        val scope = requireNotNull(r.binding.meshScope)
        val mesh = RoomMeshTransport(scope, hostLink) { currentTime / 1000 }
        val peerMesh = RoomMeshTransport(scope, peerLink) { currentTime / 1000 }
        val host = session(r.room, r.who, FakeRelay(), authority = r.binding.authority, transport = mesh,
            epochGate = { _, _ -> EpochGateResult.COMMITTED })
        var peer: RoomSession? = null
        try {
            host.holdKeeperStartup(); host.join()
            val q = RoomRekeyBinding(r.binding.room, r.binding.authority, r.binding.device, scope, emptyList(), r.route)
            val controller = entry.start(host, NativeKeeperEndpoints(q, mesh, null), backgroundScope, { true }, StandardTestDispatcher(testScheduler))
            val identity = Fixtures.primary(r.room, 1, 2)
            val joining = async {
                joinLivePersistentRoom(r.invitation, encodeLivePersistentDescriptor(LivePersistentContext(r.invitation, r.room.roomId)),
                    identity.devicePubkey, peerMesh, { currentTime / 1000 }, { currentTime }) { proof ->
                    session(r.room, identity, FakeRelay(), authority = r.binding.authority, transport = peerMesh,
                        expectedEpoch = proof.epochHint.toInt(), requireFreshEpoch = true,
                        epochGate = { _, _ -> EpochGateResult.COMMITTED })
                }
            }
            runCurrent(); assertTrue(lost); assertFalse(joining.isCompleted)
            advanceTimeBy(10_000); runCurrent()
            assertEquals(listOf(identity.participant), controller.unknownParticipants.value)
            assertFalse(joining.isCompleted)
            controller.approve(identity.participant); runCurrent()
            advanceTimeBy(7_000); runCurrent()
            peer = joining.await()
            peer.sendChat("native nearby member"); host.sendChat("native nearby reply"); runCurrent()
            assertEquals(1, host.chat.value.count { it.body == "native nearby member" })
            assertEquals(1, peer.chat.value.count { it.body == "native nearby reply" })
            assertEquals(2, host.chat.value.size); assertEquals(2, peer.chat.value.size)
            val grants = hostLink.offered.mapNotNull { RoomMeshWire.decode(it)?.second?.get("event") as? kotlinx.serialization.json.JsonObject }
                .map(NostrEvent::fromJson).filter { it.kind == KIND_INVITATION_GRANT }
            assertEquals(2, grants.size); assertEquals(grants.first(), grants.last())
            println("NATIVE_KEEPER_ENTRY_MEASUREMENT native-source-live-admission=true initial-grant-lost=true original-grant-offers=2 explicit-approval=true fresh-rows-each=2")
        } finally { peer?.leave(); host.leave(); entry.stop(); mesh.close(); peerMesh.close() }
    }

    @Test fun markedCourierOrReceiverLossRefusesAtEpochZeroWithoutFreshCredit() = runTest {
        val r = Rig(this, RoomRoute.NEARBY)
        r.entry().stop()
        val original = r.sourceStore.bytes!!.clone()
        r.queueStore.bytes = null
        assertFails { r.entry() }
        assertEquals(listOf(true, false), r.initialisations)
        assertContentEquals(original, r.sourceStore.bytes)
        r.receiverStore.bytes = null; r.vault = EpochVault(r.receiverStore)
        assertFails { r.entry() }
        assertEquals(listOf(true, false), r.initialisations)
        assertContentEquals(original, r.sourceStore.bytes)
    }

    @Test fun ambiguousCourierCreationReopensTheActualEmptyLedgerAndDuplicateEntryCannotCloseItsOwner() = runTest {
        val r = Rig(this, RoomRoute.NEARBY)
        r.queueStore.ambiguous = true
        assertFails { r.entry() }
        assertNotNull(r.queueStore.bytes)
        r.queueStore.ambiguous = false
        val entry = r.entry()
        assertEquals(listOf(true, true), r.initialisations)
        assertFails { r.entry() }
        assertTrue(r.source!!.courierReady())
        entry.stop()
        r.entry().stop()
        assertEquals(listOf(true, true, false), r.initialisations)
    }
}
