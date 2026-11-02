package dev.forgesworn.kithmoot.node

import dev.forgesworn.kithmoot.account.ParticipantSigner
import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.session.PrimaryIdentity
import dev.forgesworn.kithmoot.storage.SavedRoom
import kotlinx.serialization.json.*

internal data class NodeCreatedRoom(val custody: NodeCustody, val identity: PrimaryIdentity) {
    override fun toString() = "NodeCreatedRoom(redacted)"
    fun saved(base: String, name: String, now: Long): SavedRoom {
        val node = custody.node; val secret = custody.roomSecret
        try {
            return SavedRoom.create(secret, identity, NodeRooms.encodeLink(base, node.wire, now), listOf(node.relay), name, node.event.createdAt,
                null, node.authority, ends = node.endsAt, roomRelays = listOf(node.relay), roomRelaysSigned = true, destruct = true, nodeRoom = node, accountOwned = true).opened(now)
        } finally { secret.fill(0) }
    }
}

/** Persists authority, secret and device before first node I/O. Unknown enrol
 * or grant acknowledgements resume the same descriptor and signed grant. */
internal class NodeRoomCreator(
    private val store: NodeStore,
    private val identities: NodeJoinIdentityStore,
    private val client: NodeClient,
    private val allowed: () -> Boolean,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    suspend fun prepare(pair: NodePair, owner: ParticipantSigner, endsAt: Long? = null): NodeCustody {
        check(allowed()); require(pair.owner == owner.pubkey)
        val secret = Entropy.bytes(32); val key = Entropy.bytes(32)
        try {
            val derived = deriveRoom(secret)
            val record = try { NodeRooms.sign(key, derived.roomId, pair.origin, pair.serviceKey, now(), endsAt) } finally { derived.roomKey.fill(0) }
            val node = requireNotNull(NodeRooms.read(record.toJson(), now()))
            val identity = identities.identity(node, owner, now, allowed)
            check(allowed())
            val custody = NodeCustody.read(buildJsonObject { put("v", 1); put("node", node.wire); put("participant", owner.pubkey)
                put("deviceKey", identity.deviceSecretKey.toHex()); put("credential", identity.credential.toJson()); put("secret", secret.toHex()); put("rootKey", key.toHex()); put("phase", "creating") })
            store.save(custody); return custody
        } finally { secret.fill(0); key.fill(0) }
    }
    suspend fun complete(room: String, owner: ParticipantSigner): NodeCreatedRoom {
        check(allowed())
        var saved = requireNotNull(store.custody(owner.pubkey, room))
        require(saved.wire["rootKey"] != null && saved.pendingClose == null)
        val key = saved.deviceKey
        var released = false
        try {
            val credential = saved.credential
            val identity = if (verifyDeviceCredential(credential, room, now()) is CredentialCheck.Valid &&
                credential.tags.single { it.firstOrNull() == "expiration" }[1].toLong() > now() + 60) PrimaryIdentity(owner, key, credential)
            else PrimaryIdentity.createWith(owner, room, minOf(now() + 86400, saved.node.endsAt ?: Long.MAX_VALUE), now(), key)
            check(allowed())
            saved = store.update(owner.pubkey, room) { current -> current.changed { put("credential", identity.credential.toJson()) } }
            if (saved.phase == "creating") {
                val pair = NodePair(saved.node.origin, saved.node.serviceKey, owner.pubkey)
                client.enrol(pair, saved.node, owner); check(allowed())
                val keeper = NodeKeeperAdmissions(saved.node, owner.pubkey, store, { event, authority -> client.postGrant(saved.node, event, authority) }, allowed, now)
                val admitted = requireNotNull(keeper.admit(identity.credential.toJson(), manuallyApproved = true))
                check(allowed())
                saved = store.update(owner.pubkey, room) { current -> current.changed { put("phase", "admitted"); put("grant", admitted.grant.event.toJson()) } }
            }
            released = true; return NodeCreatedRoom(saved, identity)
        } finally { if (!released) key.fill(0) }
    }
}
