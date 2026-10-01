package dev.forgesworn.kithmoot.protocol

/**
 * Where a person wants their private conversations to live: NIP-17's DM relay
 * list, a replaceable kind 10050 event with one `relay` tag per relay, signed
 * by the person. Public by design; anyone can read which relays somebody uses
 * for private messages, which is what lets a sender look it up.
 *
 * Mirrors `src/dm-relays.ts` in the reference implementation. The rule for
 * choosing a conversation's relays is in its `docs/messages.md`, "Where it
 * lives"; both clients must choose the same relays from the same lists.
 */
const val KIND_DM_RELAYS = 10050

/** The most relays one list is read for, and the most a conversation gets. */
const val MAX_DM_RELAYS = 6

/** A conversation is never started on fewer than this many relays while the
 *  fallback has more to offer. */
const val MIN_DM_RELAYS = 2

private fun canonicalDmRelays(urls: List<String>): List<String> {
    val out = mutableListOf<String>()
    for (url in urls) {
        val normal = runCatching { canonicalRoomRelayUrl(url.trim()) }.getOrNull() ?: continue
        if (normal !in out) out += normal
    }
    return out
}

private fun relaysNamed(event: NostrEvent): List<String> =
    canonicalDmRelays(event.tags.filter { it.size >= 2 && it[0] == "relay" }.map { it[1] }).take(MAX_DM_RELAYS)

/** The relays a kind 10050 event names, canonical and deduplicated, at most
 *  [MAX_DM_RELAYS]; none for another kind, another author, or a bad signature. */
fun parseDmRelayList(event: NostrEvent, author: String? = null): List<String> {
    if (event.kind != KIND_DM_RELAYS) return emptyList()
    if (author != null && !event.pubkey.equals(author, ignoreCase = true)) return emptyList()
    if (!Events.verify(event)) return emptyList()
    return relaysNamed(event)
}

/** Of several kind 10050 events for one author, the latest with a good
 *  signature counts, then the lowest id; even one naming nothing usable. */
fun latestDmRelayList(events: List<NostrEvent>, author: String): List<String> =
    events.filter { it.kind == KIND_DM_RELAYS && it.pubkey.equals(author, ignoreCase = true) }
        .sortedWith(compareByDescending<NostrEvent> { it.createdAt }.thenBy { it.id })
        .firstOrNull { Events.verify(it) }
        ?.let(::relaysNamed)
        ?: emptyList()

/** The tags of a kind 10050 event naming [relays]. Throws on an unsafe
 *  address, none at all, or more than [MAX_DM_RELAYS]. */
fun dmRelayListTags(relays: List<String>): List<List<String>> {
    val urls = canonicalDmRelays(relays)
    require(urls.size == relays.size) { "every relay must be a wss:// address" }
    require(urls.isNotEmpty()) { "a DM relay list needs at least one relay" }
    require(urls.size <= MAX_DM_RELAYS) { "a DM relay list can name at most $MAX_DM_RELAYS relays" }
    return urls.map { listOf("relay", it) }
}

/**
 * The relays a new private conversation is started on: the other person's and
 * the starter's, alternating, theirs first, deduplicated, at most
 * [MAX_DM_RELAYS]; the fallback (the room it is started from) when neither
 * has a list; topped up from the fallback to [MIN_DM_RELAYS].
 */
fun relaysForPrivateConversation(mine: List<String>, theirs: List<String>, fallback: List<String>): List<String> {
    val ours = canonicalDmRelays(mine)
    val others = canonicalDmRelays(theirs)
    val room = canonicalDmRelays(fallback)
    val chosen = mutableListOf<String>()
    for (i in 0 until maxOf(ours.size, others.size)) {
        for (url in listOfNotNull(others.getOrNull(i), ours.getOrNull(i))) if (url !in chosen) chosen += url
    }
    if (chosen.isEmpty()) return room.take(MAX_DM_RELAYS)
    for (url in room) {
        if (chosen.size >= MIN_DM_RELAYS) break
        if (url !in chosen) chosen += url
    }
    return chosen.take(MAX_DM_RELAYS)
}
