package dev.forgesworn.kithmoot.session

import kotlinx.serialization.json.Json
import java.time.Instant
import kotlin.test.*

class LongChatTextTest {
    private val tooLong = "x".repeat(MAX_CHAT_TEXT_LENGTH + 1)

    private fun opened(sealed: SealedMedia) = openAttachment(sealed.envelope,
        ChatAttachment("https://files.example/${sealed.hash}", sealed.hash, sealed.key, sealed.name, sealed.type, sealed.envelope.size.toLong()))

    @Test fun `the limit itself has not moved`() {
        assertEquals(2_000, MAX_CHAT_TEXT_LENGTH)
        assertFalse(chatTextNeedsDocument("x".repeat(2_000)))
        assertTrue(chatTextNeedsDocument(tooLong))
    }

    @Test fun `the paste note carries the web client's exact wording and groups the count`() {
        assertNull(longChatPasteNote(""))
        assertNull(longChatPasteNote("x".repeat(2_000)))
        assertEquals("2,001 characters pasted. Send will share a short preview and the complete encrypted text document.",
            longChatPasteNote(tooLong))
        assertEquals("12,345 characters pasted. Send will share a short preview and the complete encrypted text document.",
            longChatPasteNote("x".repeat(12_345)))
        assertEquals("1,234,567", formatCharacterCount(1_234_567))
    }

    @Test fun `the preview body is the web client's, with the count grouped by thousands`() {
        val text = "a".repeat(12_345)
        assertEquals(
            "Shared full text (12,345 characters). Open the encrypted document to read or copy everything.\n\n" +
                "a".repeat(600) + "…",
            longChatPreviewBody(text))
    }

    @Test fun `the preview keeps the first 600 characters and fits under the limit`() {
        val text = (0 until 5_000).map { ('a' + it % 26) }.joinToString("")
        val body = longChatPreviewBody(text)
        assertTrue(body.endsWith(text.take(600) + "…"))
        assertFalse(text.take(601) in body)
        assertTrue(body.length <= MAX_CHAT_TEXT_LENGTH)
        assertEquals(1, body.count { it == '…' })
    }

    @Test fun `a cut through a surrogate pair drops the lone high half`() {
        val emoji = "😀"
        val split = "a".repeat(599) + emoji + "b".repeat(2_000)
        val cut = longChatPreviewBody(split).substringAfter("\n\n")
        assertEquals("a".repeat(599) + "…", cut)
        assertTrue(cut.none { it.isSurrogate() })
        val whole = "a".repeat(598) + emoji + "b".repeat(2_000)
        assertEquals("a".repeat(598) + emoji + "…", longChatPreviewBody(whole).substringAfter("\n\n"))
    }

    @Test fun `the document name is message, the UTC time without colons or full stop, then txt`() {
        assertEquals("message-2026-10-19T12-34-56-789Z.txt", longChatDocumentName(Instant.parse("2026-10-19T12:34:56.789Z")))
        assertEquals("message-2026-10-19T12-34-56-000Z.txt", longChatDocumentName(Instant.parse("2026-10-19T12:34:56Z")))
        assertEquals("message-2026-01-02T03-04-05-006Z.txt", longChatDocumentName(Instant.parse("2026-01-02T03:04:05.006Z")))
        val name = longChatDocumentName(Instant.now())
        assertTrue(name.matches(Regex("message-\\d{4}-\\d{2}-\\d{2}T\\d{2}-\\d{2}-\\d{2}-\\d{3}Z\\.txt")), name)
        assertEquals(name, canonicalAttachmentName(name), "the envelope reader must take the name as it stands")
    }

    @Test fun `sealMedia accepts plain text with or without its charset`() {
        for (type in listOf("text/plain", "text/plain;charset=utf-8")) {
            val sealed = sealMedia("hello".toByteArray(), "note.txt", type)
            assertEquals(type, sealed.type)
            val opened = opened(sealed)
            assertEquals(type, opened.type); assertEquals("note.txt", opened.name); assertEquals("hello", String(opened.bytes))
        }
    }

    @Test fun `sealMedia still refuses other types, nothing, and more than 8 MiB`() {
        assertFailsWith<IllegalArgumentException> { sealMedia("<p>".toByteArray(), "page.html", "text/html") }
        assertFailsWith<IllegalArgumentException> { sealMedia("x".toByteArray(), "a.pdf", "application/pdf") }
        assertFailsWith<IllegalArgumentException> { sealMedia("x".toByteArray(), "a.txt", "text/plain;charset=latin1") }
        assertFailsWith<IllegalArgumentException> { sealMedia(ByteArray(0), "a.txt", "text/plain") }
        assertFailsWith<IllegalArgumentException> { sealMedia(ByteArray(MAX_MEDIA_SOURCE_BYTES + 1), "a.txt", "text/plain") }
        val message = assertFailsWith<IllegalArgumentException> { sealMedia("x".toByteArray(), "a.bin", "application/octet-stream") }.message!!
        assertTrue("text" in message && "image" in message, "the refusal must not claim only images are allowed: $message")
        assertNotNull(sealMedia(ByteArray(MAX_MEDIA_SOURCE_BYTES) { 65 }, "big.txt", "text/plain"))
    }

    @Test fun `a long paste seals as a document that opens to the very same text`() {
        val text = "Café ☕ 😀 line one\nline two\n" + "z".repeat(5_000)
        val at = Instant.parse("2026-10-19T12:34:56.789Z")
        val opened = opened(sealLongChatDocument(text, at))
        assertEquals("message-2026-10-19T12-34-56-789Z.txt", opened.name)
        assertEquals(TEXT_DOCUMENT_TYPE, opened.type)
        assertEquals(text, String(opened.bytes, Charsets.UTF_8))
        assertTrue(isTextDocumentType(opened.type))
    }

    @Test fun `the document's envelope does not carry the text in the clear`() {
        val sealed = sealLongChatDocument("a secret that is quite distinctive " + "q".repeat(3_000), Instant.now())
        assertFalse(sealed.envelope.toString(Charsets.ISO_8859_1).contains("a secret that is quite distinctive"))
    }

    @Test fun `a document type is recognised whatever follows the type, and nothing else is`() {
        assertTrue(isTextDocumentType("text/plain"))
        assertTrue(isTextDocumentType("text/plain;charset=utf-8"))
        assertTrue(isTextDocumentType("Text/Plain; charset=UTF-8"))
        assertFalse(isTextDocumentType("text/html"))
        assertFalse(isTextDocumentType("image/png"))
        assertFalse(isTextDocumentType(null))
    }

    @Test fun `an attachment typed as the web client types its document keeps that type`() {
        fun parse(type: String) = parseAttachment(Json.parseToJsonElement(
            """{"url":"https://files.example/f","sha256":"${"ab".repeat(32)}","key":"${"cd".repeat(32)}","name":"message.txt","type":"$type"}"""))
        assertEquals("text/plain;charset=utf-8", parse("text/plain;charset=utf-8")?.type)
        assertEquals("text/plain", parse("text/plain")?.type)
        assertEquals("text/plain;charset=utf-8", parse("Text/Plain;Charset=UTF-8")?.type)
        assertNull(parse("text/plain;charset=utf-8;x=y")?.type)
        assertNull(parse("text/plain; boundary=\u0001")?.type)
        assertNotNull(parse("text/plain;charset=utf-8"), "the attachment itself must survive either way")
    }

    @Test fun `a remembered storage server needs both the origin and the accepted disclosure`() {
        assertEquals("https://files.example", rememberedMediaStorage("https://files.example", 1))
        assertEquals("https://files.example", rememberedMediaStorage("https://FILES.example/", 1))
        assertNull(rememberedMediaStorage(null, 1))
        assertNull(rememberedMediaStorage("https://files.example", 0))
        assertEquals("https://files.example", rememberedMediaStorage("https://files.example", 2))
        assertNull(rememberedMediaStorage("https://files.example", 3))
        assertNull(rememberedMediaStorage("http://files.example", 1))
        assertNull(rememberedMediaStorage("not a url", 1))
    }

    private fun refusal(
        text: String = tooLong, anonymous: Boolean = false, internet: Boolean = true, storage: Boolean = true,
        uploading: Boolean = false, staged: Int = 0,
    ) = longChatRefusal(text, anonymous, internet, storage, uploading, staged)

    @Test fun `a message that can go as a document is not refused`() {
        assertNull(refusal())
        assertNull(refusal(staged = MAX_CHAT_FILES - 1))
    }

    @Test fun `every reason a long message cannot go as a document is said in a sentence of its own`() {
        val reasons = mapOf(
            "anonymous" to refusal(anonymous = true),
            "no internet" to refusal(internet = false),
            "uploading" to refusal(uploading = true),
            "too large" to refusal(text = "x".repeat(MAX_MEDIA_SOURCE_BYTES + 1)),
            "no free slot" to refusal(staged = MAX_CHAT_FILES),
            "no storage" to refusal(storage = false),
        )
        for ((why, message) in reasons) {
            assertNotNull(message, why)
            assertTrue(message.startsWith("This message is over 2,000 characters."), "$why: $message")
            assertFalse('\u2014' in message || '\u2013' in message, "$why: no dashes")
        }
        assertEquals(reasons.size, reasons.values.toSet().size, "each reason reads differently")
        assertTrue("anonymous" in reasons.getValue("anonymous")!!)
        assertTrue("Internet" in reasons.getValue("no internet")!!)
        assertTrue("uploading" in reasons.getValue("uploading")!!)
        assertTrue("8 MiB" in reasons.getValue("too large")!!)
        assertTrue("file" in reasons.getValue("no free slot")!!)
        assertTrue("storage server" in reasons.getValue("no storage")!! && "shorten" in reasons.getValue("no storage")!!.lowercase())
    }

    @Test fun `the first thing in the way is the one reported`() {
        assertTrue("anonymous" in refusal(anonymous = true, internet = false, storage = false)!!)
        assertTrue("Internet" in refusal(internet = false, storage = false, staged = 4)!!)
        assertTrue("uploading" in refusal(uploading = true, storage = false)!!)
        assertTrue("8 MiB" in refusal(text = "x".repeat(MAX_MEDIA_SOURCE_BYTES + 1), storage = false)!!)
        assertTrue("file" in refusal(storage = false, staged = 4)!!)
    }

    @Test fun `size is counted in bytes, not characters`() {
        val threeBytes = "€".repeat(MAX_MEDIA_SOURCE_BYTES / 3 + 1)
        assertTrue(threeBytes.length < MAX_MEDIA_SOURCE_BYTES)
        assertTrue("8 MiB" in refusal(text = threeBytes)!!)
        assertNull(refusal(text = "€".repeat(MAX_MEDIA_SOURCE_BYTES / 3)))
    }

    @Test fun `display pieces break at line ends, never inside a pair, and lose nothing`() {
        val emoji = "😀"
        val lines = (1..300).joinToString("\n") { "line $it $emoji" }
        val pieces = splitDocumentForDisplay(lines, limit = 500)
        assertTrue(pieces.size > 1)
        assertTrue(pieces.all { it.length <= 500 })
        assertEquals(lines, pieces.joinToString("\n"))
        assertTrue(pieces.none { it.lastOrNull()?.isHighSurrogate() == true || it.firstOrNull()?.isLowSurrogate() == true })
        val oneLine = "a".repeat(499) + emoji + "b".repeat(1_200)
        val hard = splitDocumentForDisplay(oneLine, limit = 500)
        assertEquals(oneLine, hard.joinToString(""))
        assertTrue(hard.none { it.lastOrNull()?.isHighSurrogate() == true || it.firstOrNull()?.isLowSurrogate() == true })
        assertEquals(listOf("short"), splitDocumentForDisplay("short"))
        assertEquals(emptyList<String>(), splitDocumentForDisplay(""))
    }
}
