package dev.forgesworn.kithmoot.protocol

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.text.Normalizer
import java.util.Locale

/** The cleaned name to display and save under. */
data class CleanedFileName(
    val name: String,
    val ext: String,
    val hadDirectionControls: Boolean,
)

enum class SniffKind(val wire: String) {
    PNG("png"), JPEG("jpeg"), GIF("gif"), WEBP("webp"), AVIF("avif"), HEIC("heic"),
    BMP("bmp"), ICO("ico"), SVG("svg"), PDF("pdf"), MP4("mp4"), WEBM("webm"),
    OGG("ogg"), MP3("mp3"), WAV("wav"), FLAC("flac"), ZIP("zip"), OLE("ole"),
    EXE_WINDOWS("exe-windows"), EXE_ELF("exe-elf"), EXE_MAC("exe-mac"), SCRIPT("script"),
    SHORTCUT_WINDOWS("shortcut-windows"), TEXT("text"), UNKNOWN("unknown"),
    ;

    companion object {
        fun fromWire(value: String): SniffKind = entries.first { it.wire == value }
    }
}

enum class SniffFamily { IMAGE, SVG, PDF, MEDIA, ARCHIVE, OLE, PROGRAM, SHORTCUT, TEXT, UNKNOWN }
enum class DeclaredFamily { IMAGE, SVG, PDF, VIDEO, AUDIO, TEXT, OTHER }

enum class FamilyLabel(val label: String, val phrase: String) {
    IMAGE("Image", "a picture"),
    VIDEO("Video", "a video"),
    AUDIO("Audio", "an audio file"),
    PDF("PDF", "a PDF"),
    TEXT("Text", "a text file"),
    DOCUMENT("Document", "a document"),
    SPREADSHEET("Spreadsheet", "a spreadsheet"),
    PRESENTATION("Presentation", "a presentation"),
    WINDOWS_PROGRAM("Windows program", "a Windows program"),
    MAC_APP("Mac app", "a Mac app"),
    LINUX_PROGRAM("Linux program", "a Linux program"),
    ANDROID_APP("Android app", "an Android app"),
    SCRIPT("Script", "a script"),
    SHORTCUT("Shortcut", "a shortcut"),
    DISK_IMAGE("Disk image", "a disk image"),
    ARCHIVE("Archive", "an archive"),
    OFFICE_MACROS("Office file with macros", "an Office file with macros"),
    WEB_PAGE("Web page", "a web page"),
    FILE("File", "a file"),
}

enum class SaveTier(val wire: String) { QUIET("quiet"), WARN("warn"), BLOCK("block") }
enum class PreviewKind(val wire: String) {
    IMAGE("image"), VIDEO("video"), AUDIO("audio"), PDF("pdf"), TEXT("text"), NONE("none"),
}
enum class SafetyReason(val wire: String) {
    DISGUISED_PROGRAM("disguised-program"), SHORTCUT("shortcut"),
    DIRECTION_CONTROLS("direction-controls"), NAME_CHANGED("name-changed"), MISMATCH("mismatch"),
    PROGRAM("program"), SCRIPT("script"), DISK_IMAGE("disk-image"), MACROS("macros"),
    ARCHIVE("archive"), WEB_PAGE("web-page"), INLINE("inline"), SAVE("save"),
    ;

    companion object {
        fun fromWire(value: String): SafetyReason = entries.first { it.wire == value }
    }
}
enum class AttachmentTier(val wire: String) {
    INLINE("inline"), SAVE("save"), WARN("warn"), BLOCK("block"),
    ;

    companion object {
        fun fromWire(value: String): AttachmentTier = entries.first { it.wire == value }
    }
}

/** Authenticated plaintext and the untrusted names and type carried with it. */
data class AttachmentInput(
    val bytes: ByteArray,
    val envelopeName: String,
    val declaredType: String,
    val messageName: String? = null,
    val sender: String? = null,
)

/** Every field a caller needs; no later renderer may invent a different verdict. */
data class AttachmentDecision(
    val name: String,
    val ext: String,
    val kind: SniffKind,
    val family: FamilyLabel,
    val tier: AttachmentTier,
    val reason: SafetyReason,
    val save: SaveTier,
    val preview: PreviewKind,
    val mime: String,
    val message: String? = null,
    val confirmation: String? = null,
)

/**
 * Pure classification of a fetched, hash-checked and decrypted attachment.
 * This performs no I/O, mutation, rendering, scanning, cancellation or lifecycle work.
 */
object AttachmentSafety {
    const val SNIFF_BYTES = 1024
    private const val MAX_CLEAN_NAME = 180
    private const val OPAQUE = "application/octet-stream"

    private val DIRECTION_CONTROLS = Regex("[\u202a-\u202e\u2066-\u2069]")
    private val BLANK_FILLERS = Regex("[\u115f\u1160\u3164\uffa0]")
    private val CONTROL_AND_FORMAT = Regex("[\\p{Cc}\\p{Cf}]")
    private val WHITESPACE = Regex("[\\t\\n\\x0B\\f\\r \\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]+")
    private val WINDOWS_DEVICE = Regex("^(?:con|prn|aux|nul|com[1-9]|lpt[1-9])$", RegexOption.IGNORE_CASE)
    private val EXTENSION = Regex("[a-z0-9-]{1,10}")
    private val DOCTYPE = Regex("^<!doctype[^>]*>", RegexOption.IGNORE_CASE)

    val PROGRAM_EXTENSIONS = extensions("exe com scr pif msi msix msixbundle appx appxbundle msp mst cpl dll sys drv ocx gadget application xbap hta jar app pkg mpkg deb rpm apk aab xapk apks ipa appimage flatpakref flatpakrepo snap run bin elf so dylib kext prefpane mobileconfig xpi crx vsix")
    val SCRIPT_EXTENSIONS = extensions("bat cmd ps1 psm1 psd1 ps1xml vbs vbe js jse mjs wsf wsh wsc sct msc reg inf sh bash zsh csh ksh fish command tool py pyw pyc rb pl php lua applescript scpt scptd workflow action")
    val SHORTCUT_EXTENSIONS = extensions("lnk url website webloc inetloc fileloc desktop scf library-ms searchconnector-ms settingcontent-ms appref-ms glk terminal")
    val DISK_IMAGE_EXTENSIONS = extensions("iso img dmg vhd vhdx vmdk sparseimage sparsebundle")
    val MACRO_EXTENSIONS = extensions("docm dotm xlsm xltm xlsb xlam xla xll pptm potm ppam ppsm sldm one iqy slk")
    val ARCHIVE_EXTENSIONS = extensions("zip 7z rar tar gz tgz bz2 tbz2 xz txz zst cab arj lzh lha ace")
    val WEB_EXTENSIONS = extensions("html htm xhtml shtml mht mhtml svg svgz xsl chm swf")

    private val WINDOWS_EXT = extensions("exe com scr pif msi msix msixbundle appx appxbundle msp mst cpl dll sys drv ocx gadget application xbap hta")
    private val MAC_EXT = extensions("app pkg mpkg ipa dylib kext prefpane mobileconfig")
    private val LINUX_EXT = extensions("deb rpm appimage flatpakref flatpakrepo snap run bin elf so")
    private val ANDROID_EXT = extensions("apk aab xapk apks")
    private val DOCUMENT_EXT = extensions("doc docx odt rtf pages epub")
    private val SPREADSHEET_EXT = extensions("xls xlsx ods csv numbers")
    private val PRESENTATION_EXT = extensions("ppt pptx odp key")
    private val SNIFFED_MIME = mapOf(
        SniffKind.PNG to "image/png", SniffKind.JPEG to "image/jpeg", SniffKind.GIF to "image/gif",
        SniffKind.WEBP to "image/webp", SniffKind.AVIF to "image/avif", SniffKind.HEIC to "image/heic",
        SniffKind.BMP to "image/bmp", SniffKind.ICO to "image/x-icon", SniffKind.SVG to "image/svg+xml",
        SniffKind.PDF to "application/pdf", SniffKind.MP3 to "audio/mpeg", SniffKind.WAV to "audio/wav",
        SniffKind.FLAC to "audio/flac",
    )

    private fun extensions(value: String): Set<String> = value.split(' ').toSet()
    private fun ByteArray.unsigned(at: Int): Int = if (at in indices) this[at].toInt() and 0xff else -1
    private fun lower(value: String): String = value.lowercase(Locale.ROOT)
    private fun mediaType(value: String): String = lower(value.substringBefore(';').trimJs())
    private fun isJsWhitespace(character: Char): Boolean = WHITESPACE.matches(character.toString())
    private fun String.trimJs(): String = trim(::isJsWhitespace)
    private fun String.trimStartJs(): String = trimStart(::isJsWhitespace)

    private fun extensionOf(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return ""
        val ext = lower(name.substring(dot + 1))
        return if (EXTENSION.matches(ext)) ext else ""
    }

    private fun cutUnits(text: String, limit: Int): String {
        if (text.length <= limit) return text
        var end = limit
        if (text[end - 1].isHighSurrogate()) end--
        return text.substring(0, end)
    }

    fun cleanFileName(raw: String, fallbackExt: String = ""): CleanedFileName {
        var value = Normalizer.normalize(raw, Normalizer.Form.NFC)
        val hadDirectionControls = DIRECTION_CONTROLS.containsMatchIn(value)
        value = value.replace(WHITESPACE, " ")
            .replace(CONTROL_AND_FORMAT, "")
            .replace(BLANK_FILLERS, "")
            .replace(Regex("[/\\\\<>:\"|?*]"), "_")
            .trimJs().replace(Regex("^\\.+"), "").replace(Regex("[. ]+$"), "")
        if (value.isNotEmpty()) {
            val firstDot = value.indexOf('.')
            val stem = if (firstDot == -1) value else value.substring(0, firstDot)
            if (WINDOWS_DEVICE.matches(stem)) value = "_$value"
        }
        if (value.isEmpty()) value = if (fallbackExt.isNotEmpty()) "file.$fallbackExt" else "file"
        if (value.length > MAX_CLEAN_NAME) {
            val dot = value.lastIndexOf('.')
            val ext = if (dot > 0 && value.length - dot <= 11) value.substring(dot) else ""
            value = cutUnits(value.substring(0, value.length - ext.length), MAX_CLEAN_NAME - ext.length) + ext
        }
        return CleanedFileName(value, extensionOf(value), hadDirectionControls)
    }

    private fun startsWith(bytes: ByteArray, at: Int, vararg expected: Int): Boolean =
        expected.indices.all { bytes.unsigned(at + it) == expected[it] }

    private fun ascii(bytes: ByteArray, at: Int, expected: String): Boolean =
        expected.indices.all { bytes.unsigned(at + it) == expected[it].code }

    private fun decodesAsUtf8(head: ByteArray): Boolean {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val result = decoder.decode(ByteBuffer.wrap(head), CharBuffer.allocate(head.size + 1), false)
        return !result.isError
    }

    private fun opensWithSvg(head: ByteArray): Boolean {
        var value = String(head, Charsets.UTF_8).removePrefix("\ufeff")
        while (true) {
            value = value.trimStartJs()
            if (value.startsWith("<?")) {
                val end = value.indexOf("?>")
                if (end < 0) return false
                value = value.substring(end + 2)
                continue
            }
            if (value.startsWith("<!--")) {
                val end = value.indexOf("-->")
                if (end < 0) return false
                value = value.substring(end + 3)
                continue
            }
            val doctype = DOCTYPE.find(value)
            if (doctype != null) {
                value = value.substring(doctype.range.last + 1)
                continue
            }
            break
        }
        return value.length >= 5 && value.substring(0, 4).equals("<svg", ignoreCase = true) &&
            (isJsWhitespace(value[4]) || value[4] == '>' || value[4] == '/')
    }

    fun sniffKind(bytes: ByteArray): SniffKind {
        val head = bytes.copyOfRange(0, minOf(bytes.size, SNIFF_BYTES))
        if (startsWith(head, 0, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) return SniffKind.PNG
        if (startsWith(head, 0, 0xff, 0xd8, 0xff)) return SniffKind.JPEG
        if (ascii(head, 0, "GIF87a") || ascii(head, 0, "GIF89a")) return SniffKind.GIF
        if (ascii(head, 0, "RIFF") && ascii(head, 8, "WEBP")) return SniffKind.WEBP
        if (ascii(head, 0, "RIFF") && ascii(head, 8, "WAVE")) return SniffKind.WAV
        if (ascii(head, 4, "ftyp")) {
            if (ascii(head, 8, "avif") || ascii(head, 8, "avis")) return SniffKind.AVIF
            if (ascii(head, 8, "heic") || ascii(head, 8, "heix") || ascii(head, 8, "mif1")) return SniffKind.HEIC
            return SniffKind.MP4
        }
        if (ascii(head, 0, "BM")) return SniffKind.BMP
        if (startsWith(head, 0, 0x00, 0x00, 0x01, 0x00)) return SniffKind.ICO
        if (startsWith(head, 0, 0x1a, 0x45, 0xdf, 0xa3)) return SniffKind.WEBM
        if (ascii(head, 0, "OggS")) return SniffKind.OGG
        if (ascii(head, 0, "fLaC")) return SniffKind.FLAC
        if (ascii(head, 0, "ID3") || head.unsigned(0) == 0xff && head.unsigned(1).let { it >= 0 && it and 0xe0 == 0xe0 }) return SniffKind.MP3
        if (startsWith(head, 0, 0x50, 0x4b, 0x03, 0x04) || startsWith(head, 0, 0x50, 0x4b, 0x05, 0x06) ||
            startsWith(head, 0, 0x50, 0x4b, 0x07, 0x08)) return SniffKind.ZIP
        if (startsWith(head, 0, 0xd0, 0xcf, 0x11, 0xe0, 0xa1, 0xb1, 0x1a, 0xe1)) return SniffKind.OLE
        if (ascii(head, 0, "MZ")) return SniffKind.EXE_WINDOWS
        if (startsWith(head, 0, 0x7f, 0x45, 0x4c, 0x46)) return SniffKind.EXE_ELF
        if (startsWith(head, 0, 0xfe, 0xed, 0xfa, 0xce) || startsWith(head, 0, 0xfe, 0xed, 0xfa, 0xcf) ||
            startsWith(head, 0, 0xce, 0xfa, 0xed, 0xfe) || startsWith(head, 0, 0xcf, 0xfa, 0xed, 0xfe) ||
            startsWith(head, 0, 0xca, 0xfe, 0xba, 0xbe)) return SniffKind.EXE_MAC
        if (ascii(head, 0, "#!")) return SniffKind.SCRIPT
        if (startsWith(head, 0, 0x4c, 0x00, 0x00, 0x00, 0x01, 0x14, 0x02, 0x00)) return SniffKind.SHORTCUT_WINDOWS
        if (indexOfAscii(head, "%PDF-") >= 0) return SniffKind.PDF
        if (head.none { it == 0.toByte() } && decodesAsUtf8(head)) {
            return if (opensWithSvg(head)) SniffKind.SVG else SniffKind.TEXT
        }
        return SniffKind.UNKNOWN
    }

    private fun indexOfAscii(bytes: ByteArray, expected: String): Int {
        for (offset in 0..bytes.size - expected.length) {
            if (expected.indices.all { bytes.unsigned(offset + it) == expected[it].code }) return offset
        }
        return -1
    }

    fun sniffFamily(kind: SniffKind): SniffFamily = when (kind) {
        SniffKind.PNG, SniffKind.JPEG, SniffKind.GIF, SniffKind.WEBP, SniffKind.AVIF,
        SniffKind.HEIC, SniffKind.BMP, SniffKind.ICO -> SniffFamily.IMAGE
        SniffKind.SVG -> SniffFamily.SVG
        SniffKind.PDF -> SniffFamily.PDF
        SniffKind.MP4, SniffKind.WEBM, SniffKind.OGG, SniffKind.MP3, SniffKind.WAV, SniffKind.FLAC -> SniffFamily.MEDIA
        SniffKind.ZIP -> SniffFamily.ARCHIVE
        SniffKind.OLE -> SniffFamily.OLE
        SniffKind.EXE_WINDOWS, SniffKind.EXE_ELF, SniffKind.EXE_MAC, SniffKind.SCRIPT -> SniffFamily.PROGRAM
        SniffKind.SHORTCUT_WINDOWS -> SniffFamily.SHORTCUT
        SniffKind.TEXT -> SniffFamily.TEXT
        SniffKind.UNKNOWN -> SniffFamily.UNKNOWN
    }

    fun declaredFamily(type: String): DeclaredFamily {
        val value = mediaType(type)
        if (value == "image/svg+xml") return DeclaredFamily.SVG
        if (value.startsWith("image/")) return DeclaredFamily.IMAGE
        if (value == "application/pdf") return DeclaredFamily.PDF
        if (value.startsWith("video/")) return DeclaredFamily.VIDEO
        if (value.startsWith("audio/")) return DeclaredFamily.AUDIO
        if (value.startsWith("text/") || value == "application/json" || value == "application/xml") return DeclaredFamily.TEXT
        return DeclaredFamily.OTHER
    }

    fun labelPhrase(label: FamilyLabel): String = label.phrase

    fun familyLabel(ext: String, kind: SniffKind? = null, declaredType: String? = null): FamilyLabel {
        if (kind == SniffKind.EXE_WINDOWS) return FamilyLabel.WINDOWS_PROGRAM
        if (kind == SniffKind.EXE_ELF) return FamilyLabel.LINUX_PROGRAM
        if (kind == SniffKind.EXE_MAC) return FamilyLabel.MAC_APP
        if (kind == SniffKind.SCRIPT) return FamilyLabel.SCRIPT
        if (kind == SniffKind.SHORTCUT_WINDOWS) return FamilyLabel.SHORTCUT
        if (kind != null && sniffFamily(kind) == SniffFamily.IMAGE) return FamilyLabel.IMAGE
        if (kind == SniffKind.SVG) return FamilyLabel.IMAGE
        if (kind == SniffKind.PDF) return FamilyLabel.PDF
        if (ext in ANDROID_EXT) return FamilyLabel.ANDROID_APP
        if (ext in LINUX_EXT) return FamilyLabel.LINUX_PROGRAM
        if (ext in MAC_EXT) return FamilyLabel.MAC_APP
        if (ext in WINDOWS_EXT || ext == "jar") return FamilyLabel.WINDOWS_PROGRAM
        if (ext in SCRIPT_EXTENSIONS) return FamilyLabel.SCRIPT
        if (ext in SHORTCUT_EXTENSIONS) return FamilyLabel.SHORTCUT
        if (ext in DISK_IMAGE_EXTENSIONS) return FamilyLabel.DISK_IMAGE
        if (ext in MACRO_EXTENSIONS) return FamilyLabel.OFFICE_MACROS
        if (ext in ARCHIVE_EXTENSIONS) return FamilyLabel.ARCHIVE
        if (ext in WEB_EXTENSIONS && ext != "svg" && ext != "svgz") return FamilyLabel.WEB_PAGE
        if (kind == SniffKind.MP4 || kind == SniffKind.WEBM || kind == SniffKind.OGG) {
            return if (declaredType != null && lower(declaredType).startsWith("audio/")) FamilyLabel.AUDIO else FamilyLabel.VIDEO
        }
        if (kind == SniffKind.MP3 || kind == SniffKind.WAV || kind == SniffKind.FLAC) return FamilyLabel.AUDIO
        if (kind == SniffKind.ZIP && ext !in DOCUMENT_EXT && ext !in SPREADSHEET_EXT && ext !in PRESENTATION_EXT) return FamilyLabel.ARCHIVE
        if (ext in DOCUMENT_EXT) return FamilyLabel.DOCUMENT
        if (ext in SPREADSHEET_EXT) return FamilyLabel.SPREADSHEET
        if (ext in PRESENTATION_EXT) return FamilyLabel.PRESENTATION
        return when (declaredFamily(declaredType.orEmpty())) {
            DeclaredFamily.IMAGE, DeclaredFamily.SVG -> FamilyLabel.IMAGE
            DeclaredFamily.VIDEO -> FamilyLabel.VIDEO
            DeclaredFamily.AUDIO -> FamilyLabel.AUDIO
            DeclaredFamily.PDF -> FamilyLabel.PDF
            DeclaredFamily.TEXT -> FamilyLabel.TEXT
            DeclaredFamily.OTHER -> when {
                kind == SniffKind.TEXT -> FamilyLabel.TEXT
                ext == "svg" || ext == "svgz" -> FamilyLabel.IMAGE
                else -> FamilyLabel.FILE
            }
        }
    }

    private fun usualExtension(kind: SniffKind): String = when (kind) {
        SniffKind.JPEG -> "jpg"
        SniffKind.PNG, SniffKind.GIF, SniffKind.WEBP, SniffKind.AVIF, SniffKind.HEIC,
        SniffKind.BMP, SniffKind.ICO, SniffKind.SVG, SniffKind.PDF, SniffKind.MP4,
        SniffKind.WEBM, SniffKind.OGG, SniffKind.MP3, SniffKind.WAV, SniffKind.FLAC,
        SniffKind.ZIP -> kind.wire
        SniffKind.TEXT -> "txt"
        else -> ""
    }

    private fun mediaMime(kind: SniffKind, declared: String): String {
        val base = mediaType(declared)
        if (base.startsWith("video/") || base.startsWith("audio/")) return base
        return when (kind) {
            SniffKind.WEBM -> "video/webm"
            SniffKind.OGG -> "audio/ogg"
            else -> "video/mp4"
        }
    }

    private fun ownLine(reason: SafetyReason, sender: String, confirming: Boolean): String {
        val it = if (confirming) "it" else "this"
        return when (reason) {
            SafetyReason.PROGRAM -> "Programs can take over your computer. Only save $it if you asked $sender for it."
            SafetyReason.SCRIPT -> "Scripts can change or take over your computer. Only save $it if you asked $sender for it."
            SafetyReason.DISK_IMAGE -> "A disk image can hold programs. Only save $it if you asked $sender for it."
            SafetyReason.MACROS -> "Macros are programs inside a document. Only save $it if you asked $sender for it, and don’t turn macros on."
            SafetyReason.ARCHIVE -> "An archive can hold programs. Only save $it if you were expecting it."
            SafetyReason.WEB_PAGE -> "Opened from your computer, a web page can run code. Only save $it if you were expecting it."
            else -> ""
        }
    }

    private fun ownReason(ext: String, kind: SniffKind, mismatch: Boolean): SafetyReason? = when {
        ext in PROGRAM_EXTENSIONS -> SafetyReason.PROGRAM
        ext in SCRIPT_EXTENSIONS -> SafetyReason.SCRIPT
        ext in DISK_IMAGE_EXTENSIONS -> SafetyReason.DISK_IMAGE
        ext in MACRO_EXTENSIONS -> SafetyReason.MACROS
        ext in ARCHIVE_EXTENSIONS -> SafetyReason.ARCHIVE
        ext in WEB_EXTENSIONS -> SafetyReason.WEB_PAGE
        kind == SniffKind.EXE_WINDOWS || kind == SniffKind.EXE_ELF || kind == SniffKind.EXE_MAC -> SafetyReason.PROGRAM
        kind == SniffKind.SCRIPT -> SafetyReason.SCRIPT
        mismatch && kind == SniffKind.ZIP -> SafetyReason.ARCHIVE
        else -> null
    }

    fun assessAttachment(input: AttachmentInput): AttachmentDecision {
        val kind = sniffKind(input.bytes.copyOfRange(0, minOf(input.bytes.size, SNIFF_BYTES)))
        val cleaned = cleanFileName(input.envelopeName, usualExtension(kind))
        val name = cleaned.name
        val ext = cleaned.ext
        val sender = input.sender?.trimJs().orEmpty().ifEmpty { "the sender" }
        val sniffed = sniffFamily(kind)
        val declared = declaredFamily(input.declaredType)
        val label = familyLabel(ext, kind, input.declaredType)
        val declaredLabel = familyLabel("", declaredType = input.declaredType)

        fun decision(
            tier: AttachmentTier, reason: SafetyReason, save: SaveTier, preview: PreviewKind, mime: String,
            message: String? = null, confirmation: String? = null,
        ) = AttachmentDecision(name, ext, kind, label, tier, reason, save, preview, mime, message, confirmation)

        if (sniffed == SniffFamily.PROGRAM && ext !in PROGRAM_EXTENSIONS && ext !in SCRIPT_EXTENSIONS) {
            val namedLike = if (declared == DeclaredFamily.OTHER) familyLabel(ext) else declaredLabel
            return decision(AttachmentTier.BLOCK, SafetyReason.DISGUISED_PROGRAM, SaveTier.BLOCK, PreviewKind.NONE, OPAQUE,
                "KithMoot won’t open or save this. It’s named like ${labelPhrase(namedLike)}, but it’s really ${labelPhrase(label)}. That’s a common way to hide an attack.")
        }
        if (ext in SHORTCUT_EXTENSIONS || kind == SniffKind.SHORTCUT_WINDOWS) {
            return decision(AttachmentTier.BLOCK, SafetyReason.SHORTCUT, SaveTier.BLOCK, PreviewKind.NONE, OPAQUE,
                "KithMoot won’t save shortcuts. A shortcut runs something on your computer, and there’s nothing in it to read.")
        }

        val own = ownReason(ext, kind, false)
        val mismatch = when (declared) {
            DeclaredFamily.IMAGE -> sniffed != SniffFamily.IMAGE
            DeclaredFamily.SVG -> sniffed != SniffFamily.SVG
            DeclaredFamily.PDF -> sniffed != SniffFamily.PDF
            DeclaredFamily.TEXT -> sniffed != SniffFamily.TEXT && sniffed != SniffFamily.SVG
            DeclaredFamily.VIDEO, DeclaredFamily.AUDIO -> sniffed != SniffFamily.MEDIA && sniffed != SniffFamily.UNKNOWN
            DeclaredFamily.OTHER -> false
        }
        val messageName = input.messageName?.let { cleanFileName(it) }
        val nameChanged = messageName != null && messageName.ext != ext
        val warned = own != null || mismatch || cleaned.hadDirectionControls || nameChanged
        if (warned) {
            val ownFinal = ownReason(ext, kind, mismatch)
            val sniffedPhrase = labelPhrase(label)
            val reason: SafetyReason
            val message: String
            if (cleaned.hadDirectionControls) {
                reason = SafetyReason.DIRECTION_CONTROLS
                val tail = if (declared != DeclaredFamily.OTHER && sniffed != SniffFamily.UNKNOWN && declaredLabel != label) {
                    " It’s $sniffedPhrase, not ${labelPhrase(declaredLabel)}."
                } else ""
                message = "This file’s name had hidden characters that make it read differently. KithMoot removed them.$tail"
            } else if (nameChanged) {
                reason = SafetyReason.NAME_CHANGED
                message = "This file was sent as ${messageName!!.name}, but the file inside is $name."
            } else if (mismatch) {
                reason = SafetyReason.MISMATCH
                message = "This file says it’s ${labelPhrase(declaredLabel)}, but it’s really $sniffedPhrase."
            } else {
                reason = requireNotNull(ownFinal)
                message = ownLine(reason, sender, false)
            }
            val parts = if (reason == ownFinal) {
                listOf("It’s $sniffedPhrase.", ownLine(reason, sender, true))
            } else {
                listOfNotNull(message, ownFinal?.let { ownLine(it, sender, true) })
            }
            var preview = PreviewKind.NONE
            var mime = OPAQUE
            if (reason == SafetyReason.WEB_PAGE && !mismatch) {
                if (sniffed == SniffFamily.TEXT && declared == DeclaredFamily.TEXT) {
                    preview = PreviewKind.TEXT
                    mime = "text/plain"
                } else if (sniffed == SniffFamily.SVG && (declared == DeclaredFamily.SVG || declared == DeclaredFamily.IMAGE)) {
                    preview = PreviewKind.IMAGE
                    mime = "image/svg+xml"
                } else if (sniffed == SniffFamily.SVG && declared == DeclaredFamily.TEXT) {
                    preview = PreviewKind.TEXT
                    mime = "text/plain"
                }
            }
            return decision(AttachmentTier.WARN, reason, SaveTier.WARN, preview, mime, message, parts.joinToString(" "))
        }

        val inline = when (declared) {
            DeclaredFamily.IMAGE -> SNIFFED_MIME.getValue(kind).let { PreviewKind.IMAGE to it }
            DeclaredFamily.SVG -> PreviewKind.IMAGE to "image/svg+xml"
            DeclaredFamily.PDF -> PreviewKind.PDF to "application/pdf"
            DeclaredFamily.TEXT -> PreviewKind.TEXT to "text/plain"
            DeclaredFamily.VIDEO, DeclaredFamily.AUDIO -> {
                val mime = if (sniffed == SniffFamily.MEDIA) mediaMime(kind, input.declaredType) else mediaType(input.declaredType)
                (if (mime.startsWith("audio/")) PreviewKind.AUDIO else PreviewKind.VIDEO) to mime
            }
            DeclaredFamily.OTHER -> null
        }
        if (inline != null && inline.second.isNotEmpty()) {
            return decision(AttachmentTier.INLINE, SafetyReason.INLINE, SaveTier.QUIET, inline.first, inline.second)
        }
        return decision(AttachmentTier.SAVE, SafetyReason.SAVE, SaveTier.QUIET, PreviewKind.NONE, OPAQUE)
    }

    fun classifyAttachment(input: AttachmentInput): AttachmentDecision = assessAttachment(input)
}
