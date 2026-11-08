package dev.forgesworn.kithmoot.node

import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.RoomRoute
import dev.forgesworn.kithmoot.session.PrimaryIdentity
import dev.forgesworn.kithmoot.storage.SavedRoom
import kotlinx.serialization.json.*
import kotlin.test.*

class NodeSavedRoomTest {
    private val now = 1_900_000_000L
    private val root = LocalSigner(ByteArray(32) { 3 }); private val service = LocalSigner(ByteArray(32) { 5 })
    private val secret = ByteArray(32) { 6 }; private val room = deriveRoom(secret)
    private fun node(ends: Long? = null) = requireNotNull(NodeRooms.read(NodeRooms.sign(root.secretKeyForStorage(), room.roomId, "https://node.example", service.pubkey, now, ends).toJson(), now))
    private fun saved(node: NodeRoomRecord = node()): SavedRoom {
        val identity = PrimaryIdentity.create(room.roomId, now + 3600, now, participantSecretKey = ByteArray(32) { 2 }, deviceSecretKey = ByteArray(32) { 4 })
        return SavedRoom.create(secret, identity, NodeRooms.encodeLink("https://app.example/j/", node.wire, now), listOf(node.relay), "Hosted room", now,
            null, root.pubkey, ends = node.endsAt, roomRelays = listOf(node.relay), roomRelaysSigned = true, destruct = true, nodeRoom = node)
    }
    @Test fun `node saved room survives restart with a public ask only link and private identity`() {
        val saved = saved(); val restored = SavedRoom.decode(Json.parseToJsonElement(saved.json.toString()).jsonObject)
        assertEquals(node().origin, restored.nodeRoom!!.origin); assertEquals(root.pubkey, restored.authority)
        assertTrue(restored.persistent && restored.destruct); assertNull(restored.policy); assertNull(restored.invitation)
        assertEquals(restored.nodeRoom!!.event.id, NodeRooms.parseLink(restored.joinUrl, now)!!.event.id)
        assertTrue(restored.summary(now).canShareInvite); assertEquals(listOf(restored.nodeRoom!!.relay), restored.sharedRelays)
        assertEquals(saved.devicePubkey, restored.devicePubkey); assertContentEquals(secret, restored.secret)
    }
    @Test fun `saved node policy refuses public relay nearby route file storage and bearer link downgrades`() {
        val saved = saved()
        assertFailsWith<IllegalArgumentException> { saved.withRelays(listOf("wss://other.example")) }
        assertFailsWith<IllegalArgumentException> { saved.withRoomRelays(listOf("wss://other.example"), true) }
        assertFailsWith<IllegalArgumentException> { saved.withRoute(RoomRoute.MIXED) }
        assertFailsWith<IllegalArgumentException> { SavedRoom.decode(JsonObject(saved.json - "node")) }
        assertFailsWith<IllegalArgumentException> { SavedRoom.decode(JsonObject(saved.json + ("destruct" to JsonPrimitive(false)))) }
    }
    @Test fun `an expired node record remains readable only for local end handling and cannot be extended`() {
        val saved = saved(node(now + 60)); val summary = saved.summary(now + 61)
        assertTrue(summary.ended && summary.destruct); assertFalse(summary.canShareInvite)
        assertNotNull(saved.nodeRoom); assertNull(NodeRooms.parseLink(saved.joinUrl, now + 61))
        assertFailsWith<IllegalArgumentException> { saved.withRoomLifetime(now + 120, true, now) }
    }
    @Test fun `existing quiet room retains identity policy source link and metadata while selecting only its node`() {
        val identity = PrimaryIdentity.create(room.roomId, now + 3600, now, participantSecretKey = ByteArray(32) { 2 }, deviceSecretKey = ByteArray(32) { 4 })
        val policy = RoomPolicy(KindredTier.OPEN, members = listOf(identity.participant), quiet = true)
        val link = encodeJoinUrl("https://app.example/j/", secret, listOf("wss://original.example"), policy)
        val original = SavedRoom.create(secret, identity, link, listOf("wss://original.example"), "Original chat", now,
            null, root.pubkey)
        val destination = requireNotNull(NodeRooms.read(NodeRooms.sign(root.secretKeyForStorage(), room.roomId, "https://node.example", service.pubkey, now,
            existing = NodeExistingRoomPolicy(false, policy.toJson())).toJson(), now))
        val migrated = original.withExistingNodeDestination(destination, now)
        val restored = SavedRoom.decode(Json.parseToJsonElement(migrated.json.toString()).jsonObject)
        assertEquals(original.id, restored.id); assertEquals(original.devicePubkey, restored.devicePubkey)
        assertEquals(original.json.getValue("identity"), restored.json.getValue("identity"))
        assertContentEquals(original.secret, restored.secret); assertEquals(policy, restored.policy)
        assertEquals(original.name, restored.name); assertFalse(restored.destruct)
        assertEquals(link, restored.json.getValue("nodeSource").jsonPrimitive.content)
        assertEquals(listOf(destination.relay), restored.relays); assertEquals(listOf(destination.relay), restored.sharedRelays)
        assertFailsWith<IllegalArgumentException> { original.withExistingNodeDestination(node(), now) }
        assertFailsWith<IllegalArgumentException> { SavedRoom.decode(JsonObject(restored.json + ("destruct" to JsonPrimitive(true)))) }
    }

    @Test fun `fresh guest saves an existing room admission without inventing an original invitation`() {
        val identity = PrimaryIdentity.create(room.roomId, now + 3600, now, participantSecretKey = ByteArray(32) { 2 }, deviceSecretKey = ByteArray(32) { 4 })
        for (policy in listOf<RoomPolicy?>(null, RoomPolicy(KindredTier.OPEN, members = listOf(identity.participant), quiet = true))) {
            val destination = requireNotNull(NodeRooms.read(NodeRooms.sign(root.secretKeyForStorage(), room.roomId, "https://node.example", service.pubkey, now,
                existing = NodeExistingRoomPolicy(false, policy?.toJson())).toJson(), now))
            val saved = SavedRoom.createNodeGuest(secret, identity, destination,
                NodeRooms.encodeLink("https://app.example/j/", destination.wire, now), now, false)
            val restored = SavedRoom.decode(Json.parseToJsonElement(saved.json.toString()).jsonObject)
            assertEquals(destination.event.id, restored.nodeRoom!!.event.id)
            assertEquals(identity.devicePubkey, restored.devicePubkey)
            assertEquals(policy, restored.policy); assertFalse(restored.destruct)
            assertNull(restored.json["nodeSource"])
            assertEquals(listOf(destination.relay), restored.relays)
            assertFailsWith<IllegalArgumentException> { SavedRoom.decode(JsonObject(restored.json + ("destruct" to JsonPrimitive(true)))) }
            assertFailsWith<IllegalArgumentException> { SavedRoom.decode(JsonObject(restored.json - "node")) }
        }
    }

    @Test fun `opening a destination link reuses the original saved identity and refuses replacement keys`() {
        val identity = PrimaryIdentity.create(room.roomId, now + 3600, now, participantSecretKey = ByteArray(32) { 2 }, deviceSecretKey = ByteArray(32) { 4 })
        val link = encodeJoinUrl("https://app.example/j/", secret, listOf("wss://original.example"))
        val original = SavedRoom.create(secret, identity, link, listOf("wss://original.example"), "Original chat", now, null, root.pubkey)
        val destination = requireNotNull(NodeRooms.read(NodeRooms.sign(root.secretKeyForStorage(), room.roomId, "https://node.example", service.pubkey, now,
            existing = NodeExistingRoomPolicy(false)).toJson(), now))
        val targetLink = NodeRooms.encodeLink("https://app.example/j/", destination.wire, now)
        val migrated = savedNodeGuestAdmission(original, identity, destination, secret, 0, targetLink, now, false)
        assertEquals(original.json.getValue("identity"), migrated.json.getValue("identity"))
        assertEquals(original.name, migrated.name); assertEquals(link, migrated.json.getValue("nodeSource").jsonPrimitive.content)
        val stranger = PrimaryIdentity.create(room.roomId, now + 3600, now, participantSecretKey = ByteArray(32) { 7 }, deviceSecretKey = ByteArray(32) { 8 })
        assertFailsWith<IllegalArgumentException> { savedNodeGuestAdmission(original, stranger, destination, secret, 0, targetLink, now, false) }
        assertFailsWith<IllegalArgumentException> { savedNodeGuestAdmission(original, identity, destination, ByteArray(32) { 9 }, 0, targetLink, now, false) }
    }

}
