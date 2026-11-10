package dev.forgesworn.kithmoot.node

import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.session.PrimaryIdentity
import dev.forgesworn.kithmoot.session.dmPolicy
import dev.forgesworn.kithmoot.storage.SavedRoom
import kotlinx.serialization.json.*
import java.util.Base64
import kotlin.test.*

/**
 * A member's phone that saved a two-person room before its owner moved it to
 * a node must recognise the destination the owner's web or desktop app signed.
 * The saved copies are built as the phone's own join paths build them, and the
 * destination as the web signs it: `existing: { destruct, policy }`, where the
 * policy is the web's parsed `roomPolicy` object.
 */
class ExistingNodeMatchTest {
    private val now = 1_900_000_000L
    private val base = "https://app.example/j/"
    private val relays = listOf("wss://original.example")
    private val root = LocalSigner(ByteArray(32) { 3 })
    private val service = LocalSigner(ByteArray(32) { 5 })
    private val otherMember = LocalSigner(ByteArray(32) { 11 })
    private val secret = ByteArray(32) { 6 }
    private val room = deriveRoom(secret)
    private val ownerKey = LocalSigner(ByteArray(32) { 2 }).pubkey
    private val member = PrimaryIdentity.create(room.roomId, now + 86400, now, participantSecretKey = ByteArray(32) { 7 }, deviceSecretKey = ByteArray(32) { 8 })
    private val sorted = listOf(ownerKey, member.participant).sorted()
    private val bearer = ByteArray(32) { 9 }

    private fun policy(vararg members: String, agents: String? = null, quiet: Boolean = false, admitted: List<String>? = null) = buildJsonObject {
        put("tier", "open")
        admitted?.let { put("admitted", JsonArray(it.map(::JsonPrimitive))) }
        agents?.let { put("agents", it) }
        put("members", JsonArray(members.map(::JsonPrimitive)))
        if (quiet) put("quiet", true)
    }

    /** As the web signs it, read back as the phone reads a control message. */
    private fun destination(policy: JsonObject?, destruct: Boolean = false): JsonObject =
        NodeRooms.sign(root.secretKeyForStorage(), room.roomId, "https://node.example", service.pubkey, now,
            existing = NodeExistingRoomPolicy(destruct, policy)).toJson()

    /** The follower's own two checks, in its order: the pinned signature, then the saved copy. */
    private fun follows(saved: SavedRoom, raw: JsonObject): Boolean =
        NodeRooms.read(raw, now, saved.authority)?.let { existingNodeMatches(saved, it) } == true

    private fun fragment(json: JsonObject) = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toString().toByteArray())

    /** A web link the phone kept byte for byte (the contact inbox path keeps the fragment). */
    private fun webLink(version: Int, inviter: String, policy: JsonObject?, authority: String? = null): String = base + "#" + fragment(buildJsonObject {
        put("v", version); put("j", Base64.getUrlEncoder().withoutPadding().encodeToString(bearer)); put("h", inviter)
        authority?.let { put("o", it) }
        put("r", JsonArray(relays.map(::JsonPrimitive))); put("i", JsonArray(emptyList()))
        policy?.let { put("a", it) }
    })

    private fun saved(link: String): SavedRoom {
        val invitation = decodeInvitationUrl(link)
        return SavedRoom.create(secret, member, link, relays, "Private", now, null, invitation?.invitation?.roomAuthority)
    }

    /** The join paths re-encode the link through the phone's RoomPolicy, as joinInvitation does. */
    private fun reencoded(link: String): SavedRoom {
        val payload = requireNotNull(decodeInvitationUrl(link))
        return saved(encodeInvitationUrl(base, payload.invitation, payload.relays, payload.policy))
    }

    @Test fun `a contact invitation kept as the web wrote it follows the web destination`() {
        assertTrue(follows(saved(webLink(3, root.pubkey, policy(*sorted.toTypedArray()))), destination(policy(*sorted.toTypedArray()))))
    }

    @Test fun `a joined invitation re-encoded by the phone follows the web destination`() {
        assertTrue(follows(reencoded(webLink(3, root.pubkey, policy(*sorted.toTypedArray()))), destination(policy(*sorted.toTypedArray()))))
        assertTrue(follows(saved(encodeInvitationUrl(base, RoomInvitation(bearer, root.pubkey, true), relays, dmPolicy(ownerKey, member.participant))),
            destination(dmPolicy(member.participant, ownerKey).toJson())))
    }

    @Test fun `members listed in another order are the same members`() {
        val reversed = sorted.reversed().toTypedArray()
        assertTrue(follows(saved(webLink(3, root.pubkey, policy(*sorted.toTypedArray()))), destination(policy(*reversed))))
        assertTrue(follows(reencoded(webLink(3, root.pubkey, policy(*reversed))), destination(policy(*sorted.toTypedArray()))))
    }

    @Test fun `admitted issuers listed in another order are the same issuers`() {
        val issuers = listOf(LocalSigner(ByteArray(32) { 12 }).pubkey, LocalSigner(ByteArray(32) { 13 }).pubkey)
        assertTrue(follows(saved(webLink(3, root.pubkey, policy(*sorted.toTypedArray(), admitted = issuers))),
            destination(policy(*sorted.toTypedArray(), admitted = issuers.reversed()))))
    }

    @Test fun `upper case and repeated members read as the web reads them`() {
        val shouted = sorted.map(String::uppercase) + sorted.first().uppercase()
        assertTrue(follows(saved(webLink(3, root.pubkey, policy(*shouted.toTypedArray()))), destination(policy(*sorted.toTypedArray()))))
    }

    @Test fun `a quiet two person room follows`() {
        assertTrue(follows(saved(webLink(3, root.pubkey, policy(*sorted.toTypedArray(), quiet = true))),
            destination(policy(*sorted.toTypedArray(), quiet = true))))
    }

    @Test fun `version 5 and authority version 2 invitations follow`() {
        val five = encodeInvitationUrl(base, RoomInvitation(bearer, otherMember.pubkey, false, root.pubkey), relays, dmPolicy(ownerKey, member.participant))
        assertTrue(follows(saved(five), destination(policy(*sorted.toTypedArray()))))
        assertTrue(follows(saved(webLink(2, root.pubkey, policy(*sorted.toTypedArray()))), destination(policy(*sorted.toTypedArray()))))
    }

    @Test fun `the agent rule kept in the link follows, and a link the phone re-encoded without it does not`() {
        val withRule = webLink(3, root.pubkey, policy(*sorted.toTypedArray(), agents = "owned-by-members"))
        val raw = destination(policy(*sorted.toTypedArray(), agents = "owned-by-members"))
        assertTrue(follows(saved(withRule), raw))
        // RoomPolicy has no agent rule, so the join path's re-encoding drops it:
        // this saved copy cannot confirm the destination and must say so.
        assertFalse(follows(reencoded(withRule), raw))
    }

    @Test fun `a saved copy with no pinned authority cannot confirm any destination`() {
        val bare = SavedRoom.create(secret, member, encodeJoinUrl(base, secret, relays, dmPolicy(ownerKey, member.participant)), relays, "Private", now, null, null)
        assertNull(bare.authority)
        assertFalse(follows(bare, destination(policy(*sorted.toTypedArray()))))
    }

    @Test fun `a version 2 link made by another member pins the wrong key and cannot confirm`() {
        val theirs = saved(encodeInvitationUrl(base, RoomInvitation(bearer, otherMember.pubkey, false), relays, dmPolicy(ownerKey, member.participant)))
        assertEquals(otherMember.pubkey, theirs.authority)
        assertFalse(follows(theirs, destination(policy(*sorted.toTypedArray()))))
    }

    @Test fun `a different policy never matches`() {
        val saved = saved(webLink(3, root.pubkey, policy(*sorted.toTypedArray())))
        assertFalse(follows(saved, destination(null)))
        assertFalse(follows(saved, destination(policy(*(sorted + otherMember.pubkey).toTypedArray()))))
        assertFalse(follows(saved, destination(policy(sorted.first()))))
        assertFalse(follows(saved, destination(buildJsonObject { put("tier", "kith"); put("members", JsonArray(sorted.map(::JsonPrimitive))) })))
        assertFalse(follows(saved, destination(policy(*sorted.toTypedArray(), quiet = true))))
        assertFalse(follows(saved, destination(policy(*sorted.toTypedArray()), destruct = true)))
        assertFalse(follows(saved(webLink(3, root.pubkey, null)), destination(policy(*sorted.toTypedArray()))))
    }

    @Test fun `a followed room saved with members in another order still saves and reloads`() {
        val original = saved(webLink(3, root.pubkey, policy(*sorted.toTypedArray())))
        val node = requireNotNull(NodeRooms.read(destination(policy(*sorted.reversed().toTypedArray())), now, original.authority))
        val moved = original.withExistingNodeDestination(node, now)
        val restored = SavedRoom.decode(Json.parseToJsonElement(moved.json.toString()).jsonObject)
        assertEquals(node.event.id, restored.nodeRoom?.event?.id)
        assertEquals(sorted.toSet(), restored.policy?.members?.toSet())
        val held = savedNodeGuestAdmission(original, member, node, secret, 0, NodeRooms.encodeLink(base, node.wire, now), now, false)
        assertEquals(node.event.id, held.nodeRoom?.event?.id)
    }

    @Test fun `a room followed by the earlier exact comparison still loads`() {
        val original = saved(webLink(3, root.pubkey, policy(*sorted.toTypedArray())))
        val node = requireNotNull(NodeRooms.read(destination(policy(*sorted.toTypedArray())), now, original.authority))
        val restored = SavedRoom.decode(Json.parseToJsonElement(original.withExistingNodeDestination(node, now).json.toString()).jsonObject)
        assertTrue(existingNodeMatches(original, restored.nodeRoom!!))
    }
}
