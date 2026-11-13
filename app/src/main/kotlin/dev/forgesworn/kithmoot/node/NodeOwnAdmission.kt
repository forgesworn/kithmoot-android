package dev.forgesworn.kithmoot.node

import dev.forgesworn.kithmoot.account.AccountRoomOwner
import dev.forgesworn.kithmoot.account.ParticipantSigner
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.session.RoomIdentity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A device of the account that made a room on its node lets itself in with
 * the owner key and room secret the account's other devices shared in its
 * bookmark, as the device that made the room did, so none of them need be
 * online. Only a room made on the node that nobody has been removed from: a
 * removal moves the room to an epoch whose secret only its members' devices
 * were given. Anything else is left to the room's keeper, and a self-admission
 * that fails leaves no custody behind (one cut short by the app being killed
 * still does, as before). [identities] keeps the grant
 * it sends beside the asking device, so a retry after a lost reply sends the
 * grant the node may already hold again, as the web keeps its keeper journal.
 * The web client's counterpart is admitOwnBrowserNodeDevice in
 * app/src/node-room-runtime.ts.
 */
internal suspend fun admitOwnNodeDevice(record: NodeRoomRecord, identity: RoomIdentity, owner: AccountRoomOwner, secretHex: String,
    store: NodeStore, post: suspend (GroupEvent, ParticipantSigner) -> Unit, allowed: () -> Boolean,
    now: () -> Long = { System.currentTimeMillis() / 1000 }, identities: NodeJoinIdentityStore? = null): NodeCustody {
    check(allowed())
    val node = requireNotNull(NodeRooms.read(record.wire, now(), record.authority))
    require(node.existing == null && owner.removed.isEmpty()) { "This room is left to its keeper" }
    val key = owner.key.hexToBytes(); val secret = secretHex.hexToBytes()
    try {
        require(Schnorr.publicKeyHex(key) == node.authority) { "The shared owner key is not this room's" }
        val derived = deriveRoom(secret)
        try { require(derived.roomId == node.room) { "The shared secret is not this room's" } } finally { derived.roomKey.fill(0) }
    } finally { key.fill(0); secret.fill(0) }
    require(store.custody(identity.participant, node.room) == null)
    // This device's own participant is always among those learned, however many the account shared.
    val members = (owner.members.filter { it != identity.participant }.distinct().sorted().take(NodeKeeperAdmissions.MAX_LEARNED - 1) + identity.participant).sorted()
    store.admit(identity.participant, node.room) { current ->
        require(current == null)
        // Read only once no other self-admission of this device is running, so it sees the grant an earlier one kept.
        val sent = identities?.let { rememberedRow(it, node, identity, members) }
        // A kept grant ahead of this clock cannot be sent yet, and the node may hold it, so a fresh
        // one would be refused. Nothing is signed until the clock reaches it; the guest path stays open.
        check(sent == null || sent.grant.createdAt <= now() + GRANT_FUTURE_SKEW_SECONDS) { "This device's clock is behind the grant it sent" }
        NodeCustody.read(buildJsonObject {
            put("v", 1); put("node", node.wire); put("participant", identity.participant); put("deviceKey", identity.deviceSecretKey.toHex())
            put("credential", identity.credential.toJson()); put("secret", secretHex); put("rootKey", owner.key); put("phase", "creating")
            put("keeper", NodeKeeperState(members, emptyList(), listOfNotNull(sent)).toJson())
        })
    }
    val held = try {
        check(allowed())
        // Kept before it leaves: if nothing can be kept, nothing is sent.
        val sending: suspend (GroupEvent, ParticipantSigner) -> Unit = { event, authority ->
            identities?.rememberGrant(node, identity.participant, event); post(event, authority)
        }
        val keeper = NodeKeeperAdmissions(node, identity.participant, store, sending, allowed, now)
        val admitted = requireNotNull(keeper.admit(identity.credential.toJson(), manuallyApproved = true))
        check(allowed())
        store.update(identity.participant, node.room) { it.changed { put("phase", "admitted"); put("grant", admitted.grant.event.toJson()) } }
    } catch (failure: Throwable) {
        store.forget(identity.participant, node.room)
        throw failure
    }
    // A grant left here is never used again while the custody stands.
    runCatching { identities?.forgetGrant(node, identity.participant) }
    return held
}

/** The grant an earlier self-admission of this device sent, as an unconfirmed keeper journal row,
 * so [NodeKeeperAdmissions.admit] sends that same grant again, or renews it under the same grant id
 * once it has lapsed. Anything this device cannot use is ignored and a fresh grant signed. */
private fun rememberedRow(identities: NodeJoinIdentityStore, node: NodeRoomRecord, identity: RoomIdentity, members: List<String>): NodeKeeperDevice? = try {
    identities.rememberedGrant(node, identity.participant)?.let { grant ->
        val row = buildJsonObject { put("credential", identity.credential.toJson()); put("grant", grant); put("acknowledged", false) }
        NodeKeeperState.read(buildJsonObject {
            put("v", 1); put("members", JsonArray(members.map(::JsonPrimitive))); put("removed", JsonArray(emptyList())); put("devices", JsonArray(listOf(row)))
        }, node).devices.single().takeIf { it.grant.status == "active" && it.device == identity.devicePubkey && it.participant == identity.participant }
    }
} catch (_: Exception) { null }
