package dev.forgesworn.kithmoot.session

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

const val MAX_CLEAN_ATTACHMENT_BYTES = 64L * 1024 * 1024

class AttachmentCleaningFailed : IllegalStateException(
    "This file could not be cleaned safely. Nothing was uploaded. Export a fresh PNG/JPEG, MP4/WebM, MP3/WAV, PDF or plain-text copy and try again.")

data class CleanedAttachment(val name: String, val type: String, val bytes: Long)

/** Writes a fresh cleaned container to [destination]. It must never copy the original as a fallback. */
fun interface AttachmentCleaner {
    suspend fun clean(source: File, destination: File, name: String, type: String?): CleanedAttachment
}

/** The common outgoing boundary for picker files and locally recorded media, before encryption or upload. */
suspend fun sealCleanedFile(source: File, destination: File, workDirectory: File, name: String, type: String?,
    cleaner: AttachmentCleaner, limit: Long = MAX_ATTACHMENT_SOURCE_BYTES): SealedFile = withContext(Dispatchers.IO) {
    val ceiling = minOf(limit, MAX_CLEAN_ATTACHMENT_BYTES)
    require(ceiling > 0)
    if (source.length() > ceiling) throw PickedFileTooLarge(ceiling)
    if (!source.isFile || source.length() == 0L) throw AttachmentCleaningFailed()
    check(!destination.exists()) { "The outgoing destination already exists." }
    val cleaned = File.createTempFile(".cleaned-", ".source", workDirectory)
    try {
        val result = cleaner.clean(source, cleaned, name, type)
        val size = cleaned.length()
        if (size !in 1..ceiling || result.bytes != size) throw AttachmentCleaningFailed()
        runInterruptible { cleaned.inputStream().use { sealAttachment(it, size, destination, result.name, result.type) } }
    } finally { cleaned.delete() }
}

/** Spool only to app-private storage. Provider size/type/name never bypass cleaning or the byte limit. */
suspend fun sealPrivatePickedFile(open: () -> InputStream, declaredSize: Long?, workDirectory: File, name: String, type: String?,
    cleaner: AttachmentCleaner, limit: Long = MAX_ATTACHMENT_SOURCE_BYTES): SealedFile = withContext(Dispatchers.IO) {
    require(workDirectory.isDirectory || workDirectory.mkdirs()) { "Private file storage is unavailable." }
    val ceiling = minOf(limit, MAX_CLEAN_ATTACHMENT_BYTES)
    require(ceiling > 0)
    if (declaredSize != null && declaredSize > ceiling) throw PickedFileTooLarge(ceiling)
    val source = File.createTempFile(".privacy-", ".source", workDirectory)
    val destination = File.createTempFile(".outgoing-", ".wbenc", workDirectory).also { check(it.delete()) }
    try {
        runInterruptible {
            val buffer = ByteArray(64 * 1024)
            try {
                var copied = 0L
                open().use { input -> source.outputStream().use { output ->
                    while (true) {
                        check(!Thread.currentThread().isInterrupted) { "File operation cancelled" }
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), ceiling - copied + 1).toInt())
                        if (count < 0) break
                        copied += count
                        if (copied > ceiling) throw PickedFileTooLarge(ceiling)
                        output.write(buffer, 0, count)
                    }
                } }
                if (copied == 0L) throw AttachmentCleaningFailed()
                require(declaredSize == null || declaredSize <= 0 || declaredSize == copied) { "The file changed while preparing it." }
            } finally { buffer.fill(0) }
        }
        sealCleanedFile(source, destination, workDirectory, name, type, cleaner, limit)
    } finally { source.delete() }
}
