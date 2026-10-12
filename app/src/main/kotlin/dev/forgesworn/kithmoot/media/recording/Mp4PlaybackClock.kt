package dev.forgesworn.kithmoot.media.recording

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Give our own, complete AAC bitstream its original PCM playback interval.
 * Edit lists retain the decoder's priming access units and final source sound.
 * Media data and chunk offsets stay in place: the old moov becomes free space
 * and the replacement is appended only after the native muxer is released.
 * This accepts only the non-fragmented files our recording muxers produce.
 */
internal object Mp4PlaybackClock {
    private const val SAMPLE_RATE = 48_000
    private const val MAX_METADATA = 8 * 1024 * 1024
    private data class Atom(val type: String, val payload: ByteArray)

    fun apply(file: File, sourceSamples: Long, primingSamples: Int, maxBytes: Long) {
        require(sourceSamples in 1..0xffff_ffffL && primingSamples >= 0)
        RandomAccessFile(file, "rw").use { data ->
            val length = data.length()
            check(length in 1..maxBytes) { "Recording exceeds its file limit" }
            var position = 0L
            var moovPosition = -1L
            var moov: Atom? = null
            var mediaCount = 0
            var boxes = 0
            while (position < length) {
                check(++boxes <= 128 && length - position >= 8) { "Invalid recording container" }
                data.seek(position)
                var size = data.readInt().toLong() and 0xffff_ffffL
                val type = ByteArray(4).also(data::readFully).toString(Charsets.US_ASCII)
                var header = 8
                if (size == 1L) {
                    check(length - position >= 16)
                    size = data.readLong()
                    header = 16
                }
                check(size >= header && size <= length - position) { "Invalid recording box" }
                check(type != "moof") { "Fragmented recording is unsupported" }
                if (type == "mdat") mediaCount++
                if (type == "moov") {
                    check(moov == null && size - header in 1..MAX_METADATA.toLong()) { "Invalid recording metadata" }
                    moovPosition = position
                    moov = Atom(type, ByteArray((size - header).toInt()).also(data::readFully))
                }
                position += size
            }
            check(mediaCount > 0 && moov != null) { "Recording is missing media or metadata" }
            val children = atoms(requireNotNull(moov).payload)
            check(children.count { it.type == "mvhd" } == 1)
            var audioTracks = 0
            var videoTracks = 0
            val updated = children.map { child ->
                when (child.type) {
                    "mvhd" -> movieHeader(child, sourceSamples)
                    "trak" -> {
                        val track = atoms(child.payload)
                        val mdia = atoms(track.single { it.type == "mdia" }.payload)
                        val handler = mdia.single { it.type == "hdlr" }.payload
                        check(handler.size >= 12)
                        val kind = handler.copyOfRange(8, 12).toString(Charsets.US_ASCII)
                        when (kind) {
                            "soun" -> {
                                audioTracks++
                                val header = mdia.single { it.type == "mdhd" }.payload
                                val (scaleOffset, durationOffset, wide) = timeOffsets(header)
                                val view = view(header)
                                check(view.getInt(scaleOffset) == SAMPLE_RATE) { "Unexpected recording audio clock" }
                                val codedSamples = duration(view, durationOffset, wide)
                                val padding = codedSamples - sourceSamples - primingSamples
                                check(padding in 0..4096) { "Recording is missing source sound or has excessive encoder padding" }
                            }
                            "vide" -> videoTracks++
                            else -> error("Unexpected recording track")
                        }
                        val clocked = track.filter { it.type != "edts" }.map {
                            if (it.type == "tkhd") trackHeader(it, sourceSamples) else it
                        }
                        check(clocked.count { it.type == "tkhd" } == 1)
                        Atom("trak", pack(clocked + edit(sourceSamples, if (kind == "soun") primingSamples else 0)))
                    }
                    else -> child
                }
            }
            check(audioTracks == 1 && videoTracks <= 1) { "Unexpected recording tracks" }
            val replacement = pack(listOf(Atom("moov", pack(updated))))
            check(replacement.size <= MAX_METADATA && replacement.size.toLong() <= maxBytes - length) {
                "Recording exceeds its file limit"
            }
            // All validation precedes mutation. Appending metadata preserves
            // every original stco/co64 offset, including moov-before-mdat files.
            data.seek(length)
            data.write(replacement)
            data.seek(moovPosition + 4)
            data.writeBytes("free")
            data.fd.sync()
        }
    }

    private fun atoms(payload: ByteArray): List<Atom> {
        val result = mutableListOf<Atom>()
        val view = view(payload)
        var offset = 0
        while (offset < payload.size) {
            check(result.size < 128 && payload.size - offset >= 8) { "Invalid recording metadata" }
            val size = view.getInt(offset).toLong() and 0xffff_ffffL
            check(size in 8..(payload.size - offset).toLong()) { "Invalid recording metadata box" }
            val end = offset + size.toInt()
            result += Atom(payload.copyOfRange(offset + 4, offset + 8).toString(Charsets.US_ASCII),
                payload.copyOfRange(offset + 8, end))
            offset = end
        }
        return result
    }

    private fun pack(atoms: List<Atom>): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            atoms.forEach {
                check(it.type.length == 4 && it.payload.size <= MAX_METADATA - 8)
                output.writeInt(it.payload.size + 8)
                output.writeBytes(it.type)
                output.write(it.payload)
            }
        }
        bytes.toByteArray().also { check(it.size <= MAX_METADATA) }
    }

    private fun view(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

    private fun timeOffsets(payload: ByteArray): Triple<Int, Int, Boolean> {
        check(payload.isNotEmpty())
        return when (payload[0].toInt()) {
            0 -> Triple(12, 16, false).also { check(payload.size >= 20) }
            1 -> Triple(20, 24, true).also { check(payload.size >= 32) }
            else -> error("Unsupported recording time box")
        }
    }

    private fun duration(view: ByteBuffer, offset: Int, wide: Boolean) =
        if (wide) view.getLong(offset).also { check(it >= 0) } else view.getInt(offset).toLong() and 0xffff_ffffL

    private fun movieHeader(atom: Atom, samples: Long): Atom {
        val payload = atom.payload.copyOf()
        val (scale, duration, wide) = timeOffsets(payload)
        view(payload).apply {
            putInt(scale, SAMPLE_RATE)
            if (wide) putLong(duration, samples) else putInt(duration, samples.toInt())
        }
        return atom.copy(payload = payload)
    }

    private fun trackHeader(atom: Atom, samples: Long): Atom {
        val payload = atom.payload.copyOf()
        check(payload.isNotEmpty())
        view(payload).apply {
            when (payload[0].toInt()) {
                0 -> { check(payload.size >= 24); putInt(20, samples.toInt()) }
                1 -> { check(payload.size >= 36); putLong(28, samples) }
                else -> error("Unsupported recording track header")
            }
        }
        return atom.copy(payload = payload)
    }

    private fun edit(samples: Long, priming: Int): Atom {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use {
            it.writeInt(0) // version 0 and flags
            it.writeInt(1) // one continuous playback interval
            it.writeInt(samples.toInt()) // movie timescale is 48 kHz
            it.writeInt(priming) // audio media timescale is also 48 kHz
            it.writeShort(1); it.writeShort(0) // normal playback rate
        }
        return Atom("edts", pack(listOf(Atom("elst", bytes.toByteArray()))))
    }
}
