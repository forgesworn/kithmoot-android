package dev.forgesworn.kithmoot.node

import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.support.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class NodeRoomTransportTest {
    private val now = 1_900_000_000L
    private val root = LocalSigner(ByteArray(32) { 3 })
    private val device = LocalSigner(ByteArray(32) { 4 })
    private val service = LocalSigner(ByteArray(32) { 5 })
    private val node = requireNotNull(NodeRooms.read(NodeRooms.sign(root.secretKeyForStorage(), "ee".repeat(32), "https://node.example", service.pubkey, now).toJson(), now))
    private fun event(end: Long = now + 300, kind: Int = 1400) = Events.sign(device.secretKeyForStorage(), kind, now, listOf(listOf("d", node.room), listOf("expiration", end.toString())), "opaque")
    private fun auth(socket: FakeSocket) = NostrEvent.fromJson(Json.parseToJsonElement(socket.authFrames().single()).jsonArray[1])
    @Test fun `silent authenticated sockets are replaced and current subscriptions return after fresh AUTH`(): Unit = runTest {
        val sockets = FakeSocketFactory()
        val transport = NodeRoomTransport.create(node, device, NodeDoor.ROOM, sockets, backgroundScope, now = { now })
        val reader = backgroundScope.launch { transport.subscribe(listOf(dev.forgesworn.kithmoot.relay.Filter(kinds = listOf(1400)))).collect {} }
        transport.start(); runCurrent()
        val first = sockets.opened.single(); first.open(); first.deliverAuth("first"); runCurrent()
        first.deliverOk(auth(first).id, true); runCurrent()
        val original = first.requestedSubscriptions().single()
        advanceTimeBy(5_000); runCurrent(); assertEquals(2, first.requestedSubscriptions().size)
        advanceTimeBy(4_000); runCurrent(); assertTrue(first.closedByPool)
        advanceTimeBy(4_000); runCurrent()
        val second = sockets.opened.last(); assertTrue(second !== first)
        second.open(); second.deliverAuth("second"); runCurrent()
        assertTrue(second.requestedSubscriptions().isEmpty())
        second.deliverOk(auth(second).id, true); runCurrent()
        assertEquals(listOf(original), second.requestedSubscriptions())
        first.drop(); first.deliverAuth("late"); runCurrent()
        assertEquals(setOf(node.relay), transport.connected.value)
        reader.cancel(); transport.close()
    }
    @Test fun `an authenticated EOSE probe keeps an idle socket alive without reopening or changing subscriptions`(): Unit = runTest {
        val sockets = FakeSocketFactory()
        val transport = NodeRoomTransport.create(node, device, NodeDoor.ROOM, sockets, backgroundScope, now = { now })
        transport.start(); runCurrent()
        val socket = sockets.opened.single(); socket.open(); socket.deliverAuth("first"); runCurrent()
        socket.deliverOk(auth(socket).id, true); runCurrent()
        repeat(3) {
            advanceTimeBy(5_000); runCurrent()
            val probe = socket.requestedSubscriptions().last()
            socket.deliverRaw("[\"EOSE\",\"$probe\"]"); runCurrent()
            assertTrue(socket.sent.contains("[\"CLOSE\",\"$probe\"]"))
        }
        assertEquals(1, sockets.opened.size); assertFalse(socket.closedByPool); transport.close()
    }
    @Test fun `one pinned relay receives exact room scoped AUTH before queued chat`(): Unit = runTest {
        val sockets = FakeSocketFactory()
        val transport = NodeRoomTransport.create(node, device, NodeDoor.ROOM, sockets, backgroundScope, now = { now })
        transport.start(); runCurrent(); transport.publish(event())
        val socket = sockets.opened.single(); assertEquals(node.relay, socket.url)
        socket.open(); runCurrent(); assertTrue(socket.publishedFrames().isEmpty())
        socket.deliverAuth("challenge"); runCurrent()
        val signed = auth(socket)
        assertEquals(device.pubkey, signed.pubkey); assertTrue(Events.verify(signed))
        assertEquals(listOf(listOf("relay", node.relay), listOf("challenge", "challenge"), listOf("room", node.room), listOf("mode", "room")), signed.tags)
        assertTrue(socket.publishedFrames().isEmpty())
        socket.deliverOk(signed.id, true); runCurrent(); assertEquals(1, socket.publishedFrames().size)
        assertTrue(transport.boundTo(node, device.pubkey)); assertFalse(transport.boundTo(node, root.pubkey)); transport.close()
    }
    @Test fun `expired queued events never leave after AUTH succeeds`(): Unit = runTest {
        var clock = now
        val sockets = FakeSocketFactory()
        val transport = NodeRoomTransport.create(node, device, NodeDoor.ROOM, sockets, backgroundScope, now = { clock })
        transport.start(); runCurrent(); transport.publish(event(now + 2))
        val socket = sockets.opened.single(); socket.open(); runCurrent(); socket.deliverAuth("challenge"); runCurrent()
        clock += 3; socket.deliverOk(auth(socket).id, true); runCurrent()
        assertTrue(socket.publishedFrames().isEmpty()); transport.close()
    }
    @Test fun `withdrawal blocks AUTH and closes the sole socket without fallback`(): Unit = runTest {
        var allowed = true
        val sockets = FakeSocketFactory()
        val transport = NodeRoomTransport.create(node, device, NodeDoor.ROOM, sockets, backgroundScope, now = { now }, allowed = { allowed })
        transport.start(); runCurrent(); val socket = sockets.opened.single(); socket.open(); runCurrent()
        allowed = false; socket.deliverAuth("challenge"); runCurrent()
        assertTrue(socket.authFrames().isEmpty()); assertFailsWith<IllegalStateException> { transport.publish(event()) }
        advanceTimeBy(1001); runCurrent(); assertTrue(socket.closedByPool); assertEquals(1, sockets.opened.size)
        assertFalse(transport.boundTo(node, device.pubkey)); transport.close()
    }
    @Test fun `ask door permits only bounded gifts and root cannot authenticate on room door`(): Unit = runTest {
        val sockets = FakeSocketFactory()
        assertFailsWith<IllegalArgumentException> { NodeRoomTransport.create(node, root, NodeDoor.ROOM, sockets, backgroundScope, now = { now }) }
        val inbox = NodeRoomTransport.create(node, root, NodeDoor.ASK, sockets, backgroundScope, now = { now })
        inbox.start(); runCurrent(); val socket = sockets.opened.single(); socket.open(); runCurrent(); socket.deliverAuth("inbox"); runCurrent()
        assertEquals(root.pubkey, auth(socket).pubkey); assertEquals(listOf("mode", "ask"), auth(socket).tags.last())
        assertFailsWith<IllegalArgumentException> { inbox.publish(event()) }
        assertFailsWith<IllegalArgumentException> { inbox.publish(event(now + NODE_HORIZON_SECONDS + 1, 1059)) }
        assertFailsWith<IllegalArgumentException> { inbox.publish(event(kind = 27541)) }
        inbox.publish(event(kind = 1059)); socket.deliverOk(auth(socket).id, true); runCurrent()
        assertEquals(1, socket.publishedFrames().size); inbox.close()
        assertFailsWith<IllegalStateException> { inbox.start() }
    }
}
