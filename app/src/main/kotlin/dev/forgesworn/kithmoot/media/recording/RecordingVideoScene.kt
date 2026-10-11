package dev.forgesworn.kithmoot.media.recording

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import java.util.ArrayDeque
import org.webrtc.GlRectDrawer
import org.webrtc.GlUtil
import org.webrtc.VideoFrame
import org.webrtc.VideoFrameDrawer
import org.webrtc.VideoSink
import org.webrtc.VideoTrack

/** Independent sinks on the call owner's authorised, privacy-processed tracks.
 * At most three scaled I420 frames per selected source are retained. Their
 * dimensions fit the export viewport, rather than retaining full camera or
 * screen textures. The callback clock is the audio mixer's paused timeline.
 * Native sink attachment/removal always happens outside the callback monitor.
 */
class RecordingVideoScene(initial: RecordingVideoPlan) {
    private data class Snapshot(val at: Long, val frame: VideoFrame)
    private class Input(val key: RecordingVideoKey, val track: VideoTrack, val width: Int, val height: Int) {
        lateinit var sink: VideoSink
        val frames = ArrayDeque<Snapshot>()
        var bucket = -1L
        var unavailable: String? = null
        fun clear() { frames.forEach { it.frame.release() }; frames.clear(); bucket = -1 }
    }
    private val origin = initial.origin
    private val layout = initial.layout
    private val selectedShare = initial.selected
    private var plan = initial
    private val inputs = LinkedHashMap<RecordingVideoKey, Input>()
    private var timeline: (() -> Long?)? = null
    private var paused = false
    private var stopped = false
    private var disposed = false
    private var drawer: GlRectDrawer? = null
    private var overlayDrawer: GlRectDrawer? = null
    private var frameDrawer: VideoFrameDrawer? = null
    private var overlay: Bitmap? = null
    private var texture = 0
    private var overlayVersion = -1L
    private var version = 0L
    private var overlayMessages: List<String?> = emptyList()

    /** Capture diagnostics for bounded-frame and source-revocation checks. */
    @Synchronized internal fun inputSamples(): Map<RecordingVideoKey, Long> =
        inputs.mapNotNull { (key, input) -> input.frames.lastOrNull()?.let { key to it.at } }.toMap()

    @Synchronized fun bindTimeline(clock: () -> Long?) {
        check(!stopped && !disposed && timeline == null)
        timeline = clock
    }

    fun setPlan(next: RecordingVideoPlan, tracks: Map<RecordingVideoKey, VideoTrack>) {
        require(next.origin.room == origin.room && next.origin.call == origin.call && next.layout == layout) {
            "The recording belongs to another call or layout"
        }
        if (layout == RecordingVideoLayout.SCREEN_CAMERA) require(next.selected == selectedShare) {
            "The recording belongs to another screen sharer"
        }
        val remove = mutableListOf<Input>()
        val add = mutableListOf<Input>()
        synchronized(this) {
            if (stopped || disposed) return
            plan = next; version++
            val slots = next.slots.mapNotNull { slot -> slot.source?.let { it to slot } }.toMap()
            for (old in inputs.values.toList()) {
                val slot = slots[old.key]
                if (slot == null || tracks[old.key] !== old.track || slot.bounds.width != old.width || slot.videoHeight != old.height) {
                    inputs.remove(old.key); old.clear(); remove += old
                }
            }
            for ((key, slot) in slots) {
                if (inputs.containsKey(key) || slot.videoHeight <= 0) continue
                val track = tracks[key] ?: continue
                val input = Input(key, track, slot.bounds.width, slot.videoHeight)
                input.sink = VideoSink { frame -> receive(input, frame) }
                inputs[key] = input
                if (!paused) add += input
            }
        }
        remove.forEach { runCatching { it.track.removeSink(it.sink) } }
        add.forEach(::attach)
    }

    private fun attach(input: Input) {
        try {
            input.track.addSink(input.sink)
            val revoked = synchronized(this) { stopped || disposed || paused || inputs[input.key] !== input }
            if (revoked) input.track.removeSink(input.sink)
        } catch (_: Exception) {
            synchronized(this) { if (inputs[input.key] === input) input.unavailable = "Video track unavailable" }
            runCatching { input.track.removeSink(input.sink) }
        }
    }

    /** Local camera/screen-off and hold revoke queued pictures before their
     * native source is stopped. A later authoritative plan may restore it. */
    fun revoke(key: RecordingVideoKey?, reason: String) {
        val held = synchronized(this) {
            val selected = inputs.values.filter { key == null || it.key == key }
            selected.forEach { inputs.remove(it.key); it.clear(); it.unavailable = reason }
            version++
            selected
        }
        held.forEach { runCatching { it.track.removeSink(it.sink) } }
    }

    private fun receive(input: Input, frame: VideoFrame) {
        // Never call the audio clock while holding the video monitor: pause and
        // detachment acquire the audio monitor before updating this scene.
        val clock = synchronized(this) {
            if (paused || stopped || disposed || inputs[input.key] !== input || input.unavailable != null) return
            timeline
        } ?: return
        val at = clock()?.coerceAtLeast(0) ?: return
        synchronized(this) {
            if (paused || stopped || disposed || inputs[input.key] !== input || input.bucket == at / 3200) return
            input.bucket = at / 3200
        }
        var copied: VideoFrame? = null
        try {
            require(frame.rotatedWidth in 2..4096 && frame.rotatedHeight in 2..4096) { "Unsupported video dimensions" }
            val scale = minOf(1.0, input.width.toDouble() / frame.rotatedWidth, input.height.toDouble() / frame.rotatedHeight)
            val width = ((frame.buffer.width * scale).toInt() / 2 * 2).coerceAtLeast(2)
            val height = ((frame.buffer.height * scale).toInt() / 2 * 2).coerceAtLeast(2)
            val i420 = requireNotNull(frame.buffer.toI420()) { "Unsupported video buffer" }
            val buffer = try { i420.cropAndScale(0, 0, i420.width, i420.height, width, height) } finally { i420.release() }
            copied = VideoFrame(buffer, frame.rotation, frame.timestampNs)
            synchronized(this) {
                if (!paused && !stopped && !disposed && inputs[input.key] === input && (input.frames.lastOrNull()?.at ?: -1) <= at) {
                    input.frames.addLast(Snapshot(at, copied!!)); copied = null
                    while (input.frames.size > 3) input.frames.removeFirst().frame.release()
                }
            }
        } catch (error: Exception) {
            synchronized(this) {
                if (inputs[input.key] === input) {
                    input.clear()
                    input.unavailable = if (error is IllegalArgumentException) "Unsupported video dimensions" else "Video frame unavailable"
                }
            }
        } finally { copied?.release() }
    }

    /** Run only on the export worker with its encoder EGL context current.
     * Serialising rendering with revocation prevents a queued excluded input
     * entering a later output frame after setPlan returns. */
    @Synchronized fun draw(sample: Long, width: Int, height: Int) {
        check(!disposed)
        require(width == RecordingVideoPlan.WIDTH && height == RecordingVideoPlan.HEIGHT)
        val rgb = drawer ?: GlRectDrawer().also { drawer = it }
        // GlGenericDrawer caches only its current shader type. A separate RGB
        // drawer keeps text overlays from replacing the I420 shader on every
        // output frame and repeatedly compiling shaders during capture.
        val textDrawer = overlayDrawer ?: GlRectDrawer().also { overlayDrawer = it }
        val video = frameDrawer ?: VideoFrameDrawer().also { frameDrawer = it }
        // Scaled I420 chroma rows may have widths such as 122 bytes. The SDK
        // uploader supplies tightly packed planes and leaves pixel-store state
        // to its caller; the fresh encoder context defaults to four-byte rows.
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0.055f, 0.067f, 0.086f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val messages = plan.slots.map { slot ->
            val key = slot.source
            val input = key?.let(inputs::get)
            val snapshot = input?.frames?.lastOrNull { it.at <= sample }
            val message = slot.unavailable ?: input?.unavailable ?: when {
                input == null -> "Video track unavailable"
                snapshot == null -> "Waiting for video"
                sample - snapshot.at > 5L * 48_000 -> "Video unavailable: no recent frame"
                else -> null
            }
            // Clear each slot in painter order, including an unavailable PiP.
            // The text overlay must not cover a live PiP when the main share
            // is unavailable, or expose the share through a missing camera.
            val rect = slot.bounds
            GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
            GLES20.glScissor(rect.x, height - rect.y - rect.height, rect.width, rect.height)
            GLES20.glClearColor(0.13f, 0.15f, 0.18f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
            if (message == null && snapshot != null) {
                val scale = minOf(slot.bounds.width.toDouble() / snapshot.frame.rotatedWidth,
                    slot.videoHeight.toDouble() / snapshot.frame.rotatedHeight)
                val w = (snapshot.frame.rotatedWidth * scale).toInt().coerceAtLeast(1)
                val h = (snapshot.frame.rotatedHeight * scale).toInt().coerceAtLeast(1)
                val x = slot.bounds.x + (slot.bounds.width - w) / 2
                val y = slot.bounds.y + (slot.videoHeight - h) / 2
                video.drawFrame(snapshot.frame, rgb, null, x, height - y - h, w, h)
            }
            message
        }
        if (overlayVersion != version || overlayMessages != messages || texture == 0) {
            updateOverlay(messages)
            overlayVersion = version; overlayMessages = messages
        }
        val matrix = FloatArray(16).also { Matrix.setIdentityM(it, 0); Matrix.translateM(it, 0, 0f, 1f, 0f); Matrix.scaleM(it, 0, 1f, -1f, 1f) }
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        textDrawer.drawRgb(texture, matrix, width, height, 0, 0, width, height)
        GLES20.glDisable(GLES20.GL_BLEND)
        GlUtil.checkNoGLES2Error("Recording composition")
    }

    private fun updateOverlay(messages: List<String?>) {
        val bitmap = overlay ?: Bitmap.createBitmap(RecordingVideoPlan.WIDTH, RecordingVideoPlan.HEIGHT, Bitmap.Config.ARGB_8888).also { overlay = it }
        bitmap.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.rgb(14, 17, 22)
        canvas.drawRect(0f, 0f, 1280f, RecordingVideoPlan.HEADER.toFloat(), paint)
        paint.color = Color.WHITE; paint.textSize = 18f
        canvas.drawText("${plan.layout.label} · ${printable(plan.origin.name).take(80)}", 12f, 22f, paint)
        paint.textSize = 12f
        canvas.drawText("Room ${plan.origin.room}", 12f, 42f, paint)
        canvas.drawText("Call ${plan.origin.call}", 12f, 60f, paint)
        plan.slots.forEachIndexed { index, slot ->
            val rect = slot.bounds
            canvas.save(); canvas.clipRect(rect.x, rect.y, rect.x + rect.width, rect.y + rect.height)
            val message = messages[index]
            if (message != null) {
                paint.color = Color.WHITE; paint.textSize = 12f
                drawWrapped(canvas, paint, message, rect.x + 8, rect.y + 24, rect.width - 16, 16)
                if (slot.overflow.isNotEmpty()) {
                    paint.textSize = 11f
                    val count = ((rect.height - 56) / 16).coerceAtLeast(0)
                    slot.overflow.take(count).forEachIndexed { line, endpoint ->
                        canvas.drawText(printable(endpoint.name).take((rect.width / 7).coerceAtLeast(1)), (rect.x + 8).toFloat(), (rect.y + 56 + line * 16).toFloat(), paint)
                    }
                }
            }
            slot.endpoint?.let { endpoint ->
                val top = rect.y + slot.videoHeight
                paint.color = Color.argb(238, 14, 17, 22)
                canvas.drawRect(rect.x.toFloat(), top.toFloat(), (rect.x + rect.width).toFloat(), (rect.y + rect.height).toFloat(), paint)
                paint.color = Color.WHITE; paint.textSize = 14f
                canvas.drawText(printable(endpoint.name).take((rect.width / 8).coerceAtLeast(1)), (rect.x + 8).toFloat(), (top + 16).toFloat(), paint)
                paint.textSize = 10f
                var y = top + 30
                y = drawWrapped(canvas, paint, "P ${endpoint.key.participant}", rect.x + 8, y, rect.width - 16, 12)
                drawWrapped(canvas, paint, "D ${endpoint.key.device}", rect.x + 8, y, rect.width - 16, 12)
            }
            canvas.restore()
        }
        if (texture == 0) texture = GlUtil.generateTexture(GLES20.GL_TEXTURE_2D)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
    }

    private fun drawWrapped(canvas: Canvas, paint: Paint, text: String, x: Int, initialY: Int, width: Int, lineHeight: Int): Int {
        var y = initialY
        var remaining = printable(text)
        while (remaining.isNotEmpty()) {
            val count = paint.breakText(remaining, true, width.toFloat(), null).coerceAtLeast(1)
            canvas.drawText(remaining.take(count), x.toFloat(), y.toFloat(), paint)
            remaining = remaining.drop(count); y += lineHeight
        }
        return y
    }

    fun pause(on: Boolean) {
        val held = synchronized(this) {
            check(!stopped && !disposed)
            if (paused == on) return
            paused = on
            inputs.values.toList().also { list ->
                // Pause keeps the last accepted frames for the worker's final
                // pre-pause flush. Resume drops them before accepting new ones.
                if (!on) list.forEach { it.clear(); it.unavailable = null }
            }
        }
        if (on) held.forEach { runCatching { it.track.removeSink(it.sink) } } else held.forEach(::attach)
    }

    /** Freeze accepted video for finalisation while revoking future callbacks. */
    fun detachInputs() {
        val held = synchronized(this) {
            if (stopped || disposed) return
            stopped = true; inputs.values.toList()
        }
        held.forEach { runCatching { it.track.removeSink(it.sink) } }
    }

    /** Called by AvRecordingFile before releasing its current EGL context. */
    @Synchronized fun releaseGl() {
        frameDrawer?.release(); frameDrawer = null
        drawer?.release(); drawer = null
        overlayDrawer?.release(); overlayDrawer = null
        if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
        texture = 0; overlay?.recycle(); overlay = null
    }

    fun dispose() {
        detachInputs()
        synchronized(this) { inputs.values.forEach { it.clear() }; inputs.clear(); timeline = null; disposed = true }
    }

    private fun printable(value: String) = value.filterNot(Char::isISOControl)
}
