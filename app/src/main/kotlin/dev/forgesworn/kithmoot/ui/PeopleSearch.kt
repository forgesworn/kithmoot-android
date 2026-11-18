package dev.forgesworn.kithmoot.ui

import dev.forgesworn.kithmoot.account.publicKeyFrom
import dev.forgesworn.kithmoot.protocol.DisplayName
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.ui.room.decodePublicProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

internal const val PEOPLE_SEARCH_RELAY = "wss://relay.ditto.pub/"
internal const val PEOPLE_SEARCH_LIMIT = 16
private val SearchSpaces = Regex("\\s+")
private val PrivateInput = Regex("nsec1|ncryptsec1|bunker:|nostrconnect:|://", RegexOption.IGNORE_CASE)

internal fun peopleSearchQuery(value: String): String? = value.trim().replace(SearchSpaces, " ")
    .takeIf { it.length in 2..120 && !PrivateInput.containsMatchIn(it) }

internal fun peopleSearchKey(query: String): String? = publicKeyFrom(query.removePrefix("nostr:"))

/** Names are self-asserted. Only the signed key and display text leave this lookup. */
internal fun peopleSearchResults(events: List<NostrEvent>, query: String, now: Long): List<Pair<String, String?>> {
    val newest = linkedMapOf<String, NostrEvent>()
    for (event in events.take(PEOPLE_SEARCH_LIMIT)) {
        if (event.createdAt !in 0..now + 60) continue
        if (decodePublicProfile(event, setOf(event.pubkey), now) == null) continue
        val previous = newest[event.pubkey]
        if (previous == null || newerProfile(event.createdAt, event.id, previous.createdAt, previous.id)) newest[event.pubkey] = event
    }
    val exact = mutableListOf<Pair<String, String?>>()
    val partial = mutableListOf<Pair<String, String?>>()
    for ((key, event) in newest) {
        val profile = Json.parseToJsonElement(event.content).jsonObject
        val names = listOfNotNull(profile["display_name"], profile["name"]).mapNotNull {
            DisplayName.sanitise((it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content)
        }
        if (names.any { it.equals(query, ignoreCase = true) }) exact.add(key to names.first())
        else if (names.any { it.contains(query, ignoreCase = true) }) partial.add(key to names.first())
    }
    return exact + partial
}
