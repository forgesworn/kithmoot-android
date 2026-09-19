package dev.forgesworn.kithmoot.ui.room

import dev.forgesworn.kithmoot.protocol.AnnotationPoint
import dev.forgesworn.kithmoot.protocol.ScreenAnnotation
import java.util.UUID

/** Each bounded segment has its own wire id; its last point joins the next. */
class StrokeSegments(private val shareId: String, private val send: (ScreenAnnotation) -> Unit) {
    private val points = mutableListOf<AnnotationPoint>()
    private var sentAt = 0L
    fun start(point: AnnotationPoint, at: Long) { points.clear(); points.add(point); sentAt = at }
    fun move(point: AnnotationPoint, at: Long) {
        if (points.lastOrNull() != point) points.add(point)
        if (at - sentAt >= 50 || points.size >= 128) { flush(); sentAt = at }
    }
    fun finish() { flush(); points.clear() }
    private fun flush() {
        if (points.size < 2) return
        send(ScreenAnnotation("stroke", shareId, UUID.randomUUID().toString(), points.toList()))
        val last = points.last(); points.clear(); points.add(last)
    }
}
