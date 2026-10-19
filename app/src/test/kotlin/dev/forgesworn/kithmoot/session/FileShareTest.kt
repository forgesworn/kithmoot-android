package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.NostrEvent
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class FileShareTest {
    private fun temporary(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("file-share-").toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }

    private fun serving(bytes: ByteArray, onRequest: () -> Unit = {}) = OkHttpClient.Builder().addInterceptor { chain ->
        onRequest()
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(bytes.toResponseBody()).build()
    }.build()

    private fun share(sealed: SealedFile) = ChatAttachment("https://files.example/${sealed.hash}", sealed.hash, sealed.key,
        sealed.name, sealed.type, sealed.file.length())

    @Test fun `any media type seals in the reference shape and opens through the streaming reader`() = temporary { directory ->
        for ((type, stored) in listOf("application/pdf" to "application/pdf", "APPLICATION/ZIP" to "application/zip",
            null to "application/octet-stream", "" to "application/octet-stream", "text/plain;charset=utf-8" to "text/plain;charset=utf-8",
            "bad\u0000type" to "application/octet-stream", "application/vnd.android.package-archive" to "application/vnd.android.package-archive")) {
            val source = ByteArray(5_000) { (it * 7).toByte() }
            val sealed = sealAttachment(ByteArrayInputStream(source), source.size.toLong(), File.createTempFile("sealed-", ".enc", directory).also { it.delete() }, "../report.bin", type)
            assertEquals(stored, sealed.type)
            assertEquals("report.bin", sealed.name)
            assertEquals(56L + 65_536 + 16, sealed.file.length(), "a 5 KB file pads to the 64 KiB bucket")
            val opened = openFileAttachment(sealed.file, File(directory, "${sealed.hash}.out"), share(sealed))
            assertEquals(stored, opened.type)
            assertContentEquals(source, opened.file.readBytes())
        }
    }

    @Test fun `a source that is not the stated size is refused and leaves nothing behind`() = temporary { directory ->
        val source = ByteArray(2_000) { 1 }
        assertFails { sealAttachment(ByteArrayInputStream(source), 1_999, File(directory, "long.enc"), "a.bin", null) }
        assertFails { sealAttachment(ByteArrayInputStream(source), 2_001, File(directory, "short.enc"), "a.bin", null) }
        assertFails { sealAttachment(ByteArrayInputStream(ByteArray(0)), 0, File(directory, "empty.enc"), "a.bin", null) }
        assertFails { sealAttachment(ByteArrayInputStream(source), MAX_ATTACHMENT_SOURCE_BYTES + 1, File(directory, "big.enc"), "a.bin", null) }
        assertEquals(emptyList(), directory.listFiles()!!.map { it.name })
    }

    @Test fun `a picked file streams when its size is known and spools when it is not`() = temporary { directory ->
        val work = File(directory, "outgoing")
        val source = ByteArray(1_200_000) { (it % 251).toByte() }
        for (declared in listOf(source.size.toLong(), null, 0L, -1L)) {
            val sealed = sealPickedFile({ ByteArrayInputStream(source) }, declared, work, "photo.jpg", "image/jpeg")
            assertEquals(listOf(sealed.file.name), work.listFiles()!!.map { it.name }, "only the envelope remains for $declared")
            val opened = openFileAttachment(sealed.file, File(directory, "opened"), share(sealed))
            assertContentEquals(source, opened.file.readBytes())
            sealed.file.delete(); opened.file.delete()
        }
        assertFails { sealPickedFile({ ByteArrayInputStream(source) }, MAX_ATTACHMENT_SOURCE_BYTES + 1, work, "big.bin", null) }
        assertFails { sealPickedFile({ ByteArrayInputStream(source) }, null, work, "big.bin", null, limit = 1_000) }
        assertFails { sealPickedFile({ ByteArrayInputStream(ByteArray(0)) }, null, work, "empty.bin", null) }
        assertEquals(emptyList(), work.listFiles()!!.map { it.name }, "a refused file leaves no spool or envelope")
    }

    @Test fun `a received share is presented by its hints and packages are never opened`() {
        fun share(name: String?, type: String?, size: Long? = 70_000) = ChatAttachment("https://f.example/x", "ab".repeat(32), "cd".repeat(32), name, type, size)
        assertEquals(AttachmentPresentation.IMAGE, attachmentPresentation(share("a.png", "image/png")))
        assertEquals(AttachmentPresentation.IMAGE, attachmentPresentation(share("picture.png", null)))
        assertEquals(AttachmentPresentation.FILE, attachmentPresentation(share("a.png", "image/png", MAX_IMAGE_ENVELOPE_BYTES + 1L)))
        assertEquals(AttachmentPresentation.FILE, attachmentPresentation(share("a.heic", "image/heic")))
        assertEquals(AttachmentPresentation.FILE, attachmentPresentation(share("minutes.pdf", "application/pdf")))
        assertEquals(AttachmentPresentation.FILE, attachmentPresentation(share("a.png", "application/pdf")))
        assertEquals(AttachmentPresentation.FILE, attachmentPresentation(share(null, null)))
        assertEquals(AttachmentPresentation.RECORDING, attachmentPresentation(share("call.mp4", "video/mp4")))
        assertTrue(isAndroidPackage("app.apk", null))
        assertTrue(isAndroidPackage("App.APK", "application/octet-stream"))
        assertTrue(isAndroidPackage("bundle.xapk", null))
        assertTrue(isAndroidPackage("notes.txt", "application/vnd.android.package-archive"))
        assertFalse(isAndroidPackage("apk", "application/zip"))
        assertFalse(isAndroidPackage("report.pdf", "application/pdf"))
    }

    @Test fun `local file names cannot disguise an extension or escape their folder`() {
        assertEquals("invoicefdp.exe", localFileName("invoice‮fdp.exe"))
        assertEquals("passwd", localFileName("../../etc/passwd"))
        assertEquals("hidden", localFileName("...hidden"))
        assertEquals("a_b_c.txt", localFileName("a<b>c.txt"))
        assertEquals("two words.pdf", localFileName("two   words.pdf"))
        assertEquals("file", localFileName(""))
        assertEquals("file", localFileName(null))
        assertEquals("file", localFileName("​‎"))
        val long = localFileName("ü".repeat(300) + ".docx")
        assertTrue(long.toByteArray().size <= 200)
        assertTrue(long.endsWith(".docx"))
        val emoji = localFileName("😀".repeat(80) + ".png")
        assertTrue(emoji.toByteArray().size <= 200 && emoji.endsWith(".png") && emoji.removeSuffix(".png").all { it.isSurrogate() })
    }

    @Test fun `sizes read as the web card writes them`() {
        assertEquals("512 B", formatAttachmentBytes(512))
        assertEquals("64 KB", formatAttachmentBytes(65_608))
        assertEquals("1.5 MB", formatAttachmentBytes(1_572_864))
        assertEquals("257.0 MB", formatAttachmentBytes(269_484_032))
    }

    @Test fun `share hints are normalised as the reference normalises them`() {
        fun parse(json: String) = parseAttachment(Json.parseToJsonElement(json))
        val base = """"url":"https://f.example/x","sha256":"${"AB".repeat(32)}","key":"${"cd".repeat(32)}""""
        assertNull(parse("""{$base,"size":"12"}""")!!.size, "a string is not a size")
        assertEquals(12L, parse("""{$base,"size":12.0}""")!!.size)
        assertEquals(131_072L, parse("""{$base,"size":1.31072e5}""")!!.size)
        assertNull(parse("""{$base,"size":-1}""")!!.size)
        assertNull(parse("""{$base,"size":9007199254740993}""")!!.size)
        assertNull(parse("""{$base,"type":"text/plain;charset=utf-8"}""")!!.type)
        assertNull(parse("""{$base,"event":"nope"}"""))
        assertEquals("a b c", parse("""{$base,"name":"  a\t\n b​ \u0085c  "}""")!!.name)
        assertNull(parse("""{$base,"name":"‮​ "}""")!!.name)
        val long = parse("""{$base,"name":"${"😀".repeat(300)}"}""")!!.name!!
        assertEquals(255, long.codePointCount(0, long.length))
        val withEvent = parse("""{"size":5,"type":"Application/PDF","name":"m.pdf","event":"${"EF".repeat(32)}",$base}""")!!
        assertEquals("""{"url":"https://f.example/x","sha256":"${"ab".repeat(32)}","key":"${"cd".repeat(32)}","event":"${"ef".repeat(32)}","name":"m.pdf","type":"application/pdf","size":5}""",
            withEvent.toJson().toString(), "re-encoded in the reference key order, event kept")
    }

    @Test fun `a received file is fetched once, named by its envelope and removed on close unless handed on`() = temporary { directory ->
        val source = "%PDF-1.4 synthetic".toByteArray()
        val sealed = sealAttachment(ByteArrayInputStream(source), source.size.toLong(), File(directory, "sealed.enc"), "Quarterly report.pdf", "application/pdf")
        val envelope = sealed.file.readBytes()
        val root = File(directory, "opened-files")
        var fetches = 0
        val client = serving(envelope) { fetches++ }

        val closed = FileAttachmentRequest(share(sealed).copy(name = "hint.txt"), root, client)
        val opened = closed.download()
        assertEquals("Quarterly report.pdf", opened.file.name, "the envelope's name, not the hint")
        assertEquals("application/pdf", opened.type)
        assertContentEquals(source, opened.file.readBytes())
        assertSame(opened, closed.download())
        assertEquals(1, fetches)
        closed.close()
        assertFalse(opened.file.exists())

        val kept = FileAttachmentRequest(share(sealed), root, client)
        val handed = kept.download()
        kept.keep(); kept.close()
        assertTrue(handed.file.exists(), "another app may still be reading it")
        handed.file.parentFile!!.setLastModified(System.currentTimeMillis() - 7_200_000)
        FileAttachmentRequest(share(sealed), root, client).close()
        assertFalse(handed.file.exists(), "the next open sweeps what was handed on an hour ago")

        val wrong = FileAttachmentRequest(share(sealed).copy(sha256 = "00".repeat(32)), root, client)
        assertFails { wrong.download() }
        assertFails { wrong.download() }
        wrong.close()
        assertEquals(emptyList(), root.listFiles()!!.map { it.name }, "a refused download leaves nothing behind")
    }

    /** A message and envelope the TypeScript reference wrote (see
     * attachments/README.md): decoded here, re-encoded to the reference's own
     * reading, opened by the streaming reader, and carried again by this
     * client's encoder without changing a byte of the share. */
    @Test fun `a non-image share written by the reference round trips`() = temporary { directory ->
        val meta = Json.parseToJsonElement(javaClass.getResource("/attachments/web-file.json")!!.readText()).jsonObject
        val envelope = javaClass.getResourceAsStream("/attachments/web-file.enc")!!.readBytes()
        assertEquals(meta.getValue("sha256").jsonPrimitive.content, Digests.sha256(envelope).toHex())
        val roomId = meta.getValue("roomId").jsonPrimitive.content
        val roomKey = meta.getValue("roomKeyHex").jsonPrimitive.content.hexToBytes()
        val message = assertNotNull(decodeChatEvent(NostrEvent.fromJson(meta.getValue("event").jsonObject), roomId, roomKey,
            meta.getValue("now").jsonPrimitive.long))
        val expected = meta.getValue("attachments").jsonArray.toString()
        assertEquals(expected, JsonArray(message.attachments.map { it.toJson() }).toString())
        assertEquals(listOf(AttachmentPresentation.FILE, AttachmentPresentation.FILE), message.attachments.map(::attachmentPresentation))

        val request = FileAttachmentRequest(message.attachments.first(), File(directory, "opened-files"), serving(envelope))
        val opened = request.download()
        assertEquals("Minutes 19 Oct.pdf", opened.name)
        assertEquals("application/pdf", opened.type)
        assertEquals(meta.getValue("sourceSize").jsonPrimitive.long, opened.size)
        assertEquals(meta.getValue("sourceSha256").jsonPrimitive.content, fileSha256(opened.file))
        request.close()

        val room = Fixtures.room()
        val owner = Fixtures.primary(room, 1, 2)
        val resent = encodeChatEvent(body = message.body, participant = owner.participant, credential = owner.credential,
            roomId = room.roomId, roomKey = room.roomKey, deviceSecretKey = owner.deviceSecretKey, sentAt = 100, attachments = message.attachments)
        val again = assertNotNull(decodeChatEvent(resent, room.roomId, room.roomKey, 200))
        assertEquals(expected, JsonArray(again.attachments.map { it.toJson() }).toString())
    }
}
