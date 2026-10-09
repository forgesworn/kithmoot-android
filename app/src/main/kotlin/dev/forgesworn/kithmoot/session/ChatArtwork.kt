package dev.forgesworn.kithmoot.session

import kotlinx.serialization.json.*

/** A bundled image reference inside the room ciphertext, never a download URL. */
data class ChatArtwork(val pack: String, val id: String, val kind: String, val sha256: String, val label: String)

const val MAX_CHAT_ARTWORK = 4
private val artworkIdentifier = Regex("[a-z0-9][a-z0-9_-]{0,63}")
private val artworkHash = Regex("[0-9a-fA-F]{64}")
private val unicodeOther = setOf(Character.CONTROL.toInt(), Character.FORMAT.toInt(), Character.SURROGATE.toInt(),
    Character.PRIVATE_USE.toInt(), Character.UNASSIGNED.toInt())

private fun artworkWhitespace(point: Int): Boolean = point in 9..13 || point in setOf(0x20, 0xa0, 0x1680, 0x2028, 0x2029, 0x202f, 0x205f, 0x3000, 0xfeff) || point in 0x2000..0x200a

fun normaliseArtwork(value: ChatArtwork): ChatArtwork? {
    if (!artworkIdentifier.matches(value.pack) || !artworkIdentifier.matches(value.id) ||
        value.kind !in setOf("sticker", "gif") || !artworkHash.matches(value.sha256)) return null
    val clean = buildString {
        value.label.codePoints().forEach { point ->
            if (artworkWhitespace(point)) append(' ')
            else if (Character.getType(point) !in unicodeOther) appendCodePoint(point)
        }
    }.replace(Regex(" +"), " ").trim()
    val points = clean.codePoints().limit(80).toArray()
    val label = String(points, 0, points.size).trim().ifEmpty { value.id }
    return value.copy(sha256 = value.sha256.lowercase(), label = label)
}

fun ChatArtwork.toJson(): JsonObject = buildJsonObject {
    put("pack", pack); put("id", id); put("kind", kind); put("sha256", sha256); put("label", label)
}

fun parseArtwork(value: JsonElement): ChatArtwork? = runCatching {
    val obj = value as? JsonObject ?: return null
    fun string(key: String) = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    normaliseArtwork(ChatArtwork(string("pack") ?: return null, string("id") ?: return null,
        string("kind") ?: return null, string("sha256") ?: return null, string("label") ?: return null))
}.getOrNull()

fun artworkFallback(values: List<ChatArtwork>): String = values.joinToString("; ") {
    "${if (it.kind == "gif") "GIF" else "Sticker"}: ${it.label}"
}
