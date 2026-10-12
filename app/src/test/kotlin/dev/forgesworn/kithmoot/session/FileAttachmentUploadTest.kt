package dev.forgesworn.kithmoot.session

import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.HashingSink
import okio.blackholeSink
import okio.buffer
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class FileAttachmentUploadTest {
    @Test fun `explicit file fetch checks integrity size and cleans every failed temporary file`() {
        val directory = Files.createTempDirectory("recording-fetch-").toFile()
        try {
            val source = File(directory, "call.wav").also { it.writeBytes(ByteArray(1_100_000) { 73 }) }
            val sealed = sealFile(source, File(directory, "call.enc"), source.name, "audio/wav")
            val attachment = ChatAttachment("https://storage.example/${sealed.hash}", sealed.hash, sealed.key, size = sealed.file.length())
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(sealed.file.readBytes().toResponseBody()).build()
            }.build()
            val opened = fetchFileAttachment(attachment, File(directory, "opened.wav"), client)
            assertEquals(fileSha256(source), fileSha256(opened.file))
            assertFails { fetchFileAttachment(attachment.copy(sha256 = "00".repeat(32)), File(directory, "bad-hash"), client) }
            assertFails { fetchFileAttachment(attachment.copy(size = sealed.file.length() - 1), File(directory, "bad-size"), client) }
            assertEquals(setOf("call.wav", "call.enc", "opened.wav"), directory.listFiles()!!.map { it.name }.toSet())
        } finally { directory.deleteRecursively() }
    }

    @Test fun `recording upload streams the ciphertext and validates the exact storage receipt`() {
        val directory = Files.createTempDirectory("recording-upload-").toFile()
        try {
            val source = File(directory, "call.mp4")
            source.outputStream().use { output -> repeat(10) { output.write(ByteArray(1_048_576) { 73 }) } }
            val sealed = sealFile(source, File(directory, "call.enc"), source.name, "video/mp4")
            val origin = "https://storage.example"
            val auth = mediaAuthorisation("upload", sealed.hash, origin, ByteArray(32).also { it[31] = 1 }, 1000, 1060)
            var contacted = 0
            var wrongReceipt = false
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                contacted++
                val request = chain.request()
                assertEquals("$origin/upload", request.url.toString())
                assertEquals("PUT", request.method)
                assertEquals(sealed.hash, request.header("X-SHA-256"))
                assertTrue(request.header("Authorization")!!.startsWith("Nostr "))
                val body = requireNotNull(request.body)
                assertEquals(sealed.file.length(), body.contentLength())
                assertTrue(body.isOneShot())
                val hashing = HashingSink.sha256(blackholeSink())
                hashing.buffer().use { sink -> body.writeTo(sink) }
                assertEquals(sealed.hash, hashing.hash.hex())
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(201).message("Created")
                    .body("""{"url":"$origin/${sealed.hash}","sha256":"${if (wrongReceipt) "00".repeat(32) else sealed.hash}","size":${sealed.file.length()}}""".toResponseBody()).build()
            }.build()
            val attachment = uploadFileMedia(sealed, origin, auth, client)
            assertEquals(sealed.hash, attachment.sha256)
            assertEquals(sealed.key, attachment.key)
            assertEquals(sealed.file.length(), attachment.size)
            assertEquals(1, contacted)
            wrongReceipt = true
            assertFails { uploadFileMedia(sealed, origin, auth, client) }
            assertTrue(sealed.file.exists(), "Failed sharing must retain the local recording")
            assertFails { uploadFileMedia(sealed, "http://storage.example", auth, client) }
            assertEquals(2, contacted)
        } finally { directory.deleteRecursively() }
    }
}
