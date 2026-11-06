package dev.forgesworn.kithmoot.node

import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.RoomTransport
import dev.forgesworn.kithmoot.session.*
import dev.forgesworn.kithmoot.support.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
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
    @Test fun `node media follows device call membership without hiding spectators`(): Unit = runTest {
        var clock = now
        val sockets = FakeSocketFactory()
        val transport = NodeRoomTransport.create(node, LocalSigner(identity().deviceSecretKey), NodeDoor.ROOM, sockets, backgroundScope, now = { clock })
        transport.start(); runCurrent()
        val socket = sockets.opened.single(); socket.open(); runCurrent(); socket.deliverAuth("room"); runCurrent()
        val auth = NostrEvent.fromJson(Json.parseToJsonElement(socket.authFrames().single()).jsonArray[1])
        socket.deliverOk(auth.id, true); runCurrent()
        val live = session(transport, clock = { clock }); live.join(); runCurrent()
        val listener = PrimaryIdentity.create(room.roomId, now + 3600, now,
            participantSecretKey = ByteArray(32) { 7 }, deviceSecretKey = ByteArray(32) { 8 })
        val manager = PrimaryIdentity.create(room.roomId, now + 3600, now,
            participantSecretKey = ByteArray(32) { 9 }, deviceSecretKey = ByteArray(32) { 10 })
        // Another device of the listener's participant can be in another call.
        val other = PrimaryIdentity.create(room.roomId, now + 3600, now,
            participantSecretKey = ByteArray(32) { 7 }, deviceSecretKey = ByteArray(32) { 11 })
        val first = CallMembership("11".repeat(16), now)
        val second = CallMembership("22".repeat(16), now)
        fun presence(who: PrimaryIdentity, membership: CallMembership?, left: Boolean = false) {
            clock++
            live.onRosterEvent(encodeRosterEvent(RosterEntry(who.participant, who.devicePubkey,
                who.credential, updatedAt = clock, call = membership, left = left),
                room.roomId, room.roomKey, who.deviceSecretKey))
        }
        try {
            presence(listener, first); presence(manager, null); presence(other, second)
            assertEquals(emptySet(), live.remoteDevices.value)
            assertTrue(live.participants.value.flatMap { it.devices }.map { it.device }.containsAll(
                listOf(listener.devicePubkey, manager.devicePubkey, other.devicePubkey)))
            live.setCall(first)
            assertEquals(setOf(listener.devicePubkey), live.remoteDevices.value)
            presence(manager, first)
            assertEquals(setOf(listener.devicePubkey, manager.devicePubkey), live.remoteDevices.value)
            presence(listener, second)
            assertEquals(setOf(manager.devicePubkey), live.remoteDevices.value)
            live.setCall(second)
            assertEquals(setOf(listener.devicePubkey, other.devicePubkey), live.remoteDevices.value)
            presence(other, second, left = true)
            assertEquals(setOf(listener.devicePubkey), live.remoteDevices.value)
            live.setCall(null)
            assertEquals(emptySet(), live.remoteDevices.value)
        } finally { live.leave(); transport.close() }
    }
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
    @Test fun `node recovery refusal blocks traffic without terminal state or member fallback`(): Unit = runTest {
        for (refusal in listOf("closed", "removed")) {
            val sockets = FakeSocketFactory()
            val transport = NodeRoomTransport.create(node, LocalSigner(identity().deviceSecretKey), NodeDoor.ROOM, sockets, backgroundScope, now = { now })
            transport.start(); runCurrent()
            val socket = sockets.opened.single(); socket.open(); runCurrent(); socket.deliverAuth("room"); runCurrent()
            val auth = NostrEvent.fromJson(Json.parseToJsonElement(socket.authFrames().single()).jsonArray[1])
            socket.deliverOk(auth.id, true); runCurrent()
            var committed = false
            val live = RoomSession(room, identity(), transport, backgroundScope, timing = Fixtures.QUIET,
                authority = root.pubkey, now = { now }, epochSettleMs = 0, nodeRoom = node, epochProbe = false,
                expectedEpoch = 1, epochGate = { _, _ -> committed = true; EpochGateResult.COMMITTED })
            live.join(); runCurrent()
            val request = socket.publishedFrames().map { NostrEvent.fromJson(Json.parseToJsonElement(it).jsonArray[1]) }
                .single { it.kind == KIND_EPOCH_REQUEST }
            val answer = encodeEpochGrant(room.roomId, root.secretKeyForStorage(), request.pubkey, request.id, now,
                refused = refusal, roomEnds = now + NODE_HORIZON_SECONDS)
            socket.requestedSubscriptions().forEach { socket.deliverEvent(it, answer) }; runCurrent()
            assertIs<RoomEpochState.RecoveryNeeded>(live.epochState.value)
            assertFalse(committed)
            assertFailsWith<IllegalStateException> { live.sendChat("blocked") }
            assertTrue(socket.publishedFrames().map { NostrEvent.fromJson(Json.parseToJsonElement(it).jsonArray[1]) }
                .none { it.kind == KIND_MEMBER_EPOCH_REQUEST })
            live.leave(); transport.close()
        }
    }
    @Test fun `fresh node epoch request carries expiry and precedes presence`(): Unit = runTest {
        val sockets = FakeSocketFactory()
        val transport = NodeRoomTransport.create(node, LocalSigner(identity().deviceSecretKey), NodeDoor.ROOM, sockets, backgroundScope, now = { now })
        transport.start(); runCurrent()
        val socket = sockets.opened.single(); socket.open(); runCurrent(); socket.deliverAuth("room"); runCurrent()
        val auth = NostrEvent.fromJson(Json.parseToJsonElement(socket.authFrames().single()).jsonArray[1]); socket.deliverOk(auth.id, true); runCurrent()
        socket.onSend = { frame ->
            val values = Json.parseToJsonElement(frame).jsonArray
            if (values[0].jsonPrimitive.content == "EVENT") {
                val event = NostrEvent.fromJson(values[1])
                if (event.kind == KIND_EPOCH_REQUEST) {
                    assertEquals((now + NODE_HORIZON_SECONDS).toString(), event.tags.single { it[0] == "expiration" }[1])
                    assertTrue(socket.publishedFrames().none { NostrEvent.fromJson(Json.parseToJsonElement(it).jsonArray[1]).kind == KIND_ROSTER })
                    val answer = encodeEpochGrant(room.roomId, root.secretKeyForStorage(), event.pubkey, event.id, now,
                        RoomEpoch(0, secret), roomEnds = now + NODE_HORIZON_SECONDS)
                    backgroundScope.launch { socket.requestedSubscriptions().forEach { socket.deliverEvent(it, answer) } }
                }
            }
        }
        val live = RoomSession(room, identity(), transport, backgroundScope, timing = Fixtures.QUIET,
            authority = root.pubkey, now = { now }, epochSettleMs = 0, nodeRoom = node, epochProbe = false,
            requireFreshEpoch = true, epochGate = { _, _ -> EpochGateResult.COMMITTED })
        val joining = async { live.join() }; runCurrent(); joining.await()
        assertIs<RoomEpochState.Active>(live.epochState.value)
        assertTrue(socket.publishedFrames().any { NostrEvent.fromJson(Json.parseToJsonElement(it).jsonArray[1]).kind == KIND_ROSTER })
        live.leave(); transport.close()
    }
    @Test fun `node keeper also waits for its own signed root answer before presence`(): Unit = runTest {
        val sockets = FakeSocketFactory()
        val transport = NodeRoomTransport.create(node, LocalSigner(identity().deviceSecretKey), NodeDoor.ROOM, sockets, backgroundScope, now = { now })
        transport.start(); runCurrent()
        val socket = sockets.opened.single(); socket.open(); runCurrent(); socket.deliverAuth("room"); runCurrent()
        val auth = NostrEvent.fromJson(Json.parseToJsonElement(socket.authFrames().single()).jsonArray[1]); socket.deliverOk(auth.id, true); runCurrent()
        var rootAnswered = false
        socket.onSend = { frame ->
            val values = Json.parseToJsonElement(frame).jsonArray
            if (values[0].jsonPrimitive.content == "EVENT") {
                val event = NostrEvent.fromJson(values[1])
                if (event.kind == KIND_ROSTER) assertTrue(rootAnswered)
                if (event.kind in listOf(KIND_EPOCH_REQUEST, KIND_EPOCH_GRANT)) {
                    if (event.kind == KIND_EPOCH_GRANT) rootAnswered = true
                    backgroundScope.launch { socket.requestedSubscriptions().forEach { socket.deliverEvent(it, event) } }
                }
            }
        }
        val live = RoomSession(room, identity(), transport, backgroundScope, timing = Fixtures.QUIET,
            authority = root.pubkey, now = { now }, epochSettleMs = 0, nodeRoom = node, epochProbe = false,
            requireFreshEpoch = true, epochGate = { _, _ -> EpochGateResult.COMMITTED },
            epochResponder = { request -> if (request.kind != KIND_EPOCH_REQUEST) null else
                encodeEpochGrant(room.roomId, root.secretKeyForStorage(), request.pubkey, request.id, now,
                    RoomEpoch(0, secret), roomEnds = now + NODE_HORIZON_SECONDS) })
        val joining = async { live.join() }; runCurrent(); joining.await()
        assertTrue(rootAnswered); assertIs<RoomEpochState.Active>(live.epochState.value)
        live.leave(); transport.close()
    }
}
