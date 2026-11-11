package dev.forgesworn.kithmoot.node

import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.support.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

/**
 * A delivery receipt written by the closed-app listener for a node room goes out through the node's own door,
 * which signs and sends only what the node's rules allow, exactly as a message does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NodeRoomReceiptTest {
    private val now = 1_900_000_000L
    private val root = LocalSigner(ByteArray(32) { 3 })
    private val device = LocalSigner(ByteArray(32) { 4 })
    private val service = LocalSigner(ByteArray(32) { 5 })
    private val node = requireNotNull(NodeRooms.read(NodeRooms.sign(root.secretKeyForStorage(), "ee".repeat(32), "https://node.example", service.pubkey, now).toJson(), now))
    private fun auth(socket: FakeSocket) = NostrEvent.fromJson(Json.parseToJsonElement(socket.authFrames().single()).jsonArray[1])

    private fun receipt(roomEnds: Long?): NostrEvent {
        val credential = createDeviceCredential(ByteArray(32) { 6 }, device.pubkey, node.room, now + 3_600, now)
        return encodeDeliveryReceipt(EncodeDeliveryReceiptOptions(
            roomId = node.room, stream = DeliveryReceiptStream("dd".repeat(32), ByteArray(32) { 7 }),
            deviceSecretKey = device.secretKeyForStorage(), credential = credential, messageIds = listOf("m1"),
            createdAt = now, roomEnds = roomEnds,
        ))
    }

    @Test fun `a receipt for a node room's chat passes the node door and leaves over its one relay`(): Unit = runTest {
        // As the background listener signs it: capped by the node's horizon and the room's own end.
        val event = receipt(minOf(now + NODE_HORIZON_SECONDS, node.endsAt ?: Long.MAX_VALUE))
        assertEquals(KIND_DELIVERY_RECEIPT, event.kind)
        val sockets = FakeSocketFactory()
        val transport = NodeRoomTransport.create(node, device, NodeDoor.ROOM, sockets, backgroundScope, now = { now })
        transport.start(); runCurrent(); transport.publish(event)
        val socket = sockets.opened.single(); assertEquals(node.relay, socket.url)
        socket.open(); socket.deliverAuth("challenge"); runCurrent()
        socket.deliverOk(auth(socket).id, true); runCurrent()
        assertEquals(1, socket.publishedFrames().size)
        transport.close()
    }

    @Test fun `a receipt in a room that ends carries the room's end, or the door refuses it and none is sent`(): Unit = runTest {
        val sockets = FakeSocketFactory()
        val ending = requireNotNull(NodeRooms.read(NodeRooms.sign(root.secretKeyForStorage(), "ee".repeat(32), "https://node.example", service.pubkey, now, endsAt = now + 3_600).toJson(), now))
        val transport = NodeRoomTransport.create(ending, device, NodeDoor.ROOM, sockets, backgroundScope, now = { now })
        val credential = createDeviceCredential(ByteArray(32) { 6 }, device.pubkey, ending.room, now + 3_600, now)
        fun receipt(roomEnds: Long?) = encodeDeliveryReceipt(EncodeDeliveryReceiptOptions(
            roomId = ending.room, stream = DeliveryReceiptStream("dd".repeat(32), ByteArray(32) { 7 }),
            deviceSecretKey = device.secretKeyForStorage(), credential = credential, messageIds = listOf("m1"), createdAt = now, roomEnds = roomEnds,
        ))
        // Signed without the room's end, it would outlive the room.
        assertFailsWith<IllegalArgumentException> { transport.publish(receipt(null)) }
        transport.publish(receipt(ending.endsAt))
        transport.close()
    }
}
