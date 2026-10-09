package dev.forgesworn.kithmoot.relay

import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.session.Fixtures
import dev.forgesworn.kithmoot.session.session
import dev.forgesworn.kithmoot.support.FakeRelay
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import java.util.UUID
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class NativeRoomMeshLinkTest {
    private val config = RoomBleConfig("11".repeat(32), "22".repeat(32), UUID.fromString("446a88bc-5745-4a81-b2ec-10f7241afdae"))

    private class Radio(val event: (RoomBleEvent) -> Unit) : RoomBleRadio {
        val sent = mutableListOf<Pair<ByteArray, String?>>()
        var config: RoomBleConfig? = null
        var closed = false
        var failure: String? = null
        var failClose = false
        var queuedPeers = 1
        override fun start(config: RoomBleConfig) {
            this.config = config
            failure?.let { error(it) }
            event(RoomBleEvent.Status(true, 1))
        }
        override fun offer(bytes: ByteArray, to: String?): Int {
            check(!closed); sent += bytes to to
            return queuedPeers
        }
        override fun close() {
            closed = true
            if (failClose) error("close failed")
        }
    }
    private fun TestScope.link(radios: MutableList<Radio>, configure: (Radio) -> Unit = {}) =
        NativeRoomMeshLink({ listener -> Radio(listener).also { radios += it; configure(it) } }, StandardTestDispatcher(testScheduler))

    @Test fun `construction is inert and missing permissions never start another owner`() = runTest {
        val radios = mutableListOf<Radio>(); val link = link(radios) { it.failure = "PERMISSION_REQUIRED" }
        runCurrent(); assertTrue(radios.isEmpty()); assertFalse(link.reachable())
        assertFailsWith<IllegalStateException> { link.offer(byteArrayOf(1)) }
        assertFailsWith<IllegalStateException> { link.start(config) }
        assertEquals(1, radios.size); assertTrue(radios.single().closed)
        assertEquals(RoomBlePhase.FAILED, link.state.value.phase)
        assertEquals("PERMISSION_REQUIRED", link.state.value.error)
        assertFailsWith<IllegalStateException> { link.offer(byteArrayOf(1)) }
        link.close(); link.awaitClosed()
    }

    @Test fun `outbound input is snapshotted and receive callbacks are deferred`() = runTest {
        val radios = mutableListOf<Radio>(); val link = link(radios); link.start(config); runCurrent()
        assertTrue(link.reachable())
        val received = mutableListOf<ByteArray>(); link.subscribe { data, _ -> received += data }
        val bytes = byteArrayOf(1, 2)
        link.offer(bytes, "route-hint"); bytes[0] = 9
        assertTrue(radios.single().sent.isEmpty()); runCurrent()
        assertContentEquals(byteArrayOf(1, 2), radios.single().sent.single().first)
        assertEquals("route-hint", radios.single().sent.single().second)
        val inbound = byteArrayOf(3, 4); radios.single().event(RoomBleEvent.Frame(inbound, "hint")); inbound[0] = 8
        assertTrue(received.isEmpty()); runCurrent()
        assertContentEquals(byteArrayOf(3, 4), received.single())
        link.close(); link.awaitClosed()
    }

    @Test fun `rekey awaits old radio closure discards pending offers and rejects late old callbacks`() = runTest {
        val radios = mutableListOf<Radio>(); val link = link(radios); link.start(config); runCurrent()
        val transport = RoomMeshTransport(config.scope, link) { 100 }
        val event = Events.sign(Fixtures.key(2), 1460, 100, emptyList(), "ciphertext")
        transport.publish(event)
        val reset = async(start = CoroutineStart.UNDISPATCHED) { transport.beginRekey() }
        assertFalse(reset.isCompleted); assertFalse(transport.reachable())
        assertFailsWith<IllegalStateException> { transport.completeRekey() }
        runCurrent(); reset.await()
        assertEquals(2, radios.size); assertTrue(radios.first().closed); assertTrue(radios.first().sent.isEmpty())
        val received = mutableListOf<ByteArray>(); link.subscribe { bytes, _ -> received += bytes }
        radios.first().event(RoomBleEvent.Frame(byteArrayOf(1), "old"))
        radios.first().event(RoomBleEvent.Status(false, 0, "late error"))
        runCurrent(); assertTrue(received.isEmpty()); assertEquals(RoomBlePhase.READY, link.state.value.phase)
        transport.rekey(ByteArray(32)); transport.completeRekey()
        transport.publish(event); runCurrent(); assertEquals(1, radios.last().sent.size)
        transport.close(); link.awaitClosed()
    }

    @Test fun `failed physical teardown cannot reopen rekey`() = runTest {
        val radios = mutableListOf<Radio>(); val link = link(radios); link.start(config); runCurrent()
        radios.single().failClose = true
        val transport = RoomMeshTransport(config.scope, link)
        assertFailsWith<IllegalStateException> { transport.beginRekey() }
        assertFailsWith<IllegalStateException> { transport.completeRekey() }
        assertEquals(1, radios.size); assertEquals(RoomBlePhase.FAILED, link.state.value.phase)
        assertFailsWith<IllegalStateException> { link.start(config) }
        transport.close(); assertFailsWith<IllegalStateException> { link.awaitClosed() }
    }

    @Test fun `close is terminal and drains outstanding commands before another owner`() = runTest {
        val radios = mutableListOf<Radio>(); val link = link(radios); link.start(config)
        link.offer(byteArrayOf(1)); link.close(); link.close()
        assertFailsWith<IllegalStateException> { link.start(config) }
        assertFailsWith<IllegalStateException> { link.resetQueued() }
        assertFailsWith<IllegalStateException> { link.offer(byteArrayOf(1)) }
        link.awaitClosed(); assertTrue(radios.single().closed); assertTrue(radios.single().sent.isEmpty())
        assertEquals(RoomBlePhase.CLOSED, link.state.value.phase)
    }

    @Test fun `cancelled start cannot leave a radio running`() = runTest {
        val radios = mutableListOf<Radio>(); val link = link(radios)
        val start = launch(start = CoroutineStart.UNDISPATCHED) { link.start(config) }
        start.cancel(); start.join(); link.awaitClosed()
        assertTrue(radios.all { it.closed }); assertEquals(RoomBlePhase.CLOSED, link.state.value.phase)
    }

    @Test fun `host queue bounds bytes and frames without silently evicting offers`() = runTest {
        val radios = mutableListOf<Radio>(); val link = link(radios); link.start(config)
        repeat(32) { link.offer(byteArrayOf(it.toByte())) }
        assertFailsWith<IllegalStateException> { link.offer(byteArrayOf(33)) }
        runCurrent(); assertEquals(32, radios.single().sent.size)
        repeat(6) { link.offer(ByteArray(RoomMeshWire.MAX_BYTES)) }
        assertFailsWith<IllegalStateException> { link.offer(ByteArray(RoomMeshWire.MAX_BYTES)) }
        assertFailsWith<IllegalArgumentException> { link.offer(ByteArray(RoomMeshWire.MAX_BYTES + 1)) }
        link.close(); link.awaitClosed()
    }

    @Test fun `event overflow is bounded visible and stops further publication`() = runTest {
        val radios = mutableListOf<Radio>(); val link = link(radios); link.start(config); runCurrent()
        repeat(100) { radios.single().event(RoomBleEvent.Frame(byteArrayOf(1), "peer")) }
        assertEquals(RoomBlePhase.FAILED, link.state.value.phase)
        assertEquals("Bluetooth event queue full", link.state.value.error)
        assertFailsWith<IllegalStateException> { link.offer(byteArrayOf(1)) }
        link.close(); link.awaitClosed()
    }

    @Test fun `zero queued peers remains unconfirmed and cannot masquerade as delivery`() = runTest {
        val radios = mutableListOf<Radio>(); val link = link(radios) { it.queuedPeers = 0 }; link.start(config)
        val transport = RoomMeshTransport(config.scope, link) { 100 }
        val event = Events.sign(Fixtures.key(2), 1460, 100, emptyList(), "ciphertext")
        assertFailsWith<PublicationUnconfirmedException> { transport.publishConfirmed(event) }
        runCurrent(); assertEquals(0, link.state.value.lastQueuedPeers)
        assertFalse(transport.receivedEventConfirmsPublication(event.id))
        transport.close(); link.awaitClosed()
    }

    @Test fun `leaving after foreground radio closure cancels presence without a farewell crash`() = runTest {
        val radios = mutableListOf<Radio>(); val link = link(radios); link.start(config)
        val transport = RoomMeshTransport(config.scope, link) { currentTime / 1000 }
        val room = Fixtures.room()
        val live = session(room, Fixtures.primary(room, 1, 2), FakeRelay(), transport = transport)
        live.join(); runCurrent()
        transport.close(); link.awaitClosed()
        // A scheduled heartbeat while the failed lane is being left is best-effort.
        advanceTimeBy(45_000); runCurrent()
        live.leave(); runCurrent()
        assertTrue(radios.single().closed)
    }

    @Test fun `two actual room sessions chat through native owners with duplicate radio callbacks`() = runTest {
        val left = mutableListOf<Radio>(); val right = mutableListOf<Radio>()
        val aLink = link(left); val bLink = link(right)
        aLink.start(config); bLink.start(config.copy(selfId = "33".repeat(32)))
        val a = RoomMeshTransport(config.scope, aLink) { currentTime / 1000 }
        val b = RoomMeshTransport(config.scope, bLink) { currentTime / 1000 }
        val room = Fixtures.room()
        val alice = session(room, Fixtures.primary(room, 1, 2), FakeRelay(), transport = a)
        val bob = session(room, Fixtures.primary(room, 3, 4), FakeRelay(), transport = b)
        fun pump() {
            repeat(8) {
                runCurrent()
                val l = left.single().sent.toList(); left.single().sent.clear()
                val r = right.single().sent.toList(); right.single().sent.clear()
                l.forEach { (bytes, _) -> repeat(2) { right.single().event(RoomBleEvent.Frame(bytes, "alice")) } }
                r.forEach { (bytes, _) -> repeat(2) { left.single().event(RoomBleEvent.Frame(bytes, "bob")) } }
            }
            runCurrent()
        }
        alice.join(); bob.join(); pump(); advanceTimeBy(2_000); pump()
        alice.sendChat("Through the native owner"); pump()
        bob.sendChat("Reply on the same room"); pump()
        assertEquals(listOf("Through the native owner", "Reply on the same room"), alice.chat.value.map { it.body })
        assertEquals(alice.chat.value.map { it.id }, bob.chat.value.map { it.id })
        alice.leave(); bob.leave(); a.close(); b.close(); aLink.awaitClosed(); bLink.awaitClosed()
    }
    @Test fun `two actual room sessions merge native byte traffic and relay traffic without duplicate rows`() = runTest {
        val left = mutableListOf<Radio>(); val right = mutableListOf<Radio>()
        val aLink = link(left); val bLink = link(right)
        aLink.start(config); bLink.start(config.copy(selfId = "33".repeat(32)))
        val a = RoomMeshTransport(config.scope, aLink) { currentTime / 1000 }
        val b = RoomMeshTransport(config.scope, bLink) { currentTime / 1000 }
        val room = Fixtures.room()
        val relay = FakeRelay()
        val alice = session(room, Fixtures.primary(room, 1, 2), FakeRelay(), transport = HybridRoomTransport(a, relay.transport()))
        val bob = session(room, Fixtures.primary(room, 3, 4), FakeRelay(), transport = HybridRoomTransport(b, relay.transport()))
        fun pump() {
            repeat(8) {
                runCurrent()
                val l = left.single().sent.toList(); left.single().sent.clear()
                val r = right.single().sent.toList(); right.single().sent.clear()
                l.forEach { (bytes, _) -> repeat(2) { right.single().event(RoomBleEvent.Frame(bytes, "alice")) } }
                r.forEach { (bytes, _) -> repeat(2) { left.single().event(RoomBleEvent.Frame(bytes, "bob")) } }
            }
            runCurrent()
        }
        alice.join(); bob.join(); pump(); advanceTimeBy(2_000); pump()
        alice.sendChat("Through the native owner"); pump()
        bob.sendChat("Reply on the same room"); pump()
        assertEquals(listOf("Through the native owner", "Reply on the same room"), alice.chat.value.map { it.body })
        assertEquals(alice.chat.value.map { it.id }, bob.chat.value.map { it.id })
        alice.leave(); bob.leave(); a.close(); b.close(); aLink.awaitClosed(); bLink.awaitClosed()
    }
}
