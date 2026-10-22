package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.math.BigInteger

/**
 * Group grants: kind 27541 events a room's keeper signs with the room's
 * authority key, so the group's own storage node serves only the devices the
 * keeper admitted. The contract is `docs/GROUP-GRANTS.md` in wildbloom-node,
 * profile `group-grants/1`; rule numbers below are its section 4.4. Mirrors
 * `group-grants.ts` in the web client: the parser and the receiver, and the
 * builders for tests (an Android keeper grants nothing).
 *
 * A grant is never published on a relay. It travels in the room's encrypted
 * control channel (`{"op":"storage-grant","event":{...}}`) and by HTTP POST to
 * a node.
 */
const val GROUP_GRANT_KIND: Int = 27541
/** Longest a grant or reset may last after its `created_at`, seconds (rule 3). */
const val MAX_GRANT_SECONDS: Long = 7_776_000
/** How far ahead of the verifier's clock `created_at` may be, seconds (rule 7). */
const val GRANT_FUTURE_SKEW_SECONDS: Long = 30
/** The most an event may weigh as JSON, bytes (rule 1). */
const val MAX_GROUP_EVENT_BYTES: Int = 16 * 1024
const val MAX_GROUP_EVENT_TAGS: Int = 16
/** Every scope a grant may name, in the order a keeper writes them. */
val GRANT_SCOPES: List<String> = listOf("upload", "get", "delete", "purge", "turn", "relay-read", "relay-write")

private val HEX32 = Regex("^[0-9a-f]{64}$")
private val HEX16 = Regex("^[0-9a-f]{32}$")
private val HEX64B = Regex("^[0-9a-f]{128}$")
private val DECIMAL = Regex("^(0|[1-9][0-9]*)$")
private val MAX_BYTES_VALUE = BigInteger.ONE.shiftLeft(63).subtract(BigInteger.ONE)
private val GRANT_TAGS = setOf("t", "grant", "d", "device", "server", "scope", "bytes", "status", "expiration")
private val RESET_TAGS = setOf("t", "d", "server", "expiration")
private val EVENT_KEYS = setOf("id", "pubkey", "created_at", "kind", "tags", "content", "sig")

/** What `wildbloomd` accepts in a `server` tag: lower-case letters, digits and hyphens in dot-separated labels. */
fun isHostName(value: String): Boolean = value.isNotEmpty() && value.length <= 253 && value.split('.').all { label ->
    label.isNotEmpty() && label.length <= 63 && !label.startsWith('-') && !label.endsWith('-') && label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
}

/** A signed group event as it travels: the seven NIP-01 fields, `content` always empty. */
data class GroupEvent(val id: String, val pubkey: String, val createdAt: Long, val tags: List<List<String>>, val sig: String) {
    /** The event as JSON, in NIP-01's field order. */
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id); put("pubkey", pubkey); put("created_at", createdAt); put("kind", GROUP_GRANT_KIND)
        put("tags", buildJsonArray { for (t in tags) add(buildJsonArray { for (v in t) add(JsonPrimitive(v)) }) })
        put("content", ""); put("sig", sig)
    }
}

sealed interface ParsedGroupEvent {
    val event: GroupEvent
    val signer: String
    val createdAt: Long
    val expiration: Long
    val roomId: String
    val server: String
}

data class ParsedGrant(
    override val event: GroupEvent, override val signer: String, override val createdAt: Long, override val expiration: Long,
    val grantId: String, override val roomId: String, val device: String, override val server: String,
    val scopes: List<String>,
    /** Present exactly when `upload` is a scope. */
    val bytes: BigInteger?,
    /** `active` or `revoked`. */
    val status: String,
) : ParsedGroupEvent

data class ParsedReset(
    override val event: GroupEvent, override val signer: String, override val createdAt: Long, override val expiration: Long,
    override val roomId: String, override val server: String,
) : ParsedGroupEvent

/** A verdict on one event: accepted, or the status and reason a node would answer with. */
sealed interface GroupEventVerdict {
    data class Ok(val parsed: ParsedGroupEvent) : GroupEventVerdict
    data class Refused(val status: Int, val reason: String) : GroupEventVerdict
}

private val malformed = GroupEventVerdict.Refused(400, "malformed")

/**
 * Rules 1 to 7, in order, the first failure deciding: the checks that need no
 * node state. Rules 5 to 7 run only for what is given: [keepers] (signers
 * accepted), [servers] (host names accepted) and [now] (the verifier's clock).
 */
fun parseGroupEvent(value: JsonElement?, now: Long? = null, keepers: Collection<String>? = null, servers: Collection<String>? = null): GroupEventVerdict {
    val obj = value as? JsonObject ?: return malformed
    // Rule 1: size, and a strict NIP-01 event.
    if (obj.toString().toByteArray(Charsets.UTF_8).size > MAX_GROUP_EVENT_BYTES) return GroupEventVerdict.Refused(413, "too-large")
    if (obj.keys != EVENT_KEYS) return malformed
    fun string(key: String): String? = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val id = string("id")?.takeIf(HEX32::matches) ?: return malformed
    val pubkey = string("pubkey")?.takeIf(HEX32::matches) ?: return malformed
    val sig = string("sig")?.takeIf(HEX64B::matches) ?: return malformed
    val createdAt = (obj["created_at"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: return malformed
    if (createdAt < 0 || createdAt > 9_007_199_254_740_991L) return malformed
    // Rule 2: kind, empty content, tags.
    val kind = (obj["kind"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
    if (kind != GROUP_GRANT_KIND.toLong() || string("content") != "") return malformed
    val rawTags = obj["tags"] as? JsonArray ?: return malformed
    if (rawTags.size > MAX_GROUP_EVENT_TAGS) return malformed
    val tags = rawTags.map { tag ->
        val pair = tag as? JsonArray ?: return malformed
        if (pair.size != 2) return malformed
        pair.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return malformed }
    }
    val event = GroupEvent(id, pubkey, createdAt, tags, sig)
    val single = HashMap<String, String>()
    val scopes = ArrayList<String>()
    for ((name, v) in tags) {
        if (name == "scope") { scopes += v; continue }
        if (single.put(name, v) != null) return malformed
    }
    val t = single["t"]
    if (t != "grant" && t != "reset") return malformed
    val allowed = if (t == "grant") GRANT_TAGS else RESET_TAGS
    if (tags.any { it[0] !in allowed }) return malformed
    if (t == "reset" && scopes.isNotEmpty()) return malformed
    val room = single["d"]?.takeIf(HEX32::matches) ?: return malformed
    val server = single["server"]?.takeIf(::isHostName) ?: return malformed
    val expiration = single["expiration"]?.takeIf(DECIMAL::matches)?.toLongOrNull()?.takeIf { it <= 9_007_199_254_740_991L } ?: return malformed

    val parsed: ParsedGroupEvent = if (t == "reset") ParsedReset(event, pubkey, createdAt, expiration, room, server) else {
        val grantId = single["grant"]?.takeIf(HEX16::matches) ?: return malformed
        val device = single["device"]?.takeIf(HEX32::matches) ?: return malformed
        if (device == pubkey) return malformed
        val status = single["status"]?.takeIf { it == "active" || it == "revoked" } ?: return malformed
        if (scopes.isEmpty() || scopes.size > GRANT_SCOPES.size || scopes.toSet().size != scopes.size || !scopes.all { it in GRANT_SCOPES }) return malformed
        val bytesText = single["bytes"]
        if (("upload" in scopes) != (bytesText != null)) return malformed
        val bytes = bytesText?.let {
            if (!DECIMAL.matches(it)) return malformed
            BigInteger(it).takeIf { n -> n >= BigInteger.ONE && n <= MAX_BYTES_VALUE } ?: return malformed
        }
        ParsedGrant(event, pubkey, createdAt, expiration, grantId, room, device, server, scopes, bytes, status)
    }
    // Rule 3.
    if (expiration <= createdAt || expiration - createdAt > MAX_GRANT_SECONDS) return GroupEventVerdict.Refused(400, "lifetime")
    // Rule 4.
    if (!signatureOk(event)) return GroupEventVerdict.Refused(401, "signature")
    // Rules 5 to 7.
    if (keepers != null && pubkey !in keepers) return GroupEventVerdict.Refused(403, "keeper")
    if (servers != null && server !in servers) return GroupEventVerdict.Refused(403, "server")
    if (now != null) {
        if (createdAt > now + GRANT_FUTURE_SKEW_SECONDS) return GroupEventVerdict.Refused(403, "future")
        if (expiration <= now) return GroupEventVerdict.Refused(403, "expired")
    }
    return GroupEventVerdict.Ok(parsed)
}

private fun signatureOk(event: GroupEvent): Boolean = runCatching {
    if (Events.eventId(event.pubkey, event.createdAt, GROUP_GRANT_KIND, event.tags, "") != event.id) return false
    Schnorr.verify(event.sig.hexToBytes(), event.id.hexToBytes(), event.pubkey.hexToBytes())
}.getOrDefault(false)

private fun signGroupEvent(authoritySecretKey: ByteArray, createdAt: Long, tags: List<List<String>>, auxRand: ByteArray): GroupEvent {
    val signed = Events.sign(authoritySecretKey, GROUP_GRANT_KIND, createdAt, tags, "", auxRand)
    val event = GroupEvent(signed.id, signed.pubkey, signed.createdAt, signed.tags, signed.sig)
    // A caller's mistake (a bad host name, a repeated scope) is refused here, not discovered by a node.
    val verdict = parseGroupEvent(event.toJson())
    require(verdict is GroupEventVerdict.Ok) { "group grant event is not valid: ${(verdict as GroupEventVerdict.Refused).reason}" }
    return event
}

/** A grant, or with [status] `revoked` its revocation; tags in the keeper's order. For tests: an Android keeper grants nothing. */
fun buildGrant(authoritySecretKey: ByteArray, roomId: String, device: String, server: String, scopes: List<String>,
    bytes: Long?, grantId: String, createdAt: Long, expiration: Long, status: String = "active",
    auxRand: ByteArray = Entropy.bytes(32)): GroupEvent {
    val tags = mutableListOf(listOf("t", "grant"), listOf("grant", grantId), listOf("d", roomId), listOf("device", device), listOf("server", server))
    GRANT_SCOPES.filter { it in scopes }.forEach { tags += listOf("scope", it) }
    if ("upload" in scopes) tags += listOf("bytes", requireNotNull(bytes) { "an upload grant needs bytes" }.toString())
    else require(bytes == null) { "bytes is only for an upload grant" }
    tags += listOf("status", status); tags += listOf("expiration", expiration.toString())
    return signGroupEvent(authoritySecretKey, createdAt, tags, auxRand)
}

/** A reset: ends every grant of the room at one node that is not newer than it. For tests. */
fun buildReset(authoritySecretKey: ByteArray, roomId: String, server: String, createdAt: Long,
    expiration: Long = createdAt + MAX_GRANT_SECONDS, auxRand: ByteArray = Entropy.bytes(32)): GroupEvent =
    signGroupEvent(authoritySecretKey, createdAt,
        listOf(listOf("t", "reset"), listOf("d", roomId), listOf("server", server), listOf("expiration", expiration.toString())), auxRand)

/**
 * What a device keeps of the `storage-grant` ops it reads (contract 7.1): an
 * event is kept only if it verifies, follows section 4, is signed by the
 * authority pinned in the device's link and names its room. Of those it keeps
 * the grants whose `device` is its own key (newest per grant id), and every
 * revocation and reset, to carry to nodes. Memory only; the caller persists
 * [snapshot]. Mirrors `GrantReceiver` in the web client's `group-grants.ts`.
 * Not thread-safe: the caller serialises access.
 */
class GrantReceiver(authority: String, roomId: String, device: String) {
    private val authority = authority.lowercase()
    private val roomId = roomId.lowercase()
    private val device = device.lowercase()
    private val own = LinkedHashMap<String, ParsedGrant>()
    private val revoked = LinkedHashMap<String, ParsedGrant>()
    private val resets = LinkedHashMap<String, ParsedReset>()
    private val acknowledged = HashSet<String>()
    /** The newest `created_at` of any event kept: a clock from signed data, for letting go of old own grants. */
    private var horizon = 0L

    /**
     * Offer one event as read off the control channel. True when it was kept.
     * Nothing is refused for its time (rule 7 judges a node's clock): a device
     * whose clock lags must still keep a revocation to carry. [now] only moves
     * the point after which this device lets go of its own ended grants.
     */
    fun offer(value: JsonElement?, now: Long? = null): Boolean {
        val parsed = (parseGroupEvent(value, keepers = listOf(authority)) as? GroupEventVerdict.Ok)?.parsed ?: return false
        if (parsed.roomId != roomId) return false
        horizon = maxOf(horizon, parsed.createdAt)
        if (parsed is ParsedReset) {
            val held = resets[parsed.server]
            if (held != null && held.createdAt >= parsed.createdAt) return false
            resets[parsed.server] = parsed
            trim(resets, MAX_KEPT_RESETS)
            return true
        }
        parsed as ParsedGrant
        var kept = false
        if (parsed.device == device) {
            val held = own[parsed.grantId]
            if (held == null || parsed.createdAt > held.createdAt) { own[parsed.grantId] = parsed; kept = true }
            letGoOfOldOwn(now)
        }
        if (parsed.status == "revoked") {
            val held = revoked[parsed.grantId]
            if (held == null || parsed.createdAt > held.createdAt) {
                revoked[parsed.grantId] = parsed; trim(revoked, MAX_KEPT_REVOKED); kept = true
            }
        }
        return kept
    }

    private fun letGoOfOldOwn(now: Long?) {
        val at = maxOf(horizon, now ?: 0)
        val held = own.values.iterator()
        while (held.hasNext()) if (held.next().expiration + KEEP_OWN_AFTER_EXPIRY_SECONDS < at) held.remove()
        trim(own, MAX_KEPT_OWN)
    }

    private fun <T> trim(map: LinkedHashMap<String, T>, max: Int) {
        val it = map.keys.iterator()
        while (map.size > max && it.hasNext()) { it.next(); it.remove() }
    }

    private fun resetCovers(g: ParsedGrant): Boolean = resets[g.server]?.let { g.createdAt <= it.createdAt } == true

    /** This device's grants for [server] to present: active, not revoked, not covered by a reset, not ended at [now]. */
    fun grantsFor(server: String, now: Long): List<ParsedGrant> = own.values.filter {
        it.server == server && it.status == "active" && it.expiration > now && it.grantId !in revoked && !resetCovers(it)
    }

    /** Revocations and resets for [server], not yet acknowledged there, still worth carrying at [now]. */
    fun carry(server: String, now: Long): List<ParsedGroupEvent> = (revoked.values + resets.values)
        .filter { it.server == server && it.expiration > now && "$server/${it.event.id}" !in acknowledged }

    /** A node answered 200, 201 or 409 for this event. */
    fun acknowledge(server: String, eventId: String) { acknowledged += "$server/$eventId" }

    /** This device's newest active grant for [server], unless that event is already acknowledged there (contract 7.2 item 2). */
    fun ownToPost(server: String, now: Long): GroupEvent? {
        val newest = grantsFor(server, now).maxByOrNull { it.createdAt } ?: return null
        return newest.event.takeIf { "$server/${it.id}" !in acknowledged }
    }

    /** This device had grants at [server] and every one has ended by revocation or reset: "no longer let in" (contract 11). */
    fun lockedOut(server: String, now: Long): Boolean {
        val mine = own.values.filter { it.server == server }
        if (mine.isEmpty() || grantsFor(server, now).isNotEmpty()) return false
        return mine.any { it.grantId in revoked || resetCovers(it) }
    }

    /** True when nothing is held: a room with no group storage keeps nothing. */
    val isEmpty: Boolean get() = own.isEmpty() && revoked.isEmpty() && resets.isEmpty()

    /** What to persist: every kept event once, and the acknowledged keys of events still held. Plain JSON. */
    fun snapshot(): JsonObject {
        val events = LinkedHashMap<String, GroupEvent>()
        for (p in own.values + revoked.values + resets.values) events[p.event.id] = p.event
        return buildJsonObject {
            put("events", buildJsonArray { events.values.forEach { add(it.toJson()) } })
            put("acknowledged", buildJsonArray { acknowledged.filter { it.substringAfterLast('/') in events }.sorted().forEach { add(JsonPrimitive(it)) } })
        }
    }

    companion object {
        const val MAX_KEPT_REVOKED = 4_096
        const val MAX_KEPT_RESETS = 256
        const val MAX_KEPT_OWN = 256
        const val KEEP_OWN_AFTER_EXPIRY_SECONDS = 7L * 86_400

        /** A receiver from a snapshot. Every event is checked again: one altered on the device is dropped, never trusted for having been stored. */
        fun restore(authority: String, roomId: String, device: String, snapshot: JsonElement?): GrantReceiver {
            val receiver = GrantReceiver(authority, roomId, device)
            val obj = snapshot as? JsonObject ?: return receiver
            (obj["events"] as? JsonArray)?.forEach { receiver.offer(it) }
            val held = HashSet<String>()
            for (p in receiver.own.values + receiver.revoked.values + receiver.resets.values) held += p.event.id
            (obj["acknowledged"] as? JsonArray)?.forEach { item ->
                val key = (item as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@forEach
                if (key.length > 300) return@forEach
                val slash = key.lastIndexOf('/')
                if (slash < 1 || key.substring(slash + 1) !in held || !isHostName(key.substring(0, slash))) return@forEach
                receiver.acknowledged += key
            }
            return receiver
        }
    }
}
