package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.toHex
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class FileAttachmentTest {
    private fun inDirectory(test: (File) -> Unit) {
        val directory = Files.createTempDirectory("recording-envelope-").toFile()
        try { test(directory) } finally { directory.deleteRecursively() }
    }

    @Test fun `file reader opens independent web single and multirecord vectors`() = inDirectory { directory ->
        for (name in listOf("web-image", "web-multirecord")) {
            val metadata = Json.parseToJsonElement(javaClass.getResource("/attachments/$name.json")!!.readText()).jsonObject
            val envelope = File(directory, "$name.enc")
            javaClass.getResourceAsStream("/attachments/$name.enc")!!.use { input -> envelope.outputStream().use { output -> input.copyTo(output) } }
            val opened = openFileAttachment(envelope, File(directory, "$name.png"), ChatAttachment(
                "https://files.example/image", metadata.getValue("sha256").jsonPrimitive.content,
                metadata.getValue("key").jsonPrimitive.content,
            ))
            assertEquals("picture.png", opened.name)
            assertEquals("image/png", opened.type)
            if (name == "web-image") assertEquals("89504e470d0a1a0a", opened.file.inputStream().use { it.readNBytes(8) }.toHex())
            else { assertEquals(1_100_000L, opened.size); assertTrue(opened.file.readBytes().all { it == 73.toByte() }) }
        }
    }

    @Test fun `streams a recording exceeding both image limits with bounded working buffers`() = inDirectory { directory ->
        val source = File(directory, "call.mp4")
        val block = ByteArray(1024 * 1024) { (it % 251).toByte() }
        source.outputStream().use { output -> repeat(34) { output.write(block) } }
        val sealed = sealFile(source, File(directory, "call.enc"), source.name, "video/mp4")
        val opened = openFileAttachment(sealed.file, File(directory, "opened.mp4"), ChatAttachment(
            "https://files.example/${sealed.hash}", sealed.hash, sealed.key,
        ))
        assertEquals(source.length(), opened.size)
        assertEquals(fileSha256(source), fileSha256(opened.file))
        assertEquals("video/mp4", opened.type)
        exportInterop(sealed, source)
    }

    @Test fun `authentication failure exposes no plaintext including a tampered final record`() = inDirectory { directory ->
        val source = File(directory, "call.wav").also { it.writeBytes(ByteArray(1_100_000) { 73 }) }
        val sealed = sealFile(source, File(directory, "call.enc"), source.name, "audio/wav")
        val attachment = ChatAttachment("https://files.example/file", sealed.hash, sealed.key)
        assertFails { openFileAttachment(sealed.file, File(directory, "wrong-key"), attachment.copy(key = "00".repeat(32))) }
        assertFails { openFileAttachment(sealed.file, File(directory, "wrong-hash"), attachment.copy(sha256 = "00".repeat(32))) }
        java.io.RandomAccessFile(sealed.file, "rw").use { file ->
            file.seek(file.length() - 1); val value = file.read(); file.seek(file.length() - 1); file.write(value xor 1)
        }
        assertFails { openFileAttachment(sealed.file, File(directory, "tampered"), attachment.copy(sha256 = fileSha256(sealed.file))) }
        assertEquals(setOf("call.wav", "call.enc"), directory.listFiles()!!.map { it.name }.toSet())
    }

    @Test fun `never overwrites existing files and rejects unsupported types before writing`() = inDirectory { directory ->
        val source = File(directory, "call.wav").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
        val destination = File(directory, "existing").also { it.writeText("keep") }
        assertFails { sealFile(source, destination, "call.wav", "audio/wav") }
        assertEquals("keep", destination.readText())
        assertFails { sealFile(source, File(directory, "unsupported"), "call.wav", "application/octet-stream") }
        val sealed = sealFile(source, File(directory, "call.enc"), "call.wav", "audio/wav")
        assertFails { openFileAttachment(sealed.file, destination, ChatAttachment("https://files.example/file", sealed.hash, sealed.key)) }
        assertEquals("keep", destination.readText())
        assertFalse(File(directory, "unsupported").exists())
    }

    @Test fun `interruption removes partial encrypted output`() = inDirectory { directory ->
        val source = File(directory, "call.wav").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
        Thread.currentThread().interrupt()
        try { assertFails { sealFile(source, File(directory, "call.enc"), source.name, "audio/wav") } }
        finally { Thread.interrupted() }
        assertFalse(File(directory, "call.enc").exists())
    }

    @Test fun `exports a synthetic mixed call for the independent Wildbloom reader`() = inDirectory { directory ->
        val source = File(directory, "synthetic-call.wav")
        val mixer = dev.forgesworn.kithmoot.media.recording.PcmRecordingMixer()
        mixer.setSources(setOf("local", "remote"))
        dev.forgesworn.kithmoot.media.recording.PcmWaveFile(source).use { writer ->
            repeat(100) { block ->
                for ((name, frequency) in listOf("local" to 440, "remote" to 660)) {
                    val buffer = java.nio.ByteBuffer.allocate(960).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    repeat(480) { offset ->
                        val frame = block * 480 + offset
                        buffer.putShort((kotlin.math.sin(frame * 2 * Math.PI * frequency / 48_000) * 4000).toInt().toShort())
                    }
                    buffer.flip()
                    mixer.offer(name, buffer, 16, 48_000, 1, 480, block * 480L)
                }
                writer.write(mixer.drain(480))
            }
        }
        val sealed = sealFile(source, File(directory, "synthetic-call.enc"), source.name, "audio/wav")
        exportInterop(sealed, source)
        val canonical = source.copyTo(File(directory, "canonical-name.wav"))
        val named = sealFile(canonical, File(directory, "canonical-name.enc"), " \uFEFF.e\u0000\u0301.wav", "audio/wav")
        assertEquals("é.wav", named.name)
        exportInterop(named, canonical)
    }

    /** Synthetic material only. Never reuse this evidence exporter for a real call. */
    private fun exportInterop(sealed: SealedFile, source: File) {
        val root = System.getenv("KITHMOOT_RECORDING_INTEROP_DIR")?.let(::File) ?: return
        root.mkdirs()
        sealed.file.copyTo(File(root, "${source.name}.enc"), overwrite = true)
        source.copyTo(File(root, source.name), overwrite = true)
        File(root, "${source.name}.json").writeText(buildJsonObject {
            put("synthetic", true); put("sha256", sealed.hash); put("key", sealed.key)
            put("sourceSha256", fileSha256(source)); put("sourceSize", source.length())
            put("name", sealed.name); put("type", sealed.type)
        }.toString())
    }
}
