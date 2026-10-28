package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.AttachmentDecision
import dev.forgesworn.kithmoot.protocol.AttachmentInput
import dev.forgesworn.kithmoot.protocol.AttachmentSafety
import okhttp3.OkHttpClient
import java.io.File
import java.io.InputStream
import java.text.Normalizer
import java.util.concurrent.TimeUnit

/** How a share is shown before anything is fetched, decided from the
 * sender's hints alone. The envelope's own metadata is checked again after
 * it is opened; a hint never decides whether a file may be opened.
 * [DOCUMENT] is the encrypted text document a long paste becomes, read in the
 * viewer as before; any other file takes the save-or-open dialog. */
enum class AttachmentPresentation { IMAGE, DOCUMENT, RECORDING, FILE }

private val viewerImageTypes = setOf("image/gif", "image/png", "image/jpeg", "image/webp")
private val viewerImageExtensions = setOf("gif", "png", "jpg", "jpeg", "webp")

fun attachmentPresentation(attachment: ChatAttachment): AttachmentPresentation {
    if (recordingPlaybackMime(attachment.type) != null) return AttachmentPresentation.RECORDING
    // Both open in the in-app viewer, which holds a file in memory: a larger one is a file to save.
    val fits = attachment.size == null || attachment.size <= MAX_IMAGE_ENVELOPE_BYTES
    if (isTextDocument(attachment)) return if (fits) AttachmentPresentation.DOCUMENT else AttachmentPresentation.FILE
    val image = attachment.type?.let(::baseMediaType)?.let { it in viewerImageTypes }
        ?: (attachment.name?.substringAfterLast('.', "")?.lowercase() in viewerImageExtensions)
    return if (image && fits) AttachmentPresentation.IMAGE else AttachmentPresentation.FILE
}

/**
 * A long paste's document. The reference carries no type hint for it
 * (`text/plain;charset=utf-8` is dropped on the way in and out), so a web
 * sender's arrives typeless and is known by the name the web client gives it.
 */
fun isTextDocument(attachment: ChatAttachment): Boolean =
    isTextDocumentType(attachment.type) || (attachment.type == null && isLongChatDocumentName(attachment.name))

/**
 * The same classification the image viewer applies, for a received file once
 * it is downloaded and authenticated: the first [AttachmentSafety.SNIFF_BYTES]
 * of the plaintext are sniffed, and the name and type are read from the
 * envelope, the message's own name being compared with it. The classifier
 * cleans the name itself (controls, bidi overrides and invisibles), exactly as
 * [localFileName] does for the disk, and reports that it had to. A blocked
 * decision is neither saved nor opened; a warned one needs the person's say-so.
 */
fun classifyReceivedFile(file: OpenedFile, messageName: String?, sender: String? = null): AttachmentDecision {
    val head = ByteArray(AttachmentSafety.SNIFF_BYTES)
    val count = file.file.inputStream().use { input ->
        var read = 0
        while (read < head.size) { val n = input.read(head, read, head.size - read); if (n < 0) break; read += n }
        read
    }
    return AttachmentSafety.classifyAttachment(AttachmentInput(head.copyOf(count), file.name, file.type, messageName, sender))
}

/** `text/plain;charset=utf-8` becomes `text/plain`. */
fun baseMediaType(type: String): String = type.substringBefore(';').trim().lowercase()

private val packageExtensions = setOf("apk", "apks", "apkm", "xapk")

/**
 * An Android package is saved, never handed to another app from a chat:
 * Open with would put an installer one tap away from a stranger's file.
 * The check runs on [localFileName], the name the file is saved and offered
 * under, never on the raw envelope name: an invisible character inside the
 * extension (`app.a<ZWJ>pk`) is stripped on the way to disk, so a guard that
 * read the raw name would let it through and save `app.apk`.
 */
fun isAndroidPackage(name: String?, type: String?): Boolean =
    type?.let(::baseMediaType) == "application/vnd.android.package-archive" ||
        localFileName(name).trimEnd('.', ' ').substringAfterLast('.', "").lowercase() in packageExtensions

/**
 * The type another app is asked to open: the envelope's base type, or the
 * extension's when the envelope only says octet-stream. The extension is read
 * from [localFileName], as the file is named on disk. [mimeOf] maps an
 * extension to a type (the platform's table in the app).
 */
fun viewType(name: String?, type: String, mimeOf: (String) -> String?): String {
    val base = baseMediaType(type)
    if (base != "application/octet-stream") return base
    val extension = localFileName(name).substringAfterLast('.', "").lowercase().takeIf { it.isNotEmpty() }
    return extension?.let(mimeOf) ?: base
}

/** A name safe to put on disk or offer to a document provider: no path, no
 * controls, bidi overrides or invisibles that could disguise an extension,
 * no leading dots, and short enough for a 255-byte file system name. */
fun localFileName(name: String?): String {
    val leaf = name.orEmpty().split('/', '\\').last()
    val visible = buildString {
        for (point in Normalizer.normalize(leaf, Normalizer.Form.NFC).codePoints().toArray()) {
            val category = Character.getType(point).toByte()
            if (category == Character.CONTROL || category == Character.FORMAT || category == Character.SURROGATE ||
                category == Character.PRIVATE_USE || category == Character.UNASSIGNED) continue
            appendCodePoint(if (Character.isWhitespace(point) || Character.isSpaceChar(point)) ' '.code else point)
        }
    }
    val cleaned = visible.replace(Regex("[<>:\"|?*]"), "_").replace(Regex(" {2,}"), " ")
        .trimStart { it == '.' || it == ' ' }.trimEnd().ifEmpty { "file" }
    if (cleaned.toByteArray().size <= 200) return cleaned
    val dot = cleaned.lastIndexOf('.')
    val extension = if (dot > 0 && cleaned.length - dot <= 16) cleaned.substring(dot) else ""
    var stem = cleaned.removeSuffix(extension)
    while (stem.isNotEmpty() && (stem + extension).toByteArray().size > 200) {
        stem = stem.dropLast(if (stem.length >= 2 && stem[stem.length - 1].isLowSurrogate() && stem[stem.length - 2].isHighSurrogate()) 2 else 1)
    }
    return (stem.trimEnd() + extension).ifEmpty { "file" }
}

/** As the web card writes a size: bytes, whole KB, then MB to one place. */
fun formatAttachmentBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${Math.round(bytes / 1024.0)} KB"
    else -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
}

/** A 256 MiB file can take longer than a recording's ten minutes on a phone
 * uplink. The idle read and write limits still end a stalled transfer. */
internal val fileTransferHttp: OkHttpClient = recordingTransferClient().newBuilder().callTimeout(30, TimeUnit.MINUTES).build()

/**
 * Seal a file the person picked. A provider that states its size is
 * streamed straight into the envelope; one that does not is first copied to
 * a private spool (a stated size of 0 is treated as unknown), stopping one
 * byte past the limit, so an oversized file is refused without reading all
 * of it. Every temporary file is removed;
 * the sealed envelope in [workDirectory] belongs to the caller.
 */
fun sealPickedFile(open: () -> InputStream, declaredSize: Long?, workDirectory: File, name: String, type: String?,
    limit: Long = MAX_ATTACHMENT_SOURCE_BYTES): SealedFile {
    require(workDirectory.isDirectory || workDirectory.mkdirs()) { "Private file storage is unavailable." }
    require(limit in 1..MAX_ATTACHMENT_SOURCE_BYTES)
    if (declaredSize != null && declaredSize > limit) error("Choose a file up to 256 MiB.")
    val destination = File.createTempFile(".outgoing-", ".wbenc", workDirectory).also { it.delete() }
    if (declaredSize != null && declaredSize > 0) return open().use { sealAttachment(it, declaredSize, destination, name, type) }
    val spool = File.createTempFile(".outgoing-", ".source", workDirectory)
    try {
        var copied = 0L
        val buffer = ByteArray(64 * 1024)
        try {
            open().use { input -> spool.outputStream().use { output ->
                while (true) {
                    check(!Thread.currentThread().isInterrupted) { "File operation cancelled" }
                    val count = input.read(buffer)
                    if (count < 0) break
                    copied += count
                    if (copied > limit) error("Choose a file up to 256 MiB.")
                    output.write(buffer, 0, count)
                }
            } }
        } finally { buffer.fill(0) }
        if (copied == 0L) error("This file is empty.")
        return spool.inputStream().use { sealAttachment(it, copied, destination, name, type) }
    } finally { spool.delete() }
}

/** Decrypted files handed to another app outlive their dialog; anything in
 * [root] older than this is removed the next time a file is opened. */
private const val OPENED_FILE_LIFETIME_MILLIS = 3_600_000L

internal fun sweepOpenedFiles(root: File, now: Long = System.currentTimeMillis()) {
    root.listFiles()?.filter { now - it.lastModified() > OPENED_FILE_LIFETIME_MILLIS }?.forEach { it.deleteRecursively() }
}

/**
 * One received file, fetched only when the person chooses Save or Open with.
 * The ciphertext and the plaintext stream to private files under [root],
 * every record authenticates before the file appears, and it then takes the
 * envelope's own (made safe) name. Closing removes it unless [keep] said it
 * was handed to another app, which leaves it to [sweepOpenedFiles].
 */
class FileAttachmentRequest(
    private val attachment: ChatAttachment,
    root: File,
    client: OkHttpClient = fileTransferHttp,
    /** Reads a share held on the room's own storage; null for any other share. */
    private val groupSource: GroupFileSource? = null,
) : java.io.Closeable {
    private val lock = Any()
    private var running = false
    private var closed = false
    private var kept = false
    private var result: OpenedFile? = null
    private val directory: File
    private val http = client.newBuilder().dispatcher(okhttp3.Dispatcher()).eventListener(object : okhttp3.EventListener() {
        override fun callStart(call: okhttp3.Call) { if (synchronized(lock) { closed }) call.cancel() }
    }).build()

    init {
        check(root.isDirectory || root.mkdirs()) { "Private file storage is unavailable" }
        sweepOpenedFiles(root)
        directory = java.nio.file.Files.createTempDirectory(root.toPath(), "file-").toFile()
    }

    /** The authenticated file. Fetched once; a failure leaves nothing behind
     * and may be tried again. */
    fun download(): OpenedFile {
        synchronized(lock) {
            check(!closed) { "This file has been closed" }
            result?.let { return it }
            check(!running) { "This file is already downloading" }
            running = true
        }
        try {
            val decrypted = fetchFileAttachment(attachment, File(directory, ".download"), http, groupSource)
            val named = File(directory, localFileName(decrypted.name))
            check(decrypted.file.renameTo(named)) { "Could not finish the decrypted file" }
            val opened = decrypted.copy(file = named)
            synchronized(lock) {
                check(!closed) { "This file has been closed" }
                result = opened
            }
            return opened
        } finally {
            val remove = synchronized(lock) {
                running = false
                result == null || (closed && !kept)
            }
            if (remove) {
                if (synchronized(lock) { closed }) directory.deleteRecursively()
                else directory.listFiles()?.forEach { it.deleteRecursively() }
            }
        }
    }

    /** The decrypted file now belongs to another app's read grant. */
    fun keep() = synchronized(lock) { kept = true }

    override fun close() {
        val remove = synchronized(lock) { closed = true; !running && !kept }
        http.dispatcher.cancelAll()
        if (remove) directory.deleteRecursively()
    }
}
