package dev.forgesworn.kithmoot.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * A room's own storage nodes, carried in its signed group invitation (kind
 * 1463, body key `storage`) and in a signed `storage` control op: fold-kit
 * 0.14.0's `invitation-storage.ts`, ported by its grammar.
 *
 * An entry is a node's canonical base URL. Canonical is decided by the
 * grammar alone, never by a URL parser, so this client gets the same answer
 * from the same bytes as the web one:
 *
 *     entry   = scheme "://" host [ ":" port ] *( "/" segment )
 *     scheme  = "https" / "http"
 *     host    = label *( "." label )            ; 1 to 253 characters
 *     label   = 1*63( a-z / 0-9 / "-" )         ; not starting or ending with "-"
 *     port    = "1"-"9" *4DIGIT                 ; 1 to 65535, no leading zero
 *     segment = 1*( A-Z / a-z / 0-9 / "." / "_" / "~" / "-" ) ; not "." or ".."
 *
 * and: at most 256 characters; `http` only for `localhost`, `127.a.b.c` or an
 * onion v3 host; no default port; a host whose last label is all digits or
 * `0x` hex must be four decimal octets with no leading zero.
 *
 * A list is 0 to [MAX_INVITATION_STORAGE_NODES] entries, no two on one host,
 * in the authority's order of preference. `[]` means every node was withdrawn.
 */
const val MAX_INVITATION_STORAGE_NODES: Int = 8
const val MAX_STORAGE_NODE_URL_LENGTH: Int = 256

private const val MAX_HOST_LENGTH = 253
private val LABEL = Regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")
private val PORT = Regex("^[1-9][0-9]{0,4}$")
private val SEGMENT = Regex("^[A-Za-z0-9._~-]+$")
private val OCTET = Regex("^(?:0|[1-9][0-9]{0,2})$")
private val HEX_LABEL = Regex("^0x[0-9a-f]*$")
private val DIGITS = Regex("^[0-9]+$")
private val LOOPBACK = Regex("^127\\.[0-9]+\\.[0-9]+\\.[0-9]+$")
private val ONION_V3 = Regex("^[a-z2-7]{56}\\.onion$")
private val ENTRY = Regex("^(https?)://([a-z0-9.-]+)(?::([0-9]*))?((?:/[^/]*)*)$")

private fun isCanonicalHost(host: String): Boolean {
    if (host.isEmpty() || host.length > MAX_HOST_LENGTH) return false
    // split keeps empty labels ("a..b", ".a", "a."), which LABEL refuses.
    val labels = host.split('.')
    if (!labels.all { LABEL.matches(it) }) return false
    val last = labels.last()
    if (DIGITS.matches(last) || HEX_LABEL.matches(last)) {
        if (labels.size != 4) return false
        return labels.all { OCTET.matches(it) && it.toInt() <= 255 }
    }
    return true
}

private fun isPlainHttpHost(host: String): Boolean =
    host == "localhost" || LOOPBACK.matches(host) || ONION_V3.matches(host)

private fun isCanonicalPort(port: String): Boolean = PORT.matches(port) && port.toInt() <= 65_535

private fun isCanonicalPath(path: String): Boolean {
    if (path.isEmpty()) return true
    // Every segment is non-empty (so no trailing slash, no "//"), and neither "." nor "..".
    return path.substring(1).split('/').all { SEGMENT.matches(it) && it != "." && it != ".." }
}

/** True when [value] is a canonical node base URL. Never throws. */
fun isStorageNodeBase(value: String): Boolean {
    if (value.length > MAX_STORAGE_NODE_URL_LENGTH) return false
    val m = ENTRY.matchEntire(value) ?: return false
    val scheme = m.groupValues[1]
    val host = m.groupValues[2]
    val port = m.groups[3]?.value
    val path = m.groupValues[4]
    if (!isCanonicalHost(host) || !isCanonicalPath(path)) return false
    if (scheme == "http" && !isPlainHttpHost(host)) return false
    if (port != null) {
        if (!isCanonicalPort(port)) return false
        if ((scheme == "https" && port == "443") || (scheme == "http" && port == "80")) return false
    }
    return true
}

/**
 * The `server` a token names for a node: the host of a canonical base,
 * exactly as written, or null when [base] is not canonical.
 */
fun storageNodeServer(base: String): String? =
    if (isStorageNodeBase(base)) ENTRY.matchEntire(base)!!.groupValues[2] else null

/** True for a list the invitation may carry (0 to 8 canonical entries, one per host), including []. */
fun isInvitationStorage(nodes: List<String>): Boolean {
    if (nodes.size > MAX_INVITATION_STORAGE_NODES) return false
    val hosts = HashSet<String>()
    for (entry in nodes) hosts.add(storageNodeServer(entry) ?: return false)
    return hosts.size == nodes.size
}

/**
 * The storage list a JSON value carries, exactly as given, or null when it is
 * not one: not an array, an entry that is not a string, or a list
 * [isInvitationStorage] refuses. Null refuses the whole envelope (or op).
 */
fun invitationStorageOf(element: JsonElement?): List<String>? {
    val array = element as? JsonArray ?: return null
    if (array.size > MAX_INVITATION_STORAGE_NODES) return null
    val nodes = array.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return null }
    return nodes.takeIf(::isInvitationStorage)
}
