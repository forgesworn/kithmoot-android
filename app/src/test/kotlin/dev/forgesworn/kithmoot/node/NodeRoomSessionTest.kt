package dev.forgesworn.kithmoot.node

import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.RoomTransport
import dev.forgesworn.kithmoot.session.*
import dev.forgesworn.kithmoot.support.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class NodeRoomSessionTest {
    private val now = 1_900_000_000L
    private val root = LocalSigner(ByteArray(32) { 3 }); private val service = LocalSigner(ByteArray(32) { 5 })
    private val secret = ByteArray(32) { 6 }; private val room = deriveRoom(secret)
    private val node = requireNotNull(NodeRooms.read(NodeRooms.sign(root.secretKeyForStorage(), room.roomId, "https://node.example", service.pubkey, now).toJson(), now))
    private fun identity() = PrimaryIdentity.create(room.roomId, now + 90 * 86400, now,
        participantSecretKey = ByteArray(32) { 2 }, deviceSecretKey = ByteArray(32) { 4 })
    private fun TestScope.session(transport: RoomTransport, nodeRoom: NodeRoomRecord = node, probe: Boolean = false, clock: () -> Long = { now }) =
        RoomSession(room, identity(), transport, backgroundScope, timing = Fixtures.QUIET, authority = root.pubkey, now = clock,
            epochSettleMs = 0, nodeRoom = nodeRoom, epochProbe = probe)
    @Test fun `a public transport or alternate node cannot be used with the node session profile`(): Unit = runTest {
        assertFailsWith<IllegalArgumentException> { session(FakeRelay().transport()) }
        val transport = NodeRoomTransport.create(node, LocalSigner(identity().deviceSecretKey), NodeDoor.ROOM, FakeSocketFactory(), backgroundScope, now = { now })
        assertFailsWith<IllegalArgumentException> { session(transport, probe = true) }
        val foreign = requireNotNull(NodeRooms.read(NodeRooms.sign(root.secretKeyForStorage(), room.roomId, "https://other.example", service.pubkey, now).toJson(), now))
        assertFailsWith<IllegalArgumentException> { session(transport, nodeRoom = foreign) }
        transport.close()
    }
    @Test fun `ordinary node chat and presence carry a rolling thirty day expiration`(): Unit = runTest {
        var clock = now
        val sockets = FakeSocketFactory(); val transport = NodeRoomTransport.create(node, LocalSigner(identity().deviceSecretKey), NodeDoor.ROOM, sockets, backgroundScope, now = { clock })
        transport.start(); runCurrent(); val socket = sockets.opened.single(); socket.open(); runCurrent(); socket.deliverAuth("room"); runCurrent()
        val auth = NostrEvent.fromJson(Json.parseToJsonElement(socket.authFrames().single()).jsonArray[1]); socket.deliverOk(auth.id, true); runCurrent()
        val live = session(transport, clock = { clock }); live.join(); runCurrent(); live.sendChat("First"); runCurrent()
        fun events() = socket.publishedFrames().map { NostrEvent.fromJson(Json.parseToJsonElement(it).jsonArray[1]) }
        val first = events().last { it.kind == KIND_CHAT }
        assertEquals((now + NODE_HORIZON_SECONDS).toString(), first.tags.single { it[0] == "expiration" }[1])
        assertTrue(events().all { event -> event.tags.count { it[0] == "expiration" } == 1 })
        clock += 86400; live.sendChat("Next day"); runCurrent()
        val later = events().last { it.kind == KIND_CHAT }
        assertEquals((clock + NODE_HORIZON_SECONDS).toString(), later.tags.single { it[0] == "expiration" }[1])
        live.leave(); transport.close()
    }
    @Test fun `file references are refused before sending and stripped from received chat`(): Unit = runTest {
        val sockets = FakeSocketFactory(); val who = identity()
        val transport = NodeRoomTransport.create(node, LocalSigner(who.deviceSecretKey), NodeDoor.ROOM, sockets, backgroundScope, now = { now })
        val live = session(transport)
        val file = ChatAttachment("https://files.example/${"ab".repeat(32)}", "ab".repeat(32), "cd".repeat(32), "private.txt", "text/plain", 12)
        assertFailsWith<IllegalArgumentException> { live.sendChatConfirmed("File", attachments = listOf(file)) }
        assertFailsWith<IllegalArgumentException> { live.prepareChatForSend("File", attachments = listOf(file)) }
        val sender = PrimaryIdentity.create(room.roomId, now + 3600, now, participantSecretKey = ByteArray(32) { 7 }, deviceSecretKey = ByteArray(32) { 8 })
        val event = encodeChatEvent("File reference", sender.participant, sender.credential, room.roomId, room.roomKey, sender.deviceSecretKey, now,
            credentialRoomId = room.roomId, attachments = listOf(file), roomEnds = now + NODE_HORIZON_SECONDS)
        live.onChatEvent(event)
        assertEquals("File reference", live.chat.value.single().body); assertTrue(live.chat.value.single().attachments.isEmpty())
        transport.close()
    }
}
