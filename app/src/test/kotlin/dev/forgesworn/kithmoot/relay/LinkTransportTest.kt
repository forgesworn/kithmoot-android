package dev.forgesworn.kithmoot.relay

import dev.forgesworn.kithmoot.storage.RoomStorage
import dev.forgesworn.kithmoot.storage.RoomStorageException
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LinkTransportTest {
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

    @Test fun `retirement waits for native acknowledgement before local removal`() {
        val vault = LinkTransportVault(MemoryStorage())
        vault.upsert(route("route-1"))
        val runtime = RetiringRuntime()
        val manager = LinkTransportManager(vault, runtime)

        manager.retire("route-1").get()

        assertEquals(listOf("route-1"), runtime.retired)
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
        override fun read(): ByteArray? = value?.copyOf()
        override fun write(value: ByteArray) { this.value = value.copyOf() }
        override fun reset() { value = null }
    }
    private class RecordingRuntime : LinkTransportRuntime {
        var starts = 0
        override fun start(state: LinkTransportState): LinkTransportSession = error("must not start")
    }
    private class RetiringRuntime : LinkTransportRuntime {
        val retired = mutableListOf<String>()
        override fun start(state: LinkTransportState): LinkTransportSession = object : LinkTransportSession {
            override fun open(url: String, routeId: String, listener: RelaySocketListener): LinkTransportSocket = error("not needed")
            override fun pair(routeId: String, card: ByteArray, pairingSecret: ByteArray, expiresAt: ULong): StoredLinkRoute = error("not needed")
            override fun upsert(route: StoredLinkRoute) = Unit
            override fun retire(routeId: String) { retired += routeId }
            override fun remove(routeId: String) = Unit
            override fun stop() = Unit
        }
    }
}
