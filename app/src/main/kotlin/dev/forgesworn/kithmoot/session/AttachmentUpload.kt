package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.*
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.NostrEvent
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.io.File
import java.nio.ByteBuffer
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

const val MAX_MEDIA_SOURCE_BYTES = 8 * 1024 * 1024
private const val CHUNK = 1_048_576
private val mediaHttp = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).callTimeout(60, TimeUnit.SECONDS).build()
/** Images, and the plain text document a long paste becomes (see LongChatText.kt). */
private val SEALABLE_MEDIA_TYPES = setOf("image/gif", "image/png", "image/jpeg", "image/webp", "text/plain", "text/plain;charset=utf-8")
data class SealedMedia(val envelope: ByteArray, val hash: String, val key: String, val name: String, val type: String)

/** FSWNENC2, identical authenticated record and metadata format to the web writer. */
fun sealMedia(bytes: ByteArray, name: String, type: String): SealedMedia {
    require(bytes.size in 1..MAX_MEDIA_SOURCE_BYTES) { "Choose a non-empty file up to 8 MiB." }
    require(type in SEALABLE_MEDIA_TYPES) { "Choose a GIF, PNG, JPEG or WebP image, or a plain text document." }
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
    return Events.sign(secret, 24242, at, listOf(listOf("t", action), listOf("expiration", expires.toString()), listOf("server", URI(origin).host), listOf("x", hash)),
        if (action == "upload") "Upload blob $hash to ${URI(origin).host}" else "Delete blob $hash")
}
/** NIP-94 file metadata, as `buildFileEvent` in the reference writes it for
 * a sealed envelope: tags in a fixed order, the opaque envelope type and a
 * fixed name, nothing about the file inside. This client does not publish
 * it: a share's details ride only inside the room-key ciphertext, which the
 * reference reader accepts (a quiet room omits the announcement too). It is
 * kept here only so the published `file-event` vector can check the shape. */
const val FILE_EVENT_KIND = 1063
const val ENVELOPE_FILE_NAME = "wildbloom.wbenc"
fun fileEventTags(url: String, sha256: String, size: Long): List<List<String>> {
    val hash = sha256.lowercase()
    require(hash.matches(Regex("[0-9a-f]{64}")) && url.startsWith("https://", ignoreCase = true) && size > 0)
    return listOf(listOf("url", url), listOf("m", "application/vnd.forgesworn.encrypted"), listOf("x", hash), listOf("ox", hash),
        listOf("size", size.toString()), listOf("encryption", "forgesworn-aes-256-gcm-chunked-v2"), listOf("alt", "Encrypted Wildbloom file"))
}

private fun authHeader(event: NostrEvent) = "Nostr " + Base64.getUrlEncoder().withoutPadding().encodeToString(event.toJson().toString().toByteArray())

fun uploadMedia(sealed: SealedMedia, origin: String, auth: NostrEvent, client: OkHttpClient = mediaHttp): ChatAttachment {
    require(mediaStorageOrigin(origin) == origin)
    client.newCall(Request.Builder().url("$origin/upload").header("Authorization", authHeader(auth)).header("X-SHA-256", sealed.hash)
        .put(sealed.envelope.toRequestBody("application/vnd.forgesworn.encrypted".toMediaType())).build()).execute().use { response ->
        return verifiedMediaResponse(response, origin, sealed.hash, sealed.key, sealed.name, sealed.type, sealed.envelope.size.toLong())
    }
}

/** Explicitly requested upload of an immutable app-owned encrypted file.
 * Reuses Blossom and FSWNENC2; neither discovery nor public file publication.
 */
fun uploadFileMedia(sealed: SealedFile, origin: String, auth: NostrEvent, client: OkHttpClient = recordingTransferHttp): ChatAttachment {
    require(mediaStorageOrigin(origin) == origin)
    require(sealed.file.length() in 73L..(260L * 1024 * 1024) && fileSha256(sealed.file) == sealed.hash)
    val length = sealed.file.length()
    val body = object : RequestBody() {
        override fun contentType() = "application/vnd.forgesworn.encrypted".toMediaType()
        override fun contentLength() = length
        override fun isOneShot() = true
        override fun writeTo(sink: okio.BufferedSink) {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            var written = 0L
            val buffer = ByteArray(64 * 1024)
            try {
                sealed.file.inputStream().use { input ->
                    while (true) {
                        check(!Thread.currentThread().isInterrupted) { "Upload cancelled" }
                        val count = input.read(buffer)
                        if (count < 0) break
                        written += count
                        check(written <= length) { "The encrypted file changed during upload" }
                        digest.update(buffer, 0, count); sink.write(buffer, 0, count)
                    }
                }
                check(written == length && digest.digest().toHex() == sealed.hash) { "The encrypted file changed during upload" }
            } finally { buffer.fill(0) }
        }
    }
    client.newCall(Request.Builder().url("$origin/upload").header("Authorization", authHeader(auth)).header("X-SHA-256", sealed.hash)
        .put(body).build()).execute().use { response ->
        return verifiedMediaResponse(response, origin, sealed.hash, sealed.key, sealed.name, sealed.type, length)
    }
}

/**
 * The words for a file its server no longer has: a 404, or a 410 from a store
 * that has closed, as ForgeSworn's did on 23 October 2026. To the person both
 * mean the same, as the web's card says. Null for any other answer.
 */
fun goneFileNote(url: String, code: Int): String? {
    if (code != 404 && code != 410) return null
    val host = runCatching { URI(url).host }.getOrNull()
    return if (host.isNullOrBlank()) "This file is no longer on its server." else "This file is no longer on $host."
}

/**
 * Reads the encrypted bytes of a share held on the room's own storage (a node
 * answers only a signed request, so a plain download would be refused). It
 * writes them to [destination] and returns true, or returns false for a share
 * that is not on the room's storage, which is then fetched the ordinary way.
 * It blocks, and it throws when the room's nodes cannot supply the file.
 */
fun interface GroupFileSource {
    fun fetch(attachment: ChatAttachment, destination: File, limit: Long): Boolean
}

/** Called only after the recipient chooses to fetch a file. Ciphertext
 * downloads and authenticated plaintext each stay in app-private files.
 */
fun fetchFileAttachment(attachment: ChatAttachment, destination: File, client: OkHttpClient = recordingTransferHttp,
    groupSource: GroupFileSource? = null): OpenedFile {
    val uri = URI(attachment.url)
    require(uri.scheme == "https" && uri.host != null && uri.rawUserInfo == null && uri.rawFragment == null)
    require(attachment.url.length <= 2048 && !destination.exists())
    val limit = 260L * 1024 * 1024
    attachment.size?.let { require(it in 73L..limit) }
    val encrypted = File.createTempFile(".recording-download-", ".part", destination.absoluteFile.parentFile)
    try {
        if (groupSource?.fetch(attachment, encrypted, limit) == true) {
            require(encrypted.length() in 73L..limit && (attachment.size == null || encrypted.length() == attachment.size)) { "The download size does not match the message" }
            return openFileAttachment(encrypted, destination, attachment)
        }
        client.newCall(Request.Builder().url(attachment.url).build()).execute().use { response ->
            check(response.code == 200) { goneFileNote(attachment.url, response.code) ?: "Storage refused this file (${response.code})." }
            val body = checkNotNull(response.body)
            require(body.contentLength() == -1L || body.contentLength() in 73L..limit)
            var bytes = 0L
            val buffer = ByteArray(64 * 1024)
            try {
                body.byteStream().use { input -> encrypted.outputStream().use { output ->
                    while (true) {
                        check(!Thread.currentThread().isInterrupted) { "Download cancelled" }
                        val count = input.read(buffer)
                        if (count < 0) break
                        bytes += count
                        require(bytes <= limit && (attachment.size == null || bytes <= attachment.size)) { "The file exceeds its download limit" }
                        output.write(buffer, 0, count)
                    }
                } }
                require(attachment.size == null || bytes == attachment.size) { "The download size does not match the message" }
            } finally { buffer.fill(0) }
        }
        return openFileAttachment(encrypted, destination, attachment)
    } finally { encrypted.delete() }
}

private fun verifiedMediaResponse(response: Response, origin: String, hash: String, key: String, name: String, type: String, size: Long): ChatAttachment {
    check(response.code in listOf(200, 201)) { "Storage refused this upload (${response.code})." }
    val bytes = checkNotNull(response.body).byteStream().use { it.readNBytes(16_385) }
    require(bytes.size <= 16_384)
    val obj = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
    val url = obj.getValue("url").jsonPrimitive.content
    val uri = URI(url)
    require(mediaStorageOrigin(URI(uri.scheme, null, uri.host, uri.port, null, null, null).toString()) == origin && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
    require(uri.path.substringAfterLast('/').matches(Regex("${hash}(?:\\.[a-z0-9]{1,10})?")))
    require(obj.getValue("sha256").jsonPrimitive.content == hash && obj.getValue("size").jsonPrimitive.long == size)
    // The name and type hint as the reference keeps them: the name is cleaned, a type with parameters is dropped, the envelope still says what the file is.
    return ChatAttachment(url, hash, key, sanitiseAttachmentName(name), attachmentTypeHint(type), size)
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
