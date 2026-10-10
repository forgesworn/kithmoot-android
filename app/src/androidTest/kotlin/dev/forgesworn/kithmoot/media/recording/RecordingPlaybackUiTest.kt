package dev.forgesworn.kithmoot.media.recording

import android.os.SystemClock
import android.graphics.Color
import androidx.activity.compose.setContent
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.session.*
import dev.forgesworn.kithmoot.storage.RecoveryUi
import dev.forgesworn.kithmoot.ui.room.RecordingAttachmentViewer
import dev.forgesworn.kithmoot.ui.theme.KithMootTheme
import okhttp3.*
import okio.BufferedSource
import okio.buffer
import okio.source
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Actual recipient viewer controls and native AAC playback, with a synthetic
 * encrypted response. This does not claim real-room delivery or physical QA. */
class RecordingPlaybackUiTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    private val ui = RecoveryUi(useSwipeFallback = false)

    @Test fun show_fetches_once_play_is_explicit_and_background_return_stays_paused() = exercisePlayback(null)
    @Test fun browser_webm_audio_plays_with_codec_parameters() = exercisePlayback("synthetic-browser-audio.webm")
    @Test fun browser_webm_video_plays_without_container_duration() = exercisePlayback("synthetic-browser-video.webm")
    @Test fun opus_ogg_audio_plays() = exercisePlayback("synthetic-browser-audio.ogg")

    private fun hasClock(seconds: Int): Boolean {
        fun matches(node: AccessibilityNodeInfo): Boolean {
            if (node.isVisibleToUser && node.text?.toString()?.let {
                it == "0:%02d".format(seconds) || it.startsWith("0:%02d / ".format(seconds))
            } == true) return true
            return (0 until node.childCount).mapNotNull(node::getChild).any(::matches)
        }
        return InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow?.let(::matches) == true
    }

    private fun exercisePlayback(browserFixture: String?) {
        Assume.assumeTrue("Disposable emulator only", android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val app = ApplicationProvider.getApplicationContext<KithMootApplication>()
        val directory = File(app.cacheDir, "synthetic-playback-${System.nanoTime()}").apply { check(mkdirs()) }
        val contacted = AtomicInteger()
        try {
            val envelope: File
            val attachment: ChatAttachment
            if (browserFixture == null) {
                val audio = File(directory, "synthetic-received.m4a")
                AacRecordingFile(audio).use { writer -> repeat(1000) { writer.write(ShortArray(480)) } }
                val sealed = sealFile(audio, File(directory, "audio.enc"), audio.name, "audio/mp4")
                envelope = sealed.file
                attachment = ChatAttachment("https://synthetic.example/${sealed.hash}", sealed.hash, sealed.key,
                    sealed.name, sealed.type, envelope.length())
            } else {
                val assets = InstrumentationRegistry.getInstrumentation().context.assets
                val metadata = assets.open("recording/$browserFixture.json").bufferedReader().use { JSONObject(it.readText()) }
                check(metadata.getBoolean("synthetic"))
                envelope = File(directory, "browser.enc")
                assets.open("recording/$browserFixture.enc").use { input -> envelope.outputStream().use(input::copyTo) }
                val wire = ChatAttachment(metadata.getString("url"), metadata.getString("sha256"),
                    metadata.getString("key"), metadata.getString("name"), metadata.getString("type"), metadata.getLong("size"))
                attachment = checkNotNull(parseAttachment(wire.toJson()))
                assertEquals("The received chat message retains codec parameters", wire.type, attachment.type)
            }
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                contacted.incrementAndGet()
                assertNull(chain.request().header("Authorization"))
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(object : ResponseBody() {
                        private val input = envelope.source().buffer()
                        override fun source(): BufferedSource = input
                        override fun contentLength() = envelope.length()
                        override fun contentType(): MediaType? = null
                    }).build()
            }.build()
            activity.scenario.onActivity { owner ->
                owner.setContent {
                    KithMootTheme {
                        var showing by remember { mutableStateOf(false) }
                        if (showing) RecordingAttachmentViewer(attachment, { showing = false }, client)
                        else Button(onClick = { showing = true }) { Text("Show synthetic recording") }
                    }
                }
            }
            ui.await("recipient Show control") { ui.hasText("Show synthetic recording") }
            assertEquals(0, contacted.get())
            ui.click("Show synthetic recording")
            ui.await("authenticated recording ready") { ui.hasText("Play recording") && hasClock(0) }
            assertEquals(1, contacted.get())
            assertTrue(hasClock(0))
            SystemClock.sleep(600)
            assertTrue("Showing a file does not start playback", ui.hasText("Play recording"))
            assertTrue(hasClock(0))
            ui.click("Play recording")
            ui.await("native decoder advances while Pause remains available") {
                hasClock(1) && ui.hasText("Pause recording")
            }
            if (browserFixture == "synthetic-browser-video.webm") {
                ui.await("decoded browser canvas is visible") {
                    val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                    try {
                        var coloured = 0
                        for (y in 0 until screenshot.height step 4) for (x in 0 until screenshot.width step 4) {
                            val pixel = screenshot.getPixel(x, y)
                            if (Color.blue(pixel) < 80 && ((Color.red(pixel) > 180 && Color.green(pixel) < 80) ||
                                (Color.green(pixel) > 180 && Color.red(pixel) < 80))) coloured++
                        }
                        coloured > 1000
                    } finally { screenshot.recycle() }
                }
            }
            activity.scenario.moveToState(Lifecycle.State.CREATED)
            SystemClock.sleep(500)
            activity.scenario.moveToState(Lifecycle.State.RESUMED)
            ui.await("return keeps playback paused") { ui.hasText("Play recording") }
            assertEquals(1, contacted.get())
            ui.click("Play recording")
            ui.await("explicit playback resumes") { ui.hasText("Pause recording") }
            ui.click("Close recording")
            ui.await("viewer closed") { ui.hasText("Show synthetic recording") }
            ui.await("private plaintext removed") { app.recordingPlaybackCache.listFiles()?.isEmpty() == true }
            assertEquals(1, contacted.get())
        } finally {
            activity.scenario.onActivity { it.setContent { Text("Synthetic playback qualification complete") } }
            directory.deleteRecursively()
        }
    }
}
