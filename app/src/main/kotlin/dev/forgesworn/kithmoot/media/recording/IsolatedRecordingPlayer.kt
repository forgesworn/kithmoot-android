package dev.forgesworn.kithmoot.media.recording

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SharedMemory
import android.system.OsConstants
import android.view.Surface
import dev.forgesworn.kithmoot.session.MAX_RECORDING_SOURCE_BYTES
import dev.forgesworn.kithmoot.session.OpenedFile
import dev.forgesworn.kithmoot.session.recordingPlaybackMime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class RecordingPlaybackState(
    val prepared: Boolean = false,
    val playing: Boolean = false,
    val completed: Boolean = false,
    val durationMs: Int = 0,
    val positionMs: Int = 0,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
)

class RecordingPlaybackException(message: String) : Exception(message)

class IsolatedRecordingPlayer(
    context: Context,
    private val onState: (RecordingPlaybackState) -> Unit,
    private val onError: (String) -> Unit,
) : Closeable {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var closed = false
    private var bound = false
    private var connection: ServiceConnection? = null
    private var remote: IIsolatedRecordingPlayer? = null
    private var surface: Surface? = null
    private var binder: IBinder? = null
    private var playbackState = RecordingPlaybackState()

    private val death = IBinder.DeathRecipient {
        main.post { fail("The isolated recording player stopped unexpectedly.") }
    }

    private val callback = object : IIsolatedRecordingPlayerCallback.Stub() {
        override fun onPrepared(durationMs: Int, videoWidth: Int, videoHeight: Int) {
            main.post {
                if (synchronized(lock) { closed }) return@post
                playbackState = playbackState.copy(prepared = true, durationMs = durationMs.coerceAtLeast(0),
                    videoWidth = videoWidth.coerceAtLeast(0), videoHeight = videoHeight.coerceAtLeast(0))
                onState(playbackState)
            }
        }

        override fun onState(state: Int, positionMs: Int) {
            main.post {
                if (synchronized(lock) { closed }) return@post
                playbackState = playbackState.copy(
                    prepared = playbackState.prepared || state != 0,
                    playing = state == IIsolatedRecordingPlayer.STATE_STARTED,
                    completed = state == IIsolatedRecordingPlayer.STATE_COMPLETED,
                    positionMs = positionMs.coerceAtLeast(0),
                )
                onState(playbackState)
            }
        }

        override fun onError(errorCode: Int) {
            main.post { fail(errorMessage(errorCode)) }
        }
    }

    suspend fun open(file: OpenedFile) {
        check(!synchronized(lock) { closed }) { "The recording player has closed" }
        val mime = recordingPlaybackMime(file.type)
            ?: throw RecordingPlaybackException("This attachment is not a supported recording.")
        val input = copyToSharedMemory(file)
        try {
            withTimeout(10_000) { bindAndOpen(input, file.size, mime) }
        } finally {
            input.close()
        }
    }

    fun setSurface(surface: Surface?) {
        synchronized(lock) {
            if (closed) return
            this.surface = surface
        }
        command { it.setSurface(surface) }
    }

    fun play() {
        command { it.play() }
    }

    fun pause() {
        command { it.pause() }
    }

    fun seekTo(positionMs: Int) {
        command { it.seekTo(positionMs) }
    }

    private fun command(action: (IIsolatedRecordingPlayer) -> Unit) {
        val player = synchronized(lock) { remote.takeIf { !closed } } ?: return
        try {
            action(player)
        } catch (failure: Exception) {
            fail("The isolated recording player could not be controlled.")
        }
    }

    private suspend fun copyToSharedMemory(file: OpenedFile): SharedMemory = withContext(Dispatchers.IO) {
        if (file.size !in 1..MAX_RECORDING_SOURCE_BYTES || recordingPlaybackMime(file.type) == null) {
            throw RecordingPlaybackException("This recording exceeds the Android viewer limits or uses an unsupported type.")
        }
        val shared = try {
            SharedMemory.create("kithmoot-recording-plaintext", file.size.toInt())
        } catch (failure: Exception) {
            throw RecordingPlaybackException("The isolated recording player could not allocate its input.")
        }
        var mapping: ByteBuffer? = null
        val scratch = ByteArray(COPY_BUFFER_BYTES)
        try {
            val output = shared.mapReadWrite()
            mapping = output
            FileInputStream(file.file).use { stream ->
                val input = stream.channel
                check(input.size() == file.size) { "The recording changed before playback." }
                var copied = 0L
                while (copied < file.size) {
                    coroutineContext.ensureActive()
                    val wanted = minOf(scratch.size.toLong(), file.size - copied).toInt()
                    val count = input.read(ByteBuffer.wrap(scratch, 0, wanted))
                    check(count > 0) { "The recording ended before playback." }
                    output.put(scratch, 0, count)
                    copied += count
                }
                check(stream.read() == -1 && input.size() == file.size) { "The recording changed during playback setup." }
            }
            SharedMemory.unmap(output)
            mapping = null
            check(shared.setProtect(OsConstants.PROT_READ)) { "Could not protect recording plaintext." }
            shared
        } catch (cancelled: CancellationException) {
            shared.close()
            throw cancelled
        } catch (failure: Exception) {
            shared.close()
            throw RecordingPlaybackException(failure.message ?: "The recording could not be prepared.")
        } finally {
            scratch.fill(0)
            mapping?.let(SharedMemory::unmap)
        }
    }

    private suspend fun bindAndOpen(input: SharedMemory, size: Long, mime: String) =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                val finished = AtomicBoolean()
                lateinit var newConnection: ServiceConnection

                fun failOpen(message: String) {
                    if (finished.compareAndSet(false, true)) {
                        close()
                        if (continuation.isActive) continuation.resumeWithException(RecordingPlaybackException(message))
                    }
                }

                newConnection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, service: IBinder) {
                        if (finished.get() || synchronized(lock) { closed }) {
                            failOpen("The isolated recording player has closed.")
                            return
                        }
                        try {
                            val player = IIsolatedRecordingPlayer.Stub.asInterface(service)
                            service.linkToDeath(death, 0)
                            synchronized(lock) {
                                if (closed) throw RecordingPlaybackException("The recording player has closed.")
                                remote = player
                                binder = service
                            }
                            player.open(input, size, mime, synchronized(lock) { surface }, callback)
                            if (finished.compareAndSet(false, true) && continuation.isActive) continuation.resume(Unit)
                        } catch (failure: Exception) {
                            failOpen("The isolated recording player could not be started.")
                        }
                    }

                    override fun onServiceDisconnected(name: ComponentName) {
                        if (!finished.get()) failOpen("The isolated recording player stopped unexpectedly.")
                        else fail("The isolated recording player stopped unexpectedly.")
                    }

                    override fun onBindingDied(name: ComponentName) {
                        if (!finished.get()) failOpen("The isolated recording player binding died.")
                        else fail("The isolated recording player binding died.")
                    }

                    override fun onNullBinding(name: ComponentName) = failOpen("The isolated recording player returned no binding.")
                }
                continuation.invokeOnCancellation { close() }
                try {
                    synchronized(lock) {
                        check(!closed) { "The recording player has closed" }
                        connection = newConnection
                    }
                    val didBind = context.bindService(Intent(context, IsolatedRecordingPlayerService::class.java),
                        newConnection, Context.BIND_AUTO_CREATE)
                    val retained = synchronized(lock) {
                        if (closed) false else { bound = didBind; true }
                    }
                    if (didBind && !retained) runCatching { context.unbindService(newConnection) }
                    if (!didBind) failOpen("The isolated recording player is unavailable.")
                } catch (failure: Exception) {
                    failOpen(failure.message ?: "The isolated recording player is unavailable.")
                }
            }
        }

    private fun fail(message: String) {
        if (synchronized(lock) { closed }) return
        close()
        onError(message)
    }

    override fun close() {
        val old = synchronized(lock) {
            if (closed) return
            closed = true
            val result = Triple(remote, connection, bound)
            remote = null
            connection = null
            bound = false
            binder?.let { runCatching { it.unlinkToDeath(death, 0) } }
            binder = null
            result
        }
        val cleanup = Runnable {
            runCatching { old.first?.close() }
            if (old.third && old.second != null) runCatching { context.unbindService(old.second!!) }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) cleanup.run() else main.post(cleanup)
    }
}

private fun errorMessage(code: Int): String = when (code) {
    IIsolatedRecordingPlayer.ERROR_UNSUPPORTED_TYPE -> "This recording type cannot be played in the Android viewer."
    IIsolatedRecordingPlayer.ERROR_TIMEOUT -> "The isolated recording player timed out."
    IIsolatedRecordingPlayer.ERROR_CANCELLED -> "Recording playback was cancelled."
    else -> "This device could not play the recording."
}

private const val COPY_BUFFER_BYTES = 64 * 1024
