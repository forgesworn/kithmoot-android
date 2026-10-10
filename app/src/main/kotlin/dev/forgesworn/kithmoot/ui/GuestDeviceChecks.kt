package dev.forgesworn.kithmoot.ui

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

/** Local inputs only: no relay, peer connection, call track or recorded file. */
internal interface GuestDevicePreview {
    fun startCamera(owner: LifecycleOwner, view: PreviewView, onReady: () -> Unit, onFailure: () -> Unit)
    fun startMicrophone(scope: CoroutineScope, onLevel: (Float) -> Unit, onFailure: () -> Unit)
    fun stopCamera()
    fun stopMicrophone()
    fun stop()
    fun close()
}

internal class GuestDeviceChecks(private val context: Context) : GuestDevicePreview {
    private val lock = Any()
    private val cameraGeneration = AtomicLong()
    private val microphoneGeneration = AtomicLong()
    private var camera: Pair<ProcessCameraProvider, Preview>? = null
    private var microphone: AudioRecord? = null
    private var microphoneJob: Job? = null
    @Volatile private var closed = false

    /** Main-thread operation; only this preview use case is bound/unbound. */
    @SuppressLint("MissingPermission")
    override fun startCamera(owner: LifecycleOwner, view: PreviewView, onReady: () -> Unit, onFailure: () -> Unit) {
        if (closed) return
        if (!isGranted(context, android.Manifest.permission.CAMERA)) { onFailure(); return }
        stopCamera()
        val generation = cameraGeneration.incrementAndGet()
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (closed || cameraGeneration.get() != generation) return@addListener
            var binding: Pair<ProcessCameraProvider, Preview>? = null
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                binding = provider to preview
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, preview)
                if (closed || cameraGeneration.get() != generation) {
                    provider.unbind(preview)
                } else {
                    camera = binding
                    onReady()
                }
            } catch (_: Exception) {
                binding?.let { (provider, preview) -> runCatching { provider.unbind(preview) } }
                if (camera === binding) camera = null
                if (!closed && cameraGeneration.get() == generation) onFailure()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    override fun stopCamera() {
        cameraGeneration.incrementAndGet()
        camera?.let { (provider, preview) -> runCatching { provider.unbind(preview) } }
        camera = null
    }

    /** Permission is checked by the explicit UI action; construction may still refuse it. */
    @SuppressLint("MissingPermission")
    override fun startMicrophone(scope: CoroutineScope, onLevel: (Float) -> Unit, onFailure: () -> Unit) {
        if (closed) return
        if (!isGranted(context, android.Manifest.permission.RECORD_AUDIO)) { onFailure(); return }
        stopMicrophone()
        val generation = microphoneGeneration.incrementAndGet()
        microphoneJob = scope.launch(Dispatchers.IO) {
            var owned: AudioRecord? = null
            try {
                val minimum = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minimum > 0)
                val record = AudioRecord(MediaRecorder.AudioSource.MIC, 16_000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 4096))
                owned = record
                synchronized(lock) {
                    if (closed || microphoneGeneration.get() != generation) return@launch
                    check(record.state == AudioRecord.STATE_INITIALIZED)
                    record.startRecording()
                    check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                    microphone = record
                }
                val samples = ShortArray(512)
                while (isActive && !closed && microphoneGeneration.get() == generation) {
                    val count = record.read(samples, 0, samples.size, AudioRecord.READ_NON_BLOCKING)
                    check(count >= 0)
                    if (count > 0) {
                        val rms = sqrt(samples.take(count).sumOf { it.toDouble() * it } / count).toFloat() / 32768f
                        withContext(Dispatchers.Main.immediate) {
                            if (!closed && microphoneGeneration.get() == generation) onLevel(rms.coerceIn(0f, 1f))
                        }
                    }
                    delay(60)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                withContext(Dispatchers.Main.immediate) {
                    if (!closed && microphoneGeneration.get() == generation) onFailure()
                }
            } finally {
                synchronized(lock) {
                    if (microphone === owned) microphone = null
                    owned?.let { runCatching { it.stop() }; runCatching { it.release() } }
                }
            }
        }
    }

    override fun stopMicrophone() {
        microphoneGeneration.incrementAndGet()
        microphoneJob?.cancel()
        microphoneJob = null
        synchronized(lock) {
            microphone?.let { runCatching { it.stop() }; runCatching { it.release() } }
            microphone = null
        }
    }

    override fun stop() { stopCamera(); stopMicrophone() }

    override fun close() { closed = true; stop() }
}
