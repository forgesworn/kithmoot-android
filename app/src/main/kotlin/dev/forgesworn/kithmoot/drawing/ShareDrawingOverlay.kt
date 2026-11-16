package dev.forgesworn.kithmoot.drawing

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import dev.forgesworn.kithmoot.protocol.ScreenAnnotation
import dev.forgesworn.kithmoot.ui.room.LiveMark
import dev.forgesworn.kithmoot.ui.room.StrokeSegments
import dev.forgesworn.kithmoot.ui.room.clearMarks

/** Local controls and ephemeral strokes over an explicitly attached app or
 * display. Remote input can only paint; it can never turn on touch capture. */
class ShareDrawingOverlay(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(WindowManager::class.java)
    private val target = DrawingTarget()
    private var shareId: String? = null
    private var send: ((ScreenAnnotation) -> Unit)? = null
    private var stop: (() -> Unit)? = null
    private var contentWidth = 0
    private var contentHeight = 0
    private var contentVisible = true
    private var drawing = false
    private var attached: DrawingBounds? = null
    private var segments: StrokeSegments? = null
    private var canvas: MarksCanvas? = null
    private var toolbar: LinearLayout? = null
    private var drawButton: Button? = null
    private var marks = emptyList<LiveMark>()
    private val tick = object : Runnable {
        override fun run() {
            if (shareId == null) return
            place()
            main.postDelayed(this, 100)
        }
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post(action)
    }
    fun start(id: String, draw: (ScreenAnnotation) -> Unit, ended: () -> Unit) = onMain {
        closeNow()
        if (!Settings.canDrawOverlays(context)) return@onMain
        active?.get()?.takeIf { it !== this }?.closeNow()
        active = java.lang.ref.WeakReference(this)
        shareId = id; send = draw; stop = ended
        try {
            canvas = MarksCanvas(context).also { it.visibility = View.GONE; manager.addView(it, canvasParams(DrawingBounds(0, 0, 1, 1))) }
            toolbar = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(Color.rgb(16, 17, 20))
                fun button(label: String, action: () -> Unit): Button = Button(context).also {
                    it.text = label; it.textSize = 11f; it.minWidth = 0; it.minimumWidth = 0
                    it.setPadding(dp(5), 0, dp(5), 0)
                    it.setOnClickListener { action() }
                    addView(it, LinearLayout.LayoutParams(0, dp(48), 1f))
                }
                button("Screen") { setDrawing(false); target.attachScreen(); place() }
                button("App") { attachApp() }
                drawButton = button("Draw") { setDrawing(!drawing) }
                button("Clear") { shareId?.let { send?.invoke(clearMarks(it)) } }
                button("Stop") { stop?.invoke() }
                manager.addView(this, WindowManager.LayoutParams(
                    minOf(dp(340), screenBounds().width), dp(48), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT,
                ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL })
            }
            toast("Open your shared app, then tap App. For an entire display, tap Screen.")
            main.post(tick)
        } catch (_: RuntimeException) {
            closeNow()
            toast("Android could not show drawing controls. Allow display over other apps for KithMoot.")
        }
    }

    fun content(width: Int, height: Int, visible: Boolean) = onMain {
        contentWidth = width; contentHeight = height; contentVisible = visible
        if (shareId != null) place()
    }
    fun paint(id: String, live: List<LiveMark>) = onMain {
        if (shareId == id) { marks = live; canvas?.invalidate() }
    }
    fun close() = onMain { closeNow() }

    private fun attachApp() {
        setDrawing(false)
        val service = DrawingWindowService.current
        if (service == null) {
            toast("Enable KithMoot shared-app drawing in Accessibility, then return to the shared app and tap App again. Only its window bounds are read.")
            runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { toast("Open Android Accessibility settings and choose KithMoot shared-app drawing.") }
            return
        }
        val window = runCatching { service.drawingWindows().firstOrNull { it.active } }.getOrNull()
        if (window == null) { toast("Open the app you selected for sharing, then tap App."); return }
        target.attachApp(window); place()
    }
    private fun setDrawing(value: Boolean) {
        segments = null // Do not emit unfinished input after hiding or revocation.
        drawing = value && attached != null
        drawButton?.text = if (drawing) "Use app" else "Draw"
        canvas?.let { view -> attached?.let { updateCanvas(view, it) } }
    }
    private fun place() {
        if (!Settings.canDrawOverlays(context)) { closeNow(); return }
        val windows = if (target.needsWindowBounds) runCatching { DrawingWindowService.current?.drawingWindows().orEmpty() }.getOrDefault(emptyList()) else emptyList()
        val bounds = target.bounds(screenBounds(), windows, contentWidth, contentHeight, contentVisible)
        val changed = bounds != attached
        attached = bounds
        if (changed) setDrawing(false)
        canvas?.let { view ->
            if (bounds == null) { view.visibility = View.GONE; return@let }
            if (changed) updateCanvas(view, bounds)
            view.visibility = View.VISIBLE; view.invalidate()
        }
        drawButton?.isEnabled = bounds != null
    }
    private fun updateCanvas(view: View, bounds: DrawingBounds) {
        // Permission revocation can race the platform update.
        runCatching { manager.updateViewLayout(view, canvasParams(bounds)) }.onFailure { closeNow() }
    }
    private fun canvasParams(bounds: DrawingBounds) = WindowManager.LayoutParams(
        bounds.width, bounds.height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (drawing) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE), PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        x = bounds.left; y = bounds.top
        // At/below Android 12's obscuring limit, so ordinary app touches pass
        // through the non-touchable canvas. Controls occupy only their own bar.
        alpha = 0.75f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setFitInsetsTypes(0)
    }
    @Suppress("DEPRECATION")
    private fun screenBounds(): DrawingBounds {
        val rect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) manager.maximumWindowMetrics.bounds else {
            val metrics = android.util.DisplayMetrics(); manager.defaultDisplay.getRealMetrics(metrics)
            android.graphics.Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        }
        return DrawingBounds(rect.left, rect.top, rect.width(), rect.height())
    }
    private fun closeNow() {
        if (active?.get() === this) active = null
        main.removeCallbacks(tick)
        shareId = null; send = null; stop = null; segments = null; drawing = false; attached = null
        marks = emptyList(); target.clear()
        canvas?.let { runCatching { manager.removeViewImmediate(it) } }; canvas = null
        toolbar?.let { runCatching { manager.removeViewImmediate(it) } }; toolbar = null; drawButton = null
    }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun toast(value: String) { Toast.makeText(context, value, Toast.LENGTH_LONG).show() }

    companion object {
        private var active: java.lang.ref.WeakReference<ShareDrawingOverlay>? = null
        fun closeActive() { active?.get()?.close() }
    }

    private inner class MarksCanvas(context: Context) : View(context) {
        private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        override fun onDraw(output: Canvas) {
            super.onDraw(output)
            for (mark in marks) {
                val points = mark.annotation.points ?: continue
                if (points.size < 2) continue
                val path = Path().apply {
                    moveTo((points[0].x * width).toFloat(), (points[0].y * height).toFloat())
                    for (point in points.drop(1)) lineTo((point.x * width).toFloat(), (point.y * height).toFloat())
                }
                pen.strokeWidth = maxOf(dp(3).toFloat(), width / 260f)
                pen.color = Color.BLACK; pen.alpha = (180 * mark.alpha).toInt(); pen.strokeWidth += dp(2)
                output.drawPath(path, pen)
                pen.strokeWidth -= dp(2); pen.color = Color.parseColor(mark.color); pen.alpha = (255 * mark.alpha).toInt()
                output.drawPath(path, pen)
            }
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val id = shareId ?: return false
            val bounds = attached ?: return false
            if (!drawing) return false
            val point = bounds.point(event.x, event.y)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> segments = StrokeSegments(id) { annotation -> if (shareId == id && drawing) send?.invoke(annotation) }.also { it.start(point, event.eventTime) }
                MotionEvent.ACTION_MOVE -> segments?.move(point, event.eventTime)
                MotionEvent.ACTION_UP -> { segments?.finish(); segments = null; performClick() }
                MotionEvent.ACTION_CANCEL -> segments = null
            }
            return true
        }
        override fun performClick(): Boolean { super.performClick(); return true }
    }
}
