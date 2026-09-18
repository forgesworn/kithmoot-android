package dev.forgesworn.kithmoot.media.effects

/**
 * The arithmetic behind putting a sea where a room was.
 *
 * Everything in this file is plain numbers on purpose. Nothing here touches
 * `android.graphics`, a `Bitmap` or a `VideoFrame`, so the part of background
 * replacement that is easy to get subtly wrong - a photograph stretched to a
 * phone's aspect ratio, a scene that flips when the preview mirrors, a working
 * resolution that is not an even number of pixels and quietly corrupts the
 * chroma planes - is decided by functions a plain JVM test can call.
 *
 * The drawing itself is in `FrameCompositor.kt`, which is the only file here
 * that needs a device.
 */

/**
 * Largest the composited frame is ever drawn at, in pixels across the upright
 * picture.
 *
 * Mirrors `SCENE_MAX_WIDTH` in the web client's `reef-scene.ts`, and for the
 * same reason: the frame goes behind a person, through an encoder, at whatever
 * bitrate the call is running. Compositing a 720p frame would cost four times
 * as much per frame - on a phone, in Kotlin, on battery - for detail the
 * encoder is about to throw away. The camera keeps capturing at 1280x720; this
 * is only what the compositor works at.
 */
const val MAX_WORKING_WIDTH = 640

/**
 * Where to draw a `srcW x srcH` picture so it fills `dstW x dstH` without
 * distorting it, cropping the overhang.
 *
 * The CSS `object-fit: cover` rule, and the same function as `coverRect` in the
 * web client's `video-effects.ts`. A bundled sea stretched to a handset's
 * aspect ratio looks like a mistake, and a handset in portrait against a
 * landscape photograph is the normal case rather than the awkward one.
 */
data class CoverRect(val dx: Float, val dy: Float, val dw: Float, val dh: Float) {
    val right: Float get() = dx + dw
    val bottom: Float get() = dy + dh
}

fun coverRect(srcW: Int, srcH: Int, dstW: Int, dstH: Int): CoverRect {
    if (srcW <= 0 || srcH <= 0) return CoverRect(0f, 0f, dstW.toFloat(), dstH.toFloat())
    val scale = maxOf(dstW.toFloat() / srcW, dstH.toFloat() / srcH)
    val dw = srcW * scale
    val dh = srcH * scale
    return CoverRect((dstW - dw) / 2f, (dstH - dh) / 2f, dw, dh)
}

/**
 * The size the compositor works at, and the size of the buffer it asks the
 * capturer to scale down to first.
 *
 * Two things are settled here at once and they are not separable.
 *
 * **Rotation.** A phone held upright hands over a landscape buffer with a
 * rotation of 90 or 270 in the frame's metadata, and everything downstream -
 * the local renderer, the encoder, the far end - applies it. Compositing in
 * buffer space and passing the rotation on would draw the sea sideways and
 * then turn it upright along with the person, so the person would be the
 * right way up and the sea would be on its side. The compositor therefore
 * rotates the camera picture itself, works upright, and emits a frame with a
 * rotation of zero. That is why [outWidth] and [outHeight] are swapped
 * relative to [scaleWidth] and [scaleHeight] on a quarter turn.
 *
 * **Even numbers.** I420 has one chroma sample per two-by-two block of luma,
 * so an odd width or height has no honest representation. Every dimension
 * here is rounded down to even.
 */
data class WorkingSize(
    /** What the capturer's buffer is scaled to, in buffer space. */
    val scaleWidth: Int,
    val scaleHeight: Int,
    /** What the compositor draws at, upright. */
    val outWidth: Int,
    val outHeight: Int,
)

/**
 * @param bufferWidth  the captured buffer's width, before rotation
 * @param bufferHeight the captured buffer's height, before rotation
 * @param rotation     the frame's rotation in degrees: 0, 90, 180 or 270
 * @param maxWidth     the widest the upright picture may be
 */
fun workingSize(
    bufferWidth: Int,
    bufferHeight: Int,
    rotation: Int,
    maxWidth: Int = MAX_WORKING_WIDTH,
): WorkingSize {
    val quarterTurn = ((rotation % 360) + 360) % 360 % 180 != 0
    val uprightW = if (quarterTurn) bufferHeight else bufferWidth
    val uprightH = if (quarterTurn) bufferWidth else bufferHeight
    if (uprightW <= 0 || uprightH <= 0) return WorkingSize(2, 2, 2, 2)

    // Never scale up. A 320-wide camera on a cheap handset is composited at
    // 320 wide, not blown up to 640 so the numbers look tidier.
    val outW = even(minOf(uprightW, maxOf(2, maxWidth)))
    val outH = even(maxOf(2, Math.round(uprightH.toFloat() * outW / uprightW)))
    return if (quarterTurn) {
        WorkingSize(scaleWidth = outH, scaleHeight = outW, outWidth = outW, outHeight = outH)
    } else {
        WorkingSize(scaleWidth = outW, scaleHeight = outH, outWidth = outW, outHeight = outH)
    }
}

private fun even(value: Int): Int = maxOf(2, value and 1.inv())

/**
 * Whether the background picture is drawn mirrored.
 *
 * The local preview mirrors the front camera, because a preview that does not
 * is a preview nobody can use to straighten their collar. It mirrors the whole
 * rendered frame, background included, so a scene composited the right way
 * round arrives in the preview back to front - and the owner sees their sea
 * flip every time they tap Flip. Mirroring the background at composite time on
 * the front camera cancels that out: the preview mirrors it back and the scene
 * holds still.
 *
 * The far end sees the sea mirrored instead, which is a photograph of water
 * and no one can tell. The person is never mirrored here; what goes on the
 * wire is what the camera saw.
 */
fun mirrorBackground(frontFacing: Boolean): Boolean = frontFacing
