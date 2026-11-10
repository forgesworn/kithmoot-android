package dev.forgesworn.kithmoot.node

import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.account.ParticipantSigner
import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

internal data class NodeKeeperDevice(val participant: String, val device: String, val credential: JsonObject, val grant: ParsedGrant, val acknowledged: Boolean = false) {
    fun toJson() = buildJsonObject { put("credential", credential); put("grant", grant.event.toJson()); put("acknowledged", acknowledged) }
}
internal data class NodeKeeperState(val members: List<String>, val removed: List<String>, val devices: List<NodeKeeperDevice>) {
    fun toJson() = buildJsonObject { put("v", 1); put("members", JsonArray(members.map(::JsonPrimitive))); put("removed", JsonArray(removed.map(::JsonPrimitive))); put("devices", JsonArray(devices.map { it.toJson() })) }
    companion object {
        fun read(value: JsonObject?, node: NodeRoomRecord): NodeKeeperState {
            if (value == null) return NodeKeeperState(emptyList(), emptyList(), emptyList())
            require(value.keys == setOf("v", "members", "removed", "devices") && NodeEvidence.number(value, "v") == 1L)
            fun people(field: String) = value.getValue(field).jsonArray.map { it.jsonPrimitive.also { p -> require(p.isString && NodeEvidence.key.matches(p.content)) }.content }
                .also { require(it.size <= 128 && it == it.distinct().sorted()) }
            val members = people("members"); val removed = people("removed"); require(members.none { it in removed })
            val devices = value.getValue("devices").jsonArray.map { item ->
                val row = item.jsonObject; require(row.keys == setOf("credential", "grant", "acknowledged"))
                val credential = NostrEvent.fromJson(row.getValue("credential"))
                val proof = verifyDeviceCredential(credential, node.room, credential.createdAt) as? CredentialCheck.Valid ?: error("Invalid keeper device")
                val grant = ((parseGroupEvent(row["grant"], keepers = listOf(node.authority), servers = listOf(URI(node.origin).host)) as? GroupEventVerdict.Ok)?.parsed as? ParsedGrant)
                    ?: error("Invalid keeper grant")
                require(grant.roomId == node.room && grant.device == proof.device && grant.event.tags.size == 10
                    && grant.scopes.sorted() == listOf("relay-read", "relay-write", "turn") && grant.device != node.serviceKey)
                require(proof.participant in members || proof.participant in removed)
                if (proof.participant in removed) require(grant.status == "revoked")
                NodeKeeperDevice(proof.participant, proof.device, row.getValue("credential").jsonObject, grant, NodeEvidence.bool(row, "acknowledged"))
            }
            require(devices.size <= 128 && devices.map { it.device }.distinct().size == devices.size && devices.groupBy { it.participant }.values.all { it.size <= 5 })
            return NodeKeeperState(members, removed, devices)
        }
    }
}

/** Durable keeper intent precedes every grant POST. A failed or lost response
 * retries the same signed event. No key or admission answer is released until
 * the supervisor confirms both children accepted it. */
internal class NodeKeeperAdmissions(
    val node: NodeRoomRecord,
    private val participant: String,
    private val store: NodeStore,
    private val post: suspend (GroupEvent, ParticipantSigner) -> Unit,
    private val allowed: () -> Boolean,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val mutex = lockFor(participant, node.room)
    /** Who a members-only room's signed policy names, or null when it names nobody. */
    private val named: Set<String>? = node.existing?.policy?.get("members")?.jsonArray?.map { it.jsonPrimitive.content.lowercase() }?.toSet()
    /** Whether this keeper may let [person] in at all: never a removed person, nor anybody a members-only room does not name. */
    fun permits(state: NodeKeeperState, person: String): Boolean = person !in state.removed && (named == null || person in named)
    /** Whether [person]'s devices come in without asking: in this keeper's journal, or named by
     * a members-only room's policy, which has already said who belongs whether or not this
     * keeper has met them. */
    fun knows(state: NodeKeeperState, person: String): Boolean = permits(state, person) && (person in state.members || named != null)
    private fun load(): NodeCustody {
        check(allowed())
        val saved = requireNotNull(store.custody(participant, node.room))
        require(saved.node.event.id == node.event.id && saved.wire["rootKey"] != null && saved.pendingClose == null && saved.pendingRekey == null && (node.endsAt?.let { now() < it } ?: true))
        return saved
    }
    fun state(): NodeKeeperState = NodeKeeperState.read(load().keeper, node)
    private fun signedGrant(saved: NodeCustody, device: String, id: String, previous: ParsedGrant? = null, revoked: Boolean = false): ParsedGrant {
        val at = maxOf(now(), previous?.createdAt?.plus(1) ?: 0).also { require(it <= now() + 30) }
        val key = requireNotNull(saved.rootKey)
        val event = try { buildGrant(key, node.room, device, URI(node.origin).host, listOf("turn", "relay-read", "relay-write"), null, id,
            at, minOf(at + NODE_HORIZON_SECONDS, node.endsAt ?: Long.MAX_VALUE), if (revoked) "revoked" else "active") } finally { key.fill(0) }
        return (parseGroupEvent(event.toJson(), now(), listOf(node.authority), listOf(URI(node.origin).host)) as GroupEventVerdict.Ok).parsed as ParsedGrant
    }
    private suspend fun confirm(event: GroupEvent) {
        val saved = load(); val key = requireNotNull(saved.rootKey)
        try {
            post(event, LocalSigner(key)); check(allowed())
            load()
            store.update(participant, node.room) { current ->
                val state = NodeKeeperState.read(current.keeper, node)
                require(state.devices.any { it.grant.event.id == event.id })
                current.changed { put("keeper", state.copy(devices = state.devices.map { if (it.grant.event.id == event.id) it.copy(acknowledged = true) else it }).toJson()) }
            }
        } finally { key.fill(0) }
    }
    suspend fun admit(credential: JsonObject, manuallyApproved: Boolean = false): NodeKeeperDevice? = mutex.withLock {
        val saved = load(); val state = NodeKeeperState.read(saved.keeper, node)
        require(saved.pendingRemoval == null)
        val event = NostrEvent.fromJson(credential)
        val proof = verifyDeviceCredential(event, node.room, now()) as? CredentialCheck.Valid ?: return@withLock null
        if (!permits(state, proof.participant) || !knows(state, proof.participant) && !manuallyApproved || proof.device in listOf(node.authority, node.serviceKey)) return@withLock null
        // A full journal refuses rather than saving one it could not read back.
        if (proof.participant !in state.members && state.members.size >= MAX_MEMBERS) return@withLock null
        val previous = state.devices.singleOrNull { it.device == proof.device }
        if (previous != null && previous.participant != proof.participant || previous?.grant?.status == "revoked") return@withLock null
        if (previous == null && (state.devices.count { it.participant == proof.participant } >= 5 || state.devices.size >= 128)) return@withLock null
        val grant = previous?.grant?.takeIf { it.expiration > now() && (!previous.acknowledged || now() - it.createdAt < 10 * 86400 || it.expiration == node.endsAt) } ?: signedGrant(saved, proof.device, previous?.grant?.grantId ?: Entropy.bytes(16).toHex(), previous?.grant)
        val row = NodeKeeperDevice(proof.participant, proof.device, credential, grant, previous?.takeIf { it.grant.event.id == grant.event.id }?.acknowledged ?: false)
        val next = NodeKeeperState((state.members + proof.participant).distinct().sorted(), state.removed, state.devices.filterNot { it.device == proof.device } + row)
        store.update(participant, node.room) { current -> require(current.pendingClose == null); current.changed { put("keeper", next.toJson()) } }
        confirm(grant.event)
        row
    }
    /** Remember people the room already counts as members, so this keeper, and any later one
     * reading the same journal, lets their devices in without asking. Never a removed person,
     * nor anybody a members-only room does not name. Learning stops at [MAX_LEARNED], so the
     * journal keeps room for people let in by hand and for policy-named members' first devices. */
    suspend fun learnMembers(people: Collection<String>) = mutex.withLock {
        val saved = load(); val state = NodeKeeperState.read(saved.keeper, node)
        val fresh = people.map { it.lowercase() }.distinct()
            .filter { NodeEvidence.key.matches(it) && it !in state.members && permits(state, it) }
            .take((MAX_LEARNED - state.members.size).coerceAtLeast(0))
        if (fresh.isEmpty()) return@withLock
        val next = state.copy(members = (state.members + fresh).sorted())
        store.update(participant, node.room) { current -> require(current.pendingClose == null); current.changed { put("keeper", next.toJson()) } }
    }
    /** Record people another of the owner's devices removed, whom this journal never counted as
     * members, so they are never let in here either. */
    suspend fun refuse(people: Collection<String>) = mutex.withLock {
        val saved = load(); val state = NodeKeeperState.read(saved.keeper, node)
        val fresh = people.map { it.lowercase() }.distinct()
            .filter { NodeEvidence.key.matches(it) && it != participant && it !in state.members && it !in state.removed }
            .take((MAX_REMOVED - state.removed.size).coerceAtLeast(0))
        if (fresh.isEmpty()) return@withLock
        val next = state.copy(removed = (state.removed + fresh).sorted())
        store.update(participant, node.room) { current -> require(current.pendingClose == null); current.changed { put("keeper", next.toJson()) } }
    }
    /** Revocations are durable and confirmed before the caller can rekey. */
    suspend fun remove(person: String): List<NodeKeeperDevice> = mutex.withLock {
        require(NodeEvidence.key.matches(person) && person != participant)
        val saved = load(); val state = NodeKeeperState.read(saved.keeper, node)
        require(person in state.members || person in state.removed)
        val revoked = state.devices.filter { it.participant == person }.map { row ->
            if (row.grant.status == "revoked") row else row.copy(grant = signedGrant(saved, row.device, row.grant.grantId, row.grant, revoked = true), acknowledged = false)
        }
        val next = NodeKeeperState(state.members - person, (state.removed + person).distinct().sorted(), state.devices.filterNot { it.participant == person } + revoked)
        store.update(participant, node.room) { current -> require(current.pendingClose == null); current.changed { put("keeper", next.toJson()) } }
        revoked.forEach { confirm(it.grant.event) }
        revoked
    }
    suspend fun renewDue() = mutex.withLock {
        val saved = load(); val state = NodeKeeperState.read(saved.keeper, node)
        val renewed = state.devices.map { row ->
            if (row.participant in state.removed || row.grant.status != "active" || now() - row.grant.createdAt < 10 * 86400 || row.grant.expiration == node.endsAt || !row.acknowledged) row
            else row.copy(grant = signedGrant(saved, row.device, row.grant.grantId, row.grant), acknowledged = false)
        }
        if (renewed != state.devices) store.update(participant, node.room) { current -> require(current.pendingClose == null); current.changed { put("keeper", state.copy(devices = renewed).toJson()) } }
        // Every retained active grant is replayable after an unknown ACK. An
        // exact duplicate never advances its signature time or deadline.
        renewed.filter { !it.acknowledged && it.grant.status == "active" && it.participant !in state.removed }.forEach { confirm(it.grant.event) }
    }
    companion object {
        /** What [NodeKeeperState.read] accepts. */
        const val MAX_MEMBERS = 128
        const val MAX_LEARNED = 96
        /** A journal holds at most 128 people in each list (NodeKeeperState). */
        const val MAX_REMOVED = 128
        private val locks = ConcurrentHashMap<String, Mutex>()
        internal fun lockFor(participant: String, room: String): Mutex = locks.computeIfAbsent("$participant:$room") { Mutex() }
    }
}
