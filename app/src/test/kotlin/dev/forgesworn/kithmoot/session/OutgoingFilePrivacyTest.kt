package dev.forgesworn.kithmoot.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class OutgoingFilePrivacyTest {
    private fun temporary(block: suspend (File) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("outgoing-privacy-").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
    private fun open(sealed: SealedFile, root: File): ByteArray = openFileAttachment(sealed.file, File(root, "opened"),
        ChatAttachment("https://files.example/${sealed.hash}", sealed.hash, sealed.key, sealed.name, sealed.type, sealed.file.length())).file.readBytes()

    @Test fun `only cleaned bytes and the cleaned name and type enter an envelope`() = temporary { root ->
        val original = "PRIVATE AUTHOR hidden metadata and image pixels".toByteArray()
        val clean = "image pixels".toByteArray()
        var calls = 0
        for (size in listOf(original.size.toLong(), null, 0L)) {
            val work = File(root, "work").apply { mkdirs() }
            val sealed = sealPrivatePickedFile({ ByteArrayInputStream(original) }, size, work, "photo.heic", "image/heic",
                AttachmentCleaner { source, destination, name, type ->
                    calls++
                    assertContentEquals(original, source.readBytes()); assertEquals("photo.heic", name); assertEquals("image/heic", type)
                    destination.writeBytes(clean)
                    CleanedAttachment("photo.png", "image/png", clean.size.toLong())
                })
            assertEquals("photo.png", sealed.name); assertEquals("image/png", sealed.type)
            assertContentEquals(clean, open(sealed, root))
            assertEquals(listOf(sealed.file.name), work.listFiles()!!.map { it.name })
            sealed.file.delete(); File(root, "opened").delete()
        }
        assertEquals(3, calls)
    }

    @Test fun `cleaning failure cancellation empty and mismatched output leave no original or envelope`() = temporary { root ->
        for (mode in 0..3) {
            val work = File(root, "work-$mode")
            val cleaner = AttachmentCleaner { _, destination, _, _ ->
                destination.writeText("partial")
                when (mode) {
                    0 -> throw AttachmentCleaningFailed()
                    1 -> throw CancellationException("fixture cancellation")
                    2 -> { destination.writeText(""); CleanedAttachment("a.txt", "text/plain", 0) }
                    else -> CleanedAttachment("a.txt", "text/plain", 100)
                }
            }
            assertFails { sealPrivatePickedFile({ ByteArrayInputStream("original".toByteArray()) }, null, work, "a.txt", null, cleaner) }
            assertTrue(work.listFiles()!!.isEmpty())
        }
    }

    @Test fun `declared and unknown oversized sources and changing provider size cannot reach the cleaner`() = temporary { root ->
        var calls = 0; var bytesRead = 0L
        val cleaner = AttachmentCleaner { _, _, _, _ -> calls++; throw AssertionError("Must not clean") }
        val endless = object : java.io.InputStream() {
            override fun read() = 0
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int { bytesRead += length; return length }
        }
        assertFailsWith<PickedFileTooLarge> { sealPrivatePickedFile({ endless }, 101, root, "a.bin", null, cleaner, 100) }
        assertEquals(0L, bytesRead)
        assertFailsWith<PickedFileTooLarge> { sealPrivatePickedFile({ endless }, null, root, "a.bin", null, cleaner, 100) }
        assertEquals(101L, bytesRead)
        assertFails { sealPrivatePickedFile({ ByteArrayInputStream(byteArrayOf(1, 2)) }, 3, root, "a.bin", null, cleaner) }
        assertEquals(0, calls); assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test fun `recording shares clean a fresh copy and leave the local recording intact`() = temporary { root ->
        val source = File(root, "call.mp4").apply { writeText("original recording with private tags") }
        val sealed = sealCleanedFile(source, File(root, "call.enc"), root, source.name, "video/mp4",
            AttachmentCleaner { input, output, _, _ ->
                assertEquals(source, input); output.writeText("clean packets")
                CleanedAttachment("call.mp4", "video/mp4", output.length())
            })
        assertEquals("original recording with private tags", source.readText())
        assertEquals("clean packets", open(sealed, root).decodeToString())
        assertFalse(root.listFiles()!!.any { it.name.startsWith(".cleaned-") })
        java.io.RandomAccessFile(source, "rw").use { it.setLength(MAX_CLEAN_ATTACHMENT_BYTES + 1) }
        assertFailsWith<PickedFileTooLarge> {
            sealCleanedFile(source, File(root, "oversized.enc"), root, source.name, "video/mp4",
                AttachmentCleaner { _, _, _, _ -> throw AssertionError("Oversized local recordings must stay unsent") })
        }
        assertEquals(MAX_CLEAN_ATTACHMENT_BYTES + 1, source.length())
        assertFalse(File(root, "oversized.enc").exists())

    }
}
