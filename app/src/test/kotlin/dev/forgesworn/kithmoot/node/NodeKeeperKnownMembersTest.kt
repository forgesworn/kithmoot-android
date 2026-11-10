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

/** A moved room's keeper lets in the members the room already has, even when
 * its own journal has not met them, and never anybody removed or unnamed. */
@OptIn(ExperimentalCoroutinesApi::class)
class NodeKeeperKnownMembersTest {
    private val now = 1_900_000_000L
    private val root = LocalSigner(ByteArray(32) { 3 }); private val service = LocalSigner(ByteArray(32) { 5 })
    private val secret = ByteArray(32) { 6 }; private val room = deriveRoom(secret)
    private val owner = PrimaryIdentity.create(room.roomId, now + 90 * 86400, now, participantSecretKey = ByteArray(32) { 2 }, deviceSecretKey = ByteArray(32) { 4 })
    private val member = PrimaryIdentity.create(room.roomId, now + 90 * 86400, now, participantSecretKey = ByteArray(32) { 7 }, deviceSecretKey = ByteArray(32) { 8 })
    private val stranger = PrimaryIdentity.create(room.roomId, now + 90 * 86400, now, participantSecretKey = ByteArray(32) { 11 }, deviceSecretKey = ByteArray(32) { 12 })
    private val store = NodeStore(NodeMemoryStorage(), NodeMemoryStorage(), NodeMemoryStorage())
    private val vault = EpochVault(NodeMemoryStorage())
    private val posts = mutableListOf<GroupEvent>()
    private lateinit var node: NodeRoomRecord

    private fun prepare(policy: JsonObject?): NodeKeeperAdmissions {
        node = requireNotNull(NodeRooms.read(NodeRooms.sign(root.secretKeyForStorage(), room.roomId, "https://node.example", service.pubkey, now,
            existing = NodeExistingRoomPolicy(false, policy)).toJson(), now))
        store.save(NodeCustody.read(buildJsonObject {
            put("v", 1); put("node", node.wire); put("participant", owner.participant); put("deviceKey", owner.deviceSecretKey.toHex())
            put("credential", owner.credential.toJson()); put("secret", secret.toHex()); put("rootKey", root.secretKeyForStorage().toHex()); put("phase", "creating")
            // The journal this keeper's device seeded holds only its own person.
            put("keeper", NodeKeeperState(listOf(owner.participant), emptyList(), emptyList()).toJson())
        }))
        vault.initialise(node.room, root.pubkey, secret, now)
        return NodeKeeperAdmissions(node, owner.participant, store, { event, _ -> posts += event }, { true }, { now })
    }
    private fun members(vararg people: String) = buildJsonObject { put("tier", "open"); put("members", JsonArray(people.map(::JsonPrimitive))) }

    @Test fun `a members-only room lets its named members in without asking and nobody else even when asked`(): Unit = runBlocking {
        val keeper = prepare(members(owner.participant, member.participant))
        assertTrue(keeper.knows(keeper.state(), member.participant))
        assertNotNull(keeper.admit(member.credential.toJson()))
        assertFalse(keeper.permits(keeper.state(), stranger.participant))
        assertNull(keeper.admit(stranger.credential.toJson(), manuallyApproved = true))
        keeper.remove(member.participant)
        assertFalse(keeper.knows(keeper.state(), member.participant))
        assertNull(keeper.admit(member.credential.toJson(), manuallyApproved = true))
    }

    @Test fun `an open room learns the members its history shows, never a removed one, an unnamed one or a malformed key`(): Unit = runBlocking {
        val keeper = prepare(null)
        assertFalse(keeper.knows(keeper.state(), member.participant))
        assertNull(keeper.admit(member.credential.toJson()))
        keeper.learnMembers(listOf(member.participant.uppercase(), "not a key"))
        assertTrue(keeper.knows(keeper.state(), member.participant))
        assertEquals(listOf(owner.participant, member.participant).sorted(), keeper.state().members)
        keeper.remove(member.participant)
        keeper.learnMembers(listOf(member.participant))
        assertFalse(keeper.knows(keeper.state(), member.participant))
        // A later keeper reading the same journal knows what this one learned.
        keeper.learnMembers(listOf(stranger.participant))
        val later = NodeKeeperAdmissions(node, owner.participant, store, { event, _ -> posts += event }, { true }, { now })
        assertNotNull(later.admit(stranger.credential.toJson()))
        // Learning leaves the journal room for people let in by hand, and a full journal refuses
        // rather than writing one it could not read back.
        val crowd = List(200) { LocalSigner(ByteArray(32) { b -> (it + 40 + b).toByte() }) }
        later.learnMembers(crowd.map { it.pubkey })
        assertEquals(NodeKeeperAdmissions.MAX_LEARNED, later.state().members.size)
        val unmet = crowd.filter { it.pubkey !in later.state().members }
        fun credential(person: LocalSigner, seed: Int) = Events.sign(person.secretKeyForStorage(), KIND_DEVICE_CREDENTIAL, now,
            listOf(listOf("d", room.roomId), listOf("device", LocalSigner(ByteArray(32) { (seed + it).toByte() }).pubkey),
                listOf("expiration", (now + 86400).toString())), "").toJson()
        for (index in 0 until NodeKeeperAdmissions.MAX_MEMBERS - NodeKeeperAdmissions.MAX_LEARNED)
            assertNotNull(later.admit(credential(unmet[index], 100 + index), manuallyApproved = true))
        assertEquals(NodeKeeperAdmissions.MAX_MEMBERS, later.state().members.size)
        assertNull(later.admit(credential(unmet.last(), 250), manuallyApproved = true))
        assertNotNull(store.custody(owner.participant, node.room))
    }

    @Test fun `in a members room learning cannot add anybody the policy does not name`(): Unit = runBlocking {
        val keeper = prepare(members(owner.participant, member.participant))
        keeper.learnMembers(listOf(stranger.participant, member.participant))
        assertEquals(listOf(owner.participant, member.participant).sorted(), keeper.state().members)
    }

    private fun kotlinx.coroutines.test.TestScope.closedKeeper(keeper: NodeKeeperAdmissions, blocked: (String) -> Boolean = { false }):
        Pair<NodeKeeperInbox, FakeSocket> {
        val sockets = FakeSocketFactory()
        val inbox = NodeKeeperInbox(node, owner.participant, store, keeper, vault, sockets, backgroundScope, { true }, { now },
            yields = true, blocked = blocked)
        inbox.start(); runCurrent()
        val socket = sockets.opened.single(); socket.open(); runCurrent(); socket.deliverAuth("root-ask"); runCurrent()
        val auth = NostrEvent.fromJson(Json.parseToJsonElement(socket.authFrames().single()).jsonArray[1])
        socket.deliverOk(auth.id, true); runCurrent()
        socket.onSend = { frame ->
            val values = Json.parseToJsonElement(frame).jsonArray
            if (values[0].jsonPrimitive.content == "EVENT") backgroundScope.launch { socket.deliverOk(NostrEvent.fromJson(values[1]).id, true) }
        }
        return inbox to socket
    }

    @Test fun `a member who removed the room rejoins from the node link as a fresh guest a closed keeper lets in`(): Unit = runTest {
        val keeper = prepare(members(owner.participant, member.participant))
        val (inbox, socket) = closedKeeper(keeper)
        // A closed-room keeper never claims its room; an open one would.
        assertFalse(NodeKeeperClaims.claimed(node.room))
        // No saved original: the link's ask identity is the signed-in account with a new device key.
        val account = LocalSigner(ByteArray(32) { 7 })
        val guest = NodeJoinIdentityStore(NodeMemoryStorage()).identity(node, account, now = { now })
        assertEquals(member.participant, guest.participant); assertNotEquals(member.devicePubkey, guest.devicePubkey)
        val ask = NodeAdmissions.request(node, guest.deviceSecretKey, guest.credential.toJson(), now, "Member")
        val answered = async { inbox.ingest(ask.event) }; runCurrent(); answered.await()
        assertTrue(inbox.requests.value.isEmpty())
        val answer = NostrEvent.fromJson(Json.parseToJsonElement(socket.publishedFrames().single()).jsonArray[1])
        val admitted = assertIs<NodeRoomAdmission>(NodeAdmissions.readAnswer(node, guest.deviceSecretKey, answer.toJson(), ask.requestId, ask.expiresAt, now))
        val link = NodeRooms.encodeLink("https://app.example/j/", node.wire, now)
        val saved = try { savedNodeGuestAdmission(null, guest, node, admitted.secret, admitted.epochHint, link, node.event.createdAt, true) }
            finally { admitted.secret.fill(0) }
        val restored = dev.forgesworn.kithmoot.storage.SavedRoom.decode(Json.parseToJsonElement(saved.json.toString()).jsonObject)
        assertEquals(node.event.id, restored.nodeRoom?.event?.id); assertEquals(member.participant, restored.participant)
        assertEquals(setOf(owner.participant, member.participant), restored.policy?.members?.toSet())
        assertTrue(NodeRooms.verifyGrant(admitted.grant, node, guest.devicePubkey, now))
        inbox.close()
    }

    @Test fun `a closed keeper skips a member blocked on this phone`(): Unit = runTest {
        val keeper = prepare(members(owner.participant, member.participant))
        val (inbox, socket) = closedKeeper(keeper) { it == member.participant }
        inbox.ingest(NodeAdmissions.request(node, member.deviceSecretKey, member.credential.toJson(), now, "Member").event)
        runCurrent()
        assertTrue(inbox.requests.value.isEmpty()); assertTrue(socket.publishedFrames().isEmpty()); assertTrue(posts.isEmpty())
        inbox.close()
    }

    @Test fun `the inbox answers a named member's device at once and never lists a stranger`(): Unit = runTest {
        val keeper = prepare(members(owner.participant, member.participant))
        val sockets = FakeSocketFactory()
        val inbox = NodeKeeperInbox(node, owner.participant, store, keeper, vault, sockets, backgroundScope, { true }, { now })
        inbox.start(); runCurrent()
        val socket = sockets.opened.single(); socket.open(); runCurrent(); socket.deliverAuth("root-ask"); runCurrent()
        val auth = NostrEvent.fromJson(Json.parseToJsonElement(socket.authFrames().single()).jsonArray[1])
        socket.deliverOk(auth.id, true); runCurrent()
        socket.onSend = { frame ->
            val values = Json.parseToJsonElement(frame).jsonArray
            if (values[0].jsonPrimitive.content == "EVENT") backgroundScope.launch { socket.deliverOk(NostrEvent.fromJson(values[1]).id, true) }
        }
        assertTrue(NodeKeeperClaims.claimed(node.room))
        val unnamed = NodeAdmissions.request(node, stranger.deviceSecretKey, stranger.credential.toJson(), now, "Stranger")
        inbox.ingest(unnamed.event)
        assertTrue(inbox.requests.value.isEmpty())
        val ask = NodeAdmissions.request(node, member.deviceSecretKey, member.credential.toJson(), now, "Member")
        val answered = async { inbox.ingest(ask.event) }; runCurrent(); answered.await()
        assertTrue(inbox.requests.value.isEmpty())
        val answer = NostrEvent.fromJson(Json.parseToJsonElement(socket.publishedFrames().single()).jsonArray[1])
        val admitted = assertIs<NodeRoomAdmission>(NodeAdmissions.readAnswer(node, member.deviceSecretKey, answer.toJson(), ask.requestId, ask.expiresAt, now))
        admitted.secret.fill(0)
        inbox.close()
        assertFalse(NodeKeeperClaims.claimed(node.room))
    }
}
