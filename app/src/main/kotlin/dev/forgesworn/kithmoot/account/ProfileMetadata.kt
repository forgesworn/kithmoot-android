package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.NostrEvent
import kotlinx.serialization.json.*
import java.net.URI

/** Preserve fields written by other Nostr clients when updating kind 0. */
object ProfileMetadata {
    val fields = listOf("name", "display_name", "about", "picture", "banner", "website", "nip05", "lud16")
    fun latest(events: List<NostrEvent>, pubkey: String, now: Long): NostrEvent? = events.filter {
        it.kind == 0 && it.pubkey == pubkey && it.createdAt <= now + 60 && it.content.length <= 16_384 && Events.verify(it)
    }.sortedWith(compareByDescending<NostrEvent> { it.createdAt }.thenBy { it.id }).firstOrNull { event ->
        runCatching { Json.parseToJsonElement(event.content).jsonObject }.isSuccess
    }
    fun edit(base: JsonObject, values: Map<String, String>): JsonObject {
        require(values.keys.all { it in fields }) { "Unknown profile field" }
        val next = base.toMutableMap()
        for ((key, raw) in values) {
            val value = raw.trim()
            require(value.length <= if (key == "about") 4096 else 2048) { "The $key field is too long." }
            if (key in setOf("picture", "banner", "website") && value.isNotEmpty()) {
                val uri = URI(value)
                require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null) { "Use an https:// address for $key." }
            }
            if (value.isEmpty()) next.remove(key) else next[key] = JsonPrimitive(value)
        }
        return JsonObject(next).also { require(it.toString().toByteArray().size <= 16_384) { "This profile is too large." } }
    }
}
