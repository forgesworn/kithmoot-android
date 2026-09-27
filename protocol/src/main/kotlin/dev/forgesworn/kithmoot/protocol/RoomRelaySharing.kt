package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.normaliseHex
import dev.forgesworn.kithmoot.crypto.toHex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URI

/**
 * A room's own relay list, signed by the room's authority, spread over the
 * `control` chat channel as `{"op":"relays","relays":[...],"version":N,"sig":"..."}`.
 * Mirrors `room-relays.ts` and the `relays` case in `control.ts` in the
 * TypeScript reference implementation.
 *
 * The invite link fixes the relays a room starts on, and nobody could move
 * everybody in a room onto a better one. This record lets the pinned
 * authority add relays for every member at once. A member unions it with the
 * relays it already uses; it never takes one away, only what the eight-relay
 * cap forces a choice about. `version` orders successive records, so a
 * member replaying an old list cannot undo a newer one, and any member may
 * repost the newest one it holds, since the signature - not who sent it -
 * is what a device believes.
 */
const val MAX_ROOM_RELAYS: Int = 8
/** A record older than this in the control log is posted again by any
 *  member who holds it, so a newcomer reading the last month of the channel
 *  still finds it. Matches the web client's `ROOM_RELAYS_REPOST_SECONDS`. */
const val ROOM_RELAYS_REPOST_SECONDS: Long = 20L * 24 * 60 * 60
private const val MAX_ROOM_RELAY_URL_LENGTH = 256
private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "[::1]", "::1")

/** A verified record: the relays a room's authority most recently asked
 *  every member to add, and the version and signature it carried. */
data class RoomRelaysRecord(val relays: List<String>, val version: Long, val sig: String)

/** True only for a URL a signed room-relays list may name: encrypted
 *  WebSockets, or plain `ws://` on loopback, for local development only. */
fun isSafeRoomRelayUrl(raw: String): Boolean {
    if (raw.isEmpty() || raw.length > MAX_ROOM_RELAY_URL_LENGTH) return false
    val uri = runCatching { URI(raw) }.getOrNull() ?: return false
    val scheme = uri.scheme?.lowercase() ?: return false
    if (scheme == "wss") return true
    return scheme == "ws" && uri.host?.lowercase() in LOOPBACK_HOSTS
}

/**
 * The canonical form a room-relays signature covers for one URL: exactly what
 * nostr-tools' `normalizeURL` produces (lower-case scheme and host, one
 * collapsed path with any trailing slash stripped back to a bare `/`, the
 * scheme's default port dropped, query keys sorted, no fragment). A device
 * that normalised differently would verify different bytes than the
 * authority signed, so this must match the web client exactly rather than
 * merely "be sensible".
 */
fun canonicalRoomRelayUrl(raw: String): String {
    require(isSafeRoomRelayUrl(raw)) { "use a wss:// relay URL (ws:// is allowed only on localhost)" }
    val uri = URI(raw.trim())
    require(uri.rawUserInfo == null && uri.rawFragment == null) { "relay URLs cannot contain credentials or fragments" }
    val scheme = uri.scheme!!.lowercase()
    val host = requireNotNull(uri.host) { "no host" }.lowercase()
    val defaultPort = if (scheme == "wss") 443 else 80
    val port = if (uri.port == -1 || uri.port == defaultPort) "" else ":${uri.port}"
    var path = (uri.rawPath ?: "").replace(Regex("/+"), "/")
    if (path.endsWith("/") && path.length > 1) path = path.dropLast(1)
    if (path.isEmpty()) path = "/"
    if (path == "//") path = "/"
    val query = uri.rawQuery?.split("&")?.filter { it.isNotEmpty() }
        ?.takeIf { it.isNotEmpty() }
        ?.sortedBy { it.substringBefore('=') }
        ?.joinToString("&", prefix = "?")
        .orEmpty()
    return "$scheme://$host$port$path$query"
}

/** Canonical form of a whole list: each URL normalised, deduplicated, sorted.
 *  Throws on anything that could not be a relay, none at all, or too many. */
fun canonicalRoomRelays(relays: List<String>): List<String> {
    val urls = relays.map(::canonicalRoomRelayUrl).toSet().sorted()
    require(urls.isNotEmpty()) { "a room relay list needs at least one relay" }
    require(urls.size <= MAX_ROOM_RELAYS) { "a room can list at most $MAX_ROOM_RELAYS relays" }
    return urls
}

private fun requireRoomId(roomId: String): String = roomId.also { require(it.matches(Regex("[0-9a-f]{64}", RegexOption.IGNORE_CASE))) { "room id must be 64 hex characters" } }.normaliseHex()
private fun requireVersion(version: Long): Long = version.also { require(it >= 0) { "version must be a non-negative integer" } }

/** `sha256("kithmoot/v1/relays:<roomId>:<version>:<JSON array of the canonical list>")`. */
private fun relaysMessage(roomId: String, version: Long, relays: List<String>): ByteArray =
    Digests.sha256("kithmoot/v1/relays:$roomId:$version:${relaysJson(relays)}".toByteArray(Charsets.UTF_8))

private fun relaysJson(relays: List<String>): String = relays.joinToString(",", "[", "]") { "\"$it\"" }

fun signRoomRelays(roomId: String, version: Long, relays: List<String>, authoritySecretKey: ByteArray, auxRand: ByteArray = Entropy.bytes(32)): String {
    require(authoritySecretKey.size == 32) { "authority secret key must be 32 bytes" }
    val canonical = canonicalRoomRelays(relays)
    return Schnorr.sign(relaysMessage(requireRoomId(roomId), requireVersion(version), canonical), authoritySecretKey, auxRand).toHex()
}

/** Never throws: this runs on anything a relay hands over. Also refuses a
 *  list that was not sent in its canonical form - a client verifies exactly
 *  the bytes it is about to use, not a tidied-up version of them. */
fun verifyRoomRelays(roomId: String, version: Long, relays: List<String>, sig: String, authority: String): Boolean = runCatching {
    val canonical = canonicalRoomRelays(relays)
    if (canonical != relays) return false
    val signature = sig.hexToBytes()
    if (signature.size != 64 || !authority.matches(Regex("[0-9a-f]{64}", RegexOption.IGNORE_CASE))) return false
    Schnorr.verify(signature, relaysMessage(requireRoomId(roomId), requireVersion(version), canonical), authority.hexToBytes())
}.getOrDefault(false)

/** The relays this device should use for the room once [record] is adopted:
 *  the record's own relays first, then as many of [current] as still fit
 *  under the [MAX_ROOM_RELAYS] cap. Mirrors `adoptRoomRelays` in the web
 *  client's `app/src/main.ts`: nothing already used is dropped unless the cap
 *  itself forces the choice. Returns the relays that were actually added,
 *  which is empty when the record adds nothing this device does not already
 *  have. */
fun applyRoomRelays(current: List<String>, record: RoomRelaysRecord): Pair<List<String>, List<String>> {
    val missing = record.relays.filter { it !in current }
    if (missing.isEmpty()) return current to emptyList()
    val listed = current.filter { it in record.relays }
    val others = current.filter { it !in record.relays }
    val next = (listed + missing + others).take(MAX_ROOM_RELAYS)
    return next to missing
}

/** The chat body carrying a `relays` control op. */
fun encodeRoomRelaysOp(record: RoomRelaysRecord): String =
    "{\"op\":\"relays\",\"relays\":${relaysJson(record.relays)},\"version\":${record.version},\"sig\":\"${record.sig}\"}"

/**
 * Decodes a `relays` control op from a chat message body, or null when the
 * text is not one, or is one that could never be valid (wrong shape, too
 * many relays, a bad version or signature format) - never on the signature
 * itself, which [verifyRoomRelays] checks against the room's own authority.
 */
fun decodeRoomRelaysOp(body: String): RoomRelaysRecord? = runCatching {
    val obj = Json.parseToJsonElement(body) as? JsonObject ?: return null
    if (obj["op"]?.jsonPrimitive?.contentOrNull != "relays") return null
    val relaysArray = obj["relays"] as? JsonArray ?: return null
    if (relaysArray.isEmpty() || relaysArray.size > MAX_ROOM_RELAYS) return null
    val relays = relaysArray.map { (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return null }
    if (relays.any { it.length > MAX_ROOM_RELAY_URL_LENGTH }) return null
    val version = obj["version"]?.jsonPrimitive?.longOrNull ?: return null
    if (version < 0) return null
    val sig = obj["sig"]?.jsonPrimitive?.contentOrNull ?: return null
    if (!sig.matches(Regex("[0-9a-f]{128}", RegexOption.IGNORE_CASE))) return null
    RoomRelaysRecord(relays, version, sig.lowercase())
}.getOrNull()
