package dev.forgesworn.kithmoot.media.effects

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Log
import kotlin.math.PI
import kotlin.math.sin

/**
 * The fish, and the water they are in, drawn over the sea photograph.
 *
 * The drawing half of `reef-scene.ts`; [ReefShoal] is the other half and owns
 * everything that moves. Three layers go on top of the still photograph, in
 * this order and all of them behind the person:
 *
 * - **light shafts**, three of them, pre-rendered once and swayed by a
 *   rotation, on tens-of-seconds cycles;
 * - **motes**, sixteen specks of whatever it is that drifts about in warm
 *   water, with no state at all: each one's position is a function of its index
 *   and the scene clock;
 * - **the fish**, at most three at a time and usually none.
 *
 * Every sprite faces LEFT. That is a contract with the files rather than a
 * preference: one mirror decides which way a fish is swimming, instead of two
 * sets of artwork.
 */
class ReefOverlay(
    private val shoal: ReefShoal = ReefShoal(),
) {
    private var sprites: List<Bitmap> = emptyList()
    private var loaded = false

    private var shaft: Bitmap? = null
    private var shaftFor: Pair<Int, Int>? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val matrix = Matrix()

    /**
     * Load the fish photographs. Six files, fifty kilobytes the lot, decoded
     * once and kept for the life of the compositor.
     *
     * A sprite that will not decode is dropped rather than thrown: five fish is
     * a slightly smaller reef, and none at all is a sea with no fish in it.
     * Neither is a reason to take somebody's background away mid-call.
     */
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        sprites = FISH_SPRITES.mapNotNull { path ->
            runCatching {
                context.assets.open(path).use { BitmapFactory.decodeStream(it) }
            }.getOrElse {
                Log.w(TAG, "a fish sprite would not decode: $path")
                null
            }
        }
    }

    /** Give the decoded sprites back. The next [load] fetches them again. */
    fun release() {
        sprites = emptyList()
        loaded = false
        shaft = null
        shaftFor = null
    }

    /**
     * Draw the reef over whatever is already on [canvas].
     *
     * @param stillMs wall clock, so the shoal advances at the rate the frames
     *   actually arrive
     * @param reducedMotion the person has asked their phone for less movement.
     *   Then nothing moves and nothing swims: the shoal is deliberately not
     *   advanced, because a fish frozen mid-water is stranger than an empty sea.
     */
    fun draw(canvas: Canvas, width: Int, height: Int, nowMs: Long, reducedMotion: Boolean) {
        if (reducedMotion) return
        shoal.advance(nowMs)
        val t = shoal.elapsedMs / 1000f

        drawShafts(canvas, width, height, t)
        drawMotes(canvas, width, height, t)
        for (one in shoal.fish) drawFish(canvas, one, width, height, t)
        paint.alpha = 255
        paint.colorFilter = null
    }

    // -- layers ---------------------------------------------------------------

    private fun drawShafts(canvas: Canvas, width: Int, height: Int, t: Float) {
        val sprite = ensureShaft(width, height) ?: return
        for (s in SHAFTS) {
            val x = (s.at + sin(t * s.rate * 2f * PI.toFloat() + s.phase) * s.sway) * width
            val alpha = s.alpha * (0.62f + 0.38f * (0.5f + 0.5f * sin(t * s.rate * 3f + s.phase)))
            canvas.save()
            canvas.translate(x, -height * 0.32f)
            canvas.rotate(Math.toDegrees((0.2f + sin(t * s.rate * PI.toFloat() + s.phase) * 0.03f).toDouble()).toFloat())
            paint.alpha = (alpha.coerceIn(0f, 1f) * 255f).toInt()
            canvas.drawBitmap(sprite, -sprite.width / 2f, 0f, paint)
            canvas.restore()
        }
        paint.alpha = 255
    }

    private fun drawMotes(canvas: Canvas, width: Int, height: Int, t: Float) {
        paint.color = 0xFFFFFFFF.toInt()
        for (i in 0 until 16) {
            val seed = i * 0.6180339887f
            val base = seed % 1f
            val rise = (base + t * (0.008f + (i % 5) * 0.0022f)) % 1f
            val y = (1f - rise) * height
            val x = (((seed * 7.3f) % 1f) + sin(t * 0.35f + i) * 0.012f) * width
            val r = (if (i % 3 == 0) 1.6f else 1f) * (width / 640f) * 1.4f
            paint.alpha = ((0.18f + 0.22f * (0.5f + 0.5f * sin(t * 0.8f + i))) * 255f).toInt()
            canvas.drawCircle(x, y, r, paint)
        }
        paint.alpha = 255
    }

    /**
     * One photographed fish.
     *
     * Drawn about its own centre: the direction is a mirror, and the gentle
     * nose-up-as-it-rises tilt has to be negated on the mirrored side or a fish
     * swimming right would nose down as it climbed.
     */
    private fun drawFish(canvas: Canvas, fish: Fish, width: Int, height: Int, t: Float) {
        val index = spriteIndexFor(fish, sprites.size)
        if (index < 0) return
        val sprite = sprites[index]
        if (sprite.width <= 0) return

        val w = fish.size * width
        val h = w * sprite.height / sprite.width
        val x = fish.x * width
        val y = (fish.y + sin(t * fish.bobRate + fish.phase) * fish.bobAmplitude) * height
        val tilt = Math.cos((t * fish.bobRate + fish.phase).toDouble()).toFloat() * 0.1f

        matrix.reset()
        matrix.postTranslate(-sprite.width / 2f, -sprite.height / 2f)
        matrix.postScale(w / sprite.width, h / sprite.height)
        // The sprites face left, so the mirror is for the ones swimming right.
        if (fish.direction == 1) matrix.postScale(-1f, 1f)
        matrix.postRotate(Math.toDegrees((if (fish.direction == 1) -tilt else tilt).toDouble()).toFloat())
        matrix.postTranslate(x, y)

        paint.alpha = (depthAlphaOf(fish.size).coerceIn(0f, 1f) * 255f).toInt()
        canvas.drawBitmap(sprite, matrix, paint)
        paint.alpha = 255
    }

    /**
     * One light shaft, pre-rendered so the per-frame cost is a blit: a
     * top-to-bottom fade, feathered sideways so the edges are not two hard
     * lines. Wider and taller than it needs to be, because it is drawn rotated.
     */
    private fun ensureShaft(width: Int, height: Int): Bitmap? {
        val wanted = width to height
        val held = shaft
        if (held != null && shaftFor == wanted) return held
        val w = maxOf(8, Math.round(width * 0.22f))
        val h = maxOf(8, Math.round(height * 1.7f))
        val made = runCatching { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }.getOrNull() ?: return null
        val canvas = Canvas(made)
        val down = Paint().apply {
            shader = android.graphics.LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                intArrayOf(0x52FFFFFF, 0x1FD6F8FF, 0x00FFFFFF),
                floatArrayOf(0f, 0.55f, 1f),
                android.graphics.Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), down)
        val across = Paint().apply {
            shader = android.graphics.LinearGradient(
                0f, 0f, w.toFloat(), 0f,
                intArrayOf(0x00FFFFFF, 0xFFFFFFFF.toInt(), 0x00FFFFFF),
                floatArrayOf(0f, 0.5f, 1f),
                android.graphics.Shader.TileMode.CLAMP,
            )
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), across)
        shaft = made
        shaftFor = wanted
        return made
    }

    private data class Shaft(
        val at: Float,
        val sway: Float,
        val rate: Float,
        val phase: Float,
        val alpha: Float,
    )

    private companion object {
        const val TAG = "KithMootBackground"

        val SHAFTS = listOf(
            Shaft(at = 0.18f, sway = 0.05f, rate = 0.055f, phase = 0f, alpha = 0.75f),
            Shaft(at = 0.46f, sway = 0.04f, rate = 0.041f, phase = 2.1f, alpha = 0.55f),
            Shaft(at = 0.78f, sway = 0.06f, rate = 0.033f, phase = 4.3f, alpha = 0.65f),
        )
    }
}
