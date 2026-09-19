package dev.forgesworn.kithmoot.media

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.service.ScreenShareService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.sin

/** Separate instrumentation-package UID: captured media, never a person's app. */
class PlaybackToneActivity : Activity() {
    private var tone: AudioTrack? = null
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(TextView(this).apply { text = "Synthetic playback capture test" })
        val samples = ShortArray(48_000) { (sin(it * 2 * Math.PI * 440 / 48_000) * 8_000).toInt().toShort() }
        tone = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(48_000).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(samples.size * 2).build().also {
                it.write(samples, 0, samples.size); it.setLoopPoints(0, samples.size, -1); it.play()
            }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); if (intent.getBooleanExtra("stop", false)) finish() }
    override fun onDestroy() { tone?.stop(); tone?.release(); super.onDestroy() }
}

class PlaybackCaptureTest {
    @Test fun playback_enters_the_audio_buffer_and_stops_with_screen_capture() = runBlocking {
        // The automatic consent click is confined to a disposable emulator.
        assumeTrue(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        automation.grantRuntimePermission(context.packageName, android.Manifest.permission.RECORD_AUDIO)
        automation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        var permission: Intent? = null
        val returned = CountDownLatch(1)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val launcher = activity.activityResultRegistry.register("synthetic-projection", ActivityResultContracts.StartActivityForResult()) { result ->
                    if (result.resultCode == Activity.RESULT_OK) permission = result.data
                    returned.countDown()
                }
                val manager = context.getSystemService(MediaProjectionManager::class.java)
                launcher.launch(if (android.os.Build.VERSION.SDK_INT >= 34) manager.createScreenCaptureIntent(
                    android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()) else manager.createScreenCaptureIntent())
            }
            val deadline = SystemClock.uptimeMillis() + 15_000
            while (returned.count > 0 && SystemClock.uptimeMillis() < deadline) {
                val root = automation.rootInActiveWindow
                for (label in listOf("Entire screen", "Start now", "Start recording", "Share screen", "Start")) {
                    root?.findAccessibilityNodeInfosByText(label)?.firstOrNull { it.isClickable }
                        ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                SystemClock.sleep(200)
            }
            assertTrue("Screen capture consent did not complete", returned.await(1, TimeUnit.SECONDS))
            assertNotNull(permission)
            instrumentation.runOnMainSync { ScreenShareService.start(context) }
            withTimeout(5_000) { ScreenShareService.running.first { it } }
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
            val playback = PlaybackAudio()
            val callbackPeak = java.util.concurrent.atomic.AtomicInteger(0)
            val module = JavaAudioDeviceModule.builder(context).setSampleRate(48_000)
                .setAudioBufferCallback { buffer, format, channels, rate, bytes, time ->
                    playback.fill(buffer, format, channels, rate, bytes)
                    val pcm = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                    callbackPeak.set((0 until bytes / 2).maxOf { kotlin.math.abs(pcm.getShort(it * 2).toInt()) })
                    time
                }
                .createAudioDeviceModule()
            val factory = PeerConnectionFactory.builder().setAudioDeviceModule(module).createPeerConnectionFactory()
            val egl = EglBase.create()
            val media = LocalMedia(context, factory, egl, playback)
            try {
                media.startScreenShare(permission!!)
                assertTrue(playback.active)
                assertFalse(playback.microphone)
                val tone = Intent().setComponent(ComponentName(instrumentation.context.packageName, PlaybackToneActivity::class.java.name))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(tone)
                val buffer = ByteBuffer.allocateDirect(960).order(ByteOrder.LITTLE_ENDIAN)
                module.requestStartRecording()
                withTimeout(10_000) { while (callbackPeak.get() < 100) delay(10) }
                assertTrue("Another app's sound must enter WebRTC's input callback", callbackPeak.get() > 100)
                assertEquals(listOf("screen-audio", "screen"), media.tracks.value.map { it.role })
                media.stopScreenShare()
                assertFalse(playback.active)
                assertTrue(media.tracks.value.isEmpty())
                playback.fill(buffer, AudioFormat.ENCODING_PCM_16BIT, 1, 48_000, 960)
                assertTrue((0 until 960).all { buffer.get(it) == 0.toByte() })
            } finally {
                module.requestStopRecording(); media.releaseAll(); factory.dispose(); module.release(); egl.release(); ScreenShareService.stop(context)
                context.startActivity(Intent().setComponent(ComponentName(instrumentation.context.packageName, PlaybackToneActivity::class.java.name))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("stop", true))
            }
        }
    }
}
