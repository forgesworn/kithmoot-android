package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.RoomTransport
import dev.forgesworn.kithmoot.support.FakeRelay
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class LiveAdmissionTest {
    private val root = Fixtures.key(41)
    private val secret = ByteArray(32) { 7 }
    private val invitation = RoomInvitation(ByteArray(32) { 5 }, Schnorr.publicKeyHex(root), true)
    private val context = LivePersistentContext(invitation, deriveRoom(secret).roomId)
    private val device = Schnorr.publicKeyHex(Fixtures.key(11))

    private class Route : RoomTransport {
        val sent = mutableListOf<NostrEvent>()
        private val events = MutableSharedFlow<NostrEvent>(extraBufferCapacity = 128)
        var readers = 0
        var onPublish: (NostrEvent) -> Unit = {}
        override fun publish(event: NostrEvent) { sent += event; onPublish(event) }
        override fun subscribe(filters: List<Filter>): Flow<NostrEvent> = events
            .onSubscription { readers++ }.onCompletion { readers-- }
            .filter { e -> filters.any { FakeRelay.matches(it, e) } }
        fun receive(event: NostrEvent) { check(events.tryEmit(event)) }
    }

    @Test fun `lost request and lost reply recover within the unchanged cached answer lifetime`() = runTest {
        val route = Route()
        val welcome = encodePersistentInvitation(RoomInvitationHost(invitation, root), secret, 0)
        var cached: NostrEvent? = null
        route.onPublish = { request ->
            if (route.sent.size == 2) cached = encodeLivePersistentAnswer(context, request, welcome, root, 0, currentTime / 1000)
            if (route.sent.size == 3) { route.receive(cached!!); route.receive(cached!!) }
        }
        val result = async { requestLivePersistentAdmission(context, device, route, { currentTime / 1000 }, { currentTime }) }
        runCurrent(); advanceTimeBy(20_001); runCurrent()
        assertEquals(0L, result.await().epochHint)
        assertEquals(3, route.sent.size)
        assertEquals(1, route.sent.map { it.id }.distinct().size)
        assertEquals(0, route.readers)
        advanceTimeBy(100_000); assertEquals(3, route.sent.size)
    }

    @Test fun `silence is bounded to three offers and never queries retained history`() = runTest {
        val route = Route()
        val result = async { runCatching { requestLivePersistentAdmission(context, device, route, { currentTime / 1000 }, { currentTime }) } }
        runCurrent(); advanceTimeBy(90_001); runCurrent()
        assertTrue(result.await().isFailure)
        assertEquals(3, route.sent.size)
        assertEquals(0, route.readers)
    }

    @Test fun `cancellation releases exclusive ownership and uses a new ephemeral key next time`() = runTest {
        val route = Route()
        val first = launch { requestLivePersistentAdmission(context, device, route, { currentTime / 1000 }, { currentTime }) }
        runCurrent()
        assertFailsWith<IllegalStateException> { requestLivePersistentAdmission(context, device, route) }
        first.cancelAndJoin()
        assertEquals(0, route.readers)
        val second = launch { requestLivePersistentAdmission(context, device, route, { currentTime / 1000 }, { currentTime }) }
        runCurrent()
        assertNotEquals(route.sent[0].pubkey, route.sent[1].pubkey)
        assertNotEquals(route.sent[0].id, route.sent[1].id)
        second.cancelAndJoin()
        advanceTimeBy(90_000); assertEquals(2, route.sent.size)
    }

    @Test fun `local retirement and wall rollback cancel without another offer`() = runTest {
        for (mode in listOf("retired", "rollback")) {
            val route = Route(); var wall = 20L; var retired = false
            val result = async { runCatching { requestLivePersistentAdmission(context, device, route, { wall }, { currentTime }, { retired }) } }
            runCurrent()
            if (mode == "retired") retired = true else wall--
            advanceTimeBy(1001); runCurrent()
            assertTrue(result.await().isFailure)
            assertEquals(1, route.sent.size)
            assertEquals(0, route.readers)
        }
    }

    @Test fun `at most eight rooms may own challenges process wide`() = runTest {
        val routes = List(8) { Route() }
        val owners = routes.mapIndexed { i, route -> launch {
            requestLivePersistentAdmission(context.copy(roomId = i.toString(16).padStart(64, '0')), device, route,
                { currentTime / 1000 }, { currentTime })
        } }
        runCurrent()
        assertFailsWith<IllegalStateException> { requestLivePersistentAdmission(context, device, Route()) }
        owners.forEach { it.cancelAndJoin() }
        assertTrue(routes.all { it.readers == 0 })
    }

    @Test fun `invalid replies consume a bounded allowance before cryptography can repeat indefinitely`() = runTest {
        val route = Route()
        val welcome = encodePersistentInvitation(RoomInvitationHost(invitation, root), secret, 0)
        route.onPublish = { request ->
            if (route.sent.size == 1) {
                val good = encodeLivePersistentAnswer(context, request, welcome, root, 0, 0)
                repeat(64) { route.receive(good.copy(sig = "00".repeat(64))) }
                route.receive(good)
            }
        }
        val result = async { runCatching { requestLivePersistentAdmission(context, device, route, { currentTime / 1000 }, { currentTime }) } }
        runCurrent(); advanceTimeBy(90_001); runCurrent()
        assertTrue(result.await().isFailure)
        assertEquals(0, route.readers)
    }
}
