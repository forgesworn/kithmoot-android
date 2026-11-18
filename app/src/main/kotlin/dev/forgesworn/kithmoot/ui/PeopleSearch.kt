package dev.forgesworn.kithmoot.ui

import dev.forgesworn.kithmoot.account.publicKeyFrom
import dev.forgesworn.kithmoot.protocol.DisplayName
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.ui.room.PublicProfile
import dev.forgesworn.kithmoot.ui.room.decodePublicProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

internal const val PEOPLE_SEARCH_RELAY = "wss://relay.ditto.pub/"
internal const val PEOPLE_PROFILE_RELAY = "wss://purplepag.es/"
internal const val PEOPLE_SEARCH_LIMIT = 16
internal const val PEOPLE_EXACT_PROFILE_LIMIT = 4
private val SearchSpaces = Regex("\\s+")
private val PrivateInput = Regex("nsec1|ncryptsec1|bunker:|nostrconnect:|://", RegexOption.IGNORE_CASE)
private val PublicAddress = Regex("^(?:-\\s*)?(?:nostr:)?n(?:pub|profile)1", RegexOption.IGNORE_CASE)
private val AddressPrefix = Regex("^(?:-\\s*)?(?:nostr:)?", RegexOption.IGNORE_CASE)

internal fun peopleSearchQuery(value: String): String? = value.trim().replace(SearchSpaces, " ")
    .takeIf { it.length in 2..120 && !PrivateInput.containsMatchIn(it) }
    ?.takeIf { !PublicAddress.containsMatchIn(it) || peopleSearchKey(it) != null }

internal fun peopleSearchKey(query: String): String? = publicKeyFrom(AddressPrefix.replaceFirst(query, ""))

internal data class PeopleSearchProfile(val event: NostrEvent, val profile: PublicProfile)

/** Exact searches bind the result to the pasted key, even if a relay ignores the filter. */
internal fun peopleSearchProfiles(events: List<NostrEvent>, query: String, now: Long, peer: String?): List<PeopleSearchProfile> {
    val newest = linkedMapOf<String, PeopleSearchProfile>()
    val limit = if (peer == null) PEOPLE_SEARCH_LIMIT else PEOPLE_EXACT_PROFILE_LIMIT
    for (event in events.take(limit)) {
        if (event.createdAt !in 0..now + 60) continue
        val profile = decodePublicProfile(event, setOf(peer ?: event.pubkey), now) ?: continue
        val previous = newest[event.pubkey]?.event
        if (previous == null || newerProfile(event.createdAt, event.id, previous.createdAt, previous.id)) {
            newest[event.pubkey] = PeopleSearchProfile(event, profile)
        }
    }
    if (peer != null) return newest.values.toList()
    val exact = mutableListOf<PeopleSearchProfile>()
    val partial = mutableListOf<PeopleSearchProfile>()
    for (result in newest.values) {
        val profile = Json.parseToJsonElement(result.event.content).jsonObject
        val names = listOfNotNull(profile["display_name"], profile["name"]).mapNotNull {
            DisplayName.sanitise((it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content)
        }
        if (names.any { it.equals(query, ignoreCase = true) }) exact.add(result)
        else if (names.any { it.contains(query, ignoreCase = true) }) partial.add(result)
    }
    return exact + partial
}
