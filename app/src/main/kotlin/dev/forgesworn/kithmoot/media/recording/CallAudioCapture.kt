package dev.forgesworn.kithmoot.media.recording

import org.webrtc.AudioTrack
import org.webrtc.AudioTrackSink
import java.io.File
import java.nio.ByteBuffer
import java.util.IdentityHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Native call audio adapter. The caller must publish the room's signed
 * notice before constructing it and supply only the originating call's
 * currently authorised tracks. It never changes a call track or claims playback.
 * The room owner owns notice publication, consent and export retention.
 */
class CallAudioCapture(
    private val file: File,
    output: (File) -> PcmFileOutput = { PcmWaveFile(it) },
    allowed: () -> Boolean = { true },
    private val startSuspended: Boolean = false,
    /** Null denies this source. A grant must change on every semantic
     * revocation, including a revoke/restore before the next callback. The
     * callback reads immutable authority snapshots, never a session lock. */
    private val sourceGrant: (AudioTrack?) -> Any? = { Unit },
    private val clock: () -> Long = System::nanoTime,
) {
    private val permission = RecordingCapturePermission(allowed)
    private val mixer = PcmRecordingMixer()
    private val writer = output(file)
    private val worker = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "KithMootRecording").apply { isDaemon = true } }
    private data class Input(val source: String, val sink: AudioTrackSink)
    private val inputs = IdentityHashMap<AudioTrack, Input>()
    private val gains = mutableMapOf<String, Double>()
    private val sourceTracks = mutableMapOf<String, AudioTrack?>()
    private val sourceGrants = mutableMapOf<String, Any>()
    private var preparationComplete = !writer.requiresPreparation
    private var began: Long? = if (startSuspended || writer.requiresPreparation) null else clock()
    private var pausedAt: Long? = null
    private var stoppedAt: Long? = null
    private var omitted = 0L
    private var emitted = 0L
    private var nextSource = 0L
    private var localAllowed = false
    private var finished = false
    private var failure: Throwable? = null
    private val ticker: ScheduledFuture<*>

    init {
        try {
            writer.bindTimeline {
                synchronized(this) {
                    if (began == null || finished || stoppedAt != null || pausedAt != null || failure != null || !captureAllowed()) null else frameNow()
                }
            }
        } catch (error: Throwable) { worker.shutdownNow(); writer.discard(); throw error }
        if (writer.requiresPreparation) worker.execute {
            try {
                // Encoder/EGL startup must not hold the input monitor or
                // consume the two-second PCM window before capture begins.
                writer.prepare()
                synchronized(this) {
                    preparationComplete = true
                    if (!startSuspended && !finished && stoppedAt == null) {
                        check(captureAllowed()) { "Recording permission changed during preparation" }
                        began = pausedAt ?: clock()
                    }
                }
            } catch (error: Throwable) { synchronized(this) { fail(error) } }
        }
        ticker = worker.scheduleWithFixedDelay({
            try {
                val samples = synchronized(this) {
                    if (!finished && failure == null && pausedAt == null) takeUntil((frameNow() - LOOKAHEAD).coerceAtLeast(0)) else null
                }
                samples?.let(::writeSamples)
            } catch (error: Throwable) { synchronized(this) { fail(error) } }
        }, 10, 10, TimeUnit.MILLISECONDS)
    }

    @Synchronized fun prepared(): Boolean = preparationComplete && failure == null && !finished && stoppedAt == null

    /** The owner rechecks the original call before opening this timeline. */
    @Synchronized fun activate() {
        checkHealthy()
        check(preparationComplete && !finished && stoppedAt == null && began == null) { "Recording is not ready to start" }
        check(captureAllowed()) { "Recording permission changed before capture started" }
        began = clock()
        refreshSources()
    }

    /** Run on the same owner thread that manages WebRTC track lifetime.
     * Revocation drops queued samples before returning. A replaced receiver
     * gets a fresh source; it cannot inherit the previous receiver's queue.
     */
    fun setInputs(remote: Map<AudioTrack, Double>, local: Boolean) {
        val removed = mutableListOf<Pair<AudioTrack, AudioTrackSink>>()
        val added = mutableListOf<Pair<AudioTrack, Input>>()
        synchronized(this) {
            check(!finished && stoppedAt == null); checkHealthy()
            require(remote.size + (if (local) 1 else 0) <= 32)
            require(remote.values.all { it.isFinite() && it in 0.0..10.0 })
            localAllowed = local
            if (local) sourceTracks[LOCAL] = null else {
                sourceTracks.remove(LOCAL); sourceGrants.remove(LOCAL); mixer.revoke(LOCAL)
            }
            for (old in inputs.keys.filter { old -> remote.keys.none { it === old } }) {
                val input = inputs.remove(old)!!
                gains.remove(input.source)
                sourceTracks.remove(input.source); sourceGrants.remove(input.source); mixer.revoke(input.source)
                removed += old to input.sink
            }
            for ((track, gain) in remote) {
                val existing = inputs[track]
                if (existing != null) { gains[existing.source] = gain; continue }
                val source = "remote-${nextSource++}"
                val input = Input(source, AudioTrackSink { buffer, bits, rate, channels, frames, _ -> receive(source, buffer, bits, rate, channels, frames) })
                inputs[track] = input
                gains[source] = gain
                sourceTracks[source] = track
                added += track to input
            }
            refreshSources()
        }
        // Native removal may wait for a callback: never hold the callback lock.
        removed.forEach { (track, sink) -> runCatching { track.removeSink(sink) } }
        for ((track, input) in added) {
            try {
                track.addSink(input.sink)
                val revoked = synchronized(this) { finished || inputs[track] !== input }
                if (revoked) track.removeSink(input.sink)
            }
            catch (error: Throwable) {
                synchronized(this) {
                    gains.remove(input.source); sourceTracks.remove(input.source); sourceGrants.remove(input.source); mixer.revoke(input.source)
                    inputs.remove(track); refreshSources(); fail(error)
                }
                runCatching { track.removeSink(input.sink) }
                throw error
            }
        }
    }

    private fun refreshSources() {
        if (!captureAllowed()) return
        for (source in sourceTracks.keys) refreshSource(source)
    }

    private fun refreshSource(source: String): Any? {
        if (!captureAllowed() || !sourceTracks.containsKey(source)) return null
        val grant = runCatching { sourceGrant(sourceTracks[source]) }.getOrNull()
        if (grant != sourceGrants[source]) mixer.revoke(source)
        if (grant == null) sourceGrants.remove(source) else {
            sourceGrants[source] = grant
            mixer.grant(source)
        }
        return grant
    }

    private fun captureAllowed(): Boolean {
        val allowed = permission.allowed()
        if (!allowed) mixer.clear()
        return allowed
    }
    fun permissionRevoked(): Boolean = permission.revoked()

    /** Feed the post-mute, post-app-sound outgoing buffer, never a raw mic tap.
     * Copy happens synchronously before WebRTC reuses the buffer.
     */
    fun localSamples(buffer: ByteBuffer, bits: Int, rate: Int, channels: Int, frames: Int) =
        receive(LOCAL, buffer, bits, rate, channels, frames)

    @Synchronized private fun receive(source: String, buffer: ByteBuffer, bits: Int, rate: Int, channels: Int, frames: Int) {
        if (began == null || finished || stoppedAt != null || failure != null || pausedAt != null || !captureAllowed()) return
        val gain = if (source == LOCAL) { if (!localAllowed) return else 1.0 } else gains[source] ?: return
        try {
            // A callback refreshes only its own source; the worker checks all
            // sources before draining. Do not rescan every receiver for every
            // incoming 10 ms buffer in a multi-participant call.
            val grant = refreshSource(source) ?: return
            mixer.offer(source, buffer, bits, rate, channels, frames, frameNow(), gain)
            // A signed source change during the synchronous copy invalidates
            // the copied window before another callback or writer can use it.
            if (runCatching { sourceGrant(sourceTracks[source]) }.getOrNull() != grant) {
                sourceGrants.remove(source); mixer.revoke(source)
            }
        }
        catch (error: Throwable) { fail(error) }
    }

    fun pause() {
        val pending = synchronized(this) {
            check(!finished && stoppedAt == null); checkHealthy()
            if (pausedAt != null) return
            val samples = takeUntil(frameNow())
            pausedAt = clock()
            mixer.pause(true)
            worker.submit { writeSamples(samples) }
        }
        writer.pauseInputs(true)
        pending.get(10, TimeUnit.SECONDS)
    }

    fun resume() {
        val pause = synchronized(this) {
            check(!finished && stoppedAt == null); checkHealthy()
            pausedAt ?: return
        }
        // Native add/remove can wait for a callback that reads our clock.
        // Keep those operations outside the audio callback monitor.
        writer.pauseInputs(false)
        synchronized(this) {
            check(!finished && stoppedAt == null); checkHealthy()
            check(pausedAt == pause) { "Recording pause changed" }
            omitted += clock() - pause
            pausedAt = null
            mixer.pause(false)
        }
    }

    /** Freeze the timeline and detach native sinks before their tracks can be
     * disposed. Already accepted PCM remains queued for finalisation. */
    fun detachInputs() {
        val detach = synchronized(this) {
            if (finished || stoppedAt != null) return
            stoppedAt = clock()
            val held = inputs.entries.map { it.key to it.value.sink }
            inputs.clear(); gains.clear(); localAllowed = false
            held
        }
        detach.forEach { (track, sink) -> runCatching { track.removeSink(sink) } }
        writer.detachInputs()
    }

    /** Detach first on the WebRTC owner thread, then finalise on the worker.
     * A failed export is removed, never returned as a successful recording.
     */
    fun finish(): File {
        detachInputs()
        val detach = synchronized(this) {
            check(!finished)
            finished = true
            ticker.cancel(false)
            val held = inputs.entries.map { it.key to it.value.sink }
            inputs.clear(); gains.clear(); localAllowed = false
            held
        }
        detach.forEach { (track, sink) -> runCatching { track.removeSink(sink) } }
        var completion: Future<File>? = null
        try {
            completion = worker.submit<File> {
                try {
                    val samples = synchronized(this) {
                        checkHealthy()
                        check(began != null) { "Recording capture did not start" }
                        takeUntil(frameNow())
                    }
                    writeSamples(samples)
                    writer.close()
                    synchronized(this) { mixer.clear() }
                    file
                } catch (error: Throwable) {
                    // A video encoder's EGL resources belong to this worker,
                    // including the failure path. Never release them from the
                    // room/UI thread that is waiting for the completed file.
                    runCatching { writer.discard() }.onFailure { if (it !== error) error.addSuppressed(it) }
                    throw error
                }
            }
            completion.get(120, TimeUnit.SECONDS)
            return file
        } catch (error: Throwable) {
            completion?.cancel(true)
            runCatching { worker.submit { writer.discard() }.get(10, TimeUnit.SECONDS) }
                .onFailure { if (it !== error) error.addSuppressed(it) }
            worker.shutdownNow()
            synchronized(this) { mixer.clear() }
            file.delete()
            throw error
        } finally { worker.shutdown() }
    }

    fun discard() {
        detachInputs()
        val detach = synchronized(this) {
            if (finished) return
            finished = true; ticker.cancel(false)
            val held = inputs.entries.map { it.key to it.value.sink }
            inputs.clear(); gains.clear(); localAllowed = false
            held
        }
        detach.forEach { (track, sink) -> runCatching { track.removeSink(sink) } }
        try { worker.submit { writer.discard() }.get(10, TimeUnit.SECONDS) }
        finally { worker.shutdownNow(); synchronized(this) { mixer.clear() }; file.delete() }
    }

    @Synchronized fun error(): Throwable? = failure
    private fun checkHealthy() { failure?.let { throw IllegalStateException("Recording capture failed: ${it.message}", it) } }
    private fun fail(error: Throwable) { failure = error; mixer.clear() }
    private fun frameNow(): Long {
        val start = began ?: return 0
        return (((pausedAt ?: stoppedAt ?: clock()) - start - omitted).coerceAtLeast(0) / 1000) * PcmRecordingMixer.SAMPLE_RATE / 1_000_000
    }
    private fun takeUntil(frame: Long): RecordingPcmBatch {
        refreshSources()
        check(frame - emitted <= PcmRecordingMixer.CAPACITY) { "Recording worker fell behind" }
        val count = (frame - emitted).coerceAtLeast(0).toInt()
        if (count == 0) return RecordingPcmBatch(0, emptyMap())
        emitted += count
        val contributions = mixer.drainSources(count)
        return RecordingPcmBatch(count, contributions.mapNotNull { (source, samples) ->
            sourceGrants[source]?.let { source to (it to samples) }
                ?: run { samples.fill(0); null }
        }.toMap())
    }

    private fun writeSamples(batch: RecordingPcmBatch) {
        val samples = synchronized(this) {
            refreshSources()
            batch.mix { source ->
                if (!captureAllowed() || !sourceTracks.containsKey(source)) null
                else runCatching { sourceGrant(sourceTracks[source]) }.getOrNull()
            }
        }
        try { if (samples.isNotEmpty()) writer.write(samples) }
        catch (error: Throwable) { synchronized(this) { fail(error) }; throw error }
        finally { samples.fill(0) }
    }

    companion object {
        private const val LOCAL = "local"
        private const val LOOKAHEAD = PcmRecordingMixer.SAMPLE_RATE / 10
    }
}
