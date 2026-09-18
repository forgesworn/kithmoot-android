package dev.forgesworn.kithmoot.media.effects

import android.util.Log
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import org.webrtc.VideoFrame
import org.webrtc.VideoProcessor
import org.webrtc.VideoSink

/**
 * What happens to one captured frame.
 *
 * Written down as an enum and a pure function because the rule matters more
 * than the plumbing around it, and because two of the three cases are the ones
 * that would otherwise only be found on a phone.
 */
enum class FrameRoute {
    /** Straight through, untouched, no copies. */
    PASS_THROUGH,

    /** Cut the person out and put them on the chosen sea. */
    COMPOSE,

    /** Nothing is published for this frame. */
    DROP,
}

/**
 * The rule, written down.
 *
 * There is exactly one route to [FrameRoute.PASS_THROUGH] and it is the one
 * thing the person knows about: they have not chosen a background. Nothing
 * else - not the app going away, not the compositor falling behind, not a
 * segmenter that has not finished loading - is allowed to answer "publish the
 * room" on their behalf.
 *
 * The web client's equivalent rule falls back to blurring the real frame. There
 * is no blur here, so the fallback is to publish nothing at all: the far end
 * sees the picture hold still for a moment, which is a thing video calls do
 * anyway, rather than a moment of somebody's kitchen.
 *
 * @param chosen    a scene has been picked; false is the off state
 * @param capturing the capturer is running
 * @param visible   the application is in front of the person
 * @param busy      the worker is still on the previous frame
 */
fun routeFor(chosen: Boolean, capturing: Boolean, visible: Boolean, busy: Boolean): FrameRoute {
    if (!chosen) return FrameRoute.PASS_THROUGH
    if (!capturing) return FrameRoute.DROP
    // Backgrounded: the segmenter has been stopped to give the battery back,
    // so there is nothing to cut the person out with, so nothing goes out.
    if (!visible) return FrameRoute.DROP
    // Behind: drop rather than queue. A queue turns a slow phone into a call
    // that is running four seconds late and still slow.
    if (busy) return FrameRoute.DROP
    return FrameRoute.COMPOSE
}

/** Makes one composited frame from one captured frame. Implemented for real by
 *  `FrameCompositor`, which is the part that needs a device. */
interface FrameComposer {
    /** The composited frame, or null if it could not be made. Never the
     *  original: a composer that cannot compose says so and the frame is
     *  dropped. */
    fun compose(frame: VideoFrame, choice: BackgroundChoice, frontFacing: Boolean): VideoFrame?

    /** Let the model and the bitmaps go. Called when the camera stops and when
     *  the application goes away; the next frame rebuilds what it needs. */
    fun pause()

    fun close()
}

/**
 * Background replacement, as the thing libwebrtc hands every camera frame to.
 *
 * Set on the camera's `VideoSource` once, for the life of the capturer, and
 * **off by default**: with no scene chosen this is a one-branch forward of the
 * frame to the sink libwebrtc gave us, with no copy, no bitmap, no thread hop
 * and no model in memory. Somebody who never turns a background on pays for
 * this file exactly once per frame, in a null check.
 *
 * With a scene chosen, the work happens on one worker thread and at most one
 * frame is ever in flight: a frame that arrives while the last one is still
 * being composited is dropped. See [routeFor].
 */
class BackgroundProcessor(
    private val composer: FrameComposer,
    /** Single-threaded on purpose. Injectable so a test can run the work on the
     *  calling thread and assert on what came out. */
    private val worker: Executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "kithmoot-background").apply { priority = Thread.NORM_PRIORITY - 1 }
    },
    /** Told when the compositor has failed enough times in a row to be worth
     *  saying something about. Called off the caller's thread. */
    private val onTrouble: (String) -> Unit = {},
) : VideoProcessor {

    @Volatile private var sink: VideoSink? = null
    @Volatile private var capturing = false
    @Volatile private var visible = true
    @Volatile private var frontFacing = true
    @Volatile private var choice = BackgroundChoice()
    @Volatile private var closed = false
    private val busy = AtomicBoolean(false)
    @Volatile private var failures = 0

    /**
     * Choose a scene, or pass null for off.
     *
     * Turning it off does not tear anything down here: the next frame takes the
     * pass-through branch immediately, and the model and the bitmaps are given
     * back by [pause], which the camera's own stop calls.
     */
    fun setChoice(value: BackgroundChoice) {
        val was = choice
        choice = value
        if (was.scene != value.scene) failures = 0
        if (!value.on) worker.safely { composer.pause() }
    }

    fun choice(): BackgroundChoice = choice

    /** Which way the camera faces, for the background's mirror. See
     *  `mirrorBackground`. */
    fun setFrontFacing(front: Boolean) {
        frontFacing = front
    }

    /**
     * The application is, or is not, in front of the person.
     *
     * Going away stops the segmenter: a model running on a phone in somebody's
     * pocket is a battery bill for a picture nobody is looking at. Coming back
     * starts it again on the next frame.
     */
    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        if (!value) worker.safely { composer.pause() }
    }

    override fun setSink(target: VideoSink?) {
        sink = target
    }

    override fun onCapturerStarted(success: Boolean) {
        capturing = success
        failures = 0
    }

    override fun onCapturerStopped() {
        capturing = false
        worker.safely { composer.pause() }
    }

    override fun onFrameCaptured(frame: VideoFrame) {
        val out = sink ?: return
        if (closed) return
        val wanted = choice
        when (routeFor(wanted.on, capturing, visible, busy.get())) {
            FrameRoute.PASS_THROUGH -> out.onFrame(frame)
            FrameRoute.DROP -> Unit
            FrameRoute.COMPOSE -> {
                if (!busy.compareAndSet(false, true)) return
                // The caller releases this frame the moment we return, and the
                // worker has not looked at it yet.
                frame.retain()
                val front = frontFacing
                val submitted = worker.safely {
                    try {
                        val made = composer.compose(frame, wanted, front)
                        if (made == null) {
                            noteFailure()
                        } else {
                            failures = 0
                            try { out.onFrame(made) } finally { made.release() }
                        }
                    } catch (e: Exception) {
                        noteFailure(e)
                    } finally {
                        frame.release()
                        busy.set(false)
                    }
                }
                if (!submitted) {
                    frame.release()
                    busy.set(false)
                }
            }
        }
    }

    /**
     * Give the worker and the compositor back. Nothing may touch this
     * afterwards; the source it was set on has to have been disposed first, or
     * a frame can still arrive.
     */
    fun close() {
        closed = true
        worker.safely { composer.close() }
        (worker as? java.util.concurrent.ExecutorService)?.shutdown()
    }

    private fun noteFailure(e: Exception? = null) {
        failures += 1
        Log.w(TAG, "the background could not be drawn (${failures} in a row)", e)
        if (failures == MAX_CONSECUTIVE_FAILURES) {
            onTrouble("The background could not be drawn. Your camera is not being sent while it is on.")
        }
    }

    private fun Executor.safely(block: () -> Unit): Boolean = try {
        execute(block)
        true
    } catch (_: RejectedExecutionException) {
        false
    }

    private companion object {
        const val TAG = "KithMootBackground"

        /**
         * Consecutive failing frames before the person is told.
         *
         * A single failure is usually a resize landing mid-frame or a model
         * reload, and the next frame is fine, so complaining about the first
         * one would turn a hiccup into a warning. Five frames is under a fifth
         * of a second at 30fps - and every one of them is dropped, not passed
         * through.
         */
        const val MAX_CONSECUTIVE_FAILURES = 5
    }
}
