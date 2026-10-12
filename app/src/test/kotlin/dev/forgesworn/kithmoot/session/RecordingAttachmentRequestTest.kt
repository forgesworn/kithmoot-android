package dev.forgesworn.kithmoot.session

import okhttp3.*
import okio.BufferedSource
import okio.buffer
import okio.source
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class RecordingAttachmentRequestTest {
    private fun response(request: Request, file: File): Response = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
        .body(object : ResponseBody() {
            private val input = file.source().buffer()
            override fun contentType(): MediaType? = null
            override fun contentLength() = file.length()
            override fun source(): BufferedSource = input
        }).build()

    @Test fun `Show streams a recording larger than the image limit and Close removes plaintext`() {
        val root = Files.createTempDirectory("recording-viewer-").toFile()
        try {
            val source = File(root, "call.mp4")
            source.outputStream().use { output -> repeat(34) { output.write(ByteArray(1024 * 1024) { 73 }) } }
            val sealed = sealFile(source, File(root, "call.enc"), source.name, "video/mp4")
            val attachment = ChatAttachment("https://private.example/${sealed.hash}", sealed.hash, sealed.key, type = "video/mp4", size = sealed.file.length())
            val contacted = AtomicInteger()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                contacted.incrementAndGet()
                assertEquals("GET", chain.request().method)
                assertNull(chain.request().header("Authorization"))
                assertNull(chain.request().header("Cookie"))
                response(chain.request(), sealed.file)
            }.build()
            val cache = File(root, "private-cache")
            val request = RecordingAttachmentRequest(attachment, cache, client)
            assertEquals(0, contacted.get(), "Constructing the viewer must not fetch bytes")
            val result = request.download()
            assertEquals(fileSha256(source), fileSha256(result.file))
            assertEquals("video/mp4", result.type)
            assertEquals(1, contacted.get())
            request.close()
            assertFalse(result.file.exists())
            assertTrue(cache.listFiles()!!.isEmpty())
            assertFails { request.download() }
            assertEquals(1, contacted.get())
        } finally { root.deleteRecursively() }
    }

    @Test fun `Close during a response cannot resurrect a decrypted file`() {
        val root = Files.createTempDirectory("recording-viewer-close-").toFile()
        val executor = Executors.newSingleThreadExecutor()
        val release = CountDownLatch(1)
        try {
            val source = File(root, "call.m4a").apply { writeText("synthetic audio payload") }
            val sealed = sealFile(source, File(root, "call.enc"), source.name, "audio/mp4")
            val attachment = ChatAttachment("https://private.example/${sealed.hash}", sealed.hash, sealed.key)
            val entered = CountDownLatch(1)
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                response(chain.request(), sealed.file)
            }.build()
            val cache = File(root, "private-cache")
            val request = RecordingAttachmentRequest(attachment, cache, client)
            val result = executor.submit<OpenedFile> { request.download() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            request.close()
            release.countDown()
            assertFails { result.get(5, TimeUnit.SECONDS) }
            assertTrue(cache.listFiles()!!.isEmpty(), "A closed viewer must discard even a late successful response")
        } finally { release.countDown(); executor.shutdownNow(); root.deleteRecursively() }
    }

    @Test fun `Authenticated metadata decides the player type rather than the message hint`() {
        val root = Files.createTempDirectory("recording-viewer-type-").toFile()
        try {
            val source = File(root, "image.png").apply { writeText("synthetic image payload") }
            val sealed = sealMedia(source.readBytes(), source.name, "image/png")
            val envelope = File(root, "image.enc").apply { writeBytes(sealed.envelope) }
            val attachment = ChatAttachment("https://private.example/${sealed.hash}", sealed.hash, sealed.key, type = "video/mp4")
            val client = OkHttpClient.Builder().addInterceptor { chain -> response(chain.request(), envelope) }.build()
            val cache = File(root, "private-cache")
            RecordingAttachmentRequest(attachment, cache, client).use { request ->
                assertFailsWith<IllegalArgumentException> { request.download() }
            }
            assertTrue(cache.listFiles()!!.isEmpty())
        } finally { root.deleteRecursively() }
    }
}
