package dev.forgesworn.kithmoot.mls

import dev.forgesworn.kithmoot.account.ParticipantSigner
import dev.forgesworn.kithmoot.account.linkNodeBase32
import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.CircleGrantStatus
import dev.forgesworn.kithmoot.protocol.KIND_CIRCLE_EVENT_GRANT
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.VMLS_GRANT_LIFETIME_SECONDS
import dev.forgesworn.kithmoot.protocol.VmlsGrantTerms
import dev.forgesworn.kithmoot.relay.CircleGrantPlan
import dev.forgesworn.kithmoot.storage.RoomStorage
import dev.forgesworn.kithmoot.storage.RoomStorageException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Where a VMLS grant stands. [REVOKING] holds the revocation until the box
 * confirms it, so a failed publish is retried rather than lost; [REVOKED]
 * spends the grant id.
 */
enum class VmlsGrantState { ACTIVE, REVOKING, REVOKED }

/**
 * One VMLS grant a keeper issued to an MLS device at a box (P3-03b-3
 * decision 9): the latest signed grant and its revocation, signed together.
 */
data class VmlsGrantRecord(val box: String, val plan: CircleGrantPlan, val state: VmlsGrantState = VmlsGrantState.ACTIVE) {
    val issuer: String get() = plan.active.pubkey
    val persona: String get() = tag("p")
    val device: String get() = tag("device")
    val grantId: String get() = tag("grant")
    val expiration: Long get() = tag("expiration").toLong()

    init {
        require(HEX64.matches(box))
        require(tag("server") == "ws://${linkNodeBase32(box.hexToBytes())}/events") { "The grant names another box." }
        require(plan.active.tags.contains(listOf("t", "event-grant")) && plan.active.tags.any { it.firstOrNull() == "vmls" })
    }

    private fun tag(name: String): String = plan.active.tags.single { it.firstOrNull() == name }[1]

    private companion object { val HEX64 = Regex("[0-9a-f]{64}") }
}

/**
 * The VMLS grants this phone's keepers issued, kept for as long as a grant
 * may be active (3b-1's review), one per box and device: Bothy's scope
 * (its rule 18) has no issuer, and a device belongs to one persona. Bothy
 * keeps one active grant id per scope, takes a replacement only when it is
 * later (rule 14), and a revocation only when it carries that id and an
 * expiration no earlier than the latest granted (rule 17). So a renewal
 * reuses the id and never lowers the expiration, its revocation is signed
 * again with it, and [record] refuses a plan that would go back on either.
 *
 * A plan is stored with [record] before it is published, as room grants are,
 * so an interrupted install can always be withdrawn. One ledger per storage:
 * its lock is the instance's.
 */
class VmlsGrantLedger(private val storage: RoomStorage) {
    @Synchronized fun all(): List<VmlsGrantRecord> = read()

    @Synchronized fun get(box: String, device: String): VmlsGrantRecord? =
        read().singleOrNull { it.box == box && it.device == device }

    /**
     * Stores [record] before it is published. Refused while a revocation is
     * pending, for another id while one is live, or when it is not later
     * than the one stored (a stale plan would leave a revocation the box
     * refuses). A stored grant counts as lapsed only once both the plan's
     * date (the box's clock) and [phoneNow] are past it: a box clock far
     * ahead never lets a new id overwrite a live grant and its revocation
     * (D1 R5).
     */
    @Synchronized fun record(record: VmlsGrantRecord, phoneNow: Long = record.plan.active.createdAt) {
        require(record.state == VmlsGrantState.ACTIVE)
        val records = read()
        val stored = records.singleOrNull { same(it, record) }
        // A revoked grant, or one lapsed before this plan was signed, holds no scope at the box.
        if (stored != null && stored.state != VmlsGrantState.REVOKED && stored.expiration > minOf(record.plan.active.createdAt, phoneNow)) {
            require(stored.state == VmlsGrantState.ACTIVE) { "A revocation is pending for this device." }
            require(stored.grantId == record.grantId && stored.persona == record.persona) { "Another grant is live for this device." }
            require(record.plan.active.createdAt > stored.plan.active.createdAt && record.expiration >= stored.expiration) { "A later grant is already stored." }
        }
        require(stored != null || records.size < MAX_GRANTS) { "The ledger is full." }
        write(records.filterNot { same(it, record) } + record)
    }

    /**
     * The retained revocation, to publish next: the grant moves to
     * [VmlsGrantState.REVOKING], and the same revocation is handed out again
     * until [revoked] confirms the box took it. Null when nothing is live.
     */
    @Synchronized fun revoke(box: String, device: String): NostrEvent? {
        val record = get(box, device)?.takeIf { it.state != VmlsGrantState.REVOKED } ?: return null
        if (record.state == VmlsGrantState.ACTIVE) write(read().filterNot { same(it, record) } + record.copy(state = VmlsGrantState.REVOKING))
        return record.plan.revoked
    }

    /** The box confirmed [revocation]: the grant id is spent. */
    @Synchronized fun revoked(box: String, device: String, revocation: NostrEvent) {
        val record = get(box, device) ?: return
        require(record.plan.revoked == revocation) { "Not this grant's revocation." }
        write(read().filterNot { same(it, record) } + record.copy(state = VmlsGrantState.REVOKED))
    }

    /**
     * Drops grants revoked, or lapsed by both the box's clock [boxNow] and the
     * phone's [phoneNow]: a box clock far ahead never erases a live grant's
     * record, and with it the revocation a close would publish (D1 R5).
     */
    @Synchronized fun prune(boxNow: Long, phoneNow: Long) = minOf(boxNow, phoneNow).let { lapsed ->
        write(read().filterNot { it.state == VmlsGrantState.REVOKED || it.expiration <= lapsed })
    }

    /**
     * Signs, as the keeper [signer], [persona]'s [device]'s next grant at
     * [box]: the retained id while it is live, otherwise a new one. The
     * keeper's own device names the keeper as [persona]; a guest's names the
     * guest, whose grant is then no keeper's. [boxNow] is the box's clock,
     * since a scoped grant counts only up to 120 s ahead of it; the retained
     * id lapses only once [phoneNow] is past it too (D1 R5). Not stored:
     * [record] it, then publish it.
     */
    suspend fun plan(signer: ParticipantSigner, persona: String, box: String, device: String, boxNow: Long, phoneNow: Long = boxNow): CircleGrantPlan {
        val previous = synchronized(this) { get(box, device) }?.takeUnless { it.state == VmlsGrantState.REVOKED || it.expiration <= minOf(boxNow, phoneNow) }
        require(previous?.state != VmlsGrantState.REVOKING) { "A revocation is pending for this device." }
        require(previous == null || previous.persona == persona) { "The device is granted to another persona." }
        val terms = VmlsGrantTerms(
            server = "ws://${linkNodeBase32(box.hexToBytes())}/events",
            persona = persona,
            device = device,
            grantId = previous?.grantId ?: Entropy.bytes(16).toHex(),
            expiration = maxOf(boxNow + VMLS_GRANT_LIFETIME_SECONDS, previous?.expiration ?: 0),
        )
        // Later than the grant the box holds: one second on at most, so renewals never run ahead of its clock.
        val createdAt = maxOf(boxNow, (previous?.plan?.active?.createdAt ?: 0) + 1)
        return CircleGrantPlan(
            signer.sign(KIND_CIRCLE_EVENT_GRANT, createdAt, terms.tags(CircleGrantStatus.ACTIVE), ""),
            signer.sign(KIND_CIRCLE_EVENT_GRANT, createdAt + 1, terms.tags(CircleGrantStatus.REVOKED), ""),
        )
    }

    private fun read(): List<VmlsGrantRecord> = guarded {
        val bytes = storage.read() ?: return@guarded emptyList()
        try {
            require(bytes.size <= MAX_BYTES)
            val root = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            require(root.getValue("version").jsonPrimitive.content == "1")
            val entries = root.getValue("grants").jsonArray.map { value ->
                val o = value.jsonObject
                VmlsGrantRecord(
                    o.getValue("box").jsonPrimitive.content,
                    CircleGrantPlan(NostrEvent.fromJson(o.getValue("active")), NostrEvent.fromJson(o.getValue("revocation"))),
                    VmlsGrantState.valueOf(o.getValue("state").jsonPrimitive.content),
                )
            }
            require(entries.size <= MAX_GRANTS && entries.distinctBy(::key).size == entries.size)
            entries
        } finally { bytes.fill(0) }
    }

    private fun write(records: List<VmlsGrantRecord>) = guarded {
        require(records.size <= MAX_GRANTS)
        val bytes = buildJsonObject {
            put("version", 1)
            put("grants", buildJsonArray { records.forEach { r -> add(buildJsonObject {
                put("box", r.box); put("active", r.plan.active.toJson()); put("revocation", r.plan.revoked.toJson()); put("state", r.state.name)
            }) } })
        }.toString().encodeToByteArray()
        try { storage.write(bytes) } finally { bytes.fill(0) }
    }

    private fun same(a: VmlsGrantRecord, b: VmlsGrantRecord) = key(a) == key(b)
    private fun key(r: VmlsGrantRecord) = "${r.box}/${r.device}"
    private inline fun <T> guarded(block: () -> T): T = try { block() } catch (e: Exception) {
        if (e is RoomStorageException) throw e
        throw RoomStorageException(e)
    }

    private companion object {
        const val MAX_GRANTS = 256
        const val MAX_BYTES = 1024 * 1024
    }
}
