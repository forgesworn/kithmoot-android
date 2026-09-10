package dev.forgesworn.kithmoot.discovery

import dev.forgesworn.kithmoot.relay.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BoxRelayReaderTest {
    private class Socket(val listener: RelaySocketListener) : RelaySocket {
        val sent = mutableListOf<String>()
        var closed = false
        override fun send(text: String) { sent += text }
        override fun close() { closed = true; listener.onClosed("closed") }
        fun frame(text: String) { listener.onMessage(text) }
    }
    @Test fun actualEoseFromEveryRelayIsRequiredAndNoWritesAreSent() = runTest {
        val sockets = mutableListOf<Socket>(); var ready = 0; var lost = 0
        val reader = BoxRelayReader(listOf("wss://one.test", "wss://two.test"), RelaySocketFactory { _, l -> Socket(l).also { sockets += it; l.onOpen() } }, backgroundScope, { lost++ }, { testScheduler.currentTime })
        try {
            reader.subscribe(Filter(kinds = listOf(10640)), {}, { ready++ }); runCurrent()
            sockets[0].frame("[\"EOSE\",\"box-1\"]"); runCurrent(); assertEquals(0, ready)
            sockets[1].frame("[\"EOSE\",\"box-1\",\"extra\"]"); runCurrent(); assertEquals(0, ready)
            sockets[1].frame("[\"EOSE\",\"box-1\"]"); runCurrent(); assertEquals(1, ready); assertTrue(reader.trustedHistory())
            assertTrue(sockets.flatMap { it.sent }.all { it.startsWith("[\"REQ\"") }); assertEquals(0, lost)
        } finally { reader.close(); runCurrent() }
    }
    @Test fun timeoutAndDisconnectInvalidateBeforeReconnectionHistory() = runTest {
        val sockets = mutableListOf<Socket>(); var ready = 0; var lost = 0
        val reader = BoxRelayReader(listOf("wss://one.test"), RelaySocketFactory { _, l -> Socket(l).also { sockets += it; l.onOpen() } }, backgroundScope, { lost++ }, { testScheduler.currentTime })
        try {
            reader.subscribe(Filter(kinds = listOf(10640)), {}, { ready++ }); runCurrent()
            advanceTimeBy(15_000); runCurrent(); assertEquals(0, ready); assertEquals(1, lost); assertTrue(sockets[0].closed)
            advanceTimeBy(5_000); runCurrent(); sockets[1].frame("[\"EOSE\",\"box-1\"]"); runCurrent(); assertTrue(reader.trustedHistory())
            sockets[1].close(); assertFalse(reader.trustedHistory()); runCurrent(); assertEquals(2, lost)
            // A late completion from the dead socket cannot restore trust.
            sockets[1].frame("[\"EOSE\",\"box-1\"]"); runCurrent(); assertFalse(reader.trustedHistory())
            advanceTimeBy(5_000); runCurrent(); assertFalse(reader.trustedHistory())
            sockets[2].frame("[\"EOSE\",\"box-1\"]"); runCurrent(); assertTrue(reader.trustedHistory()); assertEquals(2, ready)
        } finally { reader.close(); runCurrent() }
    }
    @Test fun overflowAndDeepOrOversizedJsonFailClosed() = runTest {
        val sockets = mutableListOf<Socket>(); var lost = 0
        val reader = BoxRelayReader(listOf("wss://one.test"), RelaySocketFactory { _, l -> Socket(l).also { sockets += it; l.onOpen() } }, backgroundScope, { lost++ }, { testScheduler.currentTime })
        try {
            reader.subscribe(Filter(kinds = listOf(10640)), {}, {}); runCurrent()
            repeat(66) { sockets[0].frame("[\"NOTICE\",\"noise\"]") }; runCurrent(); assertEquals(1, lost)
            advanceTimeBy(5_000); runCurrent(); sockets[1].frame("[".repeat(1000) + "]".repeat(1000)); runCurrent(); assertEquals(2, lost)
            advanceTimeBy(5_000); runCurrent(); sockets[2].frame("x".repeat(33_001)); runCurrent(); assertEquals(3, lost)
        } finally { reader.close(); runCurrent() }
        advanceTimeBy(60_000); runCurrent(); assertEquals(3, sockets.size)
    }
    @Test fun completedExactLookupCanCloseWhileOtherHistoriesRemainLive() = runTest {
        val sockets = mutableListOf<Socket>(); var ready = 0; var lost = 0
        val reader = BoxRelayReader(listOf("wss://one.test", "wss://two.test"), RelaySocketFactory { _, l -> Socket(l).also { sockets += it; l.onOpen() } }, backgroundScope, { lost++ }, { testScheduler.currentTime })
        try {
            reader.subscribe(Filter(ids = listOf("a".repeat(64))), {}, { ready++ })
            reader.subscribe(Filter(kinds = listOf(10640)), {}, { ready++ }); runCurrent()
            sockets[0].frame("[\"EOSE\",\"box-1\"]")
            sockets[0].frame("[\"CLOSED\",\"box-1\",\"stored: all requested events found\"]"); runCurrent()
            assertEquals(0, lost); assertEquals(0, ready); assertFalse(reader.trustedHistory())
            sockets[1].frame("[\"EOSE\",\"box-1\"]")
            sockets[1].frame("[\"CLOSED\",\"box-1\",\"stored: all requested events found\"]"); runCurrent()
            assertEquals(0, lost); assertEquals(1, ready)
            sockets.forEach { it.frame("[\"EOSE\",\"box-2\"]") }; runCurrent()
            assertEquals(2, ready); assertTrue(reader.trustedHistory())
            sockets[0].frame("[\"CLOSED\",\"box-2\",\"live history lost\"]"); runCurrent()
            assertEquals(1, lost); assertFalse(reader.trustedHistory())
        } finally { reader.close(); runCurrent() }
    }
    @Test fun incompleteAndPrefixLookupClosuresStillFail() = runTest {
        for (prefix in listOf(false, true)) {
            val sockets = mutableListOf<Socket>(); var lost = 0
            val reader = BoxRelayReader(listOf("wss://one.test"), RelaySocketFactory { _, l -> Socket(l).also { sockets += it; l.onOpen() } }, backgroundScope, { lost++ }, { testScheduler.currentTime })
            try {
                reader.subscribe(Filter(ids = listOf(if (prefix) "abcd" else "a".repeat(64))), {}, {}); runCurrent()
                if (prefix) sockets[0].frame("[\"EOSE\",\"box-1\"]")
                sockets[0].frame("[\"CLOSED\",\"box-1\",\"restricted\"]"); runCurrent()
                assertEquals(1, lost); assertFalse(reader.trustedHistory())
            } finally { reader.close(); runCurrent() }
        }
    }
    @Test fun fullSignedLifecycleUsesRealReaderCompletionAndInvalidation() = runTest {
        val f = BoxFixture(); val sockets = mutableListOf<Socket>()
        val d = BoxDiscovery(f.contacts, { unavailable -> BoxRelayReader(listOf("wss://one.test"), RelaySocketFactory { _, l -> Socket(l).also { sockets += it; l.onOpen() } }, backgroundScope, unavailable, { testScheduler.currentTime }) }, {}, { f.clock })
        try {
            d.setEnabled(f.master, f.box, true); runCurrent(); val socket = sockets.single()
            fun respond() {
                val requests = socket.sent.filter { it.startsWith("[\"REQ\"") }.map { Json.parseToJsonElement(it).jsonArray }
                for (request in requests) {
                    val id = request[1].jsonPrimitive.content; val filter = request[2].jsonObject
                    val kind = filter.getValue("kinds").jsonArray.first().jsonPrimitive.int
                    val event = if (kind == 30640) f.claim else f.status
                    socket.frame("[\"EVENT\",\"$id\",${event.toCompactJson()}]")
                    socket.frame("[\"EOSE\",\"$id\"]")
                    if (filter.containsKey("ids")) socket.frame("[\"CLOSED\",\"$id\",\"stored: all requested events found\"]")
                }
            }
            respond(); runCurrent(); respond(); runCurrent()
            assertEquals(setOf("wss://owned.example/drops"), d.circleRelays())
            socket.close(); assertTrue(d.circleRelays().isEmpty()); runCurrent()
            assertTrue(d.message(f.master, f.box).contains("unavailable"))
        } finally { d.close(); runCurrent() }
    }
}
