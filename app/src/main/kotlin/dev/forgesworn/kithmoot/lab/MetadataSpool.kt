package dev.forgesworn.kithmoot.lab

import kotlinx.serialization.json.*
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID

internal class MetadataSpool(
    private val directory: File,
    private val maxRecords: Int = 100_000,
    private val maxEncodedBytes: Long = 128_000_000,
    private val maxChunks: Int = 1024,
    private val chunkRecords: Int = 256,
    private val chunkBytes: Int = 1_000_000,
    private val writer: (File, String, ByteArray) -> Unit = Companion::writeAtomic,
) {
    private val zero = "0".repeat(64)
    private var offeredRecords = 0
    private var acceptedRecords = 0
    private var committedRecords = 0
    private var droppedRecords = 0
    private var acceptedEncodedBytes = 0L
    private var committedEncodedBytes = 0L
    private var overflow = false
    private var failure: String? = null
    private var closed = false
    private var complete = false
    private var chain = zero
    private val chunks = mutableListOf<JsonObject>()
    private val pending = mutableListOf<String>()
    private var pendingBytes = 0

    init {
        require(directory.isAbsolute && !directory.exists() && directory.mkdir())
        require(maxRecords in 1..100_000 && maxEncodedBytes in 1..2_000_000_000 && maxChunks in 1..10_000 &&
            chunkRecords in 1..4096 && chunkBytes in 1..7_000_000)
        privatePermissions(directory, true)
        syncDirectory(requireNotNull(directory.parentFile))
    }

    private fun limits() = buildJsonObject {
        put("maxRecords", maxRecords); put("maxEncodedBytes", maxEncodedBytes); put("maxChunks", maxChunks)
        put("chunkRecords", chunkRecords); put("chunkBytes", chunkBytes)
    }

    private fun manifest(complete: Boolean) = buildJsonObject {
        put("schema", "vennel.metadata-spool.v1"); put("complete", complete); put("overflow", overflow)
        put("failure", failure?.let(::JsonPrimitive) ?: JsonNull); put("limits", limits())
        put("offeredRecords", offeredRecords); put("acceptedRecords", acceptedRecords); put("committedRecords", committedRecords)
        put("droppedRecords", droppedRecords); put("acceptedEncodedBytes", acceptedEncodedBytes)
        put("committedEncodedBytes", committedEncodedBytes); put("chunks", JsonArray(chunks.toList()))
        put("finalChainSha256", chain)
    }

    private fun refuse(reason: String, capacity: Boolean = false): Nothing {
        droppedRecords++; if (failure == null) failure = reason; overflow = overflow || capacity
        throw IllegalStateException("Metadata spool $reason")
    }

    private fun flush() {
        if (pending.isEmpty()) return
        check(chunks.size < maxChunks)
        val body = pending.joinToString("")
        val bytes = body.toByteArray(Charsets.UTF_8)
        val index = chunks.size
        val name = index.toString().padStart(8, '0') + ".ndjson"
        val fileSha256 = sha256(bytes)
        val chainSha256 = chained(chain, bytes)
        try { writer(directory, name, bytes) } catch (_: Throwable) {
            if (failure == null) failure = "write failed"
            throw IllegalStateException("Metadata spool write failed")
        }
        chunks += buildJsonObject {
            put("index", index); put("file", name); put("records", pending.size); put("bytes", pendingBytes)
            put("sha256", fileSha256); put("chainSha256", chainSha256)
        }
        chain = chainSha256; committedRecords += pending.size; committedEncodedBytes += pendingBytes
        pending.clear(); pendingBytes = 0
    }

    @Synchronized fun append(encodedRecord: String): Int {
        check(!closed); offeredRecords++
        failure?.let { refuse(it, overflow) }
        if (acceptedRecords >= maxRecords) refuse("capacity", true)
        if (encodedRecord.contains('\n') || encodedRecord.contains('\r')) refuse("encoding failed")
        val recordBytes = encodedRecord.toByteArray(Charsets.UTF_8).size
        val prefix = "{\"sequence\":$acceptedRecords,\"record\":"
        val overhead = prefix.toByteArray(Charsets.UTF_8).size + 2
        val available = minOf(chunkBytes.toLong(), maxEncodedBytes - acceptedEncodedBytes) - overhead
        if (recordBytes > available) refuse("capacity", true)
        val parsed = try { Json.parseToJsonElement(encodedRecord) } catch (_: Throwable) { null }
        if (parsed !is JsonObject) refuse("encoding failed")
        val encoded = prefix + encodedRecord + "}\n"
        val bytes = overhead + recordBytes
        if (acceptedEncodedBytes + bytes > maxEncodedBytes || bytes > chunkBytes) refuse("capacity", true)
        if (pending.isNotEmpty() && (pending.size >= chunkRecords || pendingBytes + bytes > chunkBytes)) {
            if (chunks.size >= maxChunks) refuse("capacity", true)
            try { flush() } catch (_: Throwable) { droppedRecords++; overflow = true; throw IllegalStateException("Metadata spool write failed") }
        }
        if (chunks.size >= maxChunks && pending.isEmpty()) refuse("capacity", true)
        pending += encoded; pendingBytes += bytes; acceptedRecords++; acceptedEncodedBytes += bytes
        return acceptedRecords - 1
    }

    @Synchronized fun snapshot(): JsonObject = manifest(complete)

    @Synchronized fun close(): JsonObject {
        check(!closed); closed = true
        failure?.let { if (it != "capacity") throw IllegalStateException("Metadata spool $it") }
        flush(); complete = true
        val body = Json.encodeToString(JsonObject.serializer(), manifest(true))
        try {
            writer(directory, "manifest.json", body.toByteArray(Charsets.UTF_8))
            writer(directory, "COMMITTED", (sha256(body.toByteArray(Charsets.UTF_8)) + "\n").toByteArray(Charsets.UTF_8))
        } catch (_: Throwable) {
            complete = false; failure = "write failed"
            File(directory, "manifest.json").delete(); File(directory, "COMMITTED").delete()
            runCatching { syncDirectory(directory) }
            throw IllegalStateException("Metadata spool write failed")
        }
        return manifest(true)
    }

    @Synchronized fun chunk(index: Int): JsonObject {
        check(closed && complete && !overflow && failure == null && index in chunks.indices)
        val descriptor = chunks[index]
        val name = descriptor.getValue("file").jsonPrimitive.content
        val bytes = File(directory, name).readBytes()
        check(bytes.size == descriptor.getValue("bytes").jsonPrimitive.int &&
            sha256(bytes) == descriptor.getValue("sha256").jsonPrimitive.content)
        return buildJsonObject {
            put("index", index); put("body", bytes.toString(Charsets.UTF_8))
            put("sha256", descriptor.getValue("sha256")); put("chainSha256", descriptor.getValue("chainSha256"))
        }
    }

    @Synchronized fun delete() { check(closed); check(directory.deleteRecursively()) }

    companion object {
        private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
        private fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
        private fun chained(previous: String, body: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(previous.toByteArray(Charsets.UTF_8)); digest.update(0); return hex(digest.digest(body))
        }
        private fun privatePermissions(file: File, directory: Boolean) {
            check(file.setReadable(false, false) && file.setWritable(false, false) && file.setExecutable(false, false))
            check(file.setReadable(true, true) && file.setWritable(true, true))
            check(!directory || file.setExecutable(true, true))
        }
        private fun syncDirectory(directory: File) {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }
        private fun writeAtomic(directory: File, name: String, body: ByteArray) {
            val temporary = File(directory, ".$name.${UUID.randomUUID()}.tmp")
            val destination = File(directory, name)
            var published = false
            try {
                check(temporary.createNewFile())
                privatePermissions(temporary, false)
                FileOutputStream(temporary).use { it.write(body); it.fd.sync() }
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
                published = true; syncDirectory(directory)
            } catch (failure: Throwable) {
                temporary.delete(); if (published) { destination.delete(); runCatching { syncDirectory(directory) } }
                throw failure
            }
        }
    }
}
