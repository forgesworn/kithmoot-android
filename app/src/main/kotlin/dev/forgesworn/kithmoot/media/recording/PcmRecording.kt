package dev.forgesworn.kithmoot.media.recording

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 48 kHz mono PCM. Callbacks may only copy into these bounded sample windows;
 * the export worker alone drains and performs disk I/O. Timestamps belong to
 * one recording clock, not the unrelated remote capture clocks.
 */
class PcmRecordingMixer(private val maxSources: Int = 32) {
    init { require(maxSources in 1..32) }
    private data class Window(val samples: ShortArray = ShortArray(CAPACITY), var next: Long = 0)
    private val windows = LinkedHashMap<String, Window>()
    private var cursor = 0L
    private var paused = false

    @Synchronized fun setSources(sources: Set<String>) {
        require(sources.size <= maxSources)
        windows.filterKeys { it !in sources }.values.forEach { it.samples.fill(0) }
        windows.keys.retainAll(sources)
        for (source in sources) windows.getOrPut(source) { Window(next = cursor) }
    }

    @Synchronized fun pause(on: Boolean) {
        paused = on
        windows.values.forEach { it.samples.fill(0); it.next = cursor }
    }

    /** A changed permission must discard this source's old epoch of PCM. */
    @Synchronized fun revoke(source: String) {
        windows.remove(source)?.samples?.fill(0)
    }

    @Synchronized internal fun grant(source: String) {
        if (source in windows) return
        require(windows.size < maxSources)
        windows[source] = Window(next = cursor)
    }

    /** Callers decide authorised sources and mute before handing us samples.
     * Unsupported formats fail explicitly rather than silently exporting silence.
     */
    @Synchronized fun offer(source: String, data: ByteBuffer, bits: Int, rate: Int, channels: Int, frames: Int, frame: Long, gain: Double = 1.0) {
        val window = windows[source] ?: return
        if (paused) return
        require(bits == 16 && rate == SAMPLE_RATE && channels in 1..2 && frames in 1..CAPACITY) {
            "Unsupported recording PCM: $bits bits, $rate Hz, $channels channels, $frames frames"
        }
        require(frame in 0..(Long.MAX_VALUE - CAPACITY))
        require(data.remaining().toLong() >= frames.toLong() * channels * 2)
        require(gain.isFinite() && gain in 0.0..10.0)
        // PCM has its own sample cadence. Anchoring every callback to its
        // arrival time accumulates scheduling jitter and eventually overflows
        // even a correctly paced source. Keep consecutive buffers contiguous;
        // re-anchor after a genuine gap, rather than each late callback.
        val start = if (window.next <= cursor || frame - window.next > SAMPLE_RATE / 10)
            maxOf(cursor, frame, window.next) else window.next
        check(start + frames <= cursor + CAPACITY) {
            "Recording audio queue exceeded its bound: source=$source, frame=$frame, next=${window.next}, cursor=$cursor, frames=$frames"
        }
        val input = data.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        for (offset in 0 until frames) {
            var sample = input.short.toInt()
            if (channels == 2) sample = (sample + input.short.toInt()) / 2
            window.samples[((start + offset) % CAPACITY).toInt()] = (sample * gain).toInt().coerceIn(-32768, 32767).toShort()
        }
        window.next = start + frames
    }

    @Synchronized fun drain(frames: Int): ShortArray =
        RecordingPcmBatch(frames, drainSources(frames).mapValues { Unit to it.value }).mix { Unit }

    /** Preserve attribution until the export worker's final permission check.
     * Revoking one source after draining must not silence other participants. */
    @Synchronized internal fun drainSources(frames: Int): Map<String, ShortArray> {
        require(frames in 1..CAPACITY)
        val output = if (paused) emptyMap() else windows.mapValues { (_, window) ->
            ShortArray(frames) { offset ->
                val index = ((cursor + offset) % CAPACITY).toInt()
                window.samples[index].also { window.samples[index] = 0 }
            }
        }
        if (!paused) cursor += frames
        return output
    }

    @Synchronized fun clear() { windows.values.forEach { it.samples.fill(0) }; windows.clear() }

    companion object {
        const val SAMPLE_RATE = 48_000
        const val CAPACITY = SAMPLE_RATE * 2
    }
}

/** One bounded worker batch, with the permission revision that admitted each
 * source. A revision must change across revocation even if permission returns
 * before the worker runs. Mixing consumes and erases the source arrays. */
internal class RecordingPcmBatch(
    val frames: Int,
    private val sources: Map<String, Pair<Any, ShortArray>>,
) {
    init {
        require(frames in 0..PcmRecordingMixer.CAPACITY)
        require(sources.size <= 32 && sources.values.all { it.second.size == frames })
    }

    fun mix(grant: (String) -> Any?): ShortArray {
        val result = ShortArray(frames)
        try {
            val admitted = sources.filter { (source, entry) ->
                runCatching { grant(source) }.getOrNull() == entry.first
            }.values.map { it.second }
            for (offset in result.indices) {
                val sum = admitted.sumOf { it[offset].toInt() }
                result[offset] = sum.coerceIn(-32768, 32767).toShort()
            }
            return result
        } catch (error: Throwable) { result.fill(0); throw error }
        finally { sources.values.forEach { it.second.fill(0) } }
    }
}

/** A playable disk-backed lossless export used to qualify the capture path.
 * The production compressed/video encoder can consume the same mixer output.
 * Never overwrites an existing file; failed writes remove only our own output.
 */
interface PcmFileOutput : AutoCloseable {
    val requiresPreparation: Boolean get() = false
    /** Worker-owned setup only; do not capture or submit media here. */
    fun prepare() {}
    /** Null means future input is revoked, paused or failed. */
    fun bindTimeline(clock: () -> Long?) {}
    fun pauseInputs(paused: Boolean) {}
    fun detachInputs() {}
    fun write(samples: ShortArray)
    fun discard()
}

class PcmWaveFile(private val file: File, private val maxBytes: Long = 256L * 1024 * 1024) : PcmFileOutput {
    private val output: RandomAccessFile
    private var bytes = 0L
    private var closed = false
    init {
        require(maxBytes in 46..(0xffffffffL - 36))
        check(file.createNewFile()) { "Recording destination already exists" }
        val made = try { RandomAccessFile(file, "rw") }
        catch (error: Throwable) { file.delete(); throw error }
        try { made.write(header(0)); output = made }
        catch (error: Throwable) { made.close(); file.delete(); throw error }
    }

    override fun write(samples: ShortArray) {
        check(!closed)
        check(bytes + samples.size * 2L + 44 <= maxBytes) { "Recording reached its file limit" }
        val buffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach(buffer::putShort)
        try { output.write(buffer.array()); bytes += buffer.capacity() }
        finally { buffer.array().fill(0) }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            output.seek(0); output.write(header(bytes)); output.fd.sync()
        } catch (error: Throwable) { file.delete(); throw error }
        finally { output.close() }
    }

    override fun discard() { try { if (!closed) { closed = true; output.close() } } finally { file.delete() } }

    private fun header(length: Long): ByteArray = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        .put("RIFF".toByteArray()).putInt((length + 36).toInt()).put("WAVEfmt ".toByteArray())
        .putInt(16).putShort(1).putShort(1).putInt(PcmRecordingMixer.SAMPLE_RATE)
        .putInt(PcmRecordingMixer.SAMPLE_RATE * 2).putShort(2).putShort(16)
        .put("data".toByteArray()).putInt(length.toInt()).array()
}
