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
