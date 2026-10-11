package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.*
import kotlinx.serialization.json.*
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.text.Normalizer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

const val MAX_RECORDING_SOURCE_BYTES = 256L * 1024 * 1024
private const val RECORD_BYTES = 1_048_576
private const val HEADER_BYTES = 56
private const val MAX_FILE_ENVELOPE_BYTES = 260L * 1024 * 1024

/** App-owned file; callers retain it until an explicit save, share or discard. */
data class SealedFile(val file: File, val hash: String, val key: String, val name: String, val type: String)
data class OpenedFile(val file: File, val name: String, val type: String, val size: Long)

private fun paddedFileSize(length: Long): Long = if (length <= RECORD_BYTES) {
    var size = 65_536L
    while (size < length) size *= 2
    size
} else ((length + RECORD_BYTES - 1) / RECORD_BYTES) * RECORD_BYTES

private fun checkFileWork() { check(!Thread.currentThread().isInterrupted) { "File operation cancelled" } }

/** Wildbloom's fixed-point filename rules, including UTF-16 truncation. */
private fun canonicalFileName(value: String): String {
    fun space(character: Char) = character.isWhitespace() || character == '\uFEFF'
    val leaf = value.split('/', '\\').last().filterNot { it.code < 32 || it.code == 127 }
    val cleaned = Normalizer.normalize(leaf, Normalizer.Form.NFC).replace(Regex("[<>:\"|?*]"), "_")
        .trimStart { it == '.' || space(it) }.trimEnd(::space).ifEmpty { "blob.bin" }
    if (cleaned.length <= 180) return cleaned
    val end = if (cleaned[179].isHighSurrogate() && cleaned[180].isLowSurrogate()) 179 else 180
    return cleaned.take(end).trimEnd(::space)
}

internal fun fileSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    try {
        file.inputStream().use { input ->
            while (true) {
                checkFileWork()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    } finally { buffer.fill(0) }
}

/** Shared Wildbloom FSWNENC2 wire format; at most one plaintext record in memory.
 * [destination] must not exist. No network or discovery action occurs here.
 */
fun sealFile(source: File, destination: File, name: String, type: String): SealedFile {
    val size = source.length()
    require(source.isFile && size in 1..MAX_RECORDING_SOURCE_BYTES)
    require(type in setOf("audio/mp4", "audio/wav", "video/mp4", "video/webm")) { "Unsupported recording format" }
    val safeName = canonicalFileName(name)
    val metadata = buildJsonObject { put("name", safeName); put("size", size); put("type", type) }.toString().toByteArray()
    require(metadata.size in 2..4096)
    val prefix = ByteBuffer.allocate(4 + metadata.size).putInt(metadata.size).put(metadata).array()
    val padded = paddedFileSize(prefix.size + size)
    val records = ((padded + RECORD_BYTES - 1) / RECORD_BYTES).toInt()
    val recovery = Entropy.bytes(32)
    val salt = Entropy.bytes(32)
    val header = ByteBuffer.allocate(HEADER_BYTES).put("FSWNENC2".toByteArray())
        .putInt(RECORD_BYTES).putInt(records).put(Entropy.bytes(8)).put(salt).array()
    val key = Digests.hkdfSha256(recovery, salt, "forgesworn-aes-256-gcm-chunked/v2".toByteArray(), 32)
    var owned = false
    var complete = false
    try {
        check(destination.createNewFile()) { "Recording destination already exists" }
        owned = true
        source.inputStream().use { input -> destination.outputStream().use { output ->
            output.write(header)
            var consumed = 0L
            for (counter in 0 until records) {
                checkFileWork()
                val length = minOf(RECORD_BYTES.toLong(), padded - counter.toLong() * RECORD_BYTES).toInt()
                val plain = Entropy.bytes(length)
                try {
                    val offset = if (counter == 0) { prefix.copyInto(plain); prefix.size } else 0
                    val count = minOf((length - offset).toLong(), size - consumed).toInt()
                    if (count > 0) {
                        require(input.readNBytes(plain, offset, count) == count) { "Recording changed during encryption" }
                        consumed += count
                    }
                    val index = ByteBuffer.allocate(4).putInt(counter).array()
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, header.copyOfRange(16, 24) + index))
                    cipher.updateAAD(header + index)
                    val sealed = cipher.doFinal(plain)
                    try { output.write(sealed) } finally { sealed.fill(0) }
                } finally { plain.fill(0) }
            }
            require(consumed == size && input.read() == -1 && source.length() == size) { "Recording changed during encryption" }
        } }
        val hash = fileSha256(destination)
        complete = true
        return SealedFile(destination, hash, recovery.toHex(), safeName, type)
    } finally {
        recovery.fill(0); key.fill(0)
        if (owned && !complete) destination.delete()
    }
}

/** Authenticate every record and metadata before exposing the output file.
 * Work stays in a private sibling temporary file until all checks pass.
 */
fun openFileAttachment(envelope: File, destination: File, attachment: ChatAttachment): OpenedFile {
    require(envelope.isFile && envelope.length() in (HEADER_BYTES + 17L)..MAX_FILE_ENVELOPE_BYTES)
    require(attachment.key.matches(Regex("[0-9a-f]{64}")) && attachment.sha256.matches(Regex("[0-9a-f]{64}")))
    require(!destination.exists()) { "Recording destination already exists" }
    val temporary = File.createTempFile(".recording-", ".part", destination.absoluteFile.parentFile)
    var key = ByteArray(0)
    val recovery = attachment.key.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    try {
        RandomAccessFile(envelope, "r").use { input ->
            val total = input.length()
            val digest = MessageDigest.getInstance("SHA-256")
            val header = ByteArray(HEADER_BYTES).also { input.readFully(it); digest.update(it) }
            require(header.copyOfRange(0, 8).toString(Charsets.US_ASCII) == "FSWNENC2")
            require(ByteBuffer.wrap(header).getInt(8) == RECORD_BYTES)
            val records = ByteBuffer.wrap(header).getInt(12)
            require(records in 1..257)
            val padded = total - HEADER_BYTES - records * 16L
            require(padded > (records - 1L) * RECORD_BYTES && padded <= records.toLong() * RECORD_BYTES)
            key = Digests.hkdfSha256(recovery, header.copyOfRange(24, 56), "forgesworn-aes-256-gcm-chunked/v2".toByteArray(), 32)
            var name = ""
            var type = ""
            var size = 0L
            var written = 0L
            temporary.outputStream().use { output ->
                for (counter in 0 until records) {
                    checkFileWork()
                    val length = minOf(RECORD_BYTES.toLong(), padded - counter.toLong() * RECORD_BYTES).toInt()
                    val sealed = ByteArray(length + 16)
                    val plain = try {
                        input.readFully(sealed); digest.update(sealed)
                        val index = ByteBuffer.allocate(4).putInt(counter).array()
                        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, header.copyOfRange(16, 24) + index))
                        cipher.updateAAD(header + index)
                        cipher.doFinal(sealed)
                    } finally { sealed.fill(0) }
                    try {
                        var offset = 0
                        if (counter == 0) {
                            val metadataSize = ByteBuffer.wrap(plain).int
                            require(metadataSize in 2..4096 && metadataSize + 4 < plain.size)
                            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                                .decode(ByteBuffer.wrap(plain, 4, metadataSize)).toString()
                            val metadata = Json.parseToJsonElement(text).jsonObject
                            require(metadata.keys == setOf("name", "size", "type"))
                            name = metadata.getValue("name").jsonPrimitive.also { require(it.isString) }.content
                            type = metadata.getValue("type").jsonPrimitive.also { require(it.isString) }.content
                            val sizeValue = metadata.getValue("size").jsonPrimitive.also { require(!it.isString) }
                            size = sizeValue.long
                            require(size in 1..MAX_RECORDING_SOURCE_BYTES && name == canonicalFileName(name))
                            require(type.isNotEmpty() && type.length <= 255 && type == type.lowercase() && type.none { it.code < 32 || it.code == 127 })
                            require(text == buildJsonObject { put("name", name); put("size", size); put("type", type) }.toString())
                            offset = 4 + metadataSize
                            require(paddedFileSize(size + offset) == padded && size + offset <= padded)
                        }
                        val count = minOf((plain.size - offset).toLong(), size - written).toInt()
                        output.write(plain, offset, count)
                        written += count
                    } finally { plain.fill(0) }
                }
            }
            require(written == size && input.read() == -1 && input.length() == total)
            require(digest.digest().toHex() == attachment.sha256) { "The download does not match the message" }
            // Reserve rather than overwrite a destination created by another operation.
            check(destination.createNewFile()) { "Recording destination already exists" }
            try { check(temporary.renameTo(destination)) { "Could not finish the recording file" } }
            catch (error: Throwable) { destination.delete(); throw error }
            return OpenedFile(destination, name, type, size)
        }
    } finally { temporary.delete(); recovery.fill(0); key.fill(0) }
}
