package dev.forgesworn.kithmoot.media.recording

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*

enum class RecordingFormat(val extension: String, val mime: String) {
    AUDIO("m4a", "audio/mp4"), VIDEO("mp4", "video/mp4")
}

data class RecordingExportDetails(
    val format: RecordingFormat,
    val origin: RecordingOrigin? = null,
    /** Only unsaved private exports are subject to their room's destruction. */
    val discardAt: Long? = null,
    val revoked: Boolean = false,
) {
    init { require(discardAt == null || (origin != null && discardAt > 0)) }
    fun unavailable(at: Long) = revoked || (discardAt != null && at >= discardAt)
}

/** Application-owned capture and one unresolved local export. Metadata survives
 * process death and binds a future explicit share to its originating call.
 * Destruction revokes a finalising capture before its worker can retain it. */
class LocalRecordingStore(private val directory: File, private val now: () -> Long = { System.currentTimeMillis() / 1000 }) {
    private var active: File? = null
    private val revokedRooms = mutableSetOf<String>()
    private val mutableExport = MutableStateFlow<File?>(null)
    val export = mutableExport.asStateFlow()

    @Synchronized fun recover(): File? {
        check(active == null)
        directory.listFiles()?.filter { it.name.endsWith(".capture") }?.forEach { removeCapture(it) }
        directory.listFiles()?.filter { it.isDirectory && it.name.endsWith(".capture.parts") }?.forEach { it.deleteRecursively() }
        directory.listFiles()?.filter { it.name.endsWith(".metadata.tmp") }?.forEach { it.delete() }
        directory.listFiles()?.filter { it.name.endsWith(".metadata") }?.forEach { metadata ->
            val stem = metadata.name.removeSuffix(".metadata")
            if (RecordingFormat.entries.none { File(directory, "$stem.${it.extension}").isFile }) metadata.delete()
        }
        return pending().also { mutableExport.value = it }
    }

    @Synchronized fun pending(): File? {
        val files = completedFiles()
        for (file in files) {
            val details = runCatching { readDetails(file) }.getOrNull()
            if (details == null || unavailable(details)) {
                // Never show an expired or corrupt export, even when storage
                // prevents deletion. begin also refuses unresolved leftovers.
                removeCompleted(file)
                continue
            }
            return file
        }
        return null
    }

    @Synchronized fun details(file: File): RecordingExportDetails {
        requireOwned(file)
        check(file.isFile) { "The recording is no longer available" }
        val value = readDetails(file)
        check(!unavailable(value)) { "The originating room's recording is no longer available" }
        return value
    }

    /** Resolve the export chosen before a document picker opened. A later
     * recording must never inherit that earlier Save authorisation. */
    @Synchronized fun selectedExport(name: String): File {
        require(name.isNotEmpty() && name == File(name).name)
        val file = File(directory, name)
        check(pending() == file) { "The original recording is no longer available" }
        details(file)
        return file
    }

    /** Only the short final draft commit belongs here. Encryption and network
     * I/O happen outside this lock. Forget cannot revoke the source between
     * this check and retaining its independently encrypted chat draft. */
    @Synchronized fun <T> withSelectedExport(name: String, commit: (File, RecordingExportDetails) -> T): T {
        val file = selectedExport(name)
        return commit(file, details(file))
    }

    @Synchronized fun begin(format: RecordingFormat = RecordingFormat.AUDIO, origin: RecordingOrigin? = null, discardAt: Long? = null): File {
        check(active == null && pending() == null) { "Save or discard the previous recording first" }
        check(directory.isDirectory || directory.mkdirs()) { "Recording storage is unavailable" }
        check(completedFiles().isEmpty() && directory.listFiles()?.none {
            it.name.endsWith(".capture") || it.name.endsWith(".capture.parts")
        } == true) { "An unfinished recording could not be removed" }
        val source = File(directory, "call-${UUID.randomUUID()}.capture")
        val value = RecordingExportDetails(format, origin, discardAt)
        check(!unavailable(value)) { "The originating room has ended" }
        writeDetails(source, value)
        active = source
        return source
    }

    @Synchronized fun complete(source: File): File {
        check(source == active && source.isFile && source.length() > 0) { "Recording export is incomplete" }
        val value = readDetails(source)
        if (unavailable(value)) {
            removeCapture(source)
            // Keep ownership if cleanup failed, so another start cannot hide it.
            if (!source.exists() && !parts(source).exists()) active = null
            throw IllegalStateException("The originating room ended; its unsaved recording was discarded")
        }
        val destination = File(directory, stem(source) + "." + value.format.extension)
        check(!destination.exists() && source.renameTo(destination)) { "Recording export could not be retained" }
        active = null
        mutableExport.value = destination
        return destination
    }

    @Synchronized fun abandon(source: File) {
        check(source == active)
        check(removeCapture(source)) { "The unfinished recording could not be removed" }
        active = null
    }

    @Synchronized fun discard(file: File) {
        requireOwned(file)
        check(RecordingFormat.entries.any { file.name.endsWith(".${it.extension}") })
        check(removeCompleted(file)) { "The recording could not be removed" }
        mutableExport.value = pending()
    }

    /** Revoke under the same lock as complete. The capture worker still owns
     * its open container and must finish/discard it; it cannot resurrect an
     * export after this call, including after a process restart. */
    @Synchronized fun forgetRoom(room: String) {
        require(room.matches(Regex("[0-9a-f]{64}")))
        // Revoke in memory even if a later metadata write or unlink fails.
        revokedRooms += room
        active?.let { source ->
            val value = readDetails(source)
            if (value.origin?.room == room) writeDetails(source, value.copy(revoked = true))
        }
        for (file in completedFiles()) {
            val value = runCatching { readDetails(file) }.getOrNull() ?: continue
            if (value.origin?.room == room) {
                writeDetails(file, value.copy(revoked = true))
                check(removeCompleted(file)) { "The room's unsaved recording could not be removed" }
            }
        }
        mutableExport.value = pending()
    }

    private fun completedFiles() = directory.listFiles()?.filter { file ->
        file.isFile && RecordingFormat.entries.any { file.name.endsWith(".${it.extension}") }
    }?.sortedBy { it.name } ?: emptyList()

    private fun unavailable(value: RecordingExportDetails) =
        value.unavailable(now()) || value.origin?.room in revokedRooms

    private fun requireOwned(file: File) { require(file.parentFile == directory && file.name == File(file.name).name) }
    private fun stem(file: File) = file.name.substringBeforeLast('.')
    private fun metadata(file: File) = File(directory, stem(file) + ".metadata")
    private fun parts(file: File) = File(directory, file.name + ".parts")

    private fun removeCapture(file: File): Boolean {
        val removed = (!file.exists() || file.delete()) && (!parts(file).exists() || parts(file).deleteRecursively())
        return removed && (!metadata(file).exists() || metadata(file).delete())
    }

    private fun removeCompleted(file: File): Boolean =
        (!file.exists() || file.delete()) && (!metadata(file).exists() || metadata(file).delete())

    private fun readDetails(file: File): RecordingExportDetails {
        val metadata = metadata(file)
        if (!metadata.exists()) {
            // Earlier unreleased audio exports remain locally saveable. Their
            // absent origin cannot be inferred from whichever room is open.
            check(file.name.endsWith(".m4a")) { "Recording metadata is unavailable" }
            return RecordingExportDetails(RecordingFormat.AUDIO)
        }
        check(metadata.isFile && metadata.length() in 2..4096) { "Recording metadata is invalid" }
        val body = Json.parseToJsonElement(metadata.readText()).jsonObject
        check(body["version"]?.jsonPrimitive?.int == 1)
        val format = RecordingFormat.valueOf(body.getValue("format").jsonPrimitive.content)
        check(file.name.endsWith(".capture") || file.name.endsWith(".${format.extension}"))
        val origin = body["origin"]?.jsonObject?.let { value -> RecordingOrigin(
            value.getValue("room").jsonPrimitive.content, value.getValue("call").jsonPrimitive.content,
            value.getValue("name").jsonPrimitive.content) }
        return RecordingExportDetails(format, origin, body["discardAt"]?.jsonPrimitive?.long, body.getValue("revoked").jsonPrimitive.boolean)
    }

    private fun writeDetails(file: File, value: RecordingExportDetails) {
        val body = buildJsonObject {
            put("version", 1); put("format", value.format.name); put("revoked", value.revoked)
            value.discardAt?.let { put("discardAt", it) }
            value.origin?.let { origin -> put("origin", buildJsonObject {
                put("room", origin.room); put("call", origin.call); put("name", origin.name)
            }) }
        }.toString()
        val destination = metadata(file)
        val temporary = File(directory, destination.name + ".tmp")
        check(temporary.createNewFile()) { "Recording metadata is busy" }
        try {
            temporary.outputStream().use { output -> output.write(body.toByteArray()); output.fd.sync() }
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
}
