package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.NostrEvent

/** The most follows People will show from one list; a list longer than this is cut, never refused. */
const val MAX_FOLLOWS = 5_000

private val HEX64 = Regex("^[0-9a-f]{64}$")

/** The order to ask for followed people's names: anyone in [first] (a
 *  waiting invitation's sender) ahead of the rest, each part in list order. */
fun followNameOrder(follows: List<String>, first: Set<String>): List<String> =
    follows.filter { it in first } + follows.filter { it !in first }

/**
 * The people [account] follows, from its public Nostr follow list (kind 3,
 * NIP-02): the newest valid list wins (ties go to the larger id, as relays
 * order replaceable events), its `p` tags in the order written, each key
 * once, never the account itself. Read-only: KithMoot never writes a follow
 * list. Null when no valid list is among [events].
 */
fun newestFollowList(events: List<NostrEvent>, account: String, now: Long): List<String>? {
    val newest = events.filter { it.kind == 3 && it.pubkey == account && it.createdAt <= now + 300 && Events.verify(it) }
        .maxWithOrNull(compareBy<NostrEvent> { it.createdAt }.thenBy { it.id }) ?: return null
    return newest.tags.asSequence()
        .filter { it.size >= 2 && it[0] == "p" }
        .map { it[1].lowercase() }
        .filter { HEX64.matches(it) && it != account }
        .distinct().take(MAX_FOLLOWS).toList()
}
