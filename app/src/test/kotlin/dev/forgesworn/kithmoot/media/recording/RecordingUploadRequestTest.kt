package dev.forgesworn.kithmoot.media.recording

import dev.forgesworn.kithmoot.session.*
import dev.forgesworn.kithmoot.storage.RoomStorage
import dev.forgesworn.kithmoot.protocol.Events
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class RecordingUploadRequestTest {
    private class Store : RoomStorage {
        var bytes: ByteArray? = null
        var fail = false
        override fun read() = bytes?.clone()
        override fun write(value: ByteArray) { check(!fail); bytes = value.clone() }
        override fun reset() { error("Never reset pending cleanup") }
    }

    @Test fun `prepared Send rejects another Upload before any bytes and preserves the retained receipt`() {
        Rig().use { r ->
            var calls = 0
            val client = OkHttpClient.Builder().addInterceptor { chain -> calls++; r.response(chain.request()) }.build()
            RecordingUploadRequest(r.drafts, r.queue, r.draft.id, r.room, r.origin, { true }, client).use { it.upload() }
            val event = Events.sign(ByteArray(32) { 1 }, KIND_CHAT, r.time,
                listOf(listOf("d", "synthetic")), "synthetic ciphertext", ByteArray(32))
            val prepared = PreparedRoomChat(r.room, "aa".repeat(32), event.pubkey,
                PendingChatOutbox.Pending(r.room, event, editable = false, text = r.draft.sealed.name, messageId = "ab".repeat(16)))
            val retained = r.drafts.retainPreparedSend(r.draft.id, r.room, prepared)
            RecordingUploadRequest(r.drafts, r.queue, r.draft.id, r.room, r.origin, { true }, client).use { assertFails { it.upload() } }
            assertEquals(1, calls)
            assertEquals(retained, r.drafts.selected(r.draft.id, r.room))
            assertTrue(r.queue.readyToSend(r.room, r.origin, r.draft.sealed.hash))
        }
    }
    private class Rig : AutoCloseable {
        val root = Files.createTempDirectory("recording-upload-request-").toFile()
        val room = "ab".repeat(32)
        val origin = "https://private.example"
        var time = 100L
        val queueStore = Store()
        val drafts = RecordingShareDraftStore(File(root, "drafts"), Store()) { time }.apply { recover() }
        val queue = RecordingUploadJournal(queueStore) { time }.apply { recover(); identity(origin) }
        val draft: RecordingShareDraft
        init {
            val source = File(root, "call.mp4").apply { writeText("Synthetic transfer bytes, not a playable movie") }
            val ticket = drafts.begin(RecordingOrigin(room, "cd".repeat(16), "Synthetic original room"), source.name, null)
            draft = drafts.complete(ticket, sealFile(source, ticket.destination, source.name, "video/mp4"))
        }
        fun response(request: Request) = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(201)
            .message("Created").body("""{"url":"$origin/${draft.sealed.hash}","sha256":"${draft.sealed.hash}","size":${draft.sealed.file.length()}}""".toResponseBody()).build()
        override fun close() { root.deleteRecursively() }
    }

    @Test fun `construction is offline and explicit Upload retains receipt with cleanup before bytes`() {
        Rig().use { r ->
            var calls = 0
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                calls++
                assertTrue(r.queueStore.bytes!!.toString(Charsets.UTF_8).contains(r.draft.sealed.hash))
                assertFalse(r.queue.readyToSend(r.room, r.origin, r.draft.sealed.hash))
                assertEquals("PUT", chain.request().method)
                r.response(chain.request())
            }.build()
            RecordingUploadRequest(r.drafts, r.queue, r.draft.id, r.room, r.origin, { true }, client).use { request ->
                assertEquals(0, calls)
                val attachment = request.upload()
                assertEquals(attachment, r.drafts.selected(r.draft.id, r.room).uploaded)
                assertTrue(r.queue.readyToSend(r.room, r.origin, r.draft.sealed.hash))
                assertTrue(r.draft.sealed.file.exists())
                assertFails { request.upload() }
            }
            assertEquals(1, calls)
        }
    }

    @Test fun `Close cancels only its request and rejects a late upload response`() {
        Rig().use { r ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            var cancelled = false
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                entered.countDown(); check(release.await(10, TimeUnit.SECONDS))
                cancelled = chain.call().isCanceled()
                r.response(chain.request())
            }.build()
            val request = RecordingUploadRequest(r.drafts, r.queue, r.draft.id, r.room, r.origin, { true }, client)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val worker = executor.submit<Boolean> { try { request.upload(); false } catch (_: Exception) { true } }
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                request.close(); release.countDown()
                assertTrue(worker.get(10, TimeUnit.SECONDS)); assertTrue(cancelled)
                assertNull(r.drafts.selected(r.draft.id, r.room).uploaded)
                assertTrue(r.draft.sealed.file.exists())
                r.time += 91
                var cleanup = 0
                r.queue.retry { _, hash, _ -> assertEquals(r.draft.sealed.hash, hash); cleanup++; true }
                assertEquals(1, cleanup)
            } finally { release.countDown(); request.close(); executor.shutdownNow() }
        }
    }

    @Test fun `Forget during upload rejects receipt and cannot resurrect the draft`() {
        Rig().use { r ->
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                r.drafts.forgetRoom(r.room); r.queue.forgetRoom(r.room)
                r.response(chain.request())
            }.build()
            RecordingUploadRequest(r.drafts, r.queue, r.draft.id, r.room, r.origin, { true }, client).use { request ->
                assertFails { request.upload() }
            }
            assertTrue(r.drafts.list().isEmpty()); assertFalse(r.draft.sealed.file.exists())
            assertFalse(r.queue.readyToSend(r.room, r.origin, r.draft.sealed.hash))
            assertFalse(r.queueStore.bytes!!.toString(Charsets.UTF_8).contains(r.room))
            r.time += 91
            var cleanup = 0
            r.queue.retry { _, _, _ -> cleanup++; true }
            assertEquals(1, cleanup)
        }
    }

    @Test fun `failed receipt finalisation preserves ciphertext and cannot be sent`() {
        Rig().use { r ->
            val client = OkHttpClient.Builder().addInterceptor { chain -> r.queueStore.fail = true; r.response(chain.request()) }.build()
            RecordingUploadRequest(r.drafts, r.queue, r.draft.id, r.room, r.origin, { true }, client).use { request ->
                assertFails { request.upload() }
            }
            assertNull(r.drafts.selected(r.draft.id, r.room).uploaded)
            assertTrue(r.draft.sealed.file.exists())
            assertFalse(r.queue.readyToSend(r.room, r.origin, r.draft.sealed.hash))
            r.queueStore.fail = false
            r.queue.retry { _, _, _ -> error("Uncommitted finish still gets grace") }
            r.time += 91
            var cleanup = 0
            r.queue.retry { _, _, _ -> cleanup++; true }
            assertEquals(1, cleanup)
        }
    }
}
