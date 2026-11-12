package dev.forgesworn.kithmoot.node

import dev.forgesworn.kithmoot.account.AccountRoomOwner
import dev.forgesworn.kithmoot.account.ParticipantSigner
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.session.RoomIdentity
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A device of the account that made a room on its node lets itself in with
 * the owner key and room secret the account's other devices shared in its
 * bookmark, as the device that made the room did, so none of them need be
 * online. Only a room made on the node that nobody has been removed from: a
 * removal moves the room to an epoch whose secret only its members' devices
 * were given. Anything else is left to the room's keeper, and a self-admission
 * that does not finish leaves nothing behind. The web client's counterpart is
 * admitOwnBrowserNodeDevice in app/src/node-room-runtime.ts.
 */
internal suspend fun admitOwnNodeDevice(record: NodeRoomRecord, identity: RoomIdentity, owner: AccountRoomOwner, secretHex: String,
    store: NodeStore, post: suspend (GroupEvent, ParticipantSigner) -> Unit, allowed: () -> Boolean,
    now: () -> Long = { System.currentTimeMillis() / 1000 }): NodeCustody {
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
    val members = (listOf(identity.participant) + owner.members).distinct().sorted().take(NodeKeeperAdmissions.MAX_LEARNED)
    store.admit(identity.participant, node.room) { current ->
        require(current == null)
        NodeCustody.read(buildJsonObject {
            put("v", 1); put("node", node.wire); put("participant", identity.participant); put("deviceKey", identity.deviceSecretKey.toHex())
            put("credential", identity.credential.toJson()); put("secret", secretHex); put("rootKey", owner.key); put("phase", "creating")
            put("keeper", NodeKeeperState(members, emptyList(), emptyList()).toJson())
        })
    }
    try {
        check(allowed())
        val keeper = NodeKeeperAdmissions(node, identity.participant, store, post, allowed, now)
        val admitted = requireNotNull(keeper.admit(identity.credential.toJson(), manuallyApproved = true))
        check(allowed())
        return store.update(identity.participant, node.room) { it.changed { put("phase", "admitted"); put("grant", admitted.grant.event.toJson()) } }
    } catch (failure: Throwable) {
        store.forget(identity.participant, node.room)
        throw failure
    }
}
