package dev.forgesworn.kithmoot.drawing

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo

/** User-enabled window geometry for single-app presentations. Never reads
 * roots, view text, titles, app packages, screenshots or accessibility nodes.
 * No window query happens until an active presentation asks for bounds. */
class DrawingWindowService : AccessibilityService() {
    override fun onServiceConnected() { current = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onDestroy() { if (current === this) current = null; super.onDestroy() }

    fun drawingWindows(): List<DrawingWindow> = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.mapNotNull { window ->
        val bounds = Rect()
        window.getBoundsInScreen(bounds)
        DrawingBounds(bounds.left, bounds.top, bounds.width(), bounds.height()).takeIf { it.valid }?.let {
            DrawingWindow(window.id, it, window.isActive)
        }
    }
    companion object {
        var current: DrawingWindowService? = null
            private set
    }
}
