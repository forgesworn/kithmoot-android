package dev.forgesworn.kithmoot.protocol

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentSafetyTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    private fun ascii(value: String) = value.map { it.code }.toIntArray()
    private fun pad(values: IntArray, length: Int = 64) = ByteArray(length) { if (it < values.size) values[it].toByte() else 0x20 }
    private fun pad(vararg values: Int) = pad(values, 64)
    private val pe = pad(0x4d, 0x5a, 0x90, 0, 3, 0, 0, 0)
    private val png = pad(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 13)
    private val zip = pad(0x50, 0x4b, 0x03, 0x04, 20, 0)
    private val pdf = pad(ascii("%PDF-1.7\n"))
    private val text = pad(ascii("Meet at the car park at 9."))
    private val rlo = "\u202e"

    private fun assess(
        envelopeName: String,
        declaredType: String,
        head: ByteArray,
        messageName: String? = envelopeName,
        sender: String? = "Rowan",
    ) = AttachmentSafety.assessAttachment(AttachmentInput(head, envelopeName, declaredType, messageName, sender))

    @Test fun nameCleaningUsesNfcEcmaWhitespaceAndExactControlRules() {
        assertEquals(CleanedFileName("invoicefdp.exe", "exe", true),
            AttachmentSafety.cleanFileName("invoice${rlo}fdp.exe"))
        assertEquals("photo.jpg.exe", AttachmentSafety.cleanFileName("photo.jpg\u200b.exe").name)
        assertEquals("report.exe", AttachmentSafety.cleanFileName("report.exe . ").name)
        assertEquals("_CON.txt", AttachmentSafety.cleanFileName("CON.txt").name)
        assertEquals("_com1", AttachmentSafety.cleanFileName("com1").name)
        assertEquals("_LPT9.log", AttachmentSafety.cleanFileName("LPT9.log").name)
        assertEquals("console.txt", AttachmentSafety.cleanFileName("console.txt").name)
        assertEquals("é.txt", AttachmentSafety.cleanFileName("e\u0301.txt").name)
        assertEquals("a b.txt", AttachmentSafety.cleanFileName("a\u00a0\u2007\u202fb.txt").name)
        assertEquals("a b.txt", AttachmentSafety.cleanFileName("a\u2028\u2029b.txt").name)
        assertEquals("ab.txt", AttachmentSafety.cleanFileName("a\u0085b.txt").name)
        assertEquals("_.._etc_passwd", AttachmentSafety.cleanFileName("..\\..\\etc/passwd").name)
        assertEquals("a_b___c_d_e_.txt", AttachmentSafety.cleanFileName("a<b>:\"c|d?e*.txt").name)
        assertEquals("hidden.txt", AttachmentSafety.cleanFileName("...hidden.txt").name)
        assertEquals("", AttachmentSafety.cleanFileName("a.thisistoolongext").ext)
        assertEquals("txt", AttachmentSafety.cleanFileName("A.TXT").ext)
        assertEquals(CleanedFileName("file", "", true), AttachmentSafety.cleanFileName("\u200b${rlo}\u2066"))
        assertEquals("file.png", AttachmentSafety.cleanFileName("\u200b", "png").name)
    }

    @Test fun truncationPreservesUtf16LimitExtensionAndSurrogatePairs() {
        val long = AttachmentSafety.cleanFileName("${"a".repeat(300)}.exe")
        assertEquals(180, long.name.length)
        assertTrue(long.name.endsWith(".exe"))
        val emoji = AttachmentSafety.cleanFileName("${"😀".repeat(200)}.png")
        assertEquals(180, emoji.name.length)
        assertTrue(emoji.name.endsWith(".png"))
        assertFalse(emoji.name.dropLast(4).last().isHighSurrogate())
        val exactCut = AttachmentSafety.cleanFileName("a".repeat(175) + "😀" + ".txt")
        assertEquals("a".repeat(175) + ".txt", exactCut.name)
        assertEquals(179, exactCut.name.length)
    }

    @Test fun lowercasingDoesNotDependOnDefaultLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(DeclaredFamily.IMAGE, AttachmentSafety.declaredFamily("IMAGE/PNG"))
            assertEquals("txt", AttachmentSafety.cleanFileName("A.TXT").ext)
            assertEquals(FamilyLabel.IMAGE, AttachmentSafety.familyLabel("png", declaredType = "IMAGE/PNG"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test fun sniffsEverySignatureFamilyIncludingSignedByteValues() {
        val samples = listOf(
            SniffKind.PNG to png,
            SniffKind.JPEG to pad(0xff, 0xd8, 0xff, 0xe0),
            SniffKind.GIF to pad(ascii("GIF89a")),
            SniffKind.WEBP to pad(ascii("RIFF") + intArrayOf(0, 0, 0, 0) + ascii("WEBP")),
            SniffKind.AVIF to pad(intArrayOf(0, 0, 0, 0x1c) + ascii("ftypavif")),
            SniffKind.HEIC to pad(intArrayOf(0, 0, 0, 0x18) + ascii("ftypheic")),
            SniffKind.BMP to pad(ascii("BM")),
            SniffKind.ICO to pad(0, 0, 1, 0, 1, 0),
            SniffKind.SVG to pad(ascii("<?xml version=\"1.0\"?><!-- c --> <svg xmlns=\"http://www.w3.org/2000/svg\"></svg>"), 100),
            SniffKind.PDF to pdf,
            SniffKind.MP4 to pad(intArrayOf(0, 0, 0, 0x20) + ascii("ftypisom")),
            SniffKind.WEBM to pad(0x1a, 0x45, 0xdf, 0xa3),
            SniffKind.OGG to pad(ascii("OggS")),
            SniffKind.MP3 to pad(ascii("ID3")),
            SniffKind.WAV to pad(ascii("RIFF") + intArrayOf(0, 0, 0, 0) + ascii("WAVE")),
            SniffKind.FLAC to pad(ascii("fLaC")),
            SniffKind.ZIP to zip,
            SniffKind.OLE to pad(0xd0, 0xcf, 0x11, 0xe0, 0xa1, 0xb1, 0x1a, 0xe1),
            SniffKind.EXE_WINDOWS to pe,
            SniffKind.EXE_ELF to pad(0x7f, 0x45, 0x4c, 0x46),
            SniffKind.EXE_MAC to pad(0xcf, 0xfa, 0xed, 0xfe),
            SniffKind.SCRIPT to pad(ascii("#!/bin/sh\n")),
            SniffKind.SHORTCUT_WINDOWS to pad(0x4c, 0, 0, 0, 1, 0x14, 2, 0),
            SniffKind.TEXT to text,
            SniffKind.UNKNOWN to bytes(1, 2, 0, 0xff, 0x13, 0x37, 0, 0x99),
        )
        for ((kind, sample) in samples) assertEquals(kind, AttachmentSafety.sniffKind(sample))
        assertEquals(SniffKind.MP3, AttachmentSafety.sniffKind(bytes(0xff, 0xfb, 0x90)))
        assertEquals(SniffKind.EXE_MAC, AttachmentSafety.sniffKind(bytes(0xca, 0xfe, 0xba, 0xbe)))
    }

    @Test fun pdfSniffingUsesTheWhole1024ByteWindowOnly() {
        assertEquals(SniffKind.PDF, AttachmentSafety.sniffKind((ascii("junk".repeat(50)) + ascii("%PDF-1.4")).let(::bytes)))
        val nearEnd = ByteArray(1024) { 0x41 }
        ascii("%PDF-").forEachIndexed { index, value -> nearEnd[1019 + index] = value.toByte() }
        assertEquals(SniffKind.PDF, AttachmentSafety.sniffKind(nearEnd))
        val outside = ByteArray(1100) { 0x41 }
        ascii("%PDF-").forEachIndexed { index, value -> outside[1050 + index] = value.toByte() }
        assertEquals(SniffKind.TEXT, AttachmentSafety.sniffKind(outside))
    }

    @Test fun utf8SniffingAcceptsOnlyAValidSequencePrefixAtTheWindowBoundary() {
        val split = ByteArray(1024) { 0x61 }
        split[1022] = 0xe2.toByte()
        split[1023] = 0x82.toByte()
        assertEquals(SniffKind.TEXT, AttachmentSafety.sniffKind(split))
        assertEquals(SniffKind.TEXT, AttachmentSafety.sniffKind(bytes(0xf0, 0x9f, 0x98)))
        assertEquals(SniffKind.UNKNOWN, AttachmentSafety.sniffKind(bytes(0x82)))
        assertEquals(SniffKind.UNKNOWN, AttachmentSafety.sniffKind(pad(0xc3, 0x28)))
        assertEquals(SniffKind.UNKNOWN, AttachmentSafety.sniffKind(pad(ascii("hello") + 0)))
    }

    @Test fun svgSniffingHandlesBomPrologsCommentsDoctypesAndWhitespace() {
        val prefix = "\ufeff \n<?xml version=\"1.0\"?><!--one--><!DOCTYPE svg><!--two-->\n"
        for (start in listOf("<svg>", "<SVG ", "<svg/", "<svg\n")) {
            assertEquals(SniffKind.SVG, AttachmentSafety.sniffKind((prefix + start).toByteArray()))
        }
        assertEquals(SniffKind.TEXT, AttachmentSafety.sniffKind("<svgx>".toByteArray()))
        assertEquals(SniffKind.TEXT, AttachmentSafety.sniffKind("<!-- open <svg>".toByteArray()))
        assertEquals(SniffKind.TEXT, AttachmentSafety.sniffKind("<?xml <svg>".toByteArray()))
    }

    @Test fun disguisedProgramDecisionIsCompleteAndPrecedesNameWarnings() {
        val actual = assess("cat.png", "image/png", pe, messageName = "cat.jpg")
        assertEquals(AttachmentDecision(
            name = "cat.png", ext = "png", kind = SniffKind.EXE_WINDOWS,
            family = FamilyLabel.WINDOWS_PROGRAM, tier = AttachmentTier.BLOCK,
            reason = SafetyReason.DISGUISED_PROGRAM, save = SaveTier.BLOCK,
            preview = PreviewKind.NONE, mime = "application/octet-stream",
            message = "KithMoot won’t open or save this. It’s named like a picture, but it’s really a Windows program. That’s a common way to hide an attack.",
        ), actual)
        assertNull(actual.confirmation)
    }

    @Test fun elfAndMachOUnderDocumentNamesAreBlocked() {
        assertEquals(AttachmentTier.BLOCK, assess("minutes.pdf", "application/pdf", pad(0x7f, 0x45, 0x4c, 0x46)).tier)
        assertEquals(AttachmentTier.BLOCK, assess("minutes.pdf", "application/pdf", pad(0xca, 0xfe, 0xba, 0xbe)).tier)
    }

    @Test fun shortcutDecisionIsCompleteForNameOrBytes() {
        val expected = AttachmentDecision(
            name = "Holiday.lnk", ext = "lnk", kind = SniffKind.TEXT,
            family = FamilyLabel.SHORTCUT, tier = AttachmentTier.BLOCK, reason = SafetyReason.SHORTCUT,
            save = SaveTier.BLOCK, preview = PreviewKind.NONE, mime = "application/octet-stream",
            message = "KithMoot won’t save shortcuts. A shortcut runs something on your computer, and there’s nothing in it to read.",
        )
        assertEquals(expected, assess("Holiday.lnk", "application/octet-stream", text))
        assertEquals(SafetyReason.SHORTCUT, assess("Holiday.pdf", "application/pdf", pad(0x4c, 0, 0, 0, 1, 0x14, 2, 0)).reason)
        assertEquals(AttachmentTier.BLOCK, assess("a.URL", "text/plain", text).tier)
    }

    @Test fun honestProgramDecisionIncludesExactMessageAndConfirmation() {
        assertEquals(AttachmentDecision(
            name = "MapSetup.exe", ext = "exe", kind = SniffKind.EXE_WINDOWS,
            family = FamilyLabel.WINDOWS_PROGRAM, tier = AttachmentTier.WARN, reason = SafetyReason.PROGRAM,
            save = SaveTier.WARN, preview = PreviewKind.NONE, mime = "application/octet-stream",
            message = "Programs can take over your computer. Only save this if you asked Rowan for it.",
            confirmation = "It’s a Windows program. Programs can take over your computer. Only save it if you asked Rowan for it.",
        ), assess("MapSetup.exe", "application/octet-stream", pe))
    }

    @Test fun directionControlsTakePrecedenceAndAddMismatchOnlyWhenFamiliesDiffer() {
        val changed = assess("invoice${rlo}fdp.exe", "application/pdf", pe, messageName = "invoicefdp.exe")
        assertEquals(SafetyReason.DIRECTION_CONTROLS, changed.reason)
        assertEquals("This file’s name had hidden characters that make it read differently. KithMoot removed them. It’s a Windows program, not a PDF.", changed.message)
        assertEquals("This file’s name had hidden characters that make it read differently. KithMoot removed them. It’s a Windows program, not a PDF. Programs can take over your computer. Only save it if you asked Rowan for it.", changed.confirmation)
        val agreeing = assess("holiday${rlo}gnp.png", "image/png", png)
        assertEquals("This file’s name had hidden characters that make it read differently. KithMoot removed them.", agreeing.message)
        assertEquals("This file’s name had hidden characters that make it read differently. KithMoot removed them.", agreeing.confirmation)
    }

    @Test fun allOwnWarningsUseExactSenderWording() {
        val expected = mapOf(
            SafetyReason.SCRIPT to Triple("run.sh", pad(ascii("#!/bin/sh\n")),
                "Scripts can change or take over your computer. Only save this if you asked Rowan for it."),
            SafetyReason.DISK_IMAGE to Triple("disk.dmg", text,
                "A disk image can hold programs. Only save this if you asked Rowan for it."),
            SafetyReason.MACROS to Triple("budget.xlsm", zip,
                "Macros are programs inside a document. Only save this if you asked Rowan for it, and don’t turn macros on."),
            SafetyReason.ARCHIVE to Triple("album.zip", zip,
                "An archive can hold programs. Only save this if you were expecting it."),
            SafetyReason.WEB_PAGE to Triple("page.html", text,
                "Opened from your computer, a web page can run code. Only save this if you were expecting it."),
        )
        for ((reason, fixture) in expected) {
            val decision = assess(fixture.first, "application/octet-stream", fixture.second, sender = " Rowan ")
            assertEquals(reason, decision.reason)
            assertEquals(fixture.third, decision.message)
            assertTrue(decision.confirmation!!.endsWith(fixture.third.replace("Only save this", "Only save it")))
        }
        assertEquals("the sender", assess("run.sh", "application/octet-stream", pad(ascii("#!")), sender = " \ufeff").message!!.let {
            if ("asked the sender for it" in it) "the sender" else it
        })
    }

    @Test fun mismatchAndNameChangeDecisionsKeepFullMessagesAndConfirmations() {
        val mismatch = assess("holiday.png", "image/png", zip)
        assertEquals(AttachmentDecision(
            name = "holiday.png", ext = "png", kind = SniffKind.ZIP, family = FamilyLabel.ARCHIVE,
            tier = AttachmentTier.WARN, reason = SafetyReason.MISMATCH, save = SaveTier.WARN,
            preview = PreviewKind.NONE, mime = "application/octet-stream",
            message = "This file says it’s a picture, but it’s really an archive.",
            confirmation = "This file says it’s a picture, but it’s really an archive. An archive can hold programs. Only save it if you were expecting it.",
        ), mismatch)
        val renamed = assess("photo.exe", "application/octet-stream", pe, messageName = "photo.jpg")
        assertEquals(SafetyReason.NAME_CHANGED, renamed.reason)
        assertEquals("This file was sent as photo.jpg, but the file inside is photo.exe.", renamed.message)
        assertEquals("This file was sent as photo.jpg, but the file inside is photo.exe. Programs can take over your computer. Only save it if you asked Rowan for it.", renamed.confirmation)
    }

    @Test fun webWarningPreviewExceptionsReturnSafeMimeAndExactOwnConfirmation() {
        val html = assess("page.html", "text/html", pad(ascii("<h1>hi</h1>")))
        assertEquals(AttachmentDecision(
            name = "page.html", ext = "html", kind = SniffKind.TEXT, family = FamilyLabel.WEB_PAGE,
            tier = AttachmentTier.WARN, reason = SafetyReason.WEB_PAGE, save = SaveTier.WARN,
            preview = PreviewKind.TEXT, mime = "text/plain",
            message = "Opened from your computer, a web page can run code. Only save this if you were expecting it.",
            confirmation = "It’s a web page. Opened from your computer, a web page can run code. Only save it if you were expecting it.",
        ), html)
        val svg = assess("logo.svg", "image/svg+xml", pad(ascii("<svg xmlns=\"http://www.w3.org/2000/svg\"/>")))
        assertEquals(PreviewKind.IMAGE, svg.preview)
        assertEquals("image/svg+xml", svg.mime)
        val svgText = assess("logo.svg", "text/plain", pad(ascii("<svg/>")))
        assertEquals(PreviewKind.TEXT, svgText.preview)
        assertEquals("text/plain", svgText.mime)
    }

    @Test fun inlineImageDecisionIsCompleteAndSniffedMimeWins() {
        val expected = AttachmentDecision(
            name = "hilltop.png", ext = "png", kind = SniffKind.PNG, family = FamilyLabel.IMAGE,
            tier = AttachmentTier.INLINE, reason = SafetyReason.INLINE, save = SaveTier.QUIET,
            preview = PreviewKind.IMAGE, mime = "image/png",
        )
        assertEquals(expected, assess("hilltop.png", "image/png", png))
        assertEquals(expected, assess("hilltop.png", "image/jpeg", png))
        assertNull(expected.message)
        assertNull(expected.confirmation)
    }

    @Test fun inlineDocumentAndMediaDecisionsUseSafeTypes() {
        assertEquals(PreviewKind.PDF to "application/pdf",
            assess("minutes.pdf", "application/pdf", pdf).let { it.preview to it.mime })
        assertEquals(PreviewKind.TEXT to "text/plain",
            assess("route-notes.txt", "text/plain", text).let { it.preview to it.mime })
        assertEquals(PreviewKind.VIDEO to "video/mp4",
            assess("clip.mp4", "video/mp4; codecs=x", pad(intArrayOf(0, 0, 0, 0x20) + ascii("ftypisom"))).let { it.preview to it.mime })
        assertEquals(PreviewKind.AUDIO to "audio/mpeg",
            assess("song.mp3", "audio/mpeg", pad(ascii("ID3"))).let { it.preview to it.mime })
        assertEquals(PreviewKind.AUDIO to "audio/webm",
            assess("sound.webm", "audio/webm", pad(0x1a, 0x45, 0xdf, 0xa3)).let { it.preview to it.mime })
        assertEquals(FamilyLabel.AUDIO, assess("sound.webm", "audio/webm", pad(0x1a, 0x45, 0xdf, 0xa3)).family)
    }

    @Test fun unknownMediaIsInlineButKnownNonMediaMismatches() {
        assertEquals(AttachmentTier.INLINE, assess("clip.bin2", "video/x-weird", bytes(1, 2, 0, 3)).tier)
        assertEquals(AttachmentTier.WARN, assess("clip.mp4", "video/mp4", zip).tier)
        assertEquals(SafetyReason.MISMATCH, assess("notes.txt", "text/plain", bytes(1, 0, 2, 3)).reason)
    }

    @Test fun quietSaveDecisionIsComplete() {
        assertEquals(AttachmentDecision(
            name = "budget.docx", ext = "docx", kind = SniffKind.ZIP, family = FamilyLabel.DOCUMENT,
            tier = AttachmentTier.SAVE, reason = SafetyReason.SAVE, save = SaveTier.QUIET,
            preview = PreviewKind.NONE, mime = "application/octet-stream",
        ), assess("budget.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", zip))
        assertEquals(AttachmentTier.INLINE, assess("data.csv", "text/csv", text).tier)
        assertEquals(AttachmentTier.SAVE, assess("mystery", "application/octet-stream", bytes(1, 0, 2)).tier)
    }

    @Test fun familyAndAliasUseTheSamePolicy() {
        assertEquals(FamilyLabel.WINDOWS_PROGRAM, AttachmentSafety.familyLabel("exe", declaredType = "application/pdf"))
        assertEquals(FamilyLabel.IMAGE, AttachmentSafety.familyLabel("png", declaredType = "image/png"))
        assertEquals(FamilyLabel.FILE, AttachmentSafety.familyLabel(""))
        val input = AttachmentInput(text, "notes.txt", "text/plain")
        assertEquals(AttachmentSafety.assessAttachment(input), AttachmentSafety.classifyAttachment(input))
    }
}
