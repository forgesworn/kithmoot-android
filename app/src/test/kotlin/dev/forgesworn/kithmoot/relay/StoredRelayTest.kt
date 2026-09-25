package dev.forgesworn.kithmoot.relay

import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.support.FakeSocketFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class StoredRelayTest {
    private val event = encodePersistentInvitation(createRoomInvitation(true), ByteArray(32) { 6 }, 1_800_000_000)

    @Test fun `stored results wait for every connected relay including a late tombstone and close subscriptions`() = runTest {
        val sockets = FakeSocketFactory()
        val pool = RelayPool(listOf("wss://one", "wss://two"), sockets, backgroundScope)
        pool.start(); runCurrent(); sockets.openAll()
        val result = async { pool.queryStored(listOf(Filter(kinds = listOf(1463, 1461)))) }
        runCurrent()
        val id = sockets.opened.first().requestedSubscriptions().single()
        sockets.opened[0].deliverEvent(id, event.copy(sig = "00".repeat(64)))
        sockets.opened[0].deliverEvent(id, event)
        sockets.opened[0].deliverRaw("""["EOSE","$id"]""")
        runCurrent()
        assertFalse(result.isCompleted)
        val later = Events.sign(ByteArray(32) { 8 }, 1461, 1_800_000_001, emptyList(), "{}")
        sockets.opened[1].deliverEvent(id, later)
        sockets.opened[1].deliverRaw("""["EOSE","$id"]""")
        assertEquals(listOf(event, later), result.await())
        assertTrue(sockets.opened.all { socket -> socket.sent.any { it.startsWith("[\"CLOSE\"") } })
        pool.stop()
    }

    @Test fun `partial results never admit on timeout disconnection or CLOSED`() = runTest {
        for (failure in listOf("timeout", "disconnect", "closed")) {
            val sockets = FakeSocketFactory()
            val pool = RelayPool(listOf("wss://one"), sockets, backgroundScope)
            pool.start(); runCurrent(); sockets.openAll()
            val result = async { runCatching { pool.queryStored(listOf(Filter(kinds = listOf(1463))), 500) } }
            runCurrent()
            val socket = sockets.opened.single()
            val id = socket.requestedSubscriptions().single()
            socket.deliverEvent(id, event)
            when (failure) {
                "disconnect" -> socket.drop()
                "closed" -> socket.deliverRaw("""["CLOSED","$id","denied"]""")
                else -> advanceTimeBy(501)
            }
            runCurrent()
            assertTrue(result.await().isFailure, failure)
            pool.stop()
        }
    }

    @Test fun `authentication refusal identifies the failing relay even when another relay completes`() = runTest {
        val sockets = FakeSocketFactory()
        val pool = RelayPool(listOf("wss://one", "wss://two"), sockets, backgroundScope)
        pool.start(); runCurrent(); sockets.openAll()
        val result = async { runCatching { pool.queryStored(listOf(Filter(kinds = listOf(1059)))) } }
        runCurrent()
        val id = sockets.opened.first().requestedSubscriptions().single()
        sockets.opened[0].deliverRaw("""["EOSE","$id"]""")
        sockets.opened[1].deliverRaw("""["CLOSED","$id","ERROR: auth-required: requested filter requires authentication"]""")
        val failure = assertIs<RelayHistoryException>(result.await().exceptionOrNull())
        assertEquals("wss://two", failure.relay)
        assertTrue(failure.authenticationRequired)
        pool.stop()
    }

    @Test fun `strict history fails when one relay stays silent while best effort returns what answered after the grace`() = runTest {
        val sockets = FakeSocketFactory()
        val pool = RelayPool(listOf("wss://one", "wss://two"), sockets, backgroundScope)
        pool.start(); runCurrent(); sockets.openAll()
        val strict = async { runCatching { pool.queryStored(listOf(Filter(kinds = listOf(1463))), 10_000) } }
        val available = async { pool.queryAvailable(listOf(Filter(kinds = listOf(1463))), 10_000) }
        runCurrent()
        for (id in sockets.opened[0].requestedSubscriptions()) {
            sockets.opened[0].deliverEvent(id, event); sockets.opened[0].deliverRaw("""["EOSE","$id"]""")
        }
        advanceTimeBy(RelayPolicy().storedGraceMs - 1); runCurrent()
        assertFalse(available.isCompleted)
        advanceTimeBy(2); runCurrent()
        assertEquals(listOf(event), available.await())
        assertEquals(RelayPolicy().storedGraceMs + 1, currentTime)
        assertFalse(strict.isCompleted)
        advanceTimeBy(10_000); runCurrent()
        assertTrue(strict.await().isFailure)
        pool.stop()
    }

    @Test fun `best effort returns at once when every relay answers or goes, deduplicated`() = runTest {
        val sockets = FakeSocketFactory()
        val pool = RelayPool(listOf("wss://one", "wss://two", "wss://three"), sockets, backgroundScope)
        pool.start(); runCurrent(); sockets.openAll()
        val result = async { pool.queryAvailable(listOf(Filter(kinds = listOf(1463)))) }
        runCurrent()
        val id = sockets.opened.first().requestedSubscriptions().single()
        sockets.opened.take(2).forEach { it.deliverEvent(id, event); it.deliverRaw("""["EOSE","$id"]""") }
        sockets.opened[2].deliverRaw("""["CLOSED","$id","denied"]""")
        runCurrent()
        assertEquals(listOf(event), result.await())
        assertEquals(0, currentTime)
        assertTrue(sockets.opened.take(2).all { socket -> socket.sent.any { it.startsWith("[\"CLOSE\"") } })
        pool.stop()
    }

    @Test fun `best effort fails when no relay answers`() = runTest {
        for (failure in listOf("timeout", "closed")) {
            val sockets = FakeSocketFactory()
            val pool = RelayPool(listOf("wss://one", "wss://two"), sockets, backgroundScope)
            pool.start(); runCurrent(); sockets.openAll()
            val result = async { runCatching { pool.queryAvailable(listOf(Filter(kinds = listOf(1463))), 500) } }
            runCurrent()
            val id = sockets.opened.first().requestedSubscriptions().single()
            if (failure == "closed") sockets.opened.forEach { it.deliverRaw("""["CLOSED","$id","auth-required: sign in"]""") }
            else advanceTimeBy(501)
            runCurrent()
            val error = result.await().exceptionOrNull()
            assertNotNull(error, failure)
            assertFalse(error is CancellationException, failure)
            if (failure == "closed") assertTrue(assertIs<RelayHistoryException>(error).authenticationRequired)
            pool.stop()
        }
    }

    @Test fun `publication requires the matching OK and can succeed after another relay rejects`() = runTest {
        val sockets = FakeSocketFactory()
        val pool = RelayPool(listOf("wss://one", "wss://two"), sockets, backgroundScope)
        pool.start(); runCurrent(); sockets.openAll()
        val result = async { pool.publishConfirmed(event) }
        runCurrent()
        sockets.opened[0].deliverRaw("""["OK","wrong-id",true,""]""")
        sockets.opened[0].deliverRaw("""["OK","${event.id}",false,"denied"]""")
        runCurrent(); assertFalse(result.isCompleted)
        sockets.opened[1].deliverRaw("""["OK","${event.id}",true,""]""")
        assertTrue(result.await())
        val rejected = async { pool.publishConfirmed(event) }
        runCurrent()
        sockets.opened.forEach { it.deliverRaw("""["OK","${event.id}",false,"denied"]""") }
        assertFalse(rejected.await())
        val missing = async { runCatching { pool.publishConfirmed(event, 500) } }
        runCurrent(); advanceTimeBy(501); runCurrent()
        assertTrue(missing.await().isFailure)
        pool.stop()
    }
}
