package dev.forgesworn.kithmoot.drawing

import dev.forgesworn.kithmoot.protocol.AnnotationPoint
import kotlin.math.abs

/** Physical display coordinates; no UI contents or application identity. */
data class DrawingBounds(val left: Int, val top: Int, val width: Int, val height: Int) {
    val valid: Boolean get() = width > 0 && height > 0
    fun point(x: Float, y: Float): AnnotationPoint = AnnotationPoint(
        (x.toDouble() / width).coerceIn(0.0, 1.0),
        (y.toDouble() / height).coerceIn(0.0, 1.0),
    )
}

data class DrawingWindow(val id: Int, val bounds: DrawingBounds, val active: Boolean)

/** An explicit attachment never follows whichever app takes focus next. A
 * hidden, missing or resized capture fails closed until its geometry agrees. */
class DrawingTarget {
    private var windowId: Int? = null
    private var wholeScreen = false
    val needsWindowBounds: Boolean get() = windowId != null
    fun attachScreen() { wholeScreen = true; windowId = null }
    fun attachApp(window: DrawingWindow) { wholeScreen = false; windowId = window.id }
    fun clear() { wholeScreen = false; windowId = null }
    fun bounds(screen: DrawingBounds, windows: List<DrawingWindow>, contentWidth: Int, contentHeight: Int, visible: Boolean): DrawingBounds? {
        if (!visible) return null
        val target = if (wholeScreen) screen else windows.firstOrNull { it.id == windowId && it.active }?.bounds ?: return null
        if (!target.valid) return null
        if (contentWidth > 0 && contentHeight > 0) {
            // Rotation, split screen and freeform resize may deliver the
            // bounds and projection callbacks in either order.
            val mismatch = abs(target.width.toDouble() / target.height - contentWidth.toDouble() / contentHeight)
            if (mismatch > 3.0 / minOf(target.width, target.height)) return null
        }
        return target
    }
}

/** Preserve the content's aspect ratio, with even encoder dimensions. */
fun drawingCaptureSize(width: Int, height: Int): Pair<Int, Int> {
    require(width > 0 && height > 0)
    val scale = minOf(1.0, 1280.0 / maxOf(width, height))
    return ((width * scale).toInt() and -2).coerceAtLeast(2) to ((height * scale).toInt() and -2).coerceAtLeast(2)
}
