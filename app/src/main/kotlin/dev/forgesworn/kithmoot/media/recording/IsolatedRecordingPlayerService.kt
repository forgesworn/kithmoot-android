package dev.forgesworn.kithmoot.media.recording

import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.os.SharedMemory
import android.view.Surface
import dev.forgesworn.kithmoot.session.MAX_RECORDING_SOURCE_BYTES
import dev.forgesworn.kithmoot.session.recordingPlaybackMime
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class IsolatedRecordingPlayerService : Service() {
    private var session: PlaybackSession? = null

    override fun onBind(intent: Intent): IBinder = session ?: PlaybackSession().also { session = it }

    override fun onUnbind(intent: Intent): Boolean {
        shutdown()
        return false
    }

    override fun onDestroy() {
        shutdown()
    }

    private fun shutdown() {
        session?.closeAndWait()
        stopSelf()
        Process.killProcess(Process.myPid())
    }

    private class PlaybackSession : IIsolatedRecordingPlayer.Stub(), AutoCloseable {
        private val thread = HandlerThread("recording-playback").apply { start() }
        private val handler = Handler(thread.looper)
        private val opened = AtomicBoolean()
        private val closed = AtomicBoolean()
        private val releaseFinished = CountDownLatch(1)

        private var player: MediaPlayer? = null
        private var source: SharedMemory? = null
        private var mapping: ByteBuffer? = null
        private var callback: IIsolatedRecordingPlayerCallback? = null
        private var prepareTimeout: Runnable? = null
        private var prepared = false
        private var state = 0
        private var durationMs = -1
        private var terminalDelivered = false
        private var resourcesClosed = false

        private val positionUpdates = object : Runnable {
            override fun run() {
                if (closed.get() || !prepared || state != IIsolatedRecordingPlayer.STATE_STARTED) return
                notifyState()
                handler.postDelayed(this, POSITION_UPDATE_MS)
            }
        }

        override fun open(plaintext: SharedMemory, plaintextSize: Long, mime: String, surface: Surface?,
                          callback: IIsolatedRecordingPlayerCallback) {
            if (closed.get()) {
                plaintext.close()
                callback.onError(IIsolatedRecordingPlayer.ERROR_CANCELLED)
                return
            }
            if (!opened.compareAndSet(false, true)) {
                plaintext.close()
                callback.onError(IIsolatedRecordingPlayer.ERROR_INVALID_INPUT)
                return
            }
            handler.post { openOnPlayerThread(plaintext, plaintextSize, mime, surface, callback) }
        }

        override fun setSurface(surface: Surface?) {
            handler.post {
                if (closed.get() || player == null) return@post
                try {
                    player?.setSurface(surface?.takeIf { it.isValid })
                } catch (failure: Exception) {
                    fail(IIsolatedRecordingPlayer.ERROR_SERVICE)
                }
            }
        }

        override fun play() {
            handler.post {
                val active = player
                if (closed.get() || !prepared || active == null || state == IIsolatedRecordingPlayer.STATE_STARTED) return@post
                try {
                    if (state == IIsolatedRecordingPlayer.STATE_COMPLETED ||
                        (durationMs > 0 && active.currentPosition >= durationMs)) {
                        active.seekTo(0)
                    }
                    active.start()
                    state = IIsolatedRecordingPlayer.STATE_STARTED
                    handler.removeCallbacks(positionUpdates)
                    notifyState()
                    handler.postDelayed(positionUpdates, POSITION_UPDATE_MS)
                } catch (failure: Exception) {
                    fail(IIsolatedRecordingPlayer.ERROR_SERVICE)
                }
            }
        }

        override fun pause() {
            handler.post {
                val active = player
                if (closed.get() || !prepared || active == null || state != IIsolatedRecordingPlayer.STATE_STARTED) return@post
                try {
                    active.pause()
                    state = IIsolatedRecordingPlayer.STATE_PAUSED
                    handler.removeCallbacks(positionUpdates)
                    notifyState()
                } catch (failure: Exception) {
                    fail(IIsolatedRecordingPlayer.ERROR_SERVICE)
                }
            }
        }

        override fun seekTo(positionMs: Int) {
            handler.post {
                val active = player
                if (closed.get() || !prepared || active == null) return@post
                val target = positionMs.coerceAtLeast(0).let {
                    if (durationMs >= 0) it.coerceAtMost(durationMs) else it
                }
                try {
                    active.seekTo(target)
                    if (state == IIsolatedRecordingPlayer.STATE_COMPLETED) {
                        state = IIsolatedRecordingPlayer.STATE_PAUSED
                    }
                    notifyState()
                } catch (failure: Exception) {
                    fail(IIsolatedRecordingPlayer.ERROR_SERVICE)
                }
            }
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            handler.post { closeOnPlayerThread() }
        }

        fun closeAndWait() {
            close()
            try {
                releaseFinished.await(2, TimeUnit.SECONDS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        private fun openOnPlayerThread(plaintext: SharedMemory, plaintextSize: Long, mime: String,
                                       surface: Surface?, recipient: IIsolatedRecordingPlayerCallback) {
            if (closed.get() || resourcesClosed) {
                plaintext.close()
                recipient.onError(IIsolatedRecordingPlayer.ERROR_CANCELLED)
                return
            }
            if (plaintextSize !in 1..MAX_RECORDING_SOURCE_BYTES) {
                rejectOpen(plaintext, recipient, IIsolatedRecordingPlayer.ERROR_INVALID_INPUT)
                return
            }
            val sharedSize = try {
                plaintext.size
            } catch (failure: Exception) {
                rejectOpen(plaintext, recipient, IIsolatedRecordingPlayer.ERROR_INVALID_INPUT)
                return
            }
            if (sharedSize.toLong() != plaintextSize) {
                rejectOpen(plaintext, recipient, IIsolatedRecordingPlayer.ERROR_INVALID_INPUT)
                return
            }
            if (recordingPlaybackMime(mime) == null) {
                rejectOpen(plaintext, recipient, IIsolatedRecordingPlayer.ERROR_UNSUPPORTED_TYPE)
                return
            }

            source = plaintext
            callback = recipient
            try {
                mapping = plaintext.mapReadOnly()
                val made = MediaPlayer()
                player = made
                made.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                made.setDataSource(SharedMemoryDataSource(mapping!!, plaintextSize))
                made.setSurface(surface?.takeIf { it.isValid })
                made.setOnPreparedListener { preparedPlayer ->
                    if (player !== preparedPlayer || closed.get()) return@setOnPreparedListener
                    try {
                        handler.removeCallbacks(positionUpdates)
                        prepareTimeout?.let(handler::removeCallbacks)
                        prepareTimeout = null
                        durationMs = preparedPlayer.duration
                        prepared = true
                        state = IIsolatedRecordingPlayer.STATE_PREPARED
                        val position = currentPosition()
                        callback?.onPrepared(durationMs.coerceAtLeast(0), preparedPlayer.videoWidth.coerceAtLeast(0),
                            preparedPlayer.videoHeight.coerceAtLeast(0))
                        callback?.onState(IIsolatedRecordingPlayer.STATE_PREPARED, position)
                    } catch (failure: Exception) {
                        fail(IIsolatedRecordingPlayer.ERROR_SERVICE)
                    }
                }
                made.setOnCompletionListener { completedPlayer ->
                    if (player !== completedPlayer || closed.get() || !prepared) return@setOnCompletionListener
                    handler.removeCallbacks(positionUpdates)
                    state = IIsolatedRecordingPlayer.STATE_COMPLETED
                    val position = if (durationMs > 0) durationMs else currentPosition()
                    callback?.onState(IIsolatedRecordingPlayer.STATE_COMPLETED, position)
                }
                made.setOnErrorListener { errorPlayer, what, _ ->
                    if (player !== errorPlayer || closed.get()) return@setOnErrorListener true
                    val code = when (what) {
                        MediaPlayer.MEDIA_ERROR_SERVER_DIED -> IIsolatedRecordingPlayer.ERROR_SERVICE
                        MediaPlayer.MEDIA_ERROR_UNSUPPORTED -> IIsolatedRecordingPlayer.ERROR_UNSUPPORTED_TYPE
                        else -> IIsolatedRecordingPlayer.ERROR_CORRUPT
                    }
                    fail(code)
                    true
                }
                val timeout = Runnable {
                    if (player === made && !prepared && !closed.get()) {
                        fail(IIsolatedRecordingPlayer.ERROR_TIMEOUT)
                    }
                }
                prepareTimeout = timeout
                made.prepareAsync()
                handler.postDelayed(timeout, PREPARE_TIMEOUT_MS)
            } catch (failure: Throwable) {
                fail(when (failure) {
                    is IOException -> IIsolatedRecordingPlayer.ERROR_CORRUPT
                    is SecurityException -> IIsolatedRecordingPlayer.ERROR_SERVICE
                    is OutOfMemoryError -> IIsolatedRecordingPlayer.ERROR_SERVICE
                    else -> IIsolatedRecordingPlayer.ERROR_CORRUPT
                })
            }
        }

        private fun rejectOpen(plaintext: SharedMemory, recipient: IIsolatedRecordingPlayerCallback, code: Int) {
            plaintext.close()
            terminalDelivered = true
            recipient.onError(code)
        }

        private fun currentPosition(): Int {
            val position = player?.currentPosition?.coerceAtLeast(0) ?: 0
            return if (durationMs > 0) position.coerceAtMost(durationMs) else position
        }

        private fun notifyState() {
            if (player == null || closed.get()) return
            callback?.onState(state, currentPosition())
        }

        private fun fail(code: Int) {
            if (terminalDelivered || resourcesClosed) return
            terminalDelivered = true
            val recipient = callback
            callback = null
            releaseResources()
            recipient?.onError(code)
        }

        private fun closeOnPlayerThread() {
            try {
                if (!resourcesClosed) {
                    resourcesClosed = true
                    val notifyCancelled = !terminalDelivered && player != null && !prepared
                    terminalDelivered = true
                    val recipient = callback
                    callback = null
                    releaseResources()
                    if (notifyCancelled) recipient?.onError(IIsolatedRecordingPlayer.ERROR_CANCELLED)
                }
            } finally {
                releaseFinished.countDown()
                thread.quitSafely()
            }
        }

        private fun releaseResources() {
            prepareTimeout?.let(handler::removeCallbacks)
            prepareTimeout = null
            handler.removeCallbacks(positionUpdates)
            val oldPlayer = player
            player = null
            try {
                oldPlayer?.setSurface(null)
            } finally {
                try {
                    oldPlayer?.release()
                } finally {
                    val oldMapping = mapping
                    mapping = null
                    try {
                        if (oldMapping != null) SharedMemory.unmap(oldMapping)
                    } finally {
                        val oldSource = source
                        source = null
                        oldSource?.close()
                    }
                }
            }
        }
    }
}

private class SharedMemoryDataSource(
    private val mapping: ByteBuffer,
    private val plaintextSize: Long,
) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position < 0 || offset < 0 || size < 0 || offset > buffer.size - size) return -1
        if (size == 0) return 0
        if (position >= plaintextSize) return 0
        val count = minOf(size.toLong(), plaintextSize - position).toInt()
        val view = mapping.duplicate()
        view.position(position.toInt())
        view.limit(position.toInt() + count)
        view.get(buffer, offset, count)
        return count
    }

    override fun getSize(): Long = plaintextSize

    override fun close() = Unit
}

private const val PREPARE_TIMEOUT_MS = 30_000L
private const val POSITION_UPDATE_MS = 250L
