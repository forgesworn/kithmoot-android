package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.toHex
import kotlinx.serialization.json.*
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.text.Normalizer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** One shared file riding inside the room-key ciphertext, as `ChatAttachment`
 * in the reference `src/chat.ts`. [name], [type] and [size] are the sender's
 * hints; [size] is the envelope's byte count, which is what a tap downloads.
 * [event] is the kind-1063 announcement id when the sender published one;
 * this client never does, and it is kept only so a decoded share re-encodes
 * exactly as it arrived. */
data class ChatAttachment(val url: String, val sha256: String, val key: String,
    val name: String? = null, val type: String? = null, val size: Long? = null, val event: String? = null)

/** The reference key order: url, sha256, key, event, name, type, size. */
fun ChatAttachment.toJson(): JsonObject = buildJsonObject {
    put("url", url); put("sha256", sha256); put("key", key); event?.let { put("event", it) }
    name?.let { put("name", it) }; type?.let { put("type", it) }; size?.let { put("size", it) }
}

private val hex64 = Regex("[0-9a-fA-F]{64}")
private val mediaTypeHint = Regex("[\\w.+-]+/[\\w.+-]+")
private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L

/** Mirrors `normaliseAttachment`: a share is https with a 32-byte hash and
 * key or it is nothing, and a bad hint is dropped rather than the share. */
fun parseAttachment(value: JsonElement): ChatAttachment? = runCatching {
    val obj = value.jsonObject
    fun string(key: String) = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val url = string("url") ?: return null
    require(url.length in 1..2048 && URI(url).scheme.equals("https", true) && URI(url).host != null)
    val hash = string("sha256")?.takeIf(hex64::matches) ?: return null
    val key = string("key")?.takeIf(hex64::matches) ?: return null
    require(obj["event"] == null || string("event")?.matches(hex64) == true)
    ChatAttachment(url, hash.lowercase(), key.lowercase(),
        sanitiseAttachmentName(string("name")),
        string("type")?.takeIf { type ->
            type.length <= 128 && (type.matches(mediaTypeHint) ||
                (recordingPlaybackMime(type) != null && type.contains(';') &&
                    type.substringAfter(';').isNotBlank() && type.all { it.code in 32..126 }))
        }?.lowercase(),
        attachmentSize(obj["size"]),
        string("event")?.lowercase())
}.getOrNull()

/** A JSON number that is a safe integer, as `Number.isSafeInteger`: `12.0`
 * and `1.31072e5` count, `12.5` and the string `"12"` do not. */
private fun attachmentSize(value: JsonElement?): Long? {
    val number = (value as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.content ?: return null
    val exact = number.toLongOrNull()
        ?: number.toBigDecimalOrNull()?.let { runCatching { it.longValueExact() }.getOrNull() }
    return exact?.takeIf { it in 0..MAX_SAFE_INTEGER }
}

/** JavaScript's `\s` and `trim()` set, which is not Kotlin's `isWhitespace`. */
private fun jsSpace(codePoint: Int): Boolean = codePoint in 0x09..0x0d || codePoint == 0x20 || codePoint == 0xa0 ||
    codePoint == 0x1680 || codePoint in 0x2000..0x200a || codePoint == 0x2028 || codePoint == 0x2029 ||
    codePoint == 0x202f || codePoint == 0x205f || codePoint == 0x3000 || codePoint == 0xfeff

private fun otherCategory(codePoint: Int): Boolean = when (Character.getType(codePoint).toByte()) {
    Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED -> true
    else -> false
}

/** `sanitiseAttachmentName` in the reference: whitespace collapsed, every
 * `\p{C}` (controls, bidi overrides, invisibles) removed, trimmed, and at
 * most 255 code points. A name with nothing left is no name. */
internal fun sanitiseAttachmentName(raw: String?): String? {
    if (raw == null) return null
    fun collapse(points: List<Int>): List<Int> {
        val out = ArrayList<Int>(points.size)
        for (point in points) {
            if (jsSpace(point)) { if (out.lastOrNull() != 0x20) out.add(0x20) } else out.add(point)
        }
        return out
    }
    fun trim(points: List<Int>) = points.dropWhile(::jsSpace).dropLastWhile(::jsSpace)
    fun text(points: List<Int>) = buildString { points.forEach { appendCodePoint(it) } }
    val cleaned = trim(collapse(collapse(raw.codePoints().toArray().toList()).filterNot(::otherCategory)))
    if (cleaned.isEmpty()) return null
    if (cleaned.size <= 255) return text(cleaned)
    return text(trim(cleaned.take(255))).ifEmpty { null }
}

const val MAX_IMAGE_ENVELOPE_BYTES = 32 * 1024 * 1024
data class OpenedAttachment(val name: String, val type: String, val bytes: ByteArray)

/** Compatible with the shared FSWNENC2 reader. No plaintext escapes before all
 * records authenticate and the complete ciphertext hash matches the message. */
fun openAttachment(envelope: ByteArray, attachment: ChatAttachment): OpenedAttachment {
    require(envelope.size in 41..MAX_IMAGE_ENVELOPE_BYTES) { "Image attachment exceeds the 32 MiB viewing limit." }
    require(Digests.sha256(envelope).toHex() == attachment.sha256) { "The download does not match the message." }
    require(attachment.key.matches(hex64))
    val magic = envelope.copyOfRange(0, 8).toString(Charsets.US_ASCII)
    val headerSize = when (magic) { "FSWNENC2" -> 56; "FSWNENC1", "WBLMENC1" -> 24; else -> error("Unknown encrypted file format.") }
    require(envelope.size > headerSize)
    val header = envelope.copyOfRange(0, headerSize)
    val chunk = ByteBuffer.wrap(header).getInt(8)
    val records = ByteBuffer.wrap(header).getInt(12)
    require(chunk == 1_048_576 && records in 1..32)
    val plainSize = envelope.size - headerSize - records * 16
    require(plainSize > (records - 1) * chunk && plainSize <= records * chunk)
    val recovery = attachment.key.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    val key = if (headerSize == 56) Digests.hkdfSha256(recovery, header.copyOfRange(24, 56),
        "forgesworn-aes-256-gcm-chunked/v2".toByteArray(), 32) else recovery
    val plaintext = ByteArray(plainSize)
    try {
        for (counter in 0 until records) {
            val index = ByteBuffer.allocate(4).putInt(counter).array()
            val nonce = header.copyOfRange(16, 24) + index
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(header + index)
            val start = headerSize + counter * (chunk + 16)
            val end = minOf(envelope.size, start + chunk + 16)
            val part = cipher.doFinal(envelope, start, end - start)
            part.copyInto(plaintext, counter * chunk); part.fill(0)
        }
        val metadataSize = ByteBuffer.wrap(plaintext).int
        require(metadataSize in 2..4096 && metadataSize + 4 < plaintext.size)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(plaintext, 4, metadataSize)).toString()
        val meta = Json.parseToJsonElement(text).jsonObject
        require(meta.keys == setOf("name", "size", "type"))
        val name = meta.getValue("name").jsonPrimitive.also { require(it.isString) }.content
        val type = meta.getValue("type").jsonPrimitive.also { require(it.isString) }.content
        val size = meta.getValue("size").jsonPrimitive.int
        require(size in 1..MAX_IMAGE_ENVELOPE_BYTES)
        require(name == canonicalAttachmentName(name))
        require(type.isNotEmpty() && type.length <= 255 && type == type.lowercase() && type.none { it.code < 32 || it.code == 127 })
        val canonical = buildJsonObject { put("name", name); put("size", size); put("type", type) }.toString()
        require(text == canonical) { "Invalid encrypted metadata." }
        val length = 4 + metadataSize + size
        val padded = if (length <= chunk) { var n = 65_536; while (n < length) n *= 2; n } else ((length + chunk - 1) / chunk) * chunk
        require(padded == plaintext.size && length <= plaintext.size)
        return OpenedAttachment(name, type, plaintext.copyOfRange(4 + metadataSize, length))
    } finally { plaintext.fill(0); recovery.fill(0); key.fill(0) }
}

internal fun canonicalAttachmentName(value: String): String {
    val leaf = value.split('/', '\\').last()
    val cleaned = Normalizer.normalize(leaf, Normalizer.Form.NFC)
        .filterNot { it.code < 32 || it.code == 127 }.replace(Regex("[<>:\"|?*]"), "_")
        .trimStart('.').trim().ifEmpty { "blob.bin" }
    val end = if (cleaned.length > 180 && cleaned[179].isHighSurrogate() && cleaned[180].isLowSurrogate()) 179 else 180
    return cleaned.take(end)
}
