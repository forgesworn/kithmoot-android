package dev.forgesworn.kithmoot.relay

import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.session.Fixtures
import dev.forgesworn.kithmoot.session.session
import dev.forgesworn.kithmoot.support.FakeRelay
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class HybridRoomTransportTest {
    private fun event(text: String = "ciphertext") = Events.sign(Fixtures.key(2), 1460, 100, emptyList(), text)
    private fun described(relay: FakeRelay) = object : RoomTransport by relay.transport() {
        override fun describe() = listOf("wss://fixture.invalid/")
        override fun receivedViaRelays(eventId: String) = describe()
    }

    @Test fun `live controls permit three spaced retries and collapse simultaneous lane copies`() = runTest {
        val mesh = FakeRelay(); val relay = FakeRelay()
        val hybrid = HybridRoomTransport(mesh.transport(), described(relay)) { currentTime / 1000 }
        val received = mutableListOf<NostrEvent>()
        backgroundScope.launch { hybrid.subscribe(listOf(Filter())).collect { received += it } }; runCurrent()
        for (kind in listOf(20466, 20467, 20468, 20469)) {
            val control = Events.sign(Fixtures.key(2), kind, currentTime / 1000, emptyList(), "control")
            val before = received.size
            mesh.publish(control); relay.publish(control); runCurrent()
            assertEquals(before + 1, received.size)
            assertTrue(hybrid.receivedEventConfirmsPublication(control.id))
            repeat(2) { offer ->
                advanceTimeBy(10_000); mesh.publish(control); relay.publish(control); runCurrent()
                assertEquals(before + offer + 2, received.size, "same signed control must remain retryable")
            }
            advanceTimeBy(10_000); relay.publish(control); runCurrent()
            assertEquals(before + 3, received.size)
        }
        val chat = event(); mesh.publish(chat); runCurrent()
        advanceTimeBy(10_000); relay.publish(chat); runCurrent()
        assertEquals(1, received.count { it.id == chat.id })
        assertEquals(listOf("wss://fixture.invalid/"), hybrid.receivedViaRelays(chat.id))
    }

    @Test fun `same event goes to both selected lanes and incoming duplicates give one row without forwarding`() = runTest {
        val mesh = FakeRelay(); val relay = FakeRelay(); val hybrid = HybridRoomTransport(mesh.transport(), described(relay))
        val received = mutableListOf<NostrEvent>()
        backgroundScope.launch { hybrid.subscribe(listOf(Filter())).collect { received += it } }; runCurrent()
        val outgoing = event(); hybrid.publish(outgoing); runCurrent()
        assertEquals(listOf(outgoing.id), mesh.published.map { it.id }); assertEquals(mesh.published, relay.published)
        assertEquals(listOf(outgoing.id), received.map { it.id })
        val incoming = event("nearby only"); mesh.publish(incoming); runCurrent()
        assertEquals(1, relay.published.size) // Receiving does not opt into forwarding.
        assertFalse(hybrid.receivedEventConfirmsPublication(incoming.id))
        assertTrue(hybrid.receivedViaRelays(incoming.id).isEmpty())
        relay.publish(incoming); runCurrent()
        assertEquals(2, received.size)
        assertTrue(hybrid.receivedEventConfirmsPublication(incoming.id))
        assertEquals(listOf("wss://fixture.invalid/"), hybrid.receivedViaRelays(incoming.id))
    }

    @Test fun `nearby acceptance plus relay refusal remains UNKNOWN but an actual relay receipt confirms`() = runTest {
        val mesh = FakeRelay(); val relay = FakeRelay().also { it.confirmsPublications = false }
        val hybrid = HybridRoomTransport(mesh.transport(), relay.transport()); val e = event()
        assertFailsWith<PublicationUnconfirmedException> { hybrid.publishConfirmed(e) }
        assertEquals(listOf(e.id), mesh.published.map { it.id }); assertTrue(relay.published.isEmpty())
        relay.confirmsPublications = true
        assertTrue(hybrid.publishConfirmed(e)); assertEquals(e.id, relay.published.single().id)
    }

    @Test fun `failed nearby lane does not disable explicitly selected internet`() = runTest {
        val mesh = object : RoomTransport {
            override fun publish(event: NostrEvent) { error("Bluetooth permission missing") }
            override fun subscribe(filters: List<Filter>) = flow<NostrEvent> { error("Bluetooth unavailable") }
        }
        val relay = FakeRelay(); val hybrid = HybridRoomTransport(mesh, relay.transport())
        val received = mutableListOf<NostrEvent>()
        backgroundScope.launch { hybrid.subscribe(listOf(Filter())).collect { received += it } }; runCurrent()
        assertTrue(hybrid.publishConfirmed(event())); runCurrent()
        assertEquals(1, received.size)
    }

    @Test fun `mesh replay cannot substitute for complete relay history`() = runTest {
        val mesh = FakeRelay().also { it.answersQueries = true }; mesh.publish(event())
        val relay = FakeRelay(); val hybrid = HybridRoomTransport(mesh.transport(), relay.transport())
        assertFailsWith<UnsupportedOperationException> { hybrid.queryStored(listOf(Filter())) }
        assertEquals(1, hybrid.queryAvailable(listOf(Filter()), 10).size)
        var complete = false
        backgroundScope.launch { hybrid.subscribeReplayed(listOf(Filter()), { complete = true }).collect {} }
        runCurrent(); assertFalse(complete)
    }

    @Test fun `failed rekey stops both lanes and cannot be reopened`() = runTest {
        val mesh = FakeRelay(); val relay = FakeRelay()
        val bad = object : RoomTransport by relay.transport() {
            override suspend fun beginRekey() { relay.transport().beginRekey(); error("reset failed") }
        }
        val hybrid = HybridRoomTransport(mesh.transport(), bad); val before = hybrid.publicationGeneration()
        assertFailsWith<IllegalStateException> { hybrid.beginRekey() }
        assertTrue(mesh.publicationBlocked); assertTrue(relay.publicationBlocked)
        assertFailsWith<IllegalStateException> { hybrid.completeRekey() }
        assertFailsWith<IllegalStateException> { hybrid.publish(event()) }
        assertFalse(hybrid.publishConfirmedGuarded(event(), before, { true }))
    }

    @Test fun `successful rekey retires old publication guards and receipt provenance`() = runTest {
        val mesh = FakeRelay(); val relay = FakeRelay(); val hybrid = HybridRoomTransport(mesh.transport(), relay.transport())
        backgroundScope.launch { hybrid.subscribe(listOf(Filter())).collect {} }; runCurrent()
        val e = event(); relay.publish(e); runCurrent(); assertTrue(hybrid.receivedEventConfirmsPublication(e.id))
        val before = hybrid.publicationGeneration(); hybrid.beginRekey()
        assertFalse(hybrid.receivedEventConfirmsPublication(e.id))
        hybrid.rekey(ByteArray(32)); hybrid.completeRekey()
        assertFalse(hybrid.publishConfirmedGuarded(e, before, { true }))
        assertTrue(hybrid.publishConfirmedGuarded(e, hybrid.publicationGeneration(), { true }))
    }

    @Test fun `late pre rekey relay query cannot restore cleared receipt provenance`() = runTest {
        val reply = CompletableDeferred<List<NostrEvent>>()
        val relay = FakeRelay()
        val late = object : RoomTransport by described(relay) {
            override suspend fun queryStored(filters: List<Filter>, timeoutMs: Long) = reply.await()
        }
        val hybrid = HybridRoomTransport(FakeRelay().transport(), late)
        val query = async { hybrid.queryStored(listOf(Filter())) }; runCurrent()
        hybrid.beginRekey(); hybrid.rekey(ByteArray(32)); hybrid.completeRekey()
        val old = event(); reply.complete(listOf(old)); query.await()
        assertFalse(hybrid.receivedEventConfirmsPublication(old.id))
        assertTrue(hybrid.receivedViaRelays(old.id).isEmpty())
    }

    @Test fun `actual RoomSessions share one conversation on simultaneous nearby and internet lanes`() = runTest {
        val mesh = FakeRelay(); val relay = FakeRelay()
        val a = HybridRoomTransport(mesh.transport(), relay.transport())
        val b = HybridRoomTransport(mesh.transport(), relay.transport())
        val room = Fixtures.room()
        val alice = session(room, Fixtures.primary(room, 1, 2), FakeRelay(), transport = a)
        val bob = session(room, Fixtures.primary(room, 3, 4), FakeRelay(), transport = b)
        alice.join(); bob.join(); runCurrent(); advanceTimeBy(2_000); runCurrent()
        alice.sendChat("Hello on both paths"); runCurrent()
        bob.sendChat("One conversation"); runCurrent()
        assertEquals(listOf("Hello on both paths", "One conversation"), alice.chat.value.map { it.body })
        assertEquals(alice.chat.value.map { it.id }, bob.chat.value.map { it.id })
        assertEquals(mesh.published.filter { it.kind == 1460 }.map { it.id }, relay.published.filter { it.kind == 1460 }.map { it.id })
        alice.leave(); bob.leave()
    }
}
