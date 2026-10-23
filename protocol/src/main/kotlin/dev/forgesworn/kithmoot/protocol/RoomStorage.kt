package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * A room's own storage nodes, signed by the room's authority (S5): the
 * nodes its keeper grants at, in order of preference. The keeper signs the
 * list into the group invitation (`storage`) and posts it as a `storage` op
 * on the control channel, `{"op":"storage","nodes":[...],"version":N,"sig":"..."}`,
 * so a member already in the room hears of a change. Mirrors `room-storage.ts`
 * and the `storage` case in `control.ts` of the web client.
 *
 * Unlike `relays`, the list is signed as given, not sorted, and `[]` is valid:
 * the authority withdrew every node. A list that is not canonical fails
 * verification rather than being mended. `version` orders lists: newest wins.
 */
data class RoomStorageRecord(
    /** Canonical node base URLs, in the authority's order. `[]` means it withdrew them all. */
    val nodes: List<String>,
    val version: Long,
    /** The authority's signature, when the record came from an op: what lets this device post it again. */
    val sig: String? = null,
)

/** Matches the web client's `ROOM_STORAGE_REPOST_SECONDS`, as for `relays`. */
const val ROOM_STORAGE_REPOST_SECONDS: Long = 20L * 24 * 60 * 60

/** The largest version a JSON number carries exactly: JavaScript's `Number.MAX_SAFE_INTEGER`. */
private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
private val HEX64 = Regex("[0-9a-f]{64}", RegexOption.IGNORE_CASE)
private val SIG = Regex("[0-9a-f]{128}", RegexOption.IGNORE_CASE)

private fun nodesJson(nodes: List<String>): String = nodes.joinToString(",", "[", "]") { "\"$it\"" }

/** `sha256("kithmoot/v1/storage:<roomId>:<version>:<JSON array of the list as given>")`. */
private fun storageMessage(roomId: String, version: Long, nodes: List<String>): ByteArray {
    require(HEX64.matches(roomId)) { "room id must be 64 hex characters" }
    require(version in 0..MAX_SAFE_INTEGER) { "version must be a non-negative integer" }
    return Digests.sha256("kithmoot/v1/storage:${roomId.lowercase()}:$version:${nodesJson(nodes)}".toByteArray(Charsets.UTF_8))
}

/** What a keeper does; here for tests and vectors. Android keepers sign no storage. */
fun signRoomStorage(roomId: String, version: Long, nodes: List<String>, authoritySecretKey: ByteArray, auxRand: ByteArray = Entropy.bytes(32)): String {
    require(authoritySecretKey.size == 32) { "authority secret key must be 32 bytes" }
    require(isInvitationStorage(nodes)) { "storage nodes must be at most $MAX_INVITATION_STORAGE_NODES canonical node URLs, one per host" }
    return Schnorr.sign(storageMessage(roomId, version, nodes), authoritySecretKey, auxRand).toHex()
}

/** Never throws: this runs on anything a relay hands over. */
fun verifyRoomStorage(roomId: String, version: Long, nodes: List<String>, sig: String, authority: String): Boolean = runCatching {
    if (!isInvitationStorage(nodes) || !SIG.matches(sig) || !HEX64.matches(authority)) return false
    Schnorr.verify(sig.hexToBytes(), storageMessage(roomId, version, nodes), authority.lowercase().hexToBytes())
}.getOrDefault(false)

/** The chat body carrying a `storage` control op. Only a record that came from an op has a signature to carry. */
fun encodeRoomStorageOp(record: RoomStorageRecord): String {
    val sig = requireNotNull(record.sig) { "only a signed storage list can be posted" }
    return "{\"op\":\"storage\",\"nodes\":${nodesJson(record.nodes)},\"version\":${record.version},\"sig\":\"$sig\"}"
}

/**
 * A `storage` control op from a chat message body, kept as sent, or null when
 * the text is not one or never could be valid: exactly the keys `op`, `nodes`,
 * `version` and `sig`, at most eight string entries of at most 256 characters,
 * a non-negative integer version and a 128-hex signature. Whether the list is
 * canonical and signed by the room's authority is [verifyRoomStorage]'s.
 */
fun decodeRoomStorageOp(body: String): RoomStorageRecord? = runCatching {
    val obj = Json.parseToJsonElement(body) as? JsonObject ?: return null
    if (obj.keys != setOf("op", "nodes", "version", "sig")) return null
    if ((obj["op"] as? JsonPrimitive)?.takeIf { it.isString }?.content != "storage") return null
    val array = obj["nodes"] as? kotlinx.serialization.json.JsonArray ?: return null
    if (array.size > MAX_INVITATION_STORAGE_NODES) return null
    val nodes = array.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return null }
    if (nodes.any { it.length > MAX_STORAGE_NODE_URL_LENGTH }) return null
    val versionValue = obj["version"] as? JsonPrimitive ?: return null
    if (versionValue.isString) return null
    val version = versionValue.longOrNull ?: return null
    if (version !in 0..MAX_SAFE_INTEGER) return null
    val sig = (obj["sig"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    if (!SIG.matches(sig)) return null
    RoomStorageRecord(nodes, version, sig.lowercase())
}.getOrNull()

/**
 * What to hold after being offered [offered]: the offer when it is newer than
 * [held] (or nothing is held). A tie keeps what is held, except that the same
 * list arriving from an op adds its signature to a record from the invitation,
 * so this device can post it again. Null when nothing changes. Mirrors
 * `adoptRoomStorage` in the web client's `room-storage-record.ts`.
 */
fun adoptRoomStorage(held: RoomStorageRecord?, offered: RoomStorageRecord): RoomStorageRecord? {
    if (held == null || offered.version > held.version) return offered
    if (offered.version == held.version && held.sig == null && offered.sig != null && held.nodes == offered.nodes) {
        return held.copy(sig = offered.sig)
    }
    return null
}

/** The record an invitation's admission carries, if it names storage at all. */
fun roomStorageFromAdmission(admission: RoomAdmission): RoomStorageRecord? {
    val nodes = admission.storage?.takeIf(::isInvitationStorage) ?: return null
    return RoomStorageRecord(nodes, admission.storageAt?.takeIf { it >= 0 } ?: 0)
}

/** The record a `storage` op carries, only if the room's [authority] signed it. */
fun roomStorageFromOp(op: RoomStorageRecord, roomId: String, authority: String): RoomStorageRecord? {
    val sig = op.sig ?: return null
    return op.takeIf { verifyRoomStorage(roomId, it.version, it.nodes, sig, authority) }
}

/**
 * The listed base a file lives on, when its URL is exactly `<base>/<sha256>`
 * for one of [nodes]. A file anywhere else is not a group file and is fetched
 * the way it always was.
 */
fun listedBaseOf(nodes: List<String>, url: String, sha256: String): String? {
    val hash = sha256.lowercase()
    return nodes.firstOrNull { url == "$it/$hash" }
}

/**
 * Whether this app reaches a listed node: https, and not an onion address.
 * Loopback and onion `http` nodes are skipped, as the web app skips them; a
 * room whose nodes are all of that kind cannot share files from this app.
 */
fun usableGroupBase(base: String): Boolean = isStorageNodeBase(base) && base.startsWith("https://") &&
    !(storageNodeServer(base)?.endsWith(".onion") ?: true)
