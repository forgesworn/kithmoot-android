package dev.forgesworn.kithmoot.session

import kotlinx.serialization.json.*

/** Unicode 16.0, with the same accepted sequences as the web client. */
object EmojiCatalog {
    private val data by lazy { Json.parseToJsonElement(checkNotNull(javaClass.getResourceAsStream("/emoji-catalog.json")).bufferedReader().use { it.readText() }).jsonObject }
    val entries: List<Pair<String, String>> by lazy { data.getValue("entries").jsonArray.map { it.jsonArray[0].jsonPrimitive.content to it.jsonArray[1].jsonPrimitive.content } }
    private val valid by lazy { data.getValue("valid").jsonArray.mapTo(HashSet()) { it.jsonPrimitive.content } }
    fun accepts(emoji: String): Boolean = emoji in valid || isCultEmoji(emoji) || isOriginalEmoji(emoji)
}
