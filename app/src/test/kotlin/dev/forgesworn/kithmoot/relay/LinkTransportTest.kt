package dev.forgesworn.kithmoot.relay

import dev.forgesworn.kithmoot.storage.RoomStorage
import dev.forgesworn.kithmoot.storage.RoomStorageException
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.util.concurrent.ExecutionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

class LinkTransportTest {
    @Test fun `relay send reaches native socket before returning even when callback worker is busy`() {
        val worker = Executors.newSingleThreadExecutor()
        val busy = CountDownLatch(1)
        val release = CountDownLatch(1)
        worker.execute { busy.countDown(); release.await(2, TimeUnit.SECONDS) }
        assertTrue(busy.await(2, TimeUnit.SECONDS))
        val sent = mutableListOf<String>()
        val pending = PendingLinkSocket(object : RelaySocketListener {
            override fun onOpen() = Unit
            override fun onMessage(text: String) = Unit
            override fun onClosed(reason: String) = Unit
        }, worker)
        pending.attach(object : LinkTransportSocket {
            override fun send(text: String) { sent += text }
            override fun disconnect() = Unit
            override fun dispose() = Unit
        })
        try {
            pending.send("guarded event")
            assertEquals(listOf("guarded event"), sent)
        } finally {
            pending.close()
            release.countDown()
            worker.shutdown()
            assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test fun `native callback cannot invert the relay and pending socket locks`() {
        val worker = Executors.newSingleThreadExecutor()
        val poolLock = Any()
        val callbackReturned = CountDownLatch(1)
        val delivered = CountDownLatch(1)
        val pending = PendingLinkSocket(object : RelaySocketListener {
            override fun onOpen() = Unit
            override fun onMessage(text: String) { synchronized(poolLock) { delivered.countDown() } }
            override fun onClosed(reason: String) = Unit
        }, worker)
        pending.attach(object : LinkTransportSocket {
            override fun send(text: String) {
                val callback = Thread {
                    pending.onMessage("ack")
                    callbackReturned.countDown()
                }
                callback.start()
                callback.join(2_000)
                check(!callback.isAlive) { "native send waited on the callback's socket monitor" }
            }
            override fun disconnect() = Unit
            override fun dispose() = Unit
        })
        try {
            synchronized(poolLock) {
                pending.send("event")
                assertTrue(callbackReturned.await(2, TimeUnit.SECONDS), "send blocked while the relay lock was held")
            }
            assertTrue(delivered.await(2, TimeUnit.SECONDS))
        } finally {
            pending.close()
            worker.shutdown()
            assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test fun `open callback before native attach still flushes ordered first send`() {
        val worker = Executors.newSingleThreadExecutor()
        val sent = CountDownLatch(1)
        val events = mutableListOf<String>()
        lateinit var pending: PendingLinkSocket
        pending = PendingLinkSocket(object : RelaySocketListener {
            override fun onOpen() { synchronized(events) { events += "open" }; pending.send("first") }
            override fun onMessage(text: String) { synchronized(events) { events += "message:$text" } }
            override fun onClosed(reason: String) = Unit
        }, worker)
        pending.onOpen()
        pending.attach(object : LinkTransportSocket {
            override fun send(text: String) { synchronized(events) { events += "send:$text" }; sent.countDown() }
            override fun disconnect() = Unit
            override fun dispose() = Unit
        })
        try {
            assertTrue(sent.await(2, TimeUnit.SECONDS))
            assertEquals(listOf("open", "send:first"), synchronized(events) { events.toList() })
        } finally {
            pending.close()
            worker.shutdown()
            assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test fun `inbound overflow closes once and drops queued frames before terminal callback`() {
        val worker = Executors.newSingleThreadExecutor()
        val busy = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val events = mutableListOf<String>()
        worker.execute { busy.countDown(); release.await(2, TimeUnit.SECONDS) }
        assertTrue(busy.await(2, TimeUnit.SECONDS))
        val pending = PendingLinkSocket(object : RelaySocketListener {
            override fun onOpen() { events += "open" }
            override fun onMessage(text: String) { events += text }
            override fun onClosed(reason: String) { events += "closed:$reason"; closed.countDown() }
        }, worker)
        pending.attach(object : LinkTransportSocket {
            override fun send(text: String) = Unit
            override fun disconnect() = Unit
            override fun dispose() = Unit
        })
        try {
            pending.onOpen()
            repeat(128) { pending.onMessage("frame") }
            pending.onClosed("late native close")
            release.countDown()
            assertTrue(closed.await(2, TimeUnit.SECONDS))
            assertEquals(listOf("closed:Link receive queue is full"), events)
        } finally {
            release.countDown()
            pending.close()
            worker.shutdown()
            assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test fun `worker rejection during shutdown does not throw into native callback`() {
        var disposed = false
        val pending = PendingLinkSocket(object : RelaySocketListener {
            override fun onOpen() = Unit
            override fun onMessage(text: String) = Unit
            override fun onClosed(reason: String) = Unit
        }, java.util.concurrent.Executor { throw RejectedExecutionException("stopped") })
        pending.attach(object : LinkTransportSocket {
            override fun send(text: String) = Unit
            override fun disconnect() = Unit
            override fun dispose() { disposed = true }
        })
        pending.onMessage("late frame")
        assertTrue(disposed)
    }
    @Test fun `vault makes and retains a 32 byte seed`() {
        val storage = MemoryStorage()
        val vault = LinkTransportVault(storage, SecureRandom(byteArrayOf(7)))

        val first = vault.state()
        val second = LinkTransportVault(storage).state()

        assertEquals(32, first.transportSeed.size)
        assertContentEquals(first.transportSeed, second.transportSeed)
        assertTrue(storage.value!!.isNotEmpty())
    }

    @Test fun `route secret is separate from the seed and supports unsigned serial`() {
        val vault = LinkTransportVault(MemoryStorage())
        val route = route("route-1").copy(cardSerial = ULong.MAX_VALUE, cardVerifiedAt = ULong.MAX_VALUE)

        vault.upsert(route)
        val restored = vault.state().routes.single()

        assertEquals(ULong.MAX_VALUE, restored.cardSerial)
        assertEquals(ULong.MAX_VALUE, restored.cardVerifiedAt)
        assertContentEquals(route.pairedRouteSecret, restored.pairedRouteSecret)
    }

    @Test fun `corrupt state fails closed without minting another identity`() {
        val storage = MemoryStorage("{bad".toByteArray())
        val vault = LinkTransportVault(storage)

        assertFailsWith<RoomStorageException> { vault.state() }
        assertContentEquals("{bad".toByteArray(), storage.value)
    }

    @Test fun `manager does not start native engine until a known route opens`() {
        val vault = LinkTransportVault(MemoryStorage())
        val runtime = RecordingRuntime()
        val manager = LinkTransportManager(vault, runtime)
        val events = mutableListOf<String>()
        manager.open("ws://x/events", "absent", listener(events))
        waitFor { events.isNotEmpty() }

        assertEquals(listOf("closed:Unknown Link route"), events)
        assertEquals(0, runtime.starts)
        manager.close()
    }

    @Test fun `manager lists persisted routes without starting native engine`() {
        val vault = LinkTransportVault(MemoryStorage())
        vault.upsert(route("route-1"))
        vault.upsert(route("route-2"))
        val runtime = RecordingRuntime()
        val manager = LinkTransportManager(vault, runtime)

        assertEquals(setOf("route-1", "route-2"), manager.routeIds())
        assertEquals(0, runtime.starts)
        manager.remove("route-1")
        assertEquals(setOf("route-2"), manager.routeIds())
        manager.close()
    }

    @Test fun `startup route enumeration leaves an absent identity absent`() {
        val storage = MemoryStorage()
        val random = CountingRandom()
        val runtime = RecordingRuntime()
        val manager = LinkTransportManager(LinkTransportVault(storage, random), runtime)
        try {
            repeat(2) { assertTrue(manager.routeIds().isEmpty()) }
            assertEquals(0, random.calls, "Reading routes must not create a transport seed")
            assertEquals(0, storage.writes, "Reading routes must not create a transport vault")
            assertNull(storage.value)
            assertEquals(0, runtime.starts)
            println("LINK_ROUTE_READ case=absent enumerations=2 routes=0 randomCalls=0 writes=0 nativeStarts=0")
        } finally { manager.close(); storage.value?.fill(0) }
    }

    @Test fun `startup route enumeration preserves existing seed routes and bytes`() {
        val storage = MemoryStorage()
        val random = CountingRandom()
        val vault = LinkTransportVault(storage, random)
        vault.upsert(route("route-1")); vault.upsert(route("route-2"))
        val before = storage.value!!.copyOf()
        val seed = vault.state().transportSeed
        val runtime = RecordingRuntime()
        val manager = LinkTransportManager(vault, runtime)
        storage.writes = 0; random.calls = 0
        try {
            repeat(2) { assertEquals(setOf("route-1", "route-2"), manager.routeIds()) }
            assertContentEquals(before, storage.value)
            val reopened = LinkTransportVault(storage).state()
            try { assertContentEquals(seed, reopened.transportSeed) }
            finally {
                reopened.transportSeed.fill(0)
                reopened.routes.forEach { it.card.fill(0); it.pairedRouteSecret.fill(0) }
            }
            assertEquals(0, random.calls); assertEquals(0, storage.writes); assertEquals(0, runtime.starts)
            println("LINK_ROUTE_READ case=existing enumerations=2 routes=2 randomCalls=0 writes=0 nativeStarts=0 bytesUnchanged=true seedUnchanged=true")
        } finally { manager.close(); before.fill(0); seed.fill(0); storage.value?.fill(0) }
    }

    @Test fun `startup route enumeration refuses corrupt and unreadable vaults without replacement`() {
        for (fault in listOf("corrupt", "unreadable")) {
            val bytes = "{bad".toByteArray()
            var writes = 0
            val storage = object : RoomStorage {
                override fun read(): ByteArray {
                    if (fault == "unreadable") error("Fixture storage is unavailable")
                    return bytes.copyOf()
                }
                override fun write(value: ByteArray) { writes++; error("Refusal must not write") }
                override fun reset() = error("Refusal must not reset")
            }
            val random = CountingRandom()
            val runtime = RecordingRuntime()
            val manager = LinkTransportManager(LinkTransportVault(storage, random), runtime)
            try {
                assertFailsWith<RoomStorageException> { manager.routeIds() }
                assertContentEquals("{bad".toByteArray(), bytes)
                assertEquals(0, writes); assertEquals(0, random.calls); assertEquals(0, runtime.starts)
                println("LINK_ROUTE_READ case=$fault randomCalls=0 writes=0 nativeStarts=0 bytesUnchanged=true")
            } finally { manager.close(); bytes.fill(0) }
        }
    }

    @Test fun `manager sends exact cadence bytes on its Link worker`() {
        val vault = LinkTransportVault(MemoryStorage()).apply { upsert(route("route-1")) }
        val runtime = RequestRuntime()
        val manager = LinkTransportManager(vault, runtime)
        val body = "{\"v\":1}".toByteArray()
        val request = LinkJsonRequest("route-1", "POST", "/cadence/v1/status", "Nostr dGVzdA==", body)

        val response = manager.request(request).get()

        assertEquals(request, runtime.request!!.copy(body = body))
        assertContentEquals(body, runtime.request!!.body)
        assertEquals("kithmoot-link", runtime.thread)
        assertEquals(403, response.status)
        assertContentEquals("{\"v\":1,\"code\":\"scope\"}".toByteArray(), response.body)
        assertEquals("relayed", response.path.status)
        manager.close()
    }

    @Test fun `unknown cadence route fails without starting native engine`() {
        val runtime = RecordingRuntime()
        val manager = LinkTransportManager(LinkTransportVault(MemoryStorage()), runtime)

        val failure = assertFailsWith<ExecutionException> {
            manager.request(LinkJsonRequest("absent", "POST", "/cadence/v1/status", "Nostr dGVzdA==", byteArrayOf(1))).get()
        }

        assertEquals("Unknown Link route", failure.cause?.message)
        assertEquals(0, runtime.starts)
        manager.close()
    }

    @Test fun `retirement waits for native acknowledgement before local removal`() {
        val vault = LinkTransportVault(MemoryStorage())
        vault.upsert(route("route-1"))
        val runtime = RetiringRuntime()
        val manager = LinkTransportManager(vault, runtime)

        manager.retire("route-1").get()
        manager.finalize("route-1").get()

        assertEquals(listOf("route-1"), runtime.retired)
        assertEquals(listOf("route-1"), runtime.finalized)
        assertEquals(setOf("route-1"), manager.routeIds(), "acknowledgement does not remove the retry credential")
        manager.remove("route-1")
        assertTrue(manager.routeIds().isEmpty())
        manager.close()
    }

    private fun route(id: String) = StoredLinkRoute(id, byteArrayOf(1, 2), ByteArray(32) { 3 }, 1UL, 2UL)
    private fun listener(events: MutableList<String>) = object : RelaySocketListener {
        override fun onOpen() { events += "open" }
        override fun onMessage(text: String) { events += "message:$text" }
        override fun onClosed(reason: String) { events += "closed:$reason" }
    }
    private fun waitFor(predicate: () -> Boolean) {
        repeat(100) { if (predicate()) return; Thread.sleep(10) }
        error("timed out")
    }

    private class MemoryStorage(initial: ByteArray? = null) : RoomStorage {
        var value = initial
        var writes = 0
        override fun read(): ByteArray? = value?.copyOf()
        override fun write(value: ByteArray) { writes++; this.value = value.copyOf() }
        override fun reset() { value = null }
    }
    private class CountingRandom : SecureRandom() {
        var calls = 0
        override fun nextBytes(bytes: ByteArray) { calls++; super.nextBytes(bytes) }
    }
    private class RecordingRuntime : LinkTransportRuntime {
        var starts = 0
        override fun start(state: LinkTransportState): LinkTransportSession { starts++; error("must not start") }
    }
    private class RetiringRuntime : LinkTransportRuntime {
        val retired = mutableListOf<String>()
        val finalized = mutableListOf<String>()
        override fun start(state: LinkTransportState): LinkTransportSession = object : LinkTransportSession {
            override fun open(url: String, routeId: String, listener: RelaySocketListener): LinkTransportSocket = error("not needed")
            override fun request(request: LinkJsonRequest): LinkJsonResponse = error("not needed")
            override fun pair(routeId: String, card: ByteArray, pairingSecret: ByteArray, expiresAt: ULong): StoredLinkRoute = error("not needed")
            override fun upsert(route: StoredLinkRoute) = Unit
            override fun retire(routeId: String) { retired += routeId }
            override fun finalize(routeId: String) { finalized += routeId }
            override fun remove(routeId: String) = Unit
            override fun stop() = Unit
        }
    }

    private class RequestRuntime : LinkTransportRuntime {
        var request: LinkJsonRequest? = null
        var thread: String? = null
        override fun start(state: LinkTransportState): LinkTransportSession = object : LinkTransportSession {
            override fun open(url: String, routeId: String, listener: RelaySocketListener): LinkTransportSocket = error("not needed")
            override fun request(request: LinkJsonRequest): LinkJsonResponse {
                this@RequestRuntime.request = request
                thread = Thread.currentThread().name
                return LinkJsonResponse(403, "{\"v\":1,\"code\":\"scope\"}".toByteArray(), LinkPathState("relayed", "relay", null, ""))
            }
            override fun pair(routeId: String, card: ByteArray, pairingSecret: ByteArray, expiresAt: ULong): StoredLinkRoute = error("not needed")
            override fun upsert(route: StoredLinkRoute) = Unit
            override fun retire(routeId: String) = Unit
            override fun finalize(routeId: String) = Unit
            override fun remove(routeId: String) = Unit
            override fun stop() = Unit
        }
    }
}
