package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.*
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.NostrEvent
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.nio.ByteBuffer
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

const val MAX_MEDIA_SOURCE_BYTES = 8 * 1024 * 1024
private const val CHUNK = 1_048_576
private val mediaHttp = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).callTimeout(60, TimeUnit.SECONDS).build()
data class SealedMedia(val envelope: ByteArray, val hash: String, val key: String, val name: String, val type: String)

/** FSWNENC2, identical authenticated record and metadata format to the web writer. */
fun sealMedia(bytes: ByteArray, name: String, type: String): SealedMedia {
    require(bytes.size in 1..MAX_MEDIA_SOURCE_BYTES) { "Choose a non-empty image up to 8 MiB." }
    require(type in setOf("image/gif", "image/png", "image/jpeg", "image/webp")) { "Choose a GIF, PNG, JPEG or WebP image." }
    val safeName = canonicalAttachmentName(name)
    val metadata = buildJsonObject { put("name", safeName); put("size", bytes.size); put("type", type) }.toString().toByteArray()
    val length = 4 + metadata.size + bytes.size
    val padded = if (length <= CHUNK) { var n = 65_536; while (n < length) n *= 2; n } else ((length + CHUNK - 1) / CHUNK) * CHUNK
    val plain = Entropy.bytes(padded)
    ByteBuffer.wrap(plain).putInt(metadata.size).put(metadata).put(bytes)
    val recovery = Entropy.bytes(32); val salt = Entropy.bytes(32)
    val records = (padded + CHUNK - 1) / CHUNK
    val header = ByteBuffer.allocate(56).put("FSWNENC2".toByteArray()).putInt(CHUNK).putInt(records).put(Entropy.bytes(8)).put(salt).array()
    val key = Digests.hkdfSha256(recovery, salt, "forgesworn-aes-256-gcm-chunked/v2".toByteArray(), 32)
    val envelope = ByteArray(56 + padded + records * 16); header.copyInto(envelope)
    try {
        for (counter in 0 until records) {
            val index = ByteBuffer.allocate(4).putInt(counter).array()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, header.copyOfRange(16, 24) + index))
            cipher.updateAAD(header + index)
            val start = counter * CHUNK; val part = cipher.doFinal(plain, start, minOf(CHUNK, padded - start))
            part.copyInto(envelope, 56 + counter * (CHUNK + 16)); part.fill(0)
        }
        return SealedMedia(envelope, Digests.sha256(envelope).toHex(), recovery.toHex(), safeName, type)
    } finally { plain.fill(0); recovery.fill(0); key.fill(0) }
}

fun mediaStorageOrigin(value: String): String {
    val uri = URI(value.trim())
    require(uri.scheme == "https" && uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.path in listOf("", "/")) { "Choose an HTTPS storage origin, without a path, query or password." }
    return URI("https", null, uri.host.lowercase(), uri.port, null, null, null).toASCIIString()
}
fun mediaAuthorisation(action: String, hash: String, origin: String, secret: ByteArray, at: Long, expires: Long): NostrEvent {
    require(action in setOf("upload", "delete") && hash.matches(Regex("[0-9a-f]{64}")) && expires > at)
    return Events.sign(secret, 24242, at - 1, listOf(listOf("t", action), listOf("expiration", expires.toString()), listOf("server", URI(origin).host), listOf("x", hash)),
        if (action == "upload") "Upload blob $hash to ${URI(origin).host}" else "Delete blob $hash")
}
private fun authHeader(event: NostrEvent) = "Nostr " + Base64.getEncoder().encodeToString(event.toJson().toString().toByteArray())

fun uploadMedia(sealed: SealedMedia, origin: String, auth: NostrEvent, client: OkHttpClient = mediaHttp): ChatAttachment {
    require(mediaStorageOrigin(origin) == origin)
    client.newCall(Request.Builder().url("$origin/upload").header("Authorization", authHeader(auth)).header("X-SHA-256", sealed.hash)
        .put(sealed.envelope.toRequestBody("application/vnd.forgesworn.encrypted".toMediaType())).build()).execute().use { response ->
        check(response.code in listOf(200, 201)) { "Storage refused this upload (${response.code})." }
        val bytes = checkNotNull(response.body).byteStream().use { it.readNBytes(16_385) }
        require(bytes.size <= 16_384)
        val obj = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        val url = obj.getValue("url").jsonPrimitive.content
        val uri = URI(url)
        require(mediaStorageOrigin(URI(uri.scheme, null, uri.host, uri.port, null, null, null).toString()) == origin && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
        require(uri.path.substringAfterLast('/').matches(Regex("${sealed.hash}(?:\\.[a-z0-9]{1,10})?")))
        require(obj.getValue("sha256").jsonPrimitive.content == sealed.hash && obj.getValue("size").jsonPrimitive.long == sealed.envelope.size.toLong())
        return ChatAttachment(url, sealed.hash, sealed.key, sealed.name, sealed.type, sealed.envelope.size.toLong())
    }
}
fun deleteUploadedMedia(origin: String, hash: String, auth: NostrEvent, client: OkHttpClient = mediaHttp): Boolean {
    require(mediaStorageOrigin(origin) == origin)
    val url = "$origin/$hash"
    client.newCall(Request.Builder().url(url).header("Authorization", authHeader(auth)).delete().build()).execute().use { response ->
        if (response.code == 404 || response.code == 410) return true
        if (!response.isSuccessful) return false
    }
    return client.newCall(Request.Builder().url(url).head().build()).execute().use { it.code == 404 || it.code == 410 }
}
