package dev.forgesworn.kithmoot.session

import okhttp3.Call
import okhttp3.Dispatcher
import okhttp3.EventListener
import okhttp3.OkHttpClient
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

private val recordingPlaybackTypes = setOf("audio/mp4", "video/mp4", "audio/wav", "audio/webm", "video/webm", "audio/ogg")

/** Codec parameters from the desktop recorder stay authenticated in metadata;
 * the base media type chooses this viewer, while the native player reads bytes. */
fun recordingPlaybackMime(type: String?): String? = type?.substringBefore(';')?.trim()?.lowercase()
    ?.takeIf { it in recordingPlaybackTypes }

private val playbackHttp = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
    .retryOnConnectionFailure(false).callTimeout(10, TimeUnit.MINUTES).build()

/** Created only after Show. Its private cache and calls belong to one viewer.
 * Closing cancels an active request and also rejects a late decrypted result. */
class RecordingAttachmentRequest(
    private val attachment: ChatAttachment,
    private val cache: File,
    client: OkHttpClient = playbackHttp,
) : Closeable {
    private val lock = Any()
    private var started = false
    private var finished = false
    private var closed = false
    private val directory: File
    private val http = client.newBuilder().dispatcher(Dispatcher()).eventListener(object : EventListener() {
        override fun callStart(call: Call) {
            if (synchronized(lock) { closed }) call.cancel()
        }
    }).build()

    init {
        check(cache.isDirectory || cache.mkdirs()) { "Private recording storage is unavailable" }
        directory = Files.createTempDirectory(cache.toPath(), "viewer-").toFile()
    }

    fun download(): OpenedFile {
        synchronized(lock) {
            check(!closed && !started) { "This recording viewer has closed" }
            started = true
        }
        var complete = false
        try {
            val result = fetchFileAttachment(attachment, File(directory, "recording"), http)
            require(recordingPlaybackMime(result.type) != null) { "This attachment is not a supported recording" }
            synchronized(lock) {
                check(!closed) { "This recording viewer has closed" }
                complete = true
            }
            return result
        } finally {
            val remove = synchronized(lock) { finished = true; !complete || closed }
            if (remove) directory.deleteRecursively()
        }
    }

    override fun close() {
        val remove = synchronized(lock) { closed = true; !started || finished }
        http.dispatcher.cancelAll()
        if (remove) directory.deleteRecursively()
    }
}
