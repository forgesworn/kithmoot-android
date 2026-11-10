package dev.forgesworn.kithmoot.node

import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.session.*
import dev.forgesworn.kithmoot.storage.RoomStorage
import dev.forgesworn.kithmoot.support.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.*

/** A message held on the old route goes, exactly as signed, on the moved room's node session. */
@OptIn(ExperimentalCoroutinesApi::class)
class NodeMoveResendTest {
    private val now = 1_900_000_000L
    private val root = LocalSigner(ByteArray(32) { 3 }); private val service = LocalSigner(ByteArray(32) { 5 })
    private val secret = ByteArray(32) { 6 }; private val room = deriveRoom(secret)
    private val node = requireNotNull(NodeRooms.read(NodeRooms.sign(root.secretKeyForStorage(), room.roomId, "https://node.example", service.pubkey, now,
        existing = NodeExistingRoomPolicy(false)).toJson(), now))
    private val identity = PrimaryIdentity.create(room.roomId, now + 90 * 86400, now, participantSecretKey = ByteArray(32) { 2 }, deviceSecretKey = ByteArray(32) { 4 })
    private class Store : RoomStorage {
        private var bytes: ByteArray? = null
        override fun read() = bytes?.clone()
        override fun write(value: ByteArray) { bytes = value.clone() }
        override fun reset() { bytes = null }
    }

    @Test fun `the node session publishes the event held on the old route`(): Unit = runTest {
        val store = Store()
        fun outbox() = PendingChatOutbox(store, room.roomId, identity.participant, identity.devicePubkey, io = EmptyCoroutineContext)
        val old = RoomSession(room, identity, FakeRelay().transport(), backgroundScope, timing = Fixtures.QUIET, authority = root.pubkey,
            now = { now }, chatOutbox = outbox())
        old.holdChatForMove(); old.join(); advanceTimeBy(1_000); runCurrent()
        assertFalse(old.sendChatDurable("written before the move"))
        val held = outbox().items().single().event
        old.leave()

        val sockets = FakeSocketFactory()
        val transport = NodeRoomTransport.create(node, LocalSigner(identity.deviceSecretKey), NodeDoor.ROOM, sockets, backgroundScope, now = { now })
        transport.start(); runCurrent()
        val socket = sockets.opened.single(); socket.open(); runCurrent(); socket.deliverAuth("room"); runCurrent()
        val auth = NostrEvent.fromJson(Json.parseToJsonElement(socket.authFrames().single()).jsonArray[1])
        socket.deliverOk(auth.id, true); runCurrent()
        socket.onSend = { frame ->
            val values = Json.parseToJsonElement(frame).jsonArray
            if (values[0].jsonPrimitive.content == "EVENT") backgroundScope.launch { socket.deliverOk(NostrEvent.fromJson(values[1]).id, true) }
        }
        val moved = RoomSession(room, identity, transport, backgroundScope, timing = Fixtures.QUIET, authority = root.pubkey, now = { now },
            epochSettleMs = 0, nodeRoom = node, chatOutbox = outbox())
        moved.join(); runCurrent()
        assertFalse(moved.chatHeldForMove)
        // Held for a move is never held on the node: the gate is a no-op there.
        moved.holdChatForMove(); assertFalse(moved.chatHeldForMove)
        assertTrue(moved.retryPendingChat())
        val sent = socket.publishedFrames().map { NostrEvent.fromJson(Json.parseToJsonElement(it).jsonArray[1]) }
        assertEquals(held, sent.single { it.kind == KIND_CHAT && it.id == held.id })
        assertTrue(outbox().items().isEmpty())
        moved.leave(); transport.close()
    }
}
