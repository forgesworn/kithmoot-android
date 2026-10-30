package dev.forgesworn.kithmoot.callrelay

import dev.forgesworn.kithmoot.protocol.SpryControlEndpoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One line from the built-in relay's node, checked (spry-turn
 * docs/supervised-mode.md, "Lifetime and output" and "What the node prints";
 * docs/embedding.md for `stopped`). The same rules as the desktop's
 * `parseNodeLine` in desktop/relay-supervisor.mjs: every field is checked and
 * anything else, or anything malformed, is ignored.
 *
 * `ready` and `urls` carry the person's public address, so their
 * [toString] never shows it.
 */
internal sealed interface RelayNodeEvent {
    class Ready(val endpoint: String, val pairing: String, val urls: List<String>, val relayPortMap: List<Pair<Int, Int>>) : RelayNodeEvent {
        override fun toString() = "Ready(redacted)"
    }
    class Urls(val urls: List<String>, val relayPortMap: List<Pair<Int, Int>>) : RelayNodeEvent {
        override fun toString() = "Urls(redacted)"
    }
    data class Status(val allocations: Long, val bytesRelayed: Long, val lastAllocation: Long?) : RelayNodeEvent
    data class Control(val relays: Long) : RelayNodeEvent
    /** `mapped` (the first grant) or `changed` ([reason] `address`, `port` or `ports`). */
    data class PortmapMapped(val changed: Boolean, val method: String, val allocationPorts: Long, val requested: Long,
        val permanent: Boolean, val reason: String?) : RelayNodeEvent
    data class PortmapRestored(val restored: Long) : RelayNodeEvent
    /** `failed` or `lost`: the router cannot host, and exit code 3 follows. */
    data class PortmapEnded(val lost: Boolean, val reason: String) : RelayNodeEvent
    /** `cleaned` or `released`. */
    data class PortmapCounts(val released: Boolean, val removed: Long, val remaining: Long) : RelayNodeEvent
    /** The library's last line: the exit code the child process would have used. */
    data class Stopped(val code: Int, val refusal: String?) : RelayNodeEvent
}

internal object RelayNodeLine {
    /** A line longer than this is dropped whole, as on desktop. */
    const val LINE_LIMIT = 8192
    /** Why a router could not host, as the node words it. */
    val PORTMAP_REASONS = setOf("no-gateway", "vpn", "wrong-interface", "no-answer", "refused", "failed",
        "carrier-grade-nat", "double-nat", "unusable-address", "too-few-ports", "record", "expired")
    private val METHODS = setOf("pcp", "natpmp", "upnp")
    private val CHANGE_REASONS = setOf("address", "port", "ports")
    private val PAIRING = Regex("^[A-Za-z0-9_-]{43}$")
    private val TURN_URL = Regex("^turn:([0-9]{1,3}(?:\\.[0-9]{1,3}){3}):([0-9]{1,5})\\?transport=(udp|tcp)$")
    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L

    fun parse(bytes: ByteArray): RelayNodeEvent? = if (bytes.size > LINE_LIMIT) null else parse(String(bytes, Charsets.UTF_8))

    fun parse(text: String): RelayNodeEvent? {
        if (text.length > LINE_LIMIT) return null
        val value = try { Json.parseToJsonElement(text) } catch (_: Exception) { return null } as? JsonObject ?: return null
        if (count(value["v"]) != 1L) return null
        val event = (value["event"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        return when (event) {
            "ready" -> {
                val endpoint = string(value["endpoint"])?.takeIf { it.startsWith("spry://") && it.length <= 2048 && endpoint(it) } ?: return null
                val pairing = string(value["pairing"])?.takeIf(PAIRING::matches) ?: return null
                RelayNodeEvent.Ready(endpoint, pairing, turnUrls(value["urls"]) ?: return null, portMap(value["relayPortMap"]) ?: return null)
            }
            "urls" -> RelayNodeEvent.Urls(turnUrls(value["urls"]) ?: return null, portMap(value["relayPortMap"]) ?: return null)
            "status" -> {
                val last = value["lastAllocation"]
                val lastAllocation = if (last is JsonNull) null else count(last) ?: return null
                RelayNodeEvent.Status(count(value["allocations"]) ?: return null, count(value["bytesRelayed"]) ?: return null, lastAllocation)
            }
            "control" -> RelayNodeEvent.Control(count(value["relays"]) ?: return null)
            "portmap" -> portmap(value)
            "stopped" -> {
                val code = count(value["code"])?.takeIf { it == 0L || it == 1L || it == 3L } ?: return null
                val raw = value["refusal"]
                val refusal = if (raw == null) null else string(raw)?.takeIf { it.isNotEmpty() && it.length <= 512 && it.none(Char::isISOControl) } ?: return null
                RelayNodeEvent.Stopped(code.toInt(), refusal)
            }
            else -> null
        }
    }

    private fun portmap(value: JsonObject): RelayNodeEvent? = when (string(value["state"])) {
        "mapped", "changed" -> {
            val changed = string(value["state"]) == "changed"
            val method = string(value["method"])?.takeIf { it in METHODS }
            val permanent = when ((value["permanent"] as? JsonPrimitive)?.takeIf { !it.isString }?.content) { "true" -> true; "false" -> false; else -> null }
            val reason = if (changed) string(value["reason"])?.takeIf { it in CHANGE_REASONS } else null
            val ports = count(value["allocationPorts"])
            val requested = count(value["requested"])
            if (method == null || permanent == null || ports == null || requested == null || (changed && reason == null)) null
            else RelayNodeEvent.PortmapMapped(changed, method, ports, requested, permanent, reason)
        }
        "restored" -> count(value["restored"])?.let(RelayNodeEvent::PortmapRestored)
        "failed", "lost" -> string(value["reason"])?.takeIf { it in PORTMAP_REASONS }?.let { RelayNodeEvent.PortmapEnded(string(value["state"]) == "lost", it) }
        "cleaned", "released" -> {
            val removed = count(value["removed"])
            val remaining = count(value["remaining"])
            if (removed == null || remaining == null) null else RelayNodeEvent.PortmapCounts(string(value["state"]) == "released", removed, remaining)
        }
        else -> null
    }

    private fun endpoint(value: String): Boolean = try { SpryControlEndpoint.parse(value); true } catch (_: Exception) { false }

    private fun string(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** A whole number from 0 to 2^53 - 1, written as a JSON number. */
    private fun count(value: JsonElement?): Long? {
        val primitive = value as? JsonPrimitive ?: return null
        if (primitive.isString || primitive is JsonNull || !primitive.content.all(Char::isDigit) || primitive.content.isEmpty() || primitive.content.length > 16) return null
        return primitive.content.toLongOrNull()?.takeIf { it in 0..MAX_SAFE_INTEGER }
    }

    private fun port(value: JsonElement?): Int? = count(value)?.takeIf { it in 1024..65535 }?.toInt()

    private fun turnUrls(value: JsonElement?): List<String>? {
        val list = value as? JsonArray ?: return null
        if (list.size !in 1..2) return null
        return list.map { item ->
            val url = string(item) ?: return null
            val match = TURN_URL.matchEntire(url) ?: return null
            if (!usableIpv4(match.groupValues[1]) || match.groupValues[2].toIntOrNull()?.takeIf { it in 1024..65535 } == null) return null
            url
        }
    }

    private fun portMap(value: JsonElement?): List<Pair<Int, Int>>? {
        val list = value as? JsonArray ?: return null
        if (list.size > 64) return null
        return list.map { item ->
            val pair = item as? JsonArray ?: return null
            if (pair.size != 2) return null
            (port(pair[0]) ?: return null) to (port(pair[1]) ?: return null)
        }
    }

    private fun usableIpv4(text: String): Boolean = text.split('.').let { parts -> parts.size == 4 && parts.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true && (part == "0" || !part.startsWith('0')) } }
}
