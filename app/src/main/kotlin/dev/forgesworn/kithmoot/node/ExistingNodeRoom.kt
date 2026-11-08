package dev.forgesworn.kithmoot.node

import dev.forgesworn.kithmoot.account.ParticipantSigner
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.session.RoomIdentity
import dev.forgesworn.kithmoot.storage.SavedRoom
import kotlinx.serialization.json.*

internal data class ExistingNodePrepared(val custody: NodeCustody, val grants: List<JsonObject>) {
    override fun toString() = "ExistingNodePrepared(redacted)"
}

internal fun existingNodeMatches(source: SavedRoom, node: NodeRoomRecord): Boolean = node.existing?.let {
    node.room == source.id && node.authority == source.authority && node.endsAt == source.ends &&
        it.destruct == source.destruct && it.policy == source.migrationPolicyEvidence()
} == true

/** Persist private destination custody and ACK its devices before announcing
 * the destination. The original SavedRoom and archive remain selected here. */
internal suspend fun prepareExistingNodeRoom(source: SavedRoom, identity: RoomIdentity, rootKey: ByteArray,
    epoch: Int, devices: List<NostrEvent>, members: List<String>, removed: List<String>, pair: NodePair,
    owner: ParticipantSigner, store: NodeStore, client: NodeClient, allowed: () -> Boolean,
    now: () -> Long = { System.currentTimeMillis() / 1000 }): ExistingNodePrepared {
    check(allowed())
    require(source.nodeRoom == null && identity.participant == source.participant && identity.devicePubkey == source.devicePubkey)
    require(owner.pubkey == identity.participant && pair.owner == owner.pubkey && Schnorr.publicKeyHex(rootKey) == source.authority)
    require(identity.participant !in removed)
    val existing = store.custody(identity.participant, source.id)
    val node = existing?.node ?: requireNotNull(NodeRooms.read(NodeRooms.sign(rootKey, source.id, pair.origin, pair.serviceKey,
        now(), source.ends, turnPorts = pair.turnPorts, existing = NodeExistingRoomPolicy(source.destruct, source.migrationPolicyEvidence())).toJson(), now()))
    require(existingNodeMatches(source, node) && node.origin == pair.origin && node.serviceKey == pair.serviceKey && node.turnPorts == pair.turnPorts)
    require(existing == null || existing.device == identity.devicePubkey && existing.pendingClose == null)
    val known = (members + identity.participant).distinct().filterNot { it in removed }.sorted()
    val initialKeeper = NodeKeeperState(known, removed.distinct().sorted(), emptyList())
    val secret = source.secret
    try {
        val custody = NodeCustody.read(buildJsonObject {
            existing?.wire?.forEach { (k,v) -> put(k,v) }
            put("v",1); put("node",node.wire); put("participant",identity.participant)
            put("deviceKey",identity.deviceSecretKey.toHex()); put("credential",identity.credential.toJson())
            put("secret",secret.toHex()); put("rootKey",rootKey.toHex()); put("epochHint",epoch)
            if(existing == null)put("phase","creating")
            if(existing?.keeper == null)put("keeper",initialKeeper.toJson())
        })
        store.save(custody)
    } finally { secret.fill(0) }
    client.enrol(pair,node,owner); check(allowed())
    val keeper = NodeKeeperAdmissions(node,identity.participant,store,{event,signer -> client.postGrant(node,event,signer)},allowed,now)
    for(person in removed) if(person in keeper.state().members)keeper.remove(person)
    val credentials = (listOf(identity.credential)+devices).distinctBy { event ->
        val proof = verifyDeviceCredential(event,source.id,now()) as? CredentialCheck.Valid ?: error("A source device needs a current room pass")
        require(proof.participant in known && proof.participant !in removed)
        proof.device
    }
    val grants = credentials.map { requireNotNull(keeper.admit(it.toJson())).grant.event.toJson() }
    val own = grants.single { grant -> NostrEvent.fromJson(grant).tags.any { it == listOf("device",identity.devicePubkey) } }
    check(allowed())
    val saved = store.update(identity.participant,source.id) { it.changed { put("phase","admitted");put("grant",own) } }
    return ExistingNodePrepared(saved,grants)
}

/** Only original room authority/policy and this exact device's live grant can
 * select the private route. Keep the original identity, history and metadata. */
internal fun acceptExistingNodeDestination(source: SavedRoom, identity: RoomIdentity, node: NodeRoomRecord,
    grant: JsonObject, epoch: Int, store: NodeStore, now: Long): SavedRoom {
    require(existingNodeMatches(source,node) && identity.participant == source.participant && identity.devicePubkey == source.devicePubkey)
    require(NodeRooms.verifyGrant(grant,node,identity.devicePubkey,now))
    val destination = source.withExistingNodeDestination(node,now)
    val secret = source.secret
    try {
        store.admit(identity.participant,source.id) { current ->
            require(current == null || current.node.event.id == node.event.id && current.device == identity.devicePubkey && current.pendingClose == null)
            NodeCustody.read(buildJsonObject {
                current?.wire?.forEach { (k,v) -> put(k,v) }
                put("v",1);put("node",node.wire);put("participant",identity.participant)
                put("deviceKey",identity.deviceSecretKey.toHex());put("credential",identity.credential.toJson())
                put("secret",secret.toHex());put("epochHint",epoch);put("phase","admitted");put("grant",grant)
            })
        }
    } finally { secret.fill(0) }
    return destination
}
