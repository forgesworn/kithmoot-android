package dev.forgesworn.kithmoot.node

import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.epoch.EpochVault
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.session.PrimaryIdentity
import dev.forgesworn.kithmoot.support.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class NodeBackgroundRoomTest {
    private val now = 1_900_000_000L
    private var clock = now
    private val root = LocalSigner(ByteArray(32) { 3 }); private val service = LocalSigner(ByteArray(32) { 5 })
    private val secret = ByteArray(32) { 6 }; private val room = deriveRoom(secret)
    private val node = requireNotNull(NodeRooms.read(NodeRooms.sign(root.secretKeyForStorage(), room.roomId, "https://node.example", service.pubkey, now).toJson(), now))
    private val person = PrimaryIdentity.create(room.roomId, now + 86400, now, participantSecretKey = ByteArray(32) { 7 }, deviceSecretKey = ByteArray(32) { 8 })
    private val grant = buildGrant(root.secretKeyForStorage(), room.roomId, person.devicePubkey, "node.example", listOf("turn", "relay-read", "relay-write"), null, "b".repeat(32), now, now + NODE_HORIZON_SECONDS)
    private val store = NodeStore(NodeMemoryStorage(), NodeMemoryStorage(), NodeMemoryStorage())
    private val saved = NodeCustody.read(buildJsonObject {
        put("v", 1); put("node", node.wire); put("participant", person.participant); put("deviceKey", person.deviceSecretKey.toHex())
        put("credential", person.credential.toJson()); put("secret", secret.toHex()); put("phase", "admitted"); put("grant", grant.toJson())
    })
    private var result: NodeAccess = NodeAccess.Active((parseGroupEvent(grant.toJson(), now) as GroupEventVerdict.Ok).parsed as ParsedGrant, "a".repeat(43), now + 3600)
    private var erased = 0
    private var stopped = 0
    private suspend fun TestScope.background(sockets: FakeSocketFactory): NodeBackgroundRoom {
        store.save(saved)
        return NodeBackgroundRoom.open(saved, store, EpochVault(NodeMemoryStorage()), sockets, backgroundScope,
            { true }, { stopped++ }, { erased++ }, { erased++ }, { clock }, query = { id, signer ->
                assertEquals("b".repeat(32), id); assertEquals(person.devicePubkey, signer.pubkey); result
            })
    }
    @Test fun `background uses device scoped AUTH on the sole node and reset closes traffic without erasure`(): Unit = runTest {
        val sockets = FakeSocketFactory(); val live = background(sockets)
        assertTrue(sockets.opened.isEmpty()); live.start(); runCurrent()
        val socket = sockets.opened.single(); assertEquals(node.relay, socket.url)
        socket.open(); runCurrent(); socket.deliverAuth("node-background"); runCurrent()
        val auth = NostrEvent.fromJson(Json.parseToJsonElement(socket.authFrames().single()).jsonArray[1])
        assertEquals(person.devicePubkey, auth.pubkey)
        assertEquals(listOf("room", room.roomId), auth.tags.single { it[0] == "room" })
        assertEquals(listOf("mode", "room"), auth.tags.single { it[0] == "mode" })
        assertFailsWith<IllegalArgumentException> { live.transport.observationPool.addRelays(listOf("wss://other.example")) }
        clock += 2
        result = NodeAccess.Reset((parseGroupEvent(buildReset(root.secretKeyForStorage(), room.roomId, "node.example", now + 1).toJson(), clock) as GroupEventVerdict.Ok).parsed as ParsedReset)
        advanceTimeBy(5001); runCurrent()
        assertTrue(live.failed); assertFalse(live.permitted()); assertTrue(socket.closedByPool)
        assertEquals(0, erased); assertEquals(1, stopped); assertNotNull(store.custody(person.participant, room.roomId))
        live.close(); assertEquals(1, stopped)
    }
    @Test fun `background verified compact closure invokes erasure after stopping the socket`(): Unit = runTest {
        val sockets = FakeSocketFactory(); val live = background(sockets); live.start(); runCurrent()
        clock += 2
        result = NodeAccess.Closed(NodeRooms.signClosure(node, root.secretKeyForStorage(), "c".repeat(32), clock))
        advanceTimeBy(5001); runCurrent()
        assertEquals(1, erased); assertTrue(live.failed); assertTrue(sockets.opened.single().closedByPool)
        live.close()
    }
}
