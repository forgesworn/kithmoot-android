package dev.forgesworn.kithmoot.media.effects

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import dev.forgesworn.kithmoot.R
import android.provider.Settings
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame
import org.webrtc.YuvHelper

/**
 * One camera frame in, one frame of somebody sitting in a tropical sea out.
 *
 * This is the only file in the package that needs a device, and everything in
 * it that could be decided by arithmetic has been moved out to `Compositing.kt`,
 * `MaskSmoother.kt` and `ReefShoal.kt`, which a plain JVM test can call.
 *
 * ## The order, and why it is that order
 *
 * 1. Ask the capturer to scale its own buffer down for us. libwebrtc does this
 *    natively and it is the cheapest scale available.
 * 2. Unpack it to ARGB and turn it upright. Upright first, because the model
 *    was trained on people the right way up, and because a sea drawn in buffer
 *    space would be turned on its side by the rotation everything downstream
 *    applies. The frame that goes out carries a rotation of zero.
 * 3. Segment the upright picture.
 * 4. Cut the person out with the smoothed mask, pulled in by a pixel and a half.
 * 5. Draw the sea, the reef over it, and the person over that.
 * 6. Pack it back to I420.
 *
 * ## What it does not do
 *
 * Segmentation is a guess. It is worst at the hair line, at held objects, in
 * low light and under fast movement, and every one of those failures is a piece
 * of the real room drawn on a beach for a frame or two. This makes a room
 * *less* legible. It is not a promise that nobody can see it, and the chooser
 * says so in those words.
 */
class FrameCompositor(
    private val context: Context,
    private val openSegmenter: ((Context) -> PersonSegmenter)? = null,
    private val reef: ReefOverlay = ReefOverlay(),
    private val clockMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    /** The person has asked their phone to remove animations. Read afresh now
     *  and then rather than once, because it can be changed mid-call. */
    private val reducedMotion: () -> Boolean = { false },
    private val onFacePrivacyStatus: (FacePrivacyMode, String) -> Unit = { _, _ -> },
    private val openFaceDetector: ((Context) -> FaceLocator)? = null,
) : FrameComposer {

    private val smoother = MaskSmoother()
    private var segmenter: PersonSegmenter? = null
    private var faceDetector: FaceLocator? = null
    private var faceRetryAt = 0L
    private var faceStatus = ""
    private var sourceOrientation: Pair<Int, Boolean>? = null
    private val privacyMask by lazy { requireNotNull(context.getDrawable(R.drawable.km_privacy_mask)) }

    private var background: Bitmap? = null
    private var backgroundFor: SeaScene? = null

    private var work: Bitmap? = null
    private var person: Bitmap? = null
    private var stencil: Bitmap? = null
    private var size: WorkingSize? = null
    private var pixels: IntArray? = null

    private var maskBitmap: Bitmap? = null
    private var maskPixels: IntArray? = null
    private var maskAlpha: ByteArray? = null

    private var argb: ByteBuffer? = null

    private val copy = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
    private val keepInside = Paint().apply {
        isFilterBitmap = true
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private val smooth = Paint().apply { isFilterBitmap = true; isAntiAlias = true }
    private val plain = Paint()

    /**
     * The camera's picture, scaled down and read out of the GPU, as a frame
     * that owns its own memory.
     *
     * This runs on the capture thread and it is the one piece of work that has
     * to. The scale is libwebrtc's own, which is the cheapest available, and
     * the readback runs on the thread that owns the GL context rather than
     * blocking across to it. `FrameComposer.detach` says why the timing of
     * this matters more than the cost of it.
     */
    @Synchronized
    override fun detach(frame: VideoFrame, choice: BackgroundChoice): VideoFrame? {
        if (closed) return null
        if (needsCameraPixels(choice)) return detach(frame)
        val wanted = workingSize(frame.buffer.width, frame.buffer.height, frame.rotation)
        // No GPU readback, retained camera texture or camera pixels in this path.
        return VideoFrame(JavaI420Buffer.allocate(wanted.scaleWidth, wanted.scaleHeight), frame.rotation, frame.timestampNs)
    }

    @Synchronized
    override fun detach(frame: VideoFrame): VideoFrame? {
        if (closed) return null
        val buffer = frame.buffer
        val wanted = workingSize(buffer.width, buffer.height, frame.rotation)
        val scaled = runCatching {
            buffer.cropAndScale(0, 0, buffer.width, buffer.height, wanted.scaleWidth, wanted.scaleHeight)
        }.getOrElse {
            Log.w(TAG, "the capturer would not scale its own buffer", it)
            return null
        }
        val i420 = runCatching { scaled.toI420() }.getOrElse {
            Log.w(TAG, "the camera texture would not read back", it)
            null
        }
        scaled.release()
        if (i420 == null) return null
        return VideoFrame(i420, frame.rotation, frame.timestampNs)
    }

    /**
     * Synchronised with [pause] and [close], and guarded by [closed].
     *
     * Compositing runs on the processor's worker; closing arrives from
     * whichever thread is stopping the camera. Without the lock those two race
     * over the same bitmaps, and the loser draws on one that has already been
     * recycled - which is a crash on a real phone, not a dropped frame. Found
     * on an emulator exactly that way.
     */
    @Synchronized
    override fun compose(frame: VideoFrame, choice: BackgroundChoice, frontFacing: Boolean): VideoFrame? {
        if (closed) return null
        val scene = choice.scene
        if (!choice.on) return null
        val orientation = ((frame.rotation % 360 + 360) % 360) to frontFacing
        if (sourceOrientation != null && sourceOrientation != orientation) pause()
        sourceOrientation = orientation
        // The buffer arrives already at the working size, because [detach]
        // scaled it. Asking again costs a couple of divides and keeps the two
        // halves from ever disagreeing about how big the picture is.
        val buffer = frame.buffer
        val wanted = workingSize(buffer.width, buffer.height, frame.rotation)
        if (size != wanted) {
            if (size != null) pause()
            release(keepSegmenter = true)
        }
        val work = ensureBitmaps(wanted) ?: return null
        val canvas = Canvas(work)
        if (choice.face == FacePrivacyMode.AVATAR) {
            faceDetector?.close(); faceDetector = null
            drawAvatar(canvas, wanted)
            privacyStatus(choice.face, "Avatar: the whole camera picture is replaced. The camera stays on locally.")
            return pack(work, wanted, frame.timestampNs)
        }

        if (!needsCameraPixels(choice) && scene != null) {
            if (!drawBackdrop(canvas, scene, choice.showsFish, wanted, frontFacing)) return null
            return pack(work, wanted, frame.timestampNs)
        }

        if (!unpack(frame, wanted, work)) return null
        val faces = if (choice.face == FacePrivacyMode.MASK) {
            try {
                val now = clockMs()
                if (faceDetector == null && now >= faceRetryAt) faceDetector = openFaceDetector?.invoke(context)
                val boxes = faceDetector?.detect(work, now) ?: emptyList()
                faceCovers(boxes, wanted.outWidth, wanted.outHeight)
            } catch (_: Exception) {
                runCatching { faceDetector?.close() }; faceDetector = null
                faceRetryAt = clockMs() + 5_000L
                null
            } catch (_: LinkageError) {
                runCatching { faceDetector?.close() }; faceDetector = null
                faceRetryAt = clockMs() + 5_000L
                null
            }
        } else {
            faceDetector?.close(); faceDetector = null
            privacyStatus(choice.face, "")
            null
        }
        if (choice.face == FacePrivacyMode.MASK && faces == null) {
            drawAvatar(canvas, wanted)
            privacyStatus(choice.face, "Picture covered: face detection is unavailable or found no face. Avatar is available.")
            return pack(work, wanted, frame.timestampNs)
        }

        if (scene != null) {
            val mask = segmentOf(work, frame.timestampNs / 1_000_000L)
            if (mask != null && !cutPersonOut(mask, wanted)) return null
            if (!drawBackdrop(canvas, scene, choice.showsFish, wanted, frontFacing)) return null
            if (mask != null) canvas.drawBitmap(person!!, 0f, 0f, plain)
        }
        if (faces != null) {
            for (box in faces) drawPrivacyMask(canvas, box)
            privacyStatus(choice.face, "Masking ${faces.size} face(s). Tracking can miss faces; Avatar replaces the whole picture.")
        }

        return pack(work, wanted, frame.timestampNs)
    }

    @Synchronized
    override fun pause() {
        sourceOrientation = null
        // The model and the sprites are the expensive things to hold; the
        // bitmaps come back on the next frame for the price of an allocation.
        runCatching { segmenter?.close() }
        segmenter = null
        runCatching { faceDetector?.close() }
        faceDetector = null
        faceRetryAt = 0L
        smoother.reset()
        reef.release()
    }

    @Synchronized
    override fun close() {
        closed = true
        pause()
        release(keepSegmenter = false)
    }

    @Volatile private var closed = false


    // -- steps ----------------------------------------------------------------

    private fun needsCameraPixels(choice: BackgroundChoice): Boolean =
        choice.face != FacePrivacyMode.AVATAR &&
            (choice.face == FacePrivacyMode.MASK || choice.scene == null || openSegmenter != null)

    private fun drawBackdrop(canvas: Canvas, scene: SeaScene, fish: Boolean, wanted: WorkingSize, frontFacing: Boolean): Boolean {
        if (!drawScene(canvas, scene, wanted, frontFacing)) return false
        if (fish) {
            reef.load(context)
            reef.draw(canvas, wanted.outWidth, wanted.outHeight, clockMs(), reducedMotion())
        }
        return true
    }

    private fun privacyStatus(mode: FacePrivacyMode, message: String) {
        if (message == faceStatus) return
        faceStatus = message
        onFacePrivacyStatus(mode, message)
    }

    private fun drawAvatar(canvas: Canvas, wanted: WorkingSize) {
        canvas.drawColor(0xFF101820.toInt())
        val height = wanted.outHeight * 0.8f
        val width = minOf(wanted.outWidth * 0.7f, height * 200f / 240f)
        // An unavailable/broken drawable still publishes the opaque fill.
        runCatching {
            drawPrivacyMask(canvas, FaceBox((wanted.outWidth - width) / 2, (wanted.outHeight - height) / 2, width, height))
        }
    }

    private fun drawPrivacyMask(canvas: Canvas, box: FaceBox) {
        // Draw an opaque rectangle beneath the artwork, including its corners.
        // The visor and vents are painted marks, never transparent holes.
        plain.color = 0xFF101820.toInt()
        canvas.drawRect(box.x, box.y, box.x + box.width, box.y + box.height, plain)
        canvas.save()
        try {
            canvas.translate(box.x, box.y)
            canvas.scale(box.width / 200f, box.height / 240f)
            privacyMask.setBounds(0, 0, 200, 240)
            privacyMask.draw(canvas)
        } finally {
            canvas.restore()
        }
    }

    /** The camera's picture, already scaled and read back by [detach], turned
     *  upright into `work`. */
    private fun unpack(frame: VideoFrame, wanted: WorkingSize, work: Bitmap): Boolean {
        val i420 = runCatching { frame.buffer.toI420() }.getOrNull() ?: return false
        try {
            val w = wanted.scaleWidth
            val h = wanted.scaleHeight
            val out = pixels ?: IntArray(w * h).also { pixels = it }
            Yuv.i420ToArgb(
                bytesOf(0, i420.dataY, i420.strideY * h), i420.strideY,
                bytesOf(1, i420.dataU, i420.strideU * ((h + 1) / 2)), i420.strideU,
                bytesOf(2, i420.dataV, i420.strideV * ((h + 1) / 2)), i420.strideV,
                out, w, h,
            )
            // Straight into a scratch bitmap at buffer size, then rotated into
            // the upright one. Two draws rather than one rotate-while-unpacking
            // loop, because the rotate is the GPU's or the platform's problem
            // and the loop would be ours.
            val flat = ensureFlat(w, h) ?: return false
            flat.setPixels(out, 0, w, 0, 0, w, h)
            val canvas = Canvas(work)
            canvas.save()
            when (((frame.rotation % 360) + 360) % 360) {
                90 -> { canvas.rotate(90f); canvas.translate(0f, -wanted.outWidth.toFloat()) }
                180 -> { canvas.rotate(180f); canvas.translate(-wanted.outWidth.toFloat(), -wanted.outHeight.toFloat()) }
                270 -> { canvas.rotate(270f); canvas.translate(-wanted.outHeight.toFloat(), 0f) }
                else -> Unit
            }
            canvas.drawBitmap(flat, 0f, 0f, copy)
            canvas.restore()
            return true
        } catch (e: Exception) {
            Log.w(TAG, "the camera frame would not unpack", e)
            return false
        } finally {
            i420.release()
        }
    }

    private fun segmentOf(work: Bitmap, stamp: Long): FloatArray? {
        val model = segmenter ?: runCatching { openSegmenter?.invoke(context) ?: UnavailableSegmenter() }
            .onFailure { Log.w(TAG, "the segmentation model would not load", it) }
            // A failed SDK graph construction can retain native resources.
            // Retrying every camera frame exhausts memory; retain the safe
            // synthetic scene until pause starts a fresh camera/effect session.
            .getOrElse { UnavailableSegmenter() }
            .also { segmenter = it; smoother.reset() }
        val mask = runCatching { model.segment(work, stamp) }
            .onFailure { Log.w(TAG, "the frame would not segment", it) }
            .getOrNull()
        if (mask == null || mask.width !in 1..MAX_WORKING_WIDTH || mask.height !in 1..MAX_WORKING_WIDTH ||
            mask.data.size != mask.width * mask.height || mask.data.any { !it.isFinite() || it !in 0f..1f }) {
            smoother.reset()
            return null
        }
        maskWidth = mask.width
        maskHeight = mask.height
        return smoother.push(mask.data, mask.width, mask.height)
    }

    private var maskWidth = 0
    private var maskHeight = 0

    /**
     * The person, alone, on a transparent background.
     *
     * The stencil is built at the output size and then eroded by four offset
     * `DST_IN` draws, which keep the smaller of the two alphas at every pixel:
     * left and right are a three-tap minimum across, up and down the same down
     * the column, and the two together are a separable erosion paid for by
     * whatever is drawing the canvas rather than by a loop over every pixel.
     *
     * A threshold moves the cut in *confidence*, which only shifts the edge as
     * far as the mask happens to ramp. What has to go is fixed in *pixels*: a
     * camera's own edge, after chroma subsampling and whatever scaling happened
     * on the way, is a pixel or so of the person blended with the room behind
     * them, and against a busy photograph that reads as a dotted line round the
     * head.
     */
    private fun cutPersonOut(mask: FloatArray, wanted: WorkingSize): Boolean {
        val mw = maskWidth
        val mh = maskHeight
        if (mw <= 0 || mh <= 0) return false
        val alpha = maskAlpha.takeIf { it != null && it.size >= mw * mh } ?: ByteArray(mw * mh).also { maskAlpha = it }
        maskToAlpha(mask, alpha, mw, mh)

        val ints = maskPixels.takeIf { it != null && it.size >= mw * mh } ?: IntArray(mw * mh).also { maskPixels = it }
        for (i in 0 until mw * mh) ints[i] = (alpha[i].toInt() and 0xFF) shl 24 or 0x00FFFFFF
        val small = ensureMask(mw, mh) ?: return false
        small.setPixels(ints, 0, mw, 0, 0, mw, mh)

        val stencil = this.stencil ?: return false
        val person = this.person ?: return false
        val work = this.work ?: return false

        val full = RectF(0f, 0f, wanted.outWidth.toFloat(), wanted.outHeight.toFloat())
        val from = Rect(0, 0, mw, mh)
        val onStencil = Canvas(stencil)
        // Scaling a low-resolution stencil up with filtering on is what softens
        // the edge for free; the feather in `maskToAlpha` does the rest.
        onStencil.drawBitmap(small, from, full, Paint().apply { isFilterBitmap = true; xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) })
        val e = STENCIL_ERODE_PX
        for (offset in listOf(-e to 0f, e to 0f, 0f to -e, 0f to e)) {
            onStencil.drawBitmap(
                small,
                from,
                RectF(full.left + offset.first, full.top + offset.second, full.right + offset.first, full.bottom + offset.second),
                keepInside,
            )
        }

        val onPerson = Canvas(person)
        onPerson.drawBitmap(work, 0f, 0f, copy)
        onPerson.drawBitmap(stencil, 0f, 0f, keepInside)
        return true
    }

    /** The sea, cover-fitted and centre-cropped, mirrored on the front camera. */
    private fun drawScene(canvas: Canvas, scene: SeaScene, wanted: WorkingSize, frontFacing: Boolean): Boolean {
        val asset = scene.asset
        if (asset == null) { canvas.drawColor(PLAIN_FILL); return true }
        val picture = ensureBackground(scene, asset) ?: return false
        val rect = coverRect(picture.width, picture.height, wanted.outWidth, wanted.outHeight)
        canvas.save()
        if (mirrorBackground(frontFacing)) {
            canvas.scale(-1f, 1f, wanted.outWidth / 2f, wanted.outHeight / 2f)
        }
        canvas.drawBitmap(
            picture,
            Rect(0, 0, picture.width, picture.height),
            RectF(rect.dx, rect.dy, rect.right, rect.bottom),
            smooth,
        )
        canvas.restore()
        return true
    }

    /** Back to I420, which is what everything past here speaks. */
    private fun pack(work: Bitmap, wanted: WorkingSize, timestampNs: Long): VideoFrame? {
        val w = wanted.outWidth
        val h = wanted.outHeight
        val bytes = w * 4 * h
        val packed = argb.takeIf { it != null && it.capacity() >= bytes }
            ?: ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder()).also { argb = it }
        packed.rewind()
        work.copyPixelsToBuffer(packed)
        packed.rewind()

        val out = runCatching { JavaI420Buffer.allocate(w, h) }.getOrNull() ?: return null
        return try {
            // Android's ARGB_8888 is R, G, B, A in memory order, which is what
            // libyuv calls ABGR. Getting this the wrong way round swaps red and
            // blue and nothing else, so it looks like a colour-profile bug.
            YuvHelper.ABGRToI420(
                packed, w * 4,
                out.dataY, out.strideY,
                out.dataU, out.strideU,
                out.dataV, out.strideV,
                w, h,
            )
            // Rotation zero: the picture is already upright.
            VideoFrame(out, 0, timestampNs)
        } catch (e: Exception) {
            Log.w(TAG, "the composited frame would not pack", e)
            out.release()
            null
        }
    }

    // -- scratch --------------------------------------------------------------

    private var flat: Bitmap? = null

    private fun ensureFlat(w: Int, h: Int): Bitmap? {
        val held = flat
        if (held != null && held.width == w && held.height == h) return held
        held?.recycle()
        val made = runCatching { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }.getOrNull()
        flat = made
        return made
    }

    private fun ensureMask(w: Int, h: Int): Bitmap? {
        val held = maskBitmap
        if (held != null && held.width == w && held.height == h) return held
        held?.recycle()
        val made = runCatching { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }.getOrNull()
        maskBitmap = made
        return made
    }

    private fun ensureBitmaps(wanted: WorkingSize): Bitmap? {
        if (size == wanted && work != null) return work
        val w = wanted.outWidth
        val h = wanted.outHeight
        val made = runCatching {
            Triple(
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888),
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888),
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888),
            )
        }.getOrElse {
            Log.w(TAG, "there was not room for the working bitmaps", it)
            return null
        }
        work = made.first
        person = made.second
        stencil = made.third
        pixels = null
        size = wanted
        smoother.reset()
        return made.first
    }

    private fun ensureBackground(scene: SeaScene, asset: String): Bitmap? {
        if (backgroundFor == scene) background?.let { return it }
        background?.recycle()
        background = null
        backgroundFor = null
        val made = runCatching {
            context.assets.open(asset).use { BitmapFactory.decodeStream(it) }
        }.getOrElse {
            Log.w(TAG, "the ${scene.label} background would not decode", it)
            null
        } ?: return null
        background = made
        backgroundFor = scene
        return made
    }

    private fun release(keepSegmenter: Boolean) {
        if (!keepSegmenter) {
            runCatching { segmenter?.close() }
            segmenter = null
        }
        work?.recycle(); work = null
        person?.recycle(); person = null
        stencil?.recycle(); stencil = null
        flat?.recycle(); flat = null
        maskBitmap?.recycle(); maskBitmap = null
        background?.recycle(); background = null
        backgroundFor = null
        pixels = null
        maskPixels = null
        maskAlpha = null
        argb = null
        size = null
        planes.fill(null)
        smoother.reset()
    }

    // Three planes, three scratch arrays: one shared array would be overwritten
    // by the next plane before the conversion had read the last.
    private val planes = arrayOfNulls<ByteArray>(3)

    private fun bytesOf(plane: Int, buffer: ByteBuffer, length: Int): ByteArray {
        val need = minOf(length, buffer.capacity())
        val held = planes[plane].let { if (it != null && it.size >= need) it else ByteArray(need).also { made -> planes[plane] = made } }
        buffer.rewind()
        buffer.get(held, 0, need)
        return held
    }

    private companion object {
        const val TAG = "KithMootBackground"
        /** The plain scene: the web client's dark page colour, #101114. */
        const val PLAIN_FILL = 0xFF101114.toInt()

        /** How far the person's outline is pulled in, in output pixels. The
         *  web client's `STENCIL_ERODE_PX`. */
        const val STENCIL_ERODE_PX = 1.5f
    }
}

/**
 * Whether this phone has been asked to remove animations.
 *
 * An accessibility setting, and one of the few that is about physical
 * discomfort rather than preference: drifting light and swimming fish behind a
 * talking head is exactly the sort of thing it exists to switch off. Android
 * spells it as an animation duration scale of zero, which is what the
 * developer options switch and the accessibility one both write.
 *
 * Read afresh now and then rather than once, because it can be changed while a
 * call is running, and cached for a second because it is a settings lookup and
 * this is asked thirty times a second.
 */
class ReducedMotion(private val context: Context, private val clockMs: () -> Long = { android.os.SystemClock.elapsedRealtime() }) {
    private var asked = Long.MIN_VALUE
    private var answer = false

    fun on(): Boolean {
        val now = clockMs()
        if (asked != Long.MIN_VALUE && now - asked < 1_000L) return answer
        asked = now
        answer = runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
        return answer
    }
}
