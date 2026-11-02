package dev.forgesworn.kithmoot.node

import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.*
import dev.forgesworn.kithmoot.session.PrimaryIdentity
import dev.forgesworn.kithmoot.support.FakeSocket
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class NodeGuestJoinTest {
    private val now = 1_900_000_000L
    private val root = LocalSigner(ByteArray(32) { 3 }); private val service = LocalSigner(ByteArray(32) { 5 })
    private val secret = ByteArray(32) { 6 }; private val room = deriveRoom(secret)
    private val node = requireNotNull(NodeRooms.read(NodeRooms.sign(root.secretKeyForStorage(), room.roomId, "https://node.example", service.pubkey, now).toJson(), now))
    private fun identity() = PrimaryIdentity.create(room.roomId, now + 3600, now, participantSecretKey = ByteArray(32) { 2 }, deviceSecretKey = ByteArray(32) { 4 })
    private val store = NodeStore(NodeMemoryStorage(), NodeMemoryStorage(), NodeMemoryStorage())
    private fun TestScope.sockets(identity: PrimaryIdentity, accept: Boolean, opened: MutableList<FakeSocket>) = object : RelaySocketFactory {
        override fun open(url: String, listener: RelaySocketListener): RelaySocket {
            assertEquals(node.relay, url)
            val socket = FakeSocket(url, listener); opened += socket
            socket.onSend = { frame ->
                val raw = Json.parseToJsonElement(frame).jsonArray
                when (raw[0].jsonPrimitive.content) {
                    "AUTH" -> backgroundScope.launch { socket.deliverOk(NostrEvent.fromJson(raw[1]).id, true) }
                    "EVENT" -> backgroundScope.launch {
                        val request = NostrEvent.fromJson(raw[1]); assertEquals(1059, request.kind)
                        assertNull(store.custody(identity.participant, node.room))
                        val grant = buildGrant(root.secretKeyForStorage(), node.room, identity.devicePubkey, "node.example", listOf("turn", "relay-read", "relay-write"), null, "ab".repeat(16), now, now + 3600)
                        val answer = NodeAdmissions.answer(node, root.secretKeyForStorage(), request.toJson(), now,
                            if (accept) NodeAdmissionGrant(secret, 0, grant.toJson()) else null)
                        socket.deliverOk(request.id, true)
                        socket.deliverEvent(socket.requestedSubscriptions().single(), answer)
                    }
                }
            }
            backgroundScope.launch { socket.open(); socket.deliverAuth("ask") }
            return socket
        }
    }
    @Test fun `accepted sealed answer is saved before guest join returns and its ask socket closes`(): Unit = runTest {
        val identity = identity(); val opened = mutableListOf<FakeSocket>()
        val joined = joinNodeAsGuest(node, identity, store, sockets(identity, true, opened), { true }, { now })
        assertEquals("admitted", joined.phase); assertContentEquals(secret, joined.roomSecret)
        assertEquals(joined.wire, store.custody(identity.participant, room.roomId)!!.wire)
        assertEquals(1, opened.size); assertTrue(opened.single().closedByPool)
        val auth = NostrEvent.fromJson(Json.parseToJsonElement(opened.single().authFrames().single()).jsonArray[1])
        assertEquals(listOf("mode", "ask"), auth.tags.last())
    }
    @Test fun `decline keeps the guest waiting without keys and cancel closes the sole node socket`(): Unit = runTest {
        val identity = identity(); val opened = mutableListOf<FakeSocket>(); var waiting = false
        val attempt = async { joinNodeAsGuest(node, identity, store, sockets(identity, false, opened), { true }, { now }, onWaiting = { waiting = true }) }
        runCurrent(); assertTrue(waiting); assertFalse(attempt.isCompleted); assertNull(store.custody(identity.participant, room.roomId))
        attempt.cancelAndJoin(); assertTrue(opened.single().closedByPool); assertEquals(1, opened.size)
    }
}
