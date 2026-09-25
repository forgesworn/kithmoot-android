package dev.forgesworn.kithmoot.media

import kotlin.math.sqrt

/**
 * Who is talking, decided from audio energy alone.
 *
 * A port of the web client's `src/speaking.ts`: two thresholds for
 * hysteresis, so a voice sitting on the line does not chatter, and a
 * hangover, so the gaps between words do not strobe the cue. Nothing
 * downstream of this makes a decision that matters - it lights a border and
 * picks whose picture is large - so an energy detector a unit test can drive
 * with numbers is worth more than a voice-activity model.
 *
 * The one difference from the web is that Android reads two different
 * scales, so it has two sets of thresholds. See [SpeakingLevels].
 */
data class SpeakingThresholds(
    /** Level at or above which somebody starts speaking. */
    val on: Double,
    /** Level below which they are heading towards stopping. */
    val off: Double,
    /** How long the level has to stay under [off] before they have stopped. */
    val hangoverMs: Long,
)

object SpeakingLevels {
    /**
     * True RMS of the PCM samples, in the -1..1 range. The same scale the
     * web's AnalyserNode reads, so the web's numbers are used unchanged. This
     * is what this device's own microphone is measured on.
     */
    val RMS = SpeakingThresholds(on = 0.02, off = 0.01, hangoverMs = 400)

    /**
     * libwebrtc's receive-side level, for everybody else.
     *
     * Its `audioLevel` and `totalAudioEnergy` are not RMS: each 10 ms frame
     * is scored by its absolute peak sample over full scale, and the energy
     * integrates the square of that per-frame peak. [levelFromEnergy] turns
     * two readings into the quadratic mean of those peaks over the interval,
     * which is steadier than a single `audioLevel` snapshot but still sits
     * above RMS by a frame's crest factor - about 2.5 to 3.5 for voiced
     * speech and room noise alike. Scaling the web's thresholds by 2.5, the
     * low end, keeps a quiet talker lit rather than missed; a phone on a
     * real call is what confirms or moves these two numbers.
     */
    val STATS = SpeakingThresholds(on = 0.05, off = 0.025, hangoverMs = 400)
}

/**
 * Turns a stream of levels into a stable yes or no, one per audio source.
 *
 * The clock is passed in rather than read, so a test can drive a whole
 * sentence in a millisecond.
 */
class SpeakingDetector(private val thresholds: SpeakingThresholds = SpeakingLevels.RMS) {
    var speaking: Boolean = false
        private set

    /** When the level first went under `off` while speaking; null otherwise. */
    private var quietSince: Long? = null

    fun update(level: Double, now: Long): Boolean {
        if (!speaking) {
            // Starting is immediate: a delay here is a delay before the room
            // can see who is talking, which is the whole feature.
            if (level >= thresholds.on) {
                speaking = true
                quietSince = null
            }
            return speaking
        }
        if (level >= thresholds.off) {
            quietSince = null
            return true
        }
        val since = quietSince
        if (since == null) {
            quietSince = now
            return true
        }
        if (now - since >= thresholds.hangoverMs) {
            speaking = false
            quietSince = null
        }
        return speaking
    }

    /** Forgets everything, for a source that has gone away or been muted. */
    fun reset() {
        speaking = false
        quietSince = null
    }
}

/**
 * One detector per source, keyed by whatever the caller finds convenient.
 *
 * [retain] is not optional housekeeping: a stale detector that still reads
 * speaking would keep a departed device's cue lit if its key came back.
 */
class SpeakingSet(private val thresholds: SpeakingThresholds) {
    private val detectors = HashMap<String, SpeakingDetector>()

    fun update(key: String, level: Double, now: Long): Boolean =
        detectors.getOrPut(key) { SpeakingDetector(thresholds) }.update(level, now)

    fun speaking(key: String): Boolean = detectors[key]?.speaking ?: false

    fun active(): Set<String> = detectors.filterValues { it.speaking }.keys.toSet()

    fun retain(keys: Set<String>) {
        detectors.keys.retainAll(keys)
    }
}

/**
 * The receive-side level over one polling interval, from two consecutive
 * readings of `totalAudioEnergy` and `totalSamplesDuration`. Null when no
 * audio arrived in between, or the counters went backwards because the
 * receiver was replaced; the caller falls back to `audioLevel` then.
 */
fun levelFromEnergy(previousEnergy: Double, previousDuration: Double, energy: Double, duration: Double): Double? {
    val elapsed = duration - previousDuration
    val gained = energy - previousEnergy
    if (elapsed <= 0.0 || gained < 0.0) return null
    return sqrt(gained / elapsed).coerceIn(0.0, 1.0)
}

/**
 * RMS of this device's microphone between two reads.
 *
 * Fed from the audio device module's record thread every 10 ms and drained
 * by the speaking poll a few times a second, so the answer is the level over
 * the whole interval rather than whichever 10 ms happened to be last.
 */
class LevelMeter {
    private var sumSquares = 0.0
    private var samples = 0L

    /** 16-bit little-endian PCM, any channel count. */
    @Synchronized
    fun addPcm16(data: ByteArray, length: Int = data.size) {
        var index = 0
        val end = length - (length % 2)
        while (index < end) {
            val sample = ((data[index].toInt() and 0xff) or (data[index + 1].toInt() shl 8)).toShort()
            val scaled = sample / 32768.0
            sumSquares += scaled * scaled
            samples++
            index += 2
        }
    }

    /**
     * The first [bytes] of a record buffer, read without moving its position.
     * The audio device module's buffers are direct, so they have no array to
     * hand over, and a direct buffer is big-endian unless told otherwise.
     */
    @Synchronized
    fun addPcm16(buffer: java.nio.ByteBuffer, bytes: Int) {
        val view = buffer.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val end = minOf(bytes, view.limit()) and 1.inv()
        var index = 0
        while (index < end) {
            val scaled = view.getShort(index) / 32768.0
            sumSquares += scaled * scaled
            samples++
            index += 2
        }
    }

    /** The RMS since the last drain, or null if nothing was recorded. */
    @Synchronized
    fun drain(): Double? {
        if (samples == 0L) return null
        val rms = sqrt(sumSquares / samples)
        sumSquares = 0.0
        samples = 0
        return rms
    }
}
