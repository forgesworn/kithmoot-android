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
        it.destruct == source.destruct && samePolicyEvidence(it.policy, source.migrationPolicyEvidence())
} == true

/** A room policy as the facts it states. Lists of keys are sets: the web
 * writes a members list in whatever order its link had, and a link written
 * on another device may have had another. */
private data class PolicyFacts(val tier: String, val admitted: Set<String>?, val members: Set<String>?, val agents: String?, val quiet: Boolean)

private val POLICY_FIELDS = setOf("tier", "admitted", "agents", "members", "quiet")

/** Null for anything this reader cannot read in full, which then matches nothing. */
private fun policyFacts(policy: JsonObject): PolicyFacts? = runCatching {
    require(policy.keys.all { it in POLICY_FIELDS })
    fun text(value: JsonElement?): String = (value as JsonPrimitive).also { require(it.isString) }.content
    fun keys(name: String): Set<String>? = policy[name]?.let { list -> (list as JsonArray).map { text(it).lowercase() }.toSet() }
    val quiet = policy["quiet"]?.let { (it as JsonPrimitive).also { flag -> require(!flag.isString) }.boolean } ?: false
    PolicyFacts(text(policy["tier"]), keys("admitted"), keys("members"), policy["agents"]?.let(::text), quiet)
}.getOrNull()

/** Whether a saved room's policy and a signed destination's say the same:
 * the same tier, the same members and admitted issuers in any order, and the
 * same agent rule and quiet flag. A policy on one side only never matches,
 * and neither does an agent rule on one side only. */
internal fun samePolicyEvidence(a: JsonObject?, b: JsonObject?): Boolean {
    if (a == null || b == null) return a == null && b == null
    val left = policyFacts(a) ?: return false
    return left == policyFacts(b)
}

/** A destination link must not replace an original device's saved identity
 * with a fresh asking identity. Only fresh guests lack source custody.
 *
 * [listedName] is what the account's room list calls the room, when another
 * of the account's devices made it: a node link carries no name, so without
 * it the room was saved as "Room d0559f3c", shown as "Untitled room", and
 * this phone had no name to give everybody else (12 November 2026). */
internal fun savedNodeGuestAdmission(source: SavedRoom?, identity: RoomIdentity, node: NodeRoomRecord,
    secret: ByteArray, epoch: Int, link: String, now: Long, accountOwned: Boolean, listedName: String? = null): SavedRoom {
    if (source == null) return SavedRoom.createNodeGuest(secret, identity, node, link, now, accountOwned).withEpochHint(epoch).let { saved ->
        listedName?.takeIf { dev.forgesworn.kithmoot.ui.start.hasOwnName(it, saved.id) }?.let(saved::renamed) ?: saved
    }
    require((source.nodeRoom?.event?.id == node.event.id || existingNodeMatches(source, node)) &&
        source.devicePubkey == identity.devicePubkey && source.participant == identity.participant)
    val originalSecret = source.secret
    try { require(originalSecret.contentEquals(secret)) { "The original room keys must be recovered before selecting this destination" } }
    finally { originalSecret.fill(0) }
    return (if (source.nodeRoom?.event?.id == node.event.id) source else source.withExistingNodeDestination(node, now)).withEpochHint(epoch)
}

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
