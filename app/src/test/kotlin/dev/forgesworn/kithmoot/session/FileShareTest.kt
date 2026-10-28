package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.AttachmentDecision
import dev.forgesworn.kithmoot.protocol.AttachmentTier
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.SafetyReason
import dev.forgesworn.kithmoot.protocol.SaveTier
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.time.Instant
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
        assertEquals(1_000L, assertFailsWith<PickedFileTooLarge> { sealPickedFile({ ByteArrayInputStream(source) }, null, work, "big.bin", null, limit = 1_000) }.limit)
        assertFails { sealPickedFile({ ByteArrayInputStream(ByteArray(0)) }, null, work, "empty.bin", null) }
        assertEquals(emptyList(), work.listFiles()!!.map { it.name }, "a refused file leaves no spool or envelope")
    }

    @Test fun `a file of unknown size stops being copied once it passes the limit that applies`() = temporary { directory ->
        val work = File(directory, "outgoing")
        var served = 0L
        val endless = object : java.io.InputStream() {
            override fun read(): Int = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int { served += length; return length }
        }
        val server = 100_000L
        val refused = assertFailsWith<PickedFileTooLarge> { sealPickedFile({ endless }, null, work, "film.mp4", "video/mp4", limit = server) }
        assertEquals(server, refused.limit)
        assertTrue(served <= server + 64 * 1024, "the copy ends one read past the limit, not at 256 MiB: $served")
        assertEquals("Choose a file up to 50 MB.", PickedFileTooLarge(50L * 1_048_576).message, "a smaller server limit is named, not the 256 MiB ceiling")
        assertEquals("Choose a file up to 256 MiB.", PickedFileTooLarge(MAX_ATTACHMENT_SOURCE_BYTES).message)
        assertFailsWith<PickedFileTooLarge> { sealPickedFile({ endless }, server + 1, work, "film.mp4", "video/mp4", limit = server) }
        assertEquals(emptyList(), work.listFiles()!!.map { it.name }, "nothing is left behind")
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
        // A long paste's document, and a plain text file, are read in the viewer as before; the web sends the paste typeless.
        assertEquals(AttachmentPresentation.DOCUMENT, attachmentPresentation(share("message-2026-10-19T12-34-56-789Z.txt", null)))
        assertEquals(AttachmentPresentation.DOCUMENT, attachmentPresentation(share("message-2026-10-19T12-34-56-789Z.txt", "text/plain")))
        assertEquals(AttachmentPresentation.DOCUMENT, attachmentPresentation(share("notes.txt", "text/plain")))
        assertEquals(AttachmentPresentation.FILE, attachmentPresentation(share("notes.txt", null)))
        assertEquals(AttachmentPresentation.FILE, attachmentPresentation(share("message-2026-10-19T12-34-56-789Z.txt", "application/zip")))
        assertEquals(AttachmentPresentation.FILE, attachmentPresentation(share("log.txt", "text/plain", MAX_IMAGE_ENVELOPE_BYTES + 1L)), "too big for the viewer's memory")
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

    @Test fun `the document stays a document whichever hint it carries`() {
        val envelopeType = TEXT_DOCUMENT_TYPE
        assertEquals("text/plain;charset=utf-8", envelopeType, "the envelope keeps the web client's own type")
        assertNull(attachmentTypeHint(envelopeType), "the message's hint is the reference's: none")
        fun share(name: String, type: String?) = ChatAttachment("https://f.example/x", "ab".repeat(32), "cd".repeat(32), name, type, 70_000)
        val name = longChatDocumentName(Instant.parse("2026-10-19T12:34:56.789Z"))
        assertTrue(isLongChatDocumentName(name))
        assertFalse(isLongChatDocumentName("message-2026-10-19.txt"))
        assertFalse(isLongChatDocumentName(null))
        // Typeless as a web sender's arrives, or typed text/plain: read in the viewer, labelled a document.
        assertTrue(isTextDocument(share(name, null)))
        assertTrue(isTextDocument(share(name, "text/plain")))
        assertFalse(isTextDocument(share("notes.txt", null)))
        assertFalse(isTextDocument(share(name, "application/zip")))
        assertEquals(AttachmentPresentation.DOCUMENT, attachmentPresentation(share(name, null)))
    }

    /** The envelope keeps an invisible character the file name on disk loses,
     * so a guard that read the raw name would see `a<ZWJ>pk`, not `apk`. */
    @Test fun `an invisible character in the extension does not get an app package past the guard`() = temporary { directory ->
        val raw = "app.a\u200Dpk"
        assertFalse(raw.substringAfterLast('.').lowercase() == "apk", "the raw extension is not apk")
        assertEquals("app.apk", localFileName(raw), "but the file is saved as app.apk")
        assertTrue(isAndroidPackage(raw, null))
        assertTrue(isAndroidPackage(raw, "application/octet-stream"))
        assertTrue(isAndroidPackage("app\u202E.apk\u200B", "text/plain"))
        assertTrue(isAndroidPackage("app.apk.", null), "a trailing dot does not hide the extension")
        assertTrue(isAndroidPackage("bundle.x\u2060apk", null))
        assertFalse(isAndroidPackage("app.a\u200Dpk.txt", "text/plain"))
        // The open-with type is read from the cleaned extension too.
        val plain = "plain words".toByteArray()
        assertEquals("application/pdf", openWithType(received(directory, "a.p\u200Ddf", "application/octet-stream", plain)) { if (it == "pdf") "application/pdf" else null })
        assertEquals("application/octet-stream", openWithType(received(directory, "a.p\u200Ddf", "application/octet-stream", plain)) { null })
        assertEquals("text/plain", openWithType(received(directory, "a.dat", "text/plain;charset=utf-8", plain)) { "application/x-never" })

        // End to end: the envelope's own name, as the dialog receives it after a download.
        val source = "PK\u0003\u0004 synthetic".toByteArray()
        val sealed = sealAttachment(ByteArrayInputStream(source), source.size.toLong(), File(directory, "sealed.enc"), raw, "application/octet-stream")
        val request = FileAttachmentRequest(share(sealed).copy(name = "harmless.txt"), File(directory, "opened-files"), serving(sealed.file.readBytes()))
        val opened = request.download()
        assertEquals(raw, opened.name, "the envelope still carries the invisible character")
        assertEquals("app.apk", opened.file.name, "and the file on disk is named without it")
        assertTrue(isAndroidPackage(opened.name, opened.type), "so the guard that decides Open with and the save type must say package")
        request.close()
    }

    @Test fun `a share held on the room's own storage is read through the group source, not the open network`() = temporary { directory ->
        val source = ByteArray(3_000) { (it % 17).toByte() }
        val sealed = sealAttachment(ByteArrayInputStream(source), source.size.toLong(), File(directory, "sealed.enc"), "plan.pdf", "application/pdf")
        val envelope = sealed.file.readBytes()
        var requests = 0
        val offline = serving(envelope) { requests++ }
        var asked = 0
        val group = GroupFileSource { attachment, destination, _ ->
            asked++
            if (attachment.url.startsWith("https://group.example/")) { destination.writeBytes(envelope); true } else false
        }
        val onGroup = FileAttachmentRequest(share(sealed).copy(url = "https://group.example/${sealed.hash}"), File(directory, "opened-a"), offline, group)
        assertContentEquals(source, onGroup.download().file.readBytes())
        assertEquals(0, requests, "a group node answers only a signed request, so nothing is fetched from the url")
        onGroup.close()

        val elsewhere = FileAttachmentRequest(share(sealed), File(directory, "opened-b"), offline, group)
        assertContentEquals(source, elsewhere.download().file.readBytes())
        assertEquals(1, requests, "a share not on the room's storage is fetched the ordinary way")
        elsewhere.close()
        assertEquals(2, asked)

        val wrong = FileAttachmentRequest(share(sealed).copy(url = "https://group.example/x", sha256 = "00".repeat(32)), File(directory, "opened-c"), offline, group)
        assertFails { wrong.download() }
        wrong.close()
        assertEquals(emptyList(), File(directory, "opened-c").listFiles()!!.map { it.name })
    }

    private fun received(directory: File, name: String, type: String, head: ByteArray, messageName: String? = name, sender: String? = "Rowan"):
        AttachmentDecision {
        val file = File.createTempFile("received-", ".bin", directory)
        file.writeBytes(head + ByteArray(3_000) { 0x20 })
        return classifyReceivedFile(OpenedFile(file, name, type, file.length()), messageName, sender)
    }

    private fun bytesOf(vararg values: Int) = ByteArray(64) { if (it < values.size) values[it].toByte() else 0x20 }

    /** What Open with and Save are given comes from the decision, not from what the sender declared. */
    @Test fun `the type another app is given comes from the safety decision, never the sender's type`() = temporary { directory ->
        // A table that would hand every extension to a browser shows the decision never asks it where the classifier has spoken.
        val hostile: (String) -> String? = { "text/html" }
        val html = "<!doctype html><html><script>alert(1)</script></html>".toByteArray()

        // HTML sealed as notes.txt and typed text/html reads as text: it is offered as text, not as a page.
        val notes = received(directory, "notes.txt", "text/html", html)
        assertEquals(AttachmentTier.INLINE, notes.tier)
        assertEquals("text/plain", notes.mime)
        assertEquals("text/plain", openWithType(notes, hostile))

        // XHTML typed as such under a .pdf name is a plain file: the extension's type, never the declared one.
        val xhtml = "<?xml version=\"1.0\"?><html xmlns=\"http://www.w3.org/1999/xhtml\"><script/></html>".toByteArray()
        val pdfNamed = received(directory, "x.pdf", "application/xhtml+xml", xhtml)
        assertEquals(AttachmentTier.SAVE, pdfNamed.tier)
        assertEquals("application/pdf", openWithType(pdfNamed) { if (it == "pdf") "application/pdf" else "application/xhtml+xml" })
        assertEquals("application/octet-stream", openWithType(pdfNamed) { "application/xhtml+xml" }, "a web type from the table is never used")
        assertEquals("application/octet-stream", openWithType(pdfNamed) { null })

        // A warned page or SVG is never offered as one: the classifier's type, or octet-stream when that is a web type.
        val svgBytes = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>".toByteArray()
        val svg = received(directory, "pic.svg", "image/svg+xml", svgBytes)
        assertEquals(AttachmentTier.WARN, svg.tier)
        assertEquals("image/svg+xml", svg.mime, "the classifier would preview it as an image")
        assertEquals("application/octet-stream", openWithType(svg, hostile))
        val page = received(directory, "page.html", "text/html", html)
        assertEquals(AttachmentTier.WARN, page.tier)
        assertEquals("text/plain", openWithType(page, hostile))
        assertEquals("application/octet-stream", openWithType(received(directory, "page.html", "application/octet-stream", html), hostile))
        assertEquals("application/octet-stream", openWithType(received(directory, "page.xhtml", "application/xhtml+xml", html), hostile))
        // SVG that earns no warning by its name is still not handed over as SVG.
        val quiet = received(directory, "pic.png", "image/svg+xml", svgBytes)
        assertEquals(AttachmentTier.INLINE, quiet.tier)
        assertEquals("image/svg+xml", quiet.mime)
        assertEquals("application/octet-stream", openWithType(quiet, hostile))

        // An XML file the table calls text/xml is a plain file the table's type must not turn into a page.
        assertEquals("application/octet-stream", openWithType(received(directory, "data.xml", "application/octet-stream", "<a/>".toByteArray())) { "text/xml" })
        assertEquals("application/octet-stream", openWithType(received(directory, "feed.atom", "application/octet-stream", "<a/>".toByteArray())) { "application/atom+xml" })

        // Ordinary files keep their own types.
        val pdf = bytesOf(*"%PDF-1.7\n".map { it.code }.toIntArray())
        assertEquals("application/pdf", openWithType(received(directory, "minutes.pdf", "application/pdf", pdf), hostile))
        assertEquals("application/pdf", openWithType(received(directory, "minutes.pdf", "application/octet-stream", pdf)) { if (it == "pdf") "application/pdf" else null })
        val docx = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        assertEquals(docx, openWithType(received(directory, "plan.docx", "application/octet-stream", bytesOf(0x50, 0x4b, 3, 4))) { if (it == "docx") docx else null })
        assertEquals("image/png", openWithType(received(directory, "a.png", "image/png", bytesOf(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)), hostile))

        // Programs, packages and anything blocked are offered as nothing in particular.
        val pe = bytesOf(0x4d, 0x5a, 0x90, 0, 3, 0, 0, 0)
        assertEquals("application/octet-stream", openWithType(received(directory, "setup.exe", "application/x-msdownload", pe), hostile))
        assertEquals("application/octet-stream", openWithType(received(directory, "app.apk", "application/vnd.android.package-archive", bytesOf(0x50, 0x4b, 3, 4))) { "application/vnd.android.package-archive" })
        assertEquals("application/octet-stream", openWithType(received(directory, "invoice.pdf", "application/pdf", pe), hostile))
    }

    /** The image viewer's own classifier decides what a received file may do. */
    @Test fun `a received file is classified before it can be saved or opened`() = temporary { directory ->
        val rlo = "\u202e"
        val pe = bytesOf(0x4d, 0x5a, 0x90, 0, 3, 0, 0, 0)
        val pdf = bytesOf(*"%PDF-1.7\n".map { it.code }.toIntArray())

        // An ordinary PDF passes without a word.
        val fine = received(directory, "minutes.pdf", "application/pdf", pdf)
        assertEquals(AttachmentTier.INLINE, fine.tier)
        assertEquals(SaveTier.QUIET, fine.save)
        assertNull(fine.message)

        // A program under a document's name is blocked: neither saved nor opened.
        val disguised = received(directory, "invoice.pdf", "application/pdf", pe)
        assertEquals(AttachmentTier.BLOCK, disguised.tier)
        assertEquals(SafetyReason.DISGUISED_PROGRAM, disguised.reason)
        assertEquals(SaveTier.BLOCK, disguised.save)

        // A program that says what it is warns, in main's wording, and needs a say-so.
        val honest = received(directory, "invoice.pdf.exe", "application/octet-stream", pe)
        assertEquals(AttachmentTier.WARN, honest.tier)
        assertEquals(SaveTier.WARN, honest.save)
        assertEquals("Programs can take over your computer. Only save this if you asked Rowan for it.", honest.message)

        // A bidi-reversed extension is cleaned, said so, and still warns.
        val reversed = received(directory, "invoice${rlo}fdp.exe", "application/pdf", pe, messageName = "invoicefdp.exe")
        assertEquals(AttachmentTier.WARN, reversed.tier)
        assertEquals(SafetyReason.DIRECTION_CONTROLS, reversed.reason)
        assertEquals("invoicefdp.exe", reversed.name)
        assertTrue(reversed.message!!.startsWith("This file’s name had hidden characters that make it read differently."))

        // A shortcut is blocked by its name or by its bytes.
        val shortcut = received(directory, "Holiday.lnk", "application/octet-stream", bytesOf(*"Meet at nine".map { it.code }.toIntArray()))
        assertEquals(AttachmentTier.BLOCK, shortcut.tier)
        assertEquals(SafetyReason.SHORTCUT, shortcut.reason)
        assertEquals(SafetyReason.SHORTCUT,
            received(directory, "Holiday.pdf", "application/pdf", bytesOf(0x4c, 0, 0, 0, 1, 0x14, 2, 0)).reason)

        // An invisible character in the extension does not hide a program from the classifier either.
        assertEquals(AttachmentTier.WARN, received(directory, "setup.e\u200Bxe", "application/octet-stream", pe).tier)
        // A file larger than the sniff window is read for its head only.
        val big = File.createTempFile("big-", ".bin", directory)
        big.outputStream().use { out -> out.write(pdf); out.write(ByteArray(2_000_000)) }
        assertEquals(AttachmentTier.INLINE, classifyReceivedFile(OpenedFile(big, "big.pdf", "application/pdf", big.length()), "big.pdf").tier)
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
