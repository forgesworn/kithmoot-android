package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.toHex
import java.math.BigInteger
import java.net.IDN
import java.net.URI
import java.util.Base64

/** A contact's endorsement and this device's monotonic replay protection. */
data class BoxPin(
    val p: String, val claim: String, val nodeId: String, val highestSerial: Long,
    val card: String? = null, val statusCreatedAt: Long? = null, val statusId: String? = null,
)
data class VerifiedBoxClaim(val id: String, val node: String, val master: String, val createdAt: Long, val retired: Boolean)
data class VerifiedBoxStatus(
    val id: String, val createdAt: Long, val validUntil: Long, val master: String,
    val card: String, val link: LinkCard, val drops: Boolean, val dropsUrl: String?,
)
sealed interface BoxClaimResult {
    data class Ok(val claim: VerifiedBoxClaim) : BoxClaimResult
    data class Refused(val reason: String) : BoxClaimResult
}
sealed interface BoxStatusResult {
    data class Ok(val status: VerifiedBoxStatus) : BoxStatusResult
    data class Refused(val reason: String) : BoxStatusResult
}

/** Bothy V1/V2 claim/status verification, without network or Android state.
 * Link transport hints are never inferred to be Nostr message endpoints. */
object BoxStatuses {
    const val MAX_AGE_SECONDS = 3 * 3600L
    private val HEX = Regex("^[0-9a-f]{64}$")
    private val GIB = BigInteger.ONE.shiftLeft(30)
    private val U64 = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)
    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8).size
    private fun checked(e: NostrEvent, kind: Int): NostrEvent {
        require(e.kind == kind && HEX.matches(e.id) && HEX.matches(e.pubkey)) { "event shape" }
        require(e.createdAt in 0..LinkCards.MAX_SAFE) { "event time" }
        require(e.tags.size <= 128 && e.tags.all { t -> t.size in 1..16 && t.all { bytes(it) <= 8192 } }) { "event tags" }
        require(bytes(e.toCompactJson()) <= 32768) { "event too large" }
        require(Events.verify(e)) { "event signature" }
        return e
    }
    private fun tag(e: NostrEvent, name: String, required: Boolean = true): List<String>? {
        val found = e.tags.filter { it.firstOrNull() == name }
        require(found.size <= 1 && (!required || found.size == 1)) { "$name must occur ${if (required) "exactly" else "at most"} once" }
        return found.firstOrNull()
    }
    private fun value(e: NostrEvent, name: String, required: Boolean = true): String? {
        val t = tag(e, name, required) ?: return null
        require(t.size == 2) { "$name shape" }
        return t[1]
    }
    private fun uint(s: String?): BigInteger {
        require(s != null && s.length <= 20 && Regex("^(0|[1-9][0-9]*)$").matches(s)) { "unsigned integer shape" }
        return BigInteger(s).also { require(it <= U64) { "unsigned integer overflow" } }
    }
    private fun oneOf(s: String?, allowed: Set<String>, name: String) { require(s in allowed) { "$name value" } }
    private fun list(e: NostrEvent, name: String, allowed: Set<String>, required: Boolean = true) {
        val t = tag(e, name, required) ?: return
        val values = t.drop(1)
        require(values.isNotEmpty() && values.size <= allowed.size && values.toSet().size == values.size && values.all { it in allowed }) { "$name values" }
    }
    private fun decodeCard(encoded: String): ByteArray {
        require(encoded.length <= 5464 && Regex("^[A-Za-z0-9+/_-]*={0,2}$").matches(encoded)) { "card encoding" }
        val raw = encoded.trimEnd('=')
        val url = raw.contains('-') || raw.contains('_')
        val decoded = (if (url) Base64.getUrlDecoder() else Base64.getDecoder()).decode(raw)
        val canonical = (if (url) Base64.getUrlEncoder() else Base64.getEncoder()).withoutPadding().encodeToString(decoded)
        require(raw == canonical) { "card encoding" }
        return decoded
    }
    private fun nodeId(encoded: String): String {
        require(Regex("^[a-z2-7]{52}$").matches(encoded)) { "node encoding" }
        val alphabet = "abcdefghijklmnopqrstuvwxyz234567"
        val result = ByteArray(32)
        var buffer = 0; var bits = 0; var offset = 0
        for (c in encoded) {
            buffer = (buffer shl 5) or alphabet.indexOf(c); bits += 5
            if (bits >= 8) { bits -= 8; result[offset++] = (buffer shr bits).toByte(); buffer = buffer and ((1 shl bits) - 1) }
        }
        require(offset == 32 && buffer == 0) { "node encoding" }
        return result.toHex()
    }
    private fun endpoint(s: String): String {
        require(bytes(s) <= 256 && s.startsWith("wss://") && !Regex("[\\s\\p{C}@#]").containsMatchIn(s)) { "drops endpoint" }
        val u = URI("https://" + s.removePrefix("wss://")).toURL()
        require(u.host.isNotEmpty() && u.userInfo == null && u.ref == null && u.port in -1..65535) { "drops endpoint" }
        val host = if (u.host.startsWith('[')) u.host.lowercase() else IDN.toASCII(u.host).lowercase()
        val port = if (u.port == -1 || u.port == 443) "" else ":${u.port}"
        val path = u.path.ifEmpty { "/" }
        val uri = URI("wss://$host$port$path" + (u.query?.let { "?$it" } ?: "")).normalize()
        require(uri.host != null) { "drops endpoint" }
        return uri.toASCIIString()
    }

    fun readClaim(raw: NostrEvent, now: Long): BoxClaimResult = try {
        require(now in 0..LinkCards.MAX_SAFE) { "clock" }
        val e = checked(raw, 30640)
        val node = value(e, "d")!!
        require(HEX.matches(node)) { "claim node" }
        require(e.content.isEmpty() && e.createdAt <= now + 300) { "claim content or time" }
        oneOf(value(e, "role"), setOf("phone", "box"), "role")
        val state = value(e, "status")!!
        oneOf(state, setOf("active", "retired"), "claim status")
        require(e.tags.all { it[0] in setOf("d", "p", "role", "status", "region", "name", "alt") }) { "unknown claim tag" }
        val people = e.tags.filter { it[0] == "p" }
        require(people.all { it.size == 4 && HEX.matches(it[1]) && it[3] in setOf("node", "master", "stash", "persona") }) { "claim keys" }
        require(people.map { it[1] }.toSet().size == people.size) { "duplicate claim key" }
        for (role in listOf("node", "master")) require(people.count { it[3] == role } == 1) { "claim $role key" }
        require(people.first { it[3] == "node" }[1] == node && people.first { it[3] == "master" }[1] == e.pubkey) { "claim key binding" }
        for ((key, max) in listOf("region" to 64, "name" to 32)) value(e, key, false)?.let { require(bytes(it) <= max) { "claim $key too long" } }
        value(e, "alt", false)
        require(if (state == "active") people.count { it[3] == "stash" } == 1 else people.none { it[3] in setOf("stash", "persona") }) { "claim stash or retirement" }
        BoxClaimResult.Ok(VerifiedBoxClaim(e.id, node, e.pubkey, e.createdAt, state == "retired"))
    } catch (e: Exception) { BoxClaimResult.Refused(e.message ?: "invalid box claim") }

    fun read(status: NostrEvent, claim: NostrEvent, pin: BoxPin, now: Long): BoxStatusResult = try {
        require(now in 0..LinkCards.MAX_SAFE) { "clock" }
        require(HEX.matches(pin.p) && HEX.matches(pin.claim) && HEX.matches(pin.nodeId) && pin.highestSerial in 0..LinkCards.MAX_SAFE) { "box pin" }
        val accepted = readClaim(claim, now)
        require(accepted is BoxClaimResult.Ok) { (accepted as BoxClaimResult.Refused).reason }
        require(accepted.claim.id == pin.claim && accepted.claim.node == pin.p) { "claim is not the contact-endorsed claim" }
        require(!accepted.claim.retired) { "claim is not active" }
        val s = checked(status, 10640)
        require(s.pubkey == pin.p && value(s, "claim") == claim.id) { "status claim binding" }
        require(s.content.isEmpty() && s.createdAt <= now + 300 && s.createdAt > now - MAX_AGE_SECONDS) { "status is stale or has invalid time/content" }
        pin.statusCreatedAt?.let { require(s.createdAt > it || (s.createdAt == it && s.id == pin.statusId)) { "status replay" } }
        require(nodeId(value(s, "node")!!) == pin.nodeId) { "node pin mismatch" }
        val card = decodeCard(value(s, "card")!!)
        val canonical = Base64.getEncoder().encodeToString(card)
        val same = pin.card?.let { decodeCard(it).contentEquals(card) } == true
        val link = LinkCards.verify(card, now, if (same) pin.highestSerial - 1 else pin.highestSerial)
        require(link is LinkVerdict.Ok) { "Link card: ${(link as LinkVerdict.Refused).reason}" }
        require(link.card.nodeId == pin.nodeId && uint(value(s, "card-exp")) == BigInteger.valueOf(link.card.expiresAt)) { "card binding" }
        val free = uint(value(s, "free")); val pool = uint(value(s, "pool"))
        require(free <= pool && free.mod(GIB) == BigInteger.ZERO && pool.mod(GIB) == BigInteger.ZERO) { "capacity rounding" }
        uint(value(s, "max-blob"))
        list(s, "classes", setOf("working", "circle", "vital", "open"))
        oneOf(value(s, "charge-control"), setOf("internal", "external-plug", "none", "mains"), "charge control")
        oneOf(value(s, "policy"), setOf("introductions", "open"), "policy")
        val sheltered = tag(s, "sheltered")!!
        require(sheltered.size == 3 && uint(sheltered[1]).mod(GIB) == BigInteger.ZERO && uint(sheltered[2]).mod(BigInteger.valueOf(100)) == BigInteger.ZERO) { "sheltered aggregates" }
        value(s, "bridge", false)?.let { require(URI(it).toURL().protocol == "https") { "bridge scheme" } }
        value(s, "lan", false)?.let { require(bytes(it) in 1..63) { "lan shape" } }
        value(s, "alt", false)
        tag(s, "software", false)?.let { require(it.size == 3 && bytes(it[1]) in 1..64 && Regex("^(unknown|[0-9a-f]{40})$").matches(it[2])) { "software shape" } }
        tag(s, "canary", false)?.let { require(it.size == 3 && uint(it[1]) <= BigInteger.valueOf(s.createdAt) && uint(it[2]) <= BigInteger.valueOf(366 * 86400L)) { "canary shape" } }
        list(s, "carriers", setOf("link", "tor", "i2p"), false)
        value(s, "relaying", false)?.let { oneOf(it, setOf("on", "off"), "relaying") }
        tag(s, "retention", false)?.let { t -> require(t.size == 4 && t.drop(1).all { Regex("^(blobs|drops|logs)=[a-z0-9-]{1,32}$").matches(it) } && t.drop(1).map { it.substringBefore('=') }.toSet().size == 3) { "retention shape" } }
        val drops = tag(s, "drops", false)
        val dropsUrl = drops?.let {
            oneOf(it.getOrNull(1), setOf("on", "off"), "drops")
            require(it.size == 2 || (it.size == 3 && it[1] == "on")) { "drops shape" }
            if (it.size == 3) endpoint(it[2]) else null
        }
        val expiration = value(s, "expiration", false)?.let { uint(it).min(BigInteger.valueOf(LinkCards.MAX_SAFE)).toLong() } ?: LinkCards.MAX_SAFE
        val until = minOf(s.createdAt + MAX_AGE_SECONDS, link.card.expiresAt, expiration)
        require(until > now) { "status expired" }
        BoxStatusResult.Ok(VerifiedBoxStatus(s.id, s.createdAt, until, claim.pubkey, canonical, link.card, drops?.get(1) == "on", dropsUrl))
    } catch (e: Exception) { BoxStatusResult.Refused(e.message ?: "invalid box status") }
}
