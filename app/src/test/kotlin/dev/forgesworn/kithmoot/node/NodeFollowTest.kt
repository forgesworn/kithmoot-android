package dev.forgesworn.kithmoot.node

import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.session.PrimaryIdentity
import dev.forgesworn.kithmoot.session.dmPolicy
import dev.forgesworn.kithmoot.storage.SavedRoom
import kotlinx.serialization.json.*
import kotlin.test.*

class NodeFollowTest {
    private val now = 1_900_000_000L
    private val base = "https://app.example/j/"
    private val relays = listOf("wss://original.example")
    private val root = LocalSigner(ByteArray(32) { 3 })
    private val stranger = LocalSigner(ByteArray(32) { 14 })
    private val service = LocalSigner(ByteArray(32) { 5 })
    private val secret = ByteArray(32) { 6 }
    private val room = deriveRoom(secret)
    private val owner = LocalSigner(ByteArray(32) { 2 }).pubkey
    private val member = PrimaryIdentity.create(room.roomId, now + 86400, now, participantSecretKey = ByteArray(32) { 7 }, deviceSecretKey = ByteArray(32) { 8 })
    private val policy = dmPolicy(owner, member.participant)
    private val link = encodeInvitationUrl(base, RoomInvitation(ByteArray(32) { 9 }, root.pubkey, true), relays, policy)
    private val saved = SavedRoom.create(secret, member, link, relays, "Private", now, null, root.pubkey)

    private fun signed(by: LocalSigner = root, at: Long = now, roomId: String = room.roomId, policy: JsonObject? = this.policy.toJson(),
        origin: String = "https://node.example") =
        NodeRooms.sign(by.secretKeyForStorage(), roomId, origin, service.pubkey, at, existing = NodeExistingRoomPolicy(false, policy)).toJson()

    @Test fun `a destination the saved copy confirms is followed and one it cannot is only explained`() {
        val followed = assertIs<NodeDestinationView.Followable>(viewNodeDestination(saved, signed(), now))
        assertEquals("node.example", followed.host)
        assertIs<NodeDestinationView.Unconfirmed>(viewNodeDestination(saved, signed(policy = null), now))
        // Anybody with the room key can post in its control channel: a pinned copy ignores another key's record.
        assertNull(viewNodeDestination(saved, signed(by = stranger, origin = "https://elsewhere.example"), now))
        val bare = SavedRoom.create(secret, member, encodeJoinUrl(base, secret, relays, policy), relays, "Private", now, null, null)
        assertEquals("node.example", assertIs<NodeDestinationView.Unconfirmed>(viewNodeDestination(bare, signed(), now)).host)
        // Another room's record, a fresh node room's, or a malformed one says nothing here.
        assertNull(viewNodeDestination(saved, signed(roomId = deriveRoom(ByteArray(32) { 1 }).roomId), now))
        assertNull(viewNodeDestination(saved, NodeRooms.sign(root.secretKeyForStorage(), room.roomId, "https://node.example", service.pubkey, now).toJson(), now))
        assertNull(viewNodeDestination(saved, buildJsonObject { put("kind", 1) }, now))
    }

    @Test fun `the waiting room words match the web`() {
        assertEquals("This room has moved to node.example. This device will follow as soon as one of the owner’s devices is online. " +
            "Messages you send meanwhile will go once it has moved.", NodeFollowWords.waiting("node.example"))
        assertEquals("This room has moved to node.example, but this device cannot confirm the move from its saved copy of the room. " +
            "Open the room’s latest link to follow it.", NodeFollowWords.unconfirmed("node.example"))
        assertEquals("Waiting to move", NodeFollowWords.WAITING_TO_MOVE)
        assertFalse(listOf(NodeFollowWords.waiting("a"), NodeFollowWords.unconfirmed("a"), NodeFollowWords.NOT_SENT_WHILE_MOVING).any { '\u2014' in it })
    }

    @Test fun `asking again backs off to every five minutes and starts over after a success`() {
        val backoff = NodeFollowBackoff()
        assertEquals(listOf(15_000L, 30_000L, 60_000L, 120_000L, 300_000L, 300_000L), List(6) { backoff.next() })
        backoff.reset()
        assertEquals(15_000L, backoff.next())
    }

    @Test fun `a seen move survives a restart, never yields to an older one and goes once followed`() {
        val first = requireNotNull(NodeRooms.read(signed(), now, root.pubkey))
        val later = requireNotNull(NodeRooms.read(signed(at = now + 10, origin = "https://later.example"), now + 10, root.pubkey))
        val kept = saved.withAnnouncedNode(first)
        val restored = SavedRoom.decode(Json.parseToJsonElement(kept.json.toString()).jsonObject)
        assertEquals(first.event.id, restored.announcedNode?.event?.id)
        // Read at its own signing time, so a record past its horizon still says the room moved.
        assertNotNull(SavedRoom.decode(restored.json).announcedNode)
        val newer = restored.withAnnouncedNode(later)
        assertEquals(later.event.id, newer.announcedNode?.event?.id)
        assertEquals(later.event.id, newer.withAnnouncedNode(first).announcedNode?.event?.id)
        // A bookmark or link refresh keeps it.
        val refreshed = SavedRoom.create(secret, member, link, relays, "Private", now + 20, null, root.pubkey).retainingHistory(newer)
        assertEquals(later.event.id, refreshed.announcedNode?.event?.id)
        val moved = newer.withExistingNodeDestination(later, now + 20)
        assertNull(moved.announcedNode); assertNull(moved.json["nodeAnnounced"])
        assertNotNull(SavedRoom.decode(Json.parseToJsonElement(moved.json.toString()).jsonObject).nodeRoom)
    }

    @Test fun `a seen move the copy cannot confirm reads as none and the room still loads`() {
        val foreign = JsonObject(saved.json + ("nodeAnnounced" to signed(by = stranger)))
        val loaded = SavedRoom.decode(foreign)
        assertNull(loaded.announcedNode)
        assertFailsWith<IllegalArgumentException> { saved.withAnnouncedNode(requireNotNull(NodeRooms.read(signed(policy = null), now))) }
        assertFailsWith<IllegalArgumentException> { SavedRoom.decode(JsonObject(saved.json + ("nodeAnnounced" to JsonPrimitive("x")))) }
    }
}
