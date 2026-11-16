package dev.forgesworn.kithmoot.drawing

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.media.LocalMedia
import dev.forgesworn.kithmoot.protocol.AnnotationPoint
import dev.forgesworn.kithmoot.protocol.ScreenAnnotation
import dev.forgesworn.kithmoot.service.ScreenShareService
import dev.forgesworn.kithmoot.ui.room.LiveMark
import dev.forgesworn.kithmoot.ui.room.MarkAuthor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ShareDrawingOverlayTest {
    @Test fun whole_screen_drawings_paint_send_and_release_touches() = presentation(false)
    @Test fun one_app_drawings_paint_send_and_release_touches() = presentation(true)

    private fun presentation(oneApp: Boolean) = runBlocking {
        assumeTrue(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        fun shell(command: String) { automation.executeShellCommand(command).use { java.io.FileInputStream(it.fileDescriptor).readBytes() } }
        // Disposable emulator only. Product code never changes these settings.
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        shell("settings put secure enabled_accessibility_services ${context.packageName}/${DrawingWindowService::class.java.name}")
        shell("settings put secure accessibility_enabled 1")
        automation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        fun until(why: String, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 15_000
            while (SystemClock.uptimeMillis() < deadline && !condition()) SystemClock.sleep(100)
            assertTrue(why, condition())
        }
        fun node(label: String): AccessibilityNodeInfo? = (automation.windows.mapNotNull { it.root } + listOfNotNull(automation.rootInActiveWindow)).asSequence().flatMap { it.findAccessibilityNodeInfosByText(label).asSequence() }.firstOrNull { it.text?.toString() == label }
        fun tap(x: Float, y: Float) {
            val now = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, x, y, 0).also { automation.injectInputEvent(it, true); it.recycle() }
            }
        }
        fun click(label: String): Boolean {
            var found = node(label) ?: return false
            repeat(5) { if (found.isClickable) return found.performAction(AccessibilityNodeInfo.ACTION_CLICK); found = found.parent ?: return false }
            return false
        }
        val target = Intent().setComponent(ComponentName(instrumentation.context.packageName, DrawingTargetActivity::class.java.name)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(target)
        SystemClock.sleep(500)
        var consent: Intent? = null
        val returned = CountDownLatch(1)
        val overlay = ShareDrawingOverlay(context)
        val commands = CopyOnWriteArrayList<ScreenAnnotation>()
        var factory: PeerConnectionFactory? = null
        var egl: EglBase? = null
        var media: LocalMedia? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                until("window geometry service connects") { DrawingWindowService.current != null }
                scenario.onActivity { activity ->
                    val launcher = activity.activityResultRegistry.register("drawing-projection", ActivityResultContracts.StartActivityForResult()) { result ->
                        if (result.resultCode == Activity.RESULT_OK) consent = result.data
                        returned.countDown()
                    }
                    val manager = context.getSystemService(MediaProjectionManager::class.java)
                    launcher.launch(if (oneApp) manager.createScreenCaptureIntent() else manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()))
                }
                val deadline = SystemClock.uptimeMillis() + 20_000
                while (returned.count > 0 && SystemClock.uptimeMillis() < deadline) {
                    if (!oneApp && node("A single app") != null) { click("A single app"); click("Entire screen") }
                    for (label in listOf("Start now", "Share screen", "Start recording", "Start", "Drawing test target", "Share this app")) click(label)
                    SystemClock.sleep(200)
                }
                assertTrue("projection chooser returned for oneApp=$oneApp", returned.await(1, TimeUnit.SECONDS))
                assertNotNull("projection consent", consent)
                instrumentation.runOnMainSync { ScreenShareService.start(context) }
                withTimeout(5_000) { ScreenShareService.running.first { it } }
                PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
                factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
                egl = EglBase.create()
                val local = LocalMedia(context, factory!!, egl!!)
                media = local
                local.onScreenContent = { w, h, visible -> overlay.content(w, h, visible) }
                val track = local.startScreenShare(consent!!, shareAudio = false)!!
                context.startActivity(target)
                overlay.start(track.id(), commands::add) { local.stopScreenShare() }
                until("toolbar appears") { click(if (oneApp) "App" else "Screen") }
                until("target button is accessible") { node("Target clicks: 0") != null }
                val button = Rect(); node("Target clicks: 0")!!.getBoundsInScreen(button)
                tap(button.exactCenterX(), button.exactCenterY())
                until("ordinary touch passes through the canvas") { node("Target clicks: 1") != null }
                val window = DrawingWindowService.current!!.drawingWindows().first { it.active }.bounds
                overlay.paint(track.id(), listOf(LiveMark(ScreenAnnotation("stroke", track.id(), "remote-test", listOf(AnnotationPoint(.2,.3), AnnotationPoint(.8,.7))), 1f, MarkAuthor("test", "Test"), "#ff6b9d")))
                SystemClock.sleep(400)
                val bitmap = automation.takeScreenshot()!!
                val coloured = (window.top until window.top + window.height step 3).sumOf { y ->
                    (window.left until window.left + window.width step 3).count { x ->
                        if (x !in 0 until bitmap.width || y !in 0 until bitmap.height) false else {
                            val colour = bitmap.getPixel(x, y)
                            Color.red(colour) > 180 && Color.red(colour) - Color.green(colour) > 40 && Color.blue(colour) > 100
                        }
                    }
                }
                bitmap.recycle()
                assertTrue("received mark paints over native target", coloured > 30)
                assertTrue("Draw control", click("Draw"))
                until("drawing mode enabled") { node("Use app") != null }
                SystemClock.sleep(300)
                val from = SystemClock.uptimeMillis()
                for (step in 0..8) {
                    val x = window.left + window.width * (.2f + step * .06f)
                    val y = window.top + window.height * (.4f + step * .025f)
                    MotionEvent.obtain(from, SystemClock.uptimeMillis(), if (step == 0) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_MOVE, x, y, 0).also { automation.injectInputEvent(it, true); it.recycle() }
                    SystemClock.sleep(30)
                }
                MotionEvent.obtain(from, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, window.left + window.width * .68f, window.top + window.height * .6f, 0).also { automation.injectInputEvent(it, true); it.recycle() }
                until("native stroke sends normalised points") { commands.any { it.op == "stroke" && it.points!!.size > 1 } }
                assertTrue(commands.filter { it.op == "stroke" }.all { it.shareId == track.id() && it.points!!.all { point -> point.x in 0.0..1.0 && point.y in 0.0..1.0 } })
                overlay.close()
                SystemClock.sleep(200)
                tap(button.exactCenterX(), button.exactCenterY())
                until("closing overlay releases input") { node("Target clicks: 2") != null }
            }
        } finally {
            overlay.close(); media?.releaseAll(); factory?.dispose(); egl?.release(); ScreenShareService.stop(context)
            context.startActivity(target.putExtra("stop", true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            shell("settings delete secure enabled_accessibility_services"); shell("settings put secure accessibility_enabled 0")
        }
    }
}
