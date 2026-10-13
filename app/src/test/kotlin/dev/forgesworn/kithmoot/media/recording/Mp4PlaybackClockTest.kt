package dev.forgesworn.kithmoot.media.recording

import java.io.File
import java.nio.file.Files
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

/** Minimal ISO box headers and zero media bytes check fail-before-write
 * boundaries. Actual source fidelity is tested through platform decoding;
 * no physical-device media fixture is checked into the repository. */
class Mp4PlaybackClockTest {
    private fun fixture(): File = Files.createTempFile("synthetic-clock-", ".m4a").toFile().also {
        fun box(type: String, payload: ByteArray): ByteArray = ByteBuffer.allocate(payload.size + 8)
            .order(ByteOrder.BIG_ENDIAN).putInt(payload.size + 8).put(type.toByteArray(Charsets.US_ASCII)).put(payload).array()
        fun clock(duration: Int) = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN)
            .putInt(12, 48_000).putInt(16, duration).array()
        val handler = ByteArray(12).also { "soun".toByteArray(Charsets.US_ASCII).copyInto(it, 8) }
        val media = box("mdia", box("mdhd", clock(98_304)) + box("hdlr", handler))
        val track = box("trak", box("tkhd", ByteArray(24)) + media)
        it.writeBytes(box("moov", box("mvhd", clock(98_304)) + track) + box("mdat", ByteArray(64)))
    }

    @Test fun metadata_growth_cannot_exceed_the_original_file_budget() {
        val file = fixture()
        try {
            val before = file.readBytes()
            try { Mp4PlaybackClock.apply(file, 96_000, 2048, file.length()); fail("Must reserve metadata space") }
            catch (_: IllegalStateException) { assertArrayEquals(before, file.readBytes()) }
        } finally { file.delete() }
    }

    @Test fun truncated_media_is_rejected_without_rewriting_metadata() {
        val file = fixture()
        try {
            file.writeBytes(file.readBytes().dropLast(17).toByteArray())
            val before = file.readBytes()
            try { Mp4PlaybackClock.apply(file, 96_000, 2048, 256 * 1024); fail("Must reject incomplete media") }
            catch (_: IllegalStateException) { assertArrayEquals(before, file.readBytes()) }
        } finally { file.delete() }
    }

    @Test fun a_source_interval_larger_than_the_complete_bitstream_is_rejected_without_mutation() {
        val file = fixture()
        try {
            val before = file.readBytes()
            try { Mp4PlaybackClock.apply(file, 144_000, 2048, 256 * 1024); fail("Cannot invent missing source sound") }
            catch (_: IllegalStateException) { assertArrayEquals(before, file.readBytes()) }
        } finally { file.delete() }
    }
}
