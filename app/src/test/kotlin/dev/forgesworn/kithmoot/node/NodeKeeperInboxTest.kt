package dev.forgesworn.kithmoot.node

import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.epoch.EpochVault
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.session.PrimaryIdentity
import dev.forgesworn.kithmoot.support.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class NodeKeeperInboxTest {
    private val now = 1_900_000_000L
    private var clock = now
    private var allowed = true
    private val root = LocalSigner(ByteArray(32) { 3 }); private val service = LocalSigner(ByteArray(32) { 5 })
    private val secret = ByteArray(32) { 6 }; private val room = deriveRoom(secret)
    private val node = requireNotNull(NodeRooms.read(NodeRooms.sign(root.secretKeyForStorage(), room.roomId, "https://node.example", service.pubkey, now).toJson(), now))
    private val owner = PrimaryIdentity.create(room.roomId, now + 90 * 86400, now, participantSecretKey = ByteArray(32) { 2 }, deviceSecretKey = ByteArray(32) { 4 })
    private val guest = PrimaryIdentity.create(room.roomId, now + 90 * 86400, now, participantSecretKey = ByteArray(32) { 7 }, deviceSecretKey = ByteArray(32) { 8 })
    private val store = NodeStore(NodeMemoryStorage(), NodeMemoryStorage(), NodeMemoryStorage())
    private val vault = EpochVault(NodeMemoryStorage())
    private val posts = mutableListOf<GroupEvent>()
    private var fail = false
    /** The node already holds another keeper's active grant for the asking device. */
    private var elsewhere = false
    private fun prepare(): NodeKeeperAdmissions {
        store.save(NodeCustody.read(buildJsonObject {
            put("v", 1); put("node", node.wire); put("participant", owner.participant); put("deviceKey", owner.deviceSecretKey.toHex())
            put("credential", owner.credential.toJson()); put("secret", secret.toHex()); put("rootKey", root.secretKeyForStorage().toHex()); put("phase", "creating")
        }))
        vault.initialise(node.room, root.pubkey, secret, now)
        return NodeKeeperAdmissions(node, owner.participant, store, { event, _ -> posts += event; if (fail) throw NodeRequestException(); if (elsewhere) throw NodeRequestException(409, "device-already-granted") }, { allowed }, { clock })
    }
    private fun TestScope.start(keeper: NodeKeeperAdmissions, sockets: FakeSocketFactory, yields: Boolean = false): NodeKeeperInbox {
        val inbox = NodeKeeperInbox(node, owner.participant, store, keeper, vault, sockets, backgroundScope, { allowed }, { clock }, yields = yields)
        inbox.start(); runCurrent()
        val socket = sockets.opened.single(); socket.open(); runCurrent(); socket.deliverAuth("root-ask"); runCurrent()
        val auth = NostrEvent.fromJson(Json.parseToJsonElement(socket.authFrames().single()).jsonArray[1])
        assertEquals(root.pubkey, auth.pubkey)
        assertEquals(listOf("mode", "ask"), auth.tags.single { it[0] == "mode" })
        socket.deliverOk(auth.id, true); runCurrent()
        socket.onSend = { frame ->
            val values = Json.parseToJsonElement(frame).jsonArray
            if (values[0].jsonPrimitive.content == "EVENT") backgroundScope.launch {
                socket.deliverOk(NostrEvent.fromJson(values[1]).id, true)
            }
        }
        return inbox
    }
    @Test fun `manual admission waits for child ACKs and retries the exact grant after failure`(): Unit = runTest {
        val keeper = prepare(); val sockets = FakeSocketFactory(); val inbox = start(keeper, sockets)
        val ask = NodeAdmissions.request(node, guest.deviceSecretKey, guest.credential.toJson(), now, "Guest")
        inbox.ingest(ask.event)
        assertEquals(listOf(guest.participant), inbox.requests.value.map { it.participant }); assertTrue(posts.isEmpty())
        fail = true; inbox.admit(ask.requestId)
        val first = posts.single(); assertTrue(sockets.opened.single().publishedFrames().isEmpty())
        assertTrue(inbox.trouble.value); assertFalse(keeper.state().devices.single().acknowledged)
        fail = false
        val retry = async { inbox.admit(ask.requestId) }; runCurrent(); retry.await()
        assertEquals(first, posts.last()); assertTrue(keeper.state().devices.single().acknowledged)
        val answer = sockets.opened.single().publishedFrames().single().let { NostrEvent.fromJson(Json.parseToJsonElement(it).jsonArray[1]) }
        val admitted = assertIs<NodeRoomAdmission>(NodeAdmissions.readAnswer(node, guest.deviceSecretKey, answer.toJson(), ask.requestId, ask.expiresAt, now))
        assertContentEquals(secret, admitted.secret); admitted.secret.fill(0)
        assertTrue(inbox.requests.value.isEmpty()); inbox.close()
    }
    @Test fun `a waiting knock is listed for every screen with its room until it is declined or its keeper closes`(): Unit = runTest {
        val keeper = prepare(); val sockets = FakeSocketFactory(); val inbox = start(keeper, sockets)
        val ask = NodeAdmissions.request(node, guest.deviceSecretKey, guest.credential.toJson(), now, "Guest")
        inbox.ingest(ask.event)
        val listed = NodeKnocks.all.value.single { it.room == node.room }
        assertEquals(ask.requestId, listed.request.id); assertEquals("Guest", listed.request.name)
        // This room's keeper is open, so a yes from another screen lets them in from here.
        assertFalse(listed.opensRoom)
        inbox.decline(ask.requestId)
        assertTrue(NodeKnocks.all.value.none { it.room == node.room })
        val again = NodeAdmissions.request(node, guest.deviceSecretKey, guest.credential.toJson(), now + 1, "Guest")
        inbox.ingest(again.event); assertEquals(1, NodeKnocks.all.value.count { it.room == node.room })
        inbox.close(); assertTrue(NodeKnocks.all.value.none { it.room == node.room })
    }
    @Test fun `a yes to a closed room's knock lets them in once the opened room hears it, and only once`(): Unit = runTest {
        val keeper = prepare()
        val closed = start(keeper, FakeSocketFactory(), yields = true)
        val ask = NodeAdmissions.request(node, guest.deviceSecretKey, guest.credential.toJson(), now, "Guest")
        closed.ingest(ask.event)
        val knock = NodeKnocks.all.value.single { it.room == node.room }
        // A guest's device asks the open room for its key, so the yes opens the room.
        assertTrue(knock.opensRoom); assertTrue(posts.isEmpty())
        // While a keeper that can let them in from here hears the same request, its card is the one shown.
        val direct = start(keeper, FakeSocketFactory())
        direct.ingest(ask.event)
        assertFalse(NodeKnocks.all.value.single { it.room == node.room }.opensRoom)
        direct.close()
        assertTrue(NodeKnocks.all.value.single { it.room == node.room }.opensRoom)
        NodeKnocks.approve(knock); closed.close()
        assertFalse(NodeKnocks.takeApproval("another room", ask.requestId, now))
        val sockets = FakeSocketFactory(); val open = start(keeper, sockets)
        val admitting = async { open.ingest(ask.event) }; runCurrent(); admitting.await()
        assertEquals(1, posts.size)
        val answer = sockets.opened.single().publishedFrames().single().let { NostrEvent.fromJson(Json.parseToJsonElement(it).jsonArray[1]) }
        val admitted = assertIs<NodeRoomAdmission>(NodeAdmissions.readAnswer(node, guest.deviceSecretKey, answer.toJson(), ask.requestId, ask.expiresAt, now))
        assertContentEquals(secret, admitted.secret); admitted.secret.fill(0)
        assertTrue(open.requests.value.isEmpty())
        assertFalse(NodeKnocks.takeApproval(node.room, ask.requestId, now))
        // The closed room's keeper hears the same request when the room closes again: answered, it stays gone.
        open.close()
        val again = start(keeper, FakeSocketFactory(), yields = true)
        again.ingest(ask.event)
        assertTrue(NodeKnocks.all.value.none { it.room == node.room })
        again.close()
    }
    @Test fun `a knock another of the account's keepers already let in is settled without an answer or a fault`(): Unit = runTest {
        val keeper = prepare(); val sockets = FakeSocketFactory(); val inbox = start(keeper, sockets)
        val ask = NodeAdmissions.request(node, guest.deviceSecretKey, guest.credential.toJson(), now, "Guest")
        inbox.ingest(ask.event)
        elsewhere = true; inbox.admit(ask.requestId)
        assertTrue(sockets.opened.single().publishedFrames().isEmpty())
        assertFalse(inbox.trouble.value); assertTrue(inbox.requests.value.isEmpty())
        assertTrue(keeper.state().devices.none { it.device == guest.devicePubkey })
        inbox.close()
    }
    @Test fun `declining is silent and repeated envelope replay cannot requeue it`(): Unit = runTest {
        val keeper = prepare(); val sockets = FakeSocketFactory(); val inbox = start(keeper, sockets)
        val ask = NodeAdmissions.request(node, guest.deviceSecretKey, guest.credential.toJson(), now)
        inbox.ingest(ask.event); inbox.decline(ask.requestId); inbox.ingest(ask.event)
        assertTrue(inbox.requests.value.isEmpty()); assertTrue(posts.isEmpty())
        assertTrue(sockets.opened.single().publishedFrames().isEmpty()); inbox.close()
    }
    @Test fun `root epoch desk requires the exact acknowledged device and stops at removal`(): Unit = runTest {
        val keeper = prepare()
        val desk = NodeRootEpochDesk(node, owner.participant, store, vault, { allowed }, { clock })
        fun request(identity: PrimaryIdentity) = encodeEpochRequest(node.room, root.pubkey, room.roomKey,
            identity.deviceSecretKey, identity.credential, clock, roomEnds = clock + NODE_HORIZON_SECONDS)
        val ask = request(guest)
        assertNull(desk.answer(ask))
        fail = true; assertFailsWith<NodeRequestException> { keeper.admit(guest.credential.toJson(), true) }
        assertNull(desk.answer(ask))
        fail = false; keeper.admit(guest.credential.toJson())
        val answer = requireNotNull(desk.answer(ask))
        val grant = assertIs<EpochGrant.Current>(decodeEpochGrant(answer, node.room, root.pubkey, guest.deviceSecretKey, ask.id, now))
        assertEquals(0, grant.epoch)
        assertEquals((now + NODE_HORIZON_SECONDS).toString(), answer.tags.single { it[0] == "expiration" }[1])
        val other = PrimaryIdentity.create(room.roomId, now + 3600, now, participantSecretKey = ByteArray(32) { 7 }, deviceSecretKey = ByteArray(32) { 9 })
        assertNull(desk.answer(request(other)))
        allowed = false; assertNull(desk.answer(ask)); allowed = true
        keeper.remove(guest.participant); assertNull(desk.answer(ask))
    }
}
