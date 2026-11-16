package dev.forgesworn.kithmoot.media.attachment

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.media.privacy.LocalAttachmentCleaner
import dev.forgesworn.kithmoot.session.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class OutgoingMetadataTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixtureContext = InstrumentationRegistry.getInstrumentation().context
    private val sentinel = "PRIVATE_METADATA_SENTINEL"

    private fun runFixture(block: suspend (File, LocalAttachmentCleaner) -> Unit) = runBlocking {
        Assume.assumeTrue("Disposable emulator only", Build.HARDWARE in setOf("ranchu", "goldfish"))
        val root = File(context.cacheDir, "metadata-test-${System.nanoTime()}").apply { mkdirs() }
        try { block(root, LocalAttachmentCleaner(context)) } finally { root.deleteRecursively() }
    }
    private suspend fun cleaned(bytes: ByteArray, name: String, type: String?, root: File, cleaner: LocalAttachmentCleaner): ByteArray {
        val sealed = sealPrivatePickedFile({ ByteArrayInputStream(bytes) }, bytes.size.toLong(), root, name, type, cleaner)
        val attachment = ChatAttachment("https://files.example/${sealed.hash}", sealed.hash, sealed.key, sealed.name, sealed.type, sealed.file.length())
        val opened = openFileAttachment(sealed.file, File(root, "opened-${System.nanoTime()}"), attachment)
        try { return opened.file.readBytes() } finally { opened.file.delete(); sealed.file.delete() }
    }
    private fun chunk(kind: String, payload: ByteArray): ByteArray {
        val content = kind.toByteArray() + payload
        return ByteBuffer.allocate(12 + payload.size).putInt(payload.size).put(content).putInt(CRC32().apply { update(content) }.value.toInt()).array()
    }

    @Test fun photos_drop_hidden_tags_and_keep_the_visible_pixels_despite_a_false_mime_hint() = runFixture { root, cleaner ->
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val tagged = png.copyOfRange(0, 33) + chunk("tEXt", "Author\u0000$sentinel".toByteArray()) + png.copyOfRange(33, png.size)
        val output = cleaned(tagged, "photo.png", "text/plain", root, cleaner)
        assertFalse(output.toString(Charsets.ISO_8859_1).contains(sentinel))
        val visible = BitmapFactory.decodeByteArray(output, 0, output.size)
        assertNotNull(visible)
        assertEquals(16, visible.width); assertEquals(16, visible.height); assertEquals(Color.RED, visible.getPixel(8, 8))
        visible.recycle()
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test fun audio_and_video_containers_drop_tags_and_still_have_playable_tracks() = runFixture { root, cleaner ->
        for (extension in listOf("wav", "mp3", "flac", "ogg", "mp4", "webm")) {
            val original = fixtureContext.assets.open("metadata/tagged.$extension").use { it.readBytes() }
            assertTrue("$extension fixture lacks private tags", original.toString(Charsets.ISO_8859_1).contains(sentinel))
            val output = cleaned(original, "sample.$extension", null, root, cleaner)
            File(context.cacheDir, "metadata-proof").apply { mkdirs() }.resolve("cleaned.$extension").writeBytes(output)
            assertTrue("$extension produced no media", output.isNotEmpty())
            assertFalse("$extension retained hidden metadata", output.toString(Charsets.ISO_8859_1).contains(sentinel))
            assertFalse("$extension did not rebuild its container", original.contentEquals(output))
            if (extension in listOf("mp4", "webm")) {
                val file = File(root, "playback.$extension").apply { writeBytes(output) }
                val extractor = android.media.MediaExtractor()
                try {
                    extractor.setDataSource(file.absolutePath)
                    assertTrue("$extension lost its tracks", extractor.trackCount > 0)
                    extractor.selectTrack(0)
                    assertTrue("$extension lost its packets", extractor.readSampleData(ByteBuffer.allocate(65536), 0) > 0)
                } finally { extractor.release(); file.delete() }
            }
        }
    }

    @Test fun pdf_pages_rebuild_without_hidden_author_or_old_document_data() = runFixture { root, cleaner ->
        val original = fixtureContext.assets.open("metadata/tagged.pdf").use { it.readBytes() }
        assertTrue(original.toString(Charsets.ISO_8859_1).contains(sentinel))
        val output = cleaned(original, "report.pdf", "application/pdf", root, cleaner)
        File(context.cacheDir, "metadata-proof").apply { mkdirs() }.resolve("cleaned.pdf").writeBytes(output)
        val file = File(root, "readable.pdf").apply { writeBytes(output) }
        val descriptor = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
        android.graphics.pdf.PdfRenderer(descriptor).use { pdf ->
            assertEquals(1, pdf.pageCount)
            pdf.openPage(0).use { page -> assertEquals(100, page.width); assertEquals(120, page.height) }
        }
        file.delete()
    }

    @Test fun office_documents_rebuild_without_author_properties_or_zip_comments() = runFixture { root, cleaner ->
        val archive = ByteArrayOutputStream()
        ZipOutputStream(archive).use { zip ->
            zip.setComment(sentinel)
            for ((name, text) in mapOf("[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Override PartName="/docProps/core.xml" ContentType="text/xml"/></Types>""",
                "docProps/core.xml" to "<author>$sentinel</author>",
                "word/document.xml" to """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>Visible words</w:t></w:r></w:p></w:body></w:document>""")) {
                zip.putNextEntry(ZipEntry(name).apply { comment = sentinel }); zip.write(text.toByteArray()); zip.closeEntry()
            }
        }
        val output = cleaned(archive.toByteArray(), "report.docx", null, root, cleaner)
        assertFalse(output.toString(Charsets.ISO_8859_1).contains(sentinel))
        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(output)).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; entries[entry.name] = zip.readBytes().decodeToString() }
        }
        assertFalse(entries.keys.any { it.startsWith("docProps/") })
        assertTrue(entries["word/document.xml"]!!.contains("Visible words"))
    }

    @Test fun unsupported_and_damaged_files_remain_unsent_and_leave_no_private_scratch() = runFixture { root, cleaner ->
        for ((name, bytes) in listOf("photo.jpg" to "MZprivate binary".toByteArray(), "broken.pdf" to "%PDF-broken".toByteArray())) {
            try { cleaned(bytes, name, "image/jpeg", root, cleaner); fail("Unsafe original must not be sealed") }
            catch (_: AttachmentCleaningFailed) { }
            assertTrue(root.listFiles()!!.isEmpty())
        }
        assertContentEquals("Visible words".toByteArray(), cleaned("Visible words".toByteArray(), "note.txt", null, root, cleaner))
    }

    private fun assertContentEquals(expected: ByteArray, actual: ByteArray) = assertTrue(expected.contentEquals(actual))
}
