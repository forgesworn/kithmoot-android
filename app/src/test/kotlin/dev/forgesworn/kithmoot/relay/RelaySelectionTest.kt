package dev.forgesworn.kithmoot.relay

import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.support.FakeSocketFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import org.junit.Test
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class RelaySelectionTest {
    @Test fun successfulRoomSyncDoesNotHideAProjectRelayRefusal() {
        val combined = combinedRelayHealth(listOf(
            mapOf("wss://one" to RelayHealth("Connected", "History read confirmed", "Write accepted")),
            mapOf("wss://one" to RelayHealth("Connected", "Authentication required", "Not checked")),
        ))
        assertEquals("Connected", combined["wss://one"]?.connection)
        assertEquals("Authentication required", combined["wss://one"]?.read)
        assertEquals("Write accepted", combined["wss://one"]?.write)
    }
    @Test fun roundTripsDirectionalChoicesAndWritesNip65Markers() {
        val choices = listOf(RelayChoice("wss://read", true, false), RelayChoice("wss://write", false, true), RelayChoice("wss://both"), RelayChoice("wss://disabled", false, false))
        assertEquals(choices, RelaySelection.decode(RelaySelection.encode(choices)))
        assertEquals(listOf(listOf("r", "wss://read", "read"), listOf("r", "wss://write", "write"), listOf("r", "wss://both")), RelaySelection.tags(choices))
        assertFails { RelaySelection.validate(listOf(RelayChoice("wss://read", true, false))) }
        assertFails { RelaySelection.validate(listOf(RelayChoice("https://not-websocket"))) }
        assertFails { RelaySelection.validate(listOf(RelayChoice("wss://same"), RelayChoice("wss://same/"))) }
    }
    @Test fun onlyReadsFromReadRelaysAndOnlyPublishesToWriteRelaysIncludingReconnect() = runTest {
        val sockets = FakeSocketFactory()
        val pool = RelayPool(listOf("wss://read", "wss://write", "wss://off"), sockets, backgroundScope,
            readRelays = setOf("wss://read"), writeRelays = setOf("wss://write"))
        pool.start(); runCurrent(); sockets.openAll()
        assertEquals(2, sockets.opened.size)
        val collector = backgroundScope.launch { pool.subscribe(listOf(Filter(kinds = listOf(1)))).collect() }; runCurrent()
        assertEquals(1, sockets.opened[0].requestedSubscriptions().size); assertTrue(sockets.opened[1].requestedSubscriptions().isEmpty())
        val event = Events.sign(ByteArray(32) { 3 }, 1, 100, emptyList(), "Test")
        val result = async { pool.publishConfirmed(event) }; runCurrent()
        assertTrue(sockets.opened[0].sent.none { it.contains(event.id) })
        assertTrue(sockets.opened[1].sent.any { it.contains(event.id) })
        sockets.opened[1].deliverRaw("""["OK","${event.id}",false,"denied"]""")
        assertFalse(result.await()); assertEquals("Write refused", pool.health.value["wss://write"]?.write)
        val id = sockets.opened[0].requestedSubscriptions().single()
        sockets.opened[0].deliverRaw("""["CLOSED","$id","auth-required: account needed"]""")
        assertEquals("Authentication required", pool.health.value["wss://read"]?.read)
        sockets.opened[1].drop(); advanceTimeBy(40_000); runCurrent(); sockets.openAll(); runCurrent()
        assertTrue(sockets.opened.last().requestedSubscriptions().isEmpty())
        collector.cancel(); pool.stop()
    }
}
