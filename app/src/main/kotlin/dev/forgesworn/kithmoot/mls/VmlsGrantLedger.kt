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
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * One VMLS grant a keeper issued to an MLS device at a box (P3-03b-3
 * decision 9): the latest signed grant and its revocation, signed together.
 * [revoked] once the revocation was sent; the grant id is then spent.
 */
data class VmlsGrantRecord(val box: String, val plan: CircleGrantPlan, val revoked: Boolean = false) {
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
 * may be active (3b-1's review). Bothy keeps one active grant id per scope
 * (its rule 18), takes a replacement only when it is later (rule 14), and a
 * revocation only when it carries that id and an expiration no earlier than
 * the latest granted (rule 17). So a renewal reuses the id and never lowers
 * the expiration, and its revocation is signed again with it.
 *
 * A plan is stored with [record] before it is published, as room grants are,
 * so an interrupted install can always be withdrawn.
 */
class VmlsGrantLedger(private val storage: RoomStorage) {
    @Synchronized fun all(): List<VmlsGrantRecord> = read()

    @Synchronized fun get(issuer: String, box: String, device: String): VmlsGrantRecord? =
        read().singleOrNull { it.issuer == issuer && it.box == box && it.device == device }

    @Synchronized fun record(record: VmlsGrantRecord) = write(read().filterNot { same(it, record) } + record)

    /** The retained revocation, marked sent: the caller publishes it next. Null when nothing is active. */
    @Synchronized fun revoke(issuer: String, box: String, device: String): NostrEvent? {
        val record = get(issuer, box, device)?.takeUnless { it.revoked } ?: return null
        write(read().filterNot { same(it, record) } + record.copy(revoked = true))
        return record.plan.revoked
    }

    /**
     * Signs, as the keeper [signer], [persona]'s [device]'s next grant at
     * [box]: the retained id while it is active, otherwise a new one. The
     * keeper's own device names the keeper as [persona]; a guest's names the
     * guest, whose grant is then no keeper's. [boxNow] is the box's clock,
     * since a scoped grant counts only up to 120 s ahead of it. Not stored.
     */
    suspend fun plan(signer: ParticipantSigner, persona: String, box: String, device: String, boxNow: Long): CircleGrantPlan {
        val previous = synchronized(this) { get(signer.pubkey, box, device) }?.takeUnless { it.revoked }
        require(previous == null || previous.persona == persona) { "The device is granted to another persona." }
        val terms = VmlsGrantTerms(
            server = "ws://${linkNodeBase32(box.hexToBytes())}/events",
            persona = persona,
            device = device,
            grantId = previous?.grantId ?: Entropy.bytes(16).toHex(),
            expiration = maxOf(boxNow + VMLS_GRANT_LIFETIME_SECONDS, previous?.expiration ?: 0),
        )
        // Later than anything retained for this id, the revocation included.
        val createdAt = maxOf(boxNow, (previous?.plan?.revoked?.createdAt ?: 0) + 1)
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
                    o.getValue("revoked").jsonPrimitive.boolean,
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
                put("box", r.box); put("active", r.plan.active.toJson()); put("revocation", r.plan.revoked.toJson()); put("revoked", r.revoked)
            }) } })
        }.toString().encodeToByteArray()
        try { storage.write(bytes) } finally { bytes.fill(0) }
    }

    private fun same(a: VmlsGrantRecord, b: VmlsGrantRecord) = key(a) == key(b)
    private fun key(r: VmlsGrantRecord) = "${r.issuer}/${r.box}/${r.device}"
    private inline fun <T> guarded(block: () -> T): T = try { block() } catch (e: Exception) {
        if (e is RoomStorageException) throw e
        throw RoomStorageException(e)
    }

    private companion object {
        const val MAX_GRANTS = 256
        const val MAX_BYTES = 1024 * 1024
    }
}
