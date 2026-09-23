package dev.forgesworn.kithmoot.ui.room

import dev.forgesworn.kithmoot.protocol.AnnotationPoint
import dev.forgesworn.kithmoot.protocol.ScreenAnnotation
import dev.forgesworn.kithmoot.protocol.MAX_ANNOTATION_POINTS
import java.util.UUID
import kotlin.math.hypot

/** The smallest movement, as a share of the image, that adds a point. */
private const val MIN_STEP = 0.002

/** Each bounded segment has its own wire id; its last point joins the next.
 *  Mirrors the web share viewer: a point is kept only once the finger has
 *  moved a little, and a segment goes out every 50 ms or at the point cap,
 *  which keeps a long stroke inside every receiver's annotation budget. */
class StrokeSegments(private val shareId: String, private val send: (ScreenAnnotation) -> Unit) {
    private val points = mutableListOf<AnnotationPoint>()
    private var sentAt = 0L
    fun start(point: AnnotationPoint, at: Long) { points.clear(); points.add(point); sentAt = at }
    fun move(point: AnnotationPoint, at: Long) {
        val last = points.lastOrNull()
        if (last == null || (points.size < MAX_ANNOTATION_POINTS && hypot(point.x - last.x, point.y - last.y) > MIN_STEP)) points.add(point)
        if (at - sentAt >= 50 || points.size >= MAX_ANNOTATION_POINTS) { flush(); sentAt = at }
    }
    fun finish() { flush(); points.clear() }
    private fun flush() {
        if (points.size < 2) return
        send(ScreenAnnotation("stroke", shareId, UUID.randomUUID().toString(), points.toList()))
        val last = points.last(); points.clear(); points.add(last)
    }
}

/** Wipe every mark on a share, for everybody watching it. */
fun clearMarks(shareId: String) = ScreenAnnotation("clear", shareId, "")
