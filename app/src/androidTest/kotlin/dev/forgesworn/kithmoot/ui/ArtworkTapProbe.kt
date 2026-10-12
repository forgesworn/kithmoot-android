package dev.forgesworn.kithmoot.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.SemanticsNode
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/** Two geometry rows from the synthetic fixture's original tap; never consumes it. */
internal class ArtworkTapProbe {
    @Volatile private var target: SemanticsNode? = null
    @Volatile private var coordinates: LayoutCoordinates? = null
    private val armed = AtomicBoolean(false)
    private val events = ArrayList<JSONObject>(2)
    private var preparedBounds: Rect? = null
    private var pickerAtPreparation: Boolean? = null

    fun prepare(node: SemanticsNode, pickerExists: Boolean) {
        target = node
        preparedBounds = node.boundsInWindow
        pickerAtPreparation = pickerExists
        armed.set(true)
    }

    fun modifier(): Modifier = Modifier.onGloballyPositioned { coordinates = it }.pointerInput(this) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { change ->
                    if (!change.previousPressed && change.pressed) record("down", change.position)
                    if (change.previousPressed && !change.pressed) record("up", change.position)
                }
            }
        }
    }

    private fun record(phase: String, localPoint: Offset) {
        if (!armed.get()) return
        synchronized(events) {
            if (events.size == 2) return
            val row = runCatching {
                val root = requireNotNull(coordinates).also { check(it.isAttached) }
                val node = requireNotNull(target)
                check(node.root != null)
                val point = root.localToWindow(localPoint)
                val bounds = node.boundsInWindow
                val touchBounds = node.touchBoundsInRoot.translate(node.positionInWindow - node.positionInRoot)
                check(point.x.isFinite() && point.y.isFinite() && bounds.width > 0 && bounds.height > 0)
                JSONObject().put("phase", phase).put("available", true)
                    .put("windowPoint", JSONArray(listOf(point.x, point.y)))
                    .put("layoutBounds", bounds.json()).put("expandedTouchBounds", touchBounds.json())
                    .put("insideLayoutBounds", bounds.contains(point))
                    .put("insideExpandedTouchBounds", touchBounds.contains(point))
            }.getOrElse { JSONObject().put("phase", phase).put("available", false) }
            events.add(row)
        }
    }

    fun snapshot(): JSONObject = JSONObject()
        .put("preparedBounds", runCatching { preparedBounds?.json() }.getOrNull() ?: JSONObject.NULL)
        .put("pickerAtPreparation", pickerAtPreparation ?: JSONObject.NULL)
        .put("events", synchronized(events) { JSONArray(events.toList()) })

    private fun Rect.json(): JSONArray {
        check(listOf(left, top, right, bottom).all { it.isFinite() })
        return JSONArray(listOf(left, top, right, bottom))
    }
}
