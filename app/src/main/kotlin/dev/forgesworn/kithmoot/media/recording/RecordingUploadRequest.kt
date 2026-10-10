package dev.forgesworn.kithmoot.media.recording

import dev.forgesworn.kithmoot.session.ChatAttachment
import dev.forgesworn.kithmoot.session.mediaStorageOrigin
import dev.forgesworn.kithmoot.session.uploadFileMedia
import okhttp3.Call
import okhttp3.Dispatcher
import okhttp3.EventListener
import okhttp3.OkHttpClient
import java.io.Closeable
import java.util.concurrent.TimeUnit

private val uploadHttp = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
    .retryOnConnectionFailure(false).callTimeout(10, TimeUnit.MINUTES).build()

/** One explicit Upload, tied to its original draft/room/server. Constructing it
 * makes no request. Close cancels only its calls; cleanup is retained after the
 * PUT worker actually stops, even when cancellation races a late response. */
class RecordingUploadRequest(
    private val drafts: RecordingShareDraftStore,
    private val journal: RecordingUploadJournal,
    private val id: String,
    private val room: String,
    private val origin: String,
    private val permitted: () -> Boolean,
    client: OkHttpClient = uploadHttp,
) : Closeable {
    private val lock = Any()
    private var closed = false
    private var started = false
    private val http = client.newBuilder().dispatcher(Dispatcher()).eventListener(object : EventListener() {
        override fun callStart(call: Call) { if (synchronized(lock) { closed }) call.cancel() }
    }).build()

    init { require(mediaStorageOrigin(origin) == origin) }

    fun upload(): ChatAttachment {
        synchronized(lock) { check(!closed && !started && permitted()); started = true }
        var ticket: RecordingUploadTicket? = null
        var receipt: ChatAttachment? = null
        var hash: String? = null
        var failure: Exception? = null
        var finishing = false
        var complete = false
        try {
            val draft = drafts.bindOrigin(id, room, origin)
            hash = draft.sealed.hash
            synchronized(lock) { check(!closed && permitted()) }
            val owned = journal.begin(room, origin, draft.sealed.hash, draft.discardAt).also { ticket = it }
            val incoming = uploadFileMedia(draft.sealed, origin, journal.uploadAuthorisation(owned), http)
            synchronized(lock) {
                check(!closed && permitted()) { "The original recording room or storage permission changed during upload" }
                receipt = incoming
                drafts.retainUpload(id, room, origin, incoming)
                finishing = true
                check(journal.finish(owned, true)) { "The recording upload was revoked while retaining its receipt" }
                check(permitted()) { "The original recording room or storage permission changed while retaining the upload" }
                complete = true
            }
            return incoming
        } catch (error: Exception) { failure = error; throw error }
        finally {
            if (!complete) {
                // Receipt rollback cannot remove the independently retained
                // ciphertext. A failed cleanup commit stays pending on disk.
                fun cleanup(action: () -> Unit) {
                    try { action() } catch (error: Exception) {
                        val original = failure
                        if (original != null) original.addSuppressed(error) else throw error
                    }
                }
                receipt?.let { cleanup { drafts.clearUpload(id, room, it) } }
                ticket?.let { owned ->
                    cleanup {
                        if (!finishing) journal.finish(owned, false)
                        else journal.discard(origin, requireNotNull(hash))
                    }
                }
            }
        }
    }

    override fun close() {
        synchronized(lock) { closed = true }
        http.dispatcher.cancelAll()
    }
}
