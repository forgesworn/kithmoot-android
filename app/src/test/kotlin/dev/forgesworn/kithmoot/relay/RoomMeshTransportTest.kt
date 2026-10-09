package dev.forgesworn.kithmoot.relay

import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.service.flushPending
import dev.forgesworn.kithmoot.service.FlushOutcome
import dev.forgesworn.kithmoot.session.PendingChatOutbox
import dev.forgesworn.kithmoot.session.PendingChatState
import dev.forgesworn.kithmoot.storage.RoomStorage
import dev.forgesworn.kithmoot.session.Fixtures
import dev.forgesworn.kithmoot.session.RoomSession
import dev.forgesworn.kithmoot.session.session
import dev.forgesworn.kithmoot.support.FakeRelay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class RoomMeshTransportTest {
    private val meshScope = "11".repeat(32)
    private fun event(text: String = "opaque ciphertext", kind: Int = 1460, at: Long = 100,
        tags: List<List<String>> = emptyList()) = Events.sign(Fixtures.key(2), kind, at, tags, text)
    private fun frame(event: NostrEvent, scope: String = meshScope) = RoomMeshWire.encode(RoomMeshWire.EVENT,
        buildJsonObject { put("scope", scope); put("event", event.toJson()) })

    @Test fun `an unconfined subscriber waiting for room state cannot block concurrent mesh publication`() {
        val link = Link()
        val mesh = RoomMeshTransport(meshScope, link) { 100 }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2) { task -> Thread(task, "mesh-lock-regression").apply { isDaemon = true } }
        val original = event("received before announcement")
        try {
            scope.launch {
                mesh.subscribe(listOf(Filter())).collect {
                    if (it.id == original.id) {
                        entered.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                    }
                }
            }
            val receive = workers.submit { link.inbound(frame(original)) }
            assertTrue(entered.await(5, TimeUnit.SECONDS), "Subscriber did not receive the original event")
            // A RoomSession announcement can hold its state lock while publishing.
            // Reception must release the mesh lock before resuming that subscriber.
            val publish = workers.submit { mesh.publish(event("concurrent announcement", kind = 1463)) }
            publish.get(2, TimeUnit.SECONDS)
            receive.get(2, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            workers.shutdown()
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
            scope.cancel()
            mesh.close()
        }
    }

    private class Link : RoomMeshLink {
        var receive: ((ByteArray, String) -> Unit)? = null
        val offered = mutableListOf<Pair<ByteArray, String?>>()
        var reset = 0; var closed = false; var resetFailure = false; var unsubscribeFailure = false
        override fun subscribe(receive: (ByteArray, String) -> Unit): AutoCloseable {
            this.receive = receive
            return AutoCloseable { this.receive = null; if (unsubscribeFailure) error("unsubscribe failed") }
        }
        override fun offer(bytes: ByteArray, to: String?) { check(!closed); offered += bytes.copyOf() to to }
        override suspend fun resetQueued() { reset++; if (resetFailure) error("queue barrier failed"); offered.clear() }
        override fun reachable() = !closed
        override fun close() { closed = true; offered.clear(); receive = null }
        fun inbound(bytes: ByteArray, from: String = "unverified-peer") { receive?.invoke(bytes, from) }
    }

    @Test fun `a burst exceeding subscriber capacity closes its bounded queue without leaking a reader`() = runTest {
        val link = Link(); val mesh = RoomMeshTransport(meshScope, link) { 100 }
        val received = mutableListOf<NostrEvent>()
        var failure: Exception? = null
        backgroundScope.launch {
            try { mesh.subscribe(listOf(Filter())).collect { received += it } }
            catch (error: Exception) { failure = error }
        }
        runCurrent()
        repeat(66) { link.inbound(frame(event("burst $it"))) }
        runCurrent()
        // One message was handed to the waiting delivery worker, plus the
        // bounded 64 queued behind it; the next message closes the reader.
        assertEquals(65, received.size)
        assertIs<IllegalStateException>(failure)
        assertEquals("Mesh subscription capacity exceeded", failure!!.message)
        val after = mutableListOf<NostrEvent>()
        backgroundScope.launch { mesh.subscribe(listOf(Filter())).collect { after += it } }
        runCurrent()
        assertEquals(64, after.size)
        mesh.close()
    }

    @Test fun inbound_subscription_excludes_local_publications_and_their_cached_replay() = runTest {
        val link = Link(); val mesh = RoomMeshTransport(meshScope, link) { 100 }
        val first = event("first local"); val second = event("second local")
        mesh.publish(first)
        val normal = mutableListOf<NostrEvent>(); val inbound = mutableListOf<NostrEvent>()
        backgroundScope.launch { mesh.subscribe(listOf(Filter())).collect { normal += it } }
        backgroundScope.launch { mesh.subscribeInbound(listOf(Filter())).collect { inbound += it } }
        runCurrent(); mesh.publish(second); runCurrent()
        assertEquals(listOf(first.id, second.id), normal.map { it.id })
        assertTrue(inbound.isEmpty(), "Local publication and local cache are not inbound observations")
        assertFalse(mesh.receivedEventConfirmsPublication(first.id))
        mesh.close()
    }

    @Test fun a_received_copy_of_a_locally_published_event_is_observed_once_without_a_second_room_row() = runTest {
        val link = Link(); val mesh = RoomMeshTransport(meshScope, link) { 100 }
        val original = event(); val normal = mutableListOf<NostrEvent>(); val inbound = mutableListOf<NostrEvent>()
        backgroundScope.launch { mesh.subscribe(listOf(Filter())).collect { normal += it } }
        backgroundScope.launch { mesh.subscribeInbound(listOf(Filter())).collect { inbound += it } }
        runCurrent(); mesh.publish(original); runCurrent()
        inbound.clear() // Isolate the later receive from the local-publication check above.
        link.inbound(frame(original)); link.inbound(frame(original), "another-unverified-label"); runCurrent()
        assertEquals(listOf(original.id), inbound.map { it.id })
        assertEquals(listOf(original.id), normal.map { it.id })
        assertFalse(mesh.receivedEventConfirmsPublication(original.id))
        val replay = mutableListOf<NostrEvent>()
        backgroundScope.launch { mesh.subscribeInbound(listOf(Filter())).collect { replay += it } }; runCurrent()
        assertEquals(listOf(original.id), replay.map { it.id })
        mesh.close()
    }

    @Test fun inbound_replay_keeps_remote_provenance_through_local_republication_and_expires() = runTest {
        var at = 100L; val link = Link(); val mesh = RoomMeshTransport(meshScope, link) { at }
        val remote = event("remote"); val local = event("local")
        link.inbound(frame(remote)); mesh.publish(local); mesh.publish(remote)
        val replay = mutableListOf<NostrEvent>()
        val read = backgroundScope.launch { mesh.subscribeInbound(listOf(Filter())).collect { replay += it } }
        runCurrent(); assertEquals(listOf(remote.id), replay.map { it.id })
        read.cancel(); runCurrent(); at = 3699
        link.inbound(frame(local)) // A late genuine receive must not extend the original cache expiry.
        at = 3701
        val expired = mutableListOf<NostrEvent>()
        backgroundScope.launch { mesh.subscribeInbound(listOf(Filter())).collect { expired += it } }; runCurrent()
        assertTrue(expired.isEmpty()); mesh.close()
    }

    @Test fun inbound_observations_obey_validation_and_are_cleared_by_the_epoch_barrier() = runTest {
        val link = Link(); val mesh = RoomMeshTransport(meshScope, link) { 100 }
        val good = event(); val inbound = mutableListOf<NostrEvent>()
        backgroundScope.launch { mesh.subscribeInbound(listOf(Filter())).collect { inbound += it } }; runCurrent()
        link.inbound(frame(good.copy(content = "tampered")))
        link.inbound(frame(good, "22".repeat(32)))
        link.inbound(frame(event(tags = listOf(listOf("expiration", "99")))))
        runCurrent(); assertTrue(inbound.isEmpty())
        link.inbound(frame(good)); runCurrent(); assertEquals(listOf(good.id), inbound.map { it.id })
        mesh.beginRekey(); link.inbound(frame(event("old in flight")))
        mesh.rekey(ByteArray(32)); mesh.completeRekey()
        val replay = mutableListOf<NostrEvent>()
        backgroundScope.launch { mesh.subscribeInbound(listOf(Filter())).collect { replay += it } }; runCurrent()
        assertTrue(replay.isEmpty())
        link.inbound(frame(good)); runCurrent()
        assertEquals(listOf(good.id, good.id), inbound.filter { it.id == good.id }.map { it.id })
        mesh.close()
    }

    @Test fun inbound_control_retries_have_their_own_bounded_observation_without_retained_history() = runTest {
        var at = 100L; val link = Link(); val mesh = RoomMeshTransport(meshScope, link) { at }
        val request = event("live request", 20466)
        val normal = mutableListOf<NostrEvent>(); val inbound = mutableListOf<NostrEvent>()
        backgroundScope.launch { mesh.subscribe(listOf(Filter())).collect { normal += it } }
        backgroundScope.launch { mesh.subscribeInbound(listOf(Filter())).collect { inbound += it } }; runCurrent()
        repeat(3) { mesh.publish(request); runCurrent(); at++ }
        assertEquals(3, normal.size); assertTrue(inbound.isEmpty())
        repeat(5) {
            link.inbound(frame(request)); link.inbound(frame(request)); runCurrent(); at++
        }
        assertEquals(3, inbound.size); assertEquals(3, normal.size)
        val replay = mutableListOf<NostrEvent>()
        backgroundScope.launch { mesh.subscribeInbound(listOf(Filter())).collect { replay += it } }; runCurrent()
        assertTrue(replay.isEmpty()); mesh.close()
    }

    @Test fun `control retries reach their owner three times without becoming retained history`() = runTest {
        var at = 100L
        val link = Link(); val mesh = RoomMeshTransport(meshScope, link) { at }
        val received = mutableListOf<NostrEvent>()
        backgroundScope.launch { mesh.subscribe(listOf(Filter())).collect { received += it } }; runCurrent()
        val request = event("live request", 20466)
        link.inbound(frame(request)); link.inbound(frame(request)); runCurrent()
        assertEquals(1, received.size)
        repeat(4) { at++; link.inbound(frame(request)); runCurrent() }
        assertEquals(3, received.size)
        val replayed = mutableListOf<NostrEvent>()
        backgroundScope.launch { mesh.subscribe(listOf(Filter())).collect { replayed += it } }; runCurrent()
        assertTrue(replayed.isEmpty())
        mesh.close()
    }

    @Test fun `invalid scope signature expiry and numeric types never reach subscribers`() = runTest {
        val link = Link(); val mesh = RoomMeshTransport(meshScope, link) { 100 }
        val received = mutableListOf<NostrEvent>()
        backgroundScope.launch { mesh.subscribe(listOf(Filter())).collect { received += it } }; runCurrent()
        val good = event()
        link.inbound(frame(good)); link.inbound(frame(good))
        link.inbound(frame(good.copy(content = "tampered"))); link.inbound(frame(good, "22".repeat(32)))
        link.inbound(frame(event(tags = listOf(listOf("expiration", "101"), listOf("expiration", "99")))))
        link.inbound(frame(event(tags = listOf(listOf("expiration", "nonsense")))))
        link.inbound(frame(event(kind = -1))); link.inbound(frame(event(kind = 65536)))
        link.inbound(frame(event(at = 401))); link.inbound(frame(event(at = -1)))
        val numericString = JsonObject(good.toJson() + ("kind" to JsonPrimitive("1460")))
        link.inbound(RoomMeshWire.encode(RoomMeshWire.EVENT, buildJsonObject {
            put("scope", meshScope); put("event", numericString)
        }))
        runCurrent(); assertEquals(listOf(good.id), received.map { it.id })
        mesh.close()
    }

    @Test fun `replay retains only bounded chat and invitation and honours expiry despite clock rollback`() = runTest {
        var at = 100L; val link = Link(); val mesh = RoomMeshTransport(meshScope, link) { at }
        repeat(70) { link.inbound(frame(event("message $it"))) }
        link.inbound(frame(event("live", kind = 20461)))
        val received = mutableListOf<NostrEvent>()
        val read = backgroundScope.launch { mesh.subscribe(listOf(Filter())).collect { received += it } }
        runCurrent(); assertEquals(64, received.size); assertTrue(received.all { it.kind == 1460 })
        read.cancel(); runCurrent(); at = 3701
        val expired = mutableListOf<NostrEvent>()
        val later = backgroundScope.launch { mesh.subscribe(listOf(Filter())).collect { expired += it } }
        runCurrent(); assertTrue(expired.isEmpty()); later.cancel(); runCurrent()
        at = 100
        val rollback = mutableListOf<NostrEvent>()
        backgroundScope.launch { mesh.subscribe(listOf(Filter())).collect { rollback += it } }; runCurrent()
        assertTrue(rollback.isEmpty()); mesh.close()
    }

    @Test fun `query amplification is bounded globally even with rotating source labels`() = runTest {
        val link = Link(); val mesh = RoomMeshTransport(meshScope, link) { 100 }
        repeat(20) { mesh.publish(event("message $it")) }; link.offered.clear()
        val query = RoomMeshWire.encode(RoomMeshWire.QUERY, buildJsonObject {
            put("scope", meshScope); put("filters", JsonArray(listOf(Filter().toJson())))
        })
        repeat(100) { link.inbound(query, "source-$it") }
        assertEquals(8, link.offered.size)
        assertTrue(link.offered.all { it.second == "source-0" })
        mesh.close()
    }

    @Test fun `rekey drops queued and retained old events and guards stale durable attempts`() = runTest {
        val link = Link(); val mesh = RoomMeshTransport(meshScope, link) { 100 }
        val message = event(); val before = mesh.publicationGeneration()
        assertFailsWith<PublicationUnconfirmedException> { mesh.publishConfirmed(message) }; assertEquals(1, link.offered.size)
        mesh.beginRekey(); assertEquals(1, link.reset); assertFalse(mesh.reachable())
        assertFalse(mesh.publishConfirmedGuarded(message, before, { true }))
        assertFailsWith<IllegalStateException> { mesh.publish(message) }
        mesh.publishRecovery(event("epoch request", kind = 20468))
        assertFailsWith<IllegalArgumentException> { mesh.publishRecovery(message) }
        link.inbound(frame(event("old in flight")))
        mesh.rekey(ByteArray(32)); mesh.completeRekey(); link.offered.clear()
        assertFalse(mesh.publishConfirmedGuarded(message, before, { true })); assertTrue(link.offered.isEmpty())
        assertFalse(mesh.publishConfirmedGuarded(message, mesh.publicationGeneration(), { false }))
        val replay = mutableListOf<NostrEvent>()
        backgroundScope.launch { mesh.subscribe(listOf(Filter())).collect { replay += it } }; runCurrent()
        assertTrue(replay.isEmpty())
        assertFailsWith<PublicationUnconfirmedException> { mesh.publishConfirmedGuarded(message, mesh.publicationGeneration(), { true }) }
        mesh.close(); assertTrue(link.closed)
        assertFailsWith<IllegalStateException> { mesh.publish(message) }
        assertFalse(mesh.reachable())
    }

    @Test fun `failed queue barrier cannot be reopened by completeRekey`() = runTest {
        val link = Link().also { it.resetFailure = true }; val mesh = RoomMeshTransport(meshScope, link) { 100 }
        assertFailsWith<IllegalStateException> { mesh.beginRekey() }
        assertFailsWith<IllegalStateException> { mesh.completeRekey() }
        assertFailsWith<IllegalStateException> { mesh.publishRecovery(event(kind = 20468)) }
        mesh.close()
    }

    @Test fun `teardown still closes the link when removing its receiver fails`() {
        val link = Link().also { it.unsubscribeFailure = true }
        val mesh = RoomMeshTransport(meshScope, link) { 100 }
        assertFailsWith<IllegalStateException> { mesh.close() }
        assertTrue(link.closed); assertFalse(mesh.reachable())
        mesh.close()
    }

    @Test fun `available replay never masquerades as verified retained history`() = runTest {
        val link = Link(); val mesh = RoomMeshTransport(meshScope, link) { 100 }
        mesh.publish(event())
        assertFailsWith<UnsupportedOperationException> { mesh.queryStored(listOf(Filter())) }
        var replayComplete = false
        backgroundScope.launch { mesh.subscribeReplayed(listOf(Filter()), { replayComplete = true }).collect {} }
        runCurrent(); advanceTimeBy(1_000); runCurrent(); assertFalse(replayComplete)
        assertEquals(1, mesh.queryAvailable(listOf(Filter()), 10).size)
        mesh.close()
    }

    @Test fun `malformed framing oversized depth and invalid UTF8 are refused`() {
        val valid = frame(event())
        assertNotNull(RoomMeshWire.decode(valid))
        for (bytes in listOf(byteArrayOf(), valid.copyOf(3), valid.copyOf(valid.size - 1),
            valid + byteArrayOf(0), byteArrayOf(-1, -1, -1, -1, 0, 0),
            byteArrayOf(0, 0, 0, 2, -64, -128))) assertNull(RoomMeshWire.decode(bytes))
        val deep = ("[".repeat(100) + "0" + "]".repeat(100)).toByteArray()
        assertNull(RoomMeshWire.decode(java.nio.ByteBuffer.allocate(deep.size + 4).putInt(deep.size).put(deep).array()))
        val link = Link(); val mesh = RoomMeshTransport(meshScope, link) { 100 }
        assertFailsWith<IllegalArgumentException> { mesh.publish(event("x".repeat(17000))) }
        mesh.close()
    }

    @Test fun `frozen TypeScript MeshFrame bytes decode and reencode exactly`() {
        val json = Json.parseToJsonElement(checkNotNull(javaClass.getResource("/mesh-room-wire.json")).readText()).jsonObject
        for (fixture in json.getValue("frames").jsonArray) {
            val row = fixture.jsonObject
            val expected = row.getValue("frame").jsonObject
            val bytes = java.util.Base64.getDecoder().decode(row.getValue("base64").jsonPrimitive.content)
            val (kind, payload) = assertNotNull(RoomMeshWire.decode(bytes))
            assertEquals(expected.getValue("kind").jsonPrimitive.content, kind)
            assertEquals(expected.getValue("payload"), payload)
            assertContentEquals(bytes, RoomMeshWire.encode(kind, payload))
            if (kind == RoomMeshWire.EVENT) assertTrue(Events.verify(NostrEvent.fromJson(payload.getValue("event"))))
        }
    }

    @Test fun `durable send remains unknown after mesh admission local echo retry and journal reopen`() = runTest {
        // Journal IO uses a real dispatcher while background timers use virtual time.
        // Keep this durability test's wire clock fixed so IO cannot expire its retry.
        val clock = { 100L }
        val link = Link(); val mesh = RoomMeshTransport(meshScope, link, clock)
        val room = Fixtures.room(); val identity = Fixtures.primary(room, 1, 2)
        val storage = object : RoomStorage {
            var bytes: ByteArray? = null
            override fun read() = bytes?.copyOf()
            override fun write(value: ByteArray) { bytes = value.copyOf() }
            override fun reset() { bytes = null }
        }
        val outbox = PendingChatOutbox(storage, room.roomId, identity.participant, identity.devicePubkey)
        val sender = RoomSession(room, identity, transport = mesh, scope = backgroundScope,
            timing = Fixtures.QUIET, now = clock, nowMs = { clock() * 1000 },
            random = kotlin.random.Random(7), epochSettleMs = 0, chatOutbox = outbox)
        sender.join(); runCurrent(); advanceTimeBy(2_000); runCurrent()
        assertFalse(sender.sendChatDurable("Keep the same encrypted event")); runCurrent()
        assertEquals(PendingChatState.UNKNOWN, outbox.items().single().state)
        val original = outbox.items().single().event
        sender.reconcilePendingChats()
        assertEquals(original, outbox.items().single().event)
        assertFalse(sender.retryPendingChat()); runCurrent()
        val reopened = PendingChatOutbox(storage, room.roomId, identity.participant, identity.devicePubkey)
        assertEquals(original, reopened.items().single().event)
        assertEquals(PendingChatState.UNKNOWN, reopened.items().single().state)
        val sent = link.offered.mapNotNull { RoomMeshWire.decode(it.first) }
            .filter { it.first == RoomMeshWire.EVENT }.map { NostrEvent.fromJson(it.second.getValue("event")) }
            .filter { it.kind == 1460 }
        assertEquals(2, sent.size); assertTrue(sent.all { it.id == original.id })
        sender.leave(); mesh.close()
    }

    @Test fun `background mesh flush persists possible handoff before local offer`() = runTest {
        val storage = object : RoomStorage {
            var bytes: ByteArray? = null
            override fun read() = bytes?.copyOf()
            override fun write(value: ByteArray) { bytes = value.copyOf() }
            override fun reset() { bytes = null }
        }
        val link = Link(); val mesh = RoomMeshTransport(meshScope, link) { 100 }
        val original = event()
        val outbox = PendingChatOutbox(storage, "room", "self", original.pubkey)
        outbox.retain("epoch", original)
        assertEquals(FlushOutcome.NOT_CONFIRMED, flushPending(outbox, "epoch", mesh))
        val reopened = PendingChatOutbox(storage, "room", "self", original.pubkey)
        assertEquals(original, reopened.items().single().event)
        assertEquals(PendingChatState.UNKNOWN, reopened.items().single().state)
        assertEquals(1, link.offered.size)
        mesh.close()
    }

    @Test fun `actual room sessions exchange encrypted chat through bytes with no relay and one row per event`() = runTest {
        val left = Link(); val right = Link()
        val a = RoomMeshTransport(meshScope, left) { currentTime / 1000 }
        val b = RoomMeshTransport(meshScope, right) { currentTime / 1000 }
        val room = Fixtures.room()
        val alice = session(room, Fixtures.primary(room, 1, 2), FakeRelay(), transport = a)
        val bob = session(room, Fixtures.primary(room, 3, 4), FakeRelay(), transport = b)
        fun pump() {
            repeat(8) {
                runCurrent()
                val l = left.offered.toList(); left.offered.clear()
                val r = right.offered.toList(); right.offered.clear()
                l.forEach { (bytes, _) -> right.inbound(bytes, "alice"); right.inbound(bytes, "alice") }
                r.forEach { (bytes, _) -> left.inbound(bytes, "bob"); left.inbound(bytes, "bob") }
            }
            runCurrent()
        }
        alice.join(); bob.join(); pump(); advanceTimeBy(2_000); pump()
        alice.sendChat("Hello from mesh"); pump()
        bob.sendChat("Reply without internet"); pump()
        assertEquals(listOf("Hello from mesh", "Reply without internet"), alice.chat.value.map { it.body })
        assertEquals(alice.chat.value.map { it.id }, bob.chat.value.map { it.id })
        alice.leave(); bob.leave(); a.close(); b.close()
    }
}
