package dev.forgesworn.kithmoot.media.recording

import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.session.ChatAttachment
import dev.forgesworn.kithmoot.session.OpenedFile
import dev.forgesworn.kithmoot.session.openFileAttachment
import dev.forgesworn.kithmoot.session.sealFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class IsolatedRecordingPlayerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun manifest_keeps_the_player_non_exported_in_a_dedicated_process() {
        val services = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SERVICES).services.orEmpty()
        val service = services.single { it.name == IsolatedRecordingPlayerService::class.java.name }
        assertFalse(service.exported)
        assertTrue(service.flags and ServiceInfo.FLAG_ISOLATED_PROCESS == 0)
        assertEquals("dev.forgesworn.kithmoot.recording.playback", service.processName)
    }

    @Test fun generated_m4a_prepares_without_autoplay_then_plays_pauses_and_closes() = runBlocking<Unit> {
        assumeDisposableEmulator()
        val directory = temporaryDirectory("playback")
        var player: IsolatedRecordingPlayer? = null
        try {
            val audio = File(directory, "valid.m4a")
            writeRecording(audio)
            val latest = AtomicReference(RecordingPlaybackState())
            val states = CopyOnWriteArrayList<RecordingPlaybackState>()
            val prepared = CountDownLatch(1)
            val playing = CountDownLatch(1)
            val paused = CountDownLatch(1)
            val sawPlaying = AtomicBoolean()
            val playingCallbacks = AtomicInteger()
            val errors = AtomicInteger()
            val offMain = AtomicBoolean()
            val created = IsolatedRecordingPlayer(context, { state ->
                if (Looper.myLooper() != Looper.getMainLooper()) offMain.set(true)
                latest.set(state)
                states += state
                if (state.prepared) prepared.countDown()
                if (state.playing) {
                    sawPlaying.set(true)
                    playingCallbacks.incrementAndGet()
                    playing.countDown()
                } else if (sawPlaying.get() && !state.completed) {
                    paused.countDown()
                }
            }, {
                if (Looper.myLooper() != Looper.getMainLooper()) offMain.set(true)
                errors.incrementAndGet()
            })
            player = created

            created.open(OpenedFile(audio, audio.name, "audio/mp4", audio.length()))
            assertTrue("Recording was not prepared", prepared.await(PREPARE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertTrue(latest.get().prepared)
            assertFalse(latest.get().playing)
            assertTrue("Generated recording has no duration", latest.get().durationMs > 0)
            assertEquals(0, errors.get())

            SystemClock.sleep(NO_AUTOPLAY_MS)
            assertFalse("Opening the recording started playback", latest.get().playing)
            assertEquals("Playback started without an explicit play command", 0, playingCallbacks.get())

            created.play()
            assertTrue("Explicit play did not start playback", playing.await(STATE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            awaitUntil("Playback position did not advance") { latest.get().positionMs > 0 }
            assertTrue(latest.get().playing)
            assertEquals(0, errors.get())

            created.pause()
            assertTrue("Playback did not pause", paused.await(STATE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertFalse(latest.get().playing)
            val pausedPosition = latest.get().positionMs
            SystemClock.sleep(PAUSED_QUIET_MS)
            assertFalse(latest.get().playing)
            assertEquals("Position advanced while paused", pausedPosition, latest.get().positionMs)
            assertEquals(0, errors.get())
            assertFalse(offMain.get())

            created.close()
            val callbackCount = states.size
            SystemClock.sleep(CLOSED_QUIET_MS)
            assertEquals("Closed player emitted another state", callbackCount, states.size)
            assertEquals(0, errors.get())
            assertAppAlive()
        } finally {
            player?.close()
            directory.deleteRecursively()
        }
    }

    @Test fun authenticated_corrupt_and_truncated_plaintext_report_error_without_fallback() = runBlocking<Unit> {
        assumeDisposableEmulator()
        val directory = temporaryDirectory("rejected")
        try {
            val valid = File(directory, "source.m4a")
            writeRecording(valid)
            val truncated = ByteArray(TRUNCATED_BYTES)
            assertEquals(TRUNCATED_BYTES, valid.inputStream().use { it.read(truncated) })
            val corrupt = ByteArray(TRUNCATED_BYTES) { index -> (index * 31 + 7).toByte() }
            try {
                assertRejectedAfterAuthentication(directory, "corrupt", corrupt)
                assertAppAlive()
                assertRejectedAfterAuthentication(directory, "truncated", truncated)
                assertAppAlive()
            } finally {
                corrupt.fill(0)
                truncated.fill(0)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private suspend fun assertRejectedAfterAuthentication(directory: File, label: String, plaintext: ByteArray) {
        val source = File(directory, "$label-source.m4a")
        source.outputStream().use { it.write(plaintext) }
        val envelope = File(directory, "$label.enc")
        val sealed = sealFile(source, envelope, source.name, "audio/mp4")
        val attachment = ChatAttachment("https://unused.invalid/${sealed.hash}", sealed.hash, sealed.key,
            sealed.name, sealed.type, envelope.length())
        val opened = openFileAttachment(envelope, File(directory, "$label-opened.m4a"), attachment)
        assertEquals(plaintext.size.toLong(), opened.size)

        val preparedCallbacks = AtomicInteger()
        val playingCallbacks = AtomicInteger()
        val errors = AtomicInteger()
        val error = CountDownLatch(1)
        val offMain = AtomicBoolean()
        var player: IsolatedRecordingPlayer? = null
        try {
            val created = IsolatedRecordingPlayer(context, { state ->
                if (Looper.myLooper() != Looper.getMainLooper()) offMain.set(true)
                if (state.prepared) preparedCallbacks.incrementAndGet()
                if (state.playing) playingCallbacks.incrementAndGet()
            }, {
                if (Looper.myLooper() != Looper.getMainLooper()) offMain.set(true)
                errors.incrementAndGet()
                error.countDown()
            })
            player = created
            created.open(opened)
            created.play()
            assertTrue("$label plaintext did not report a controlled error",
                error.await(ERROR_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            SystemClock.sleep(CLOSED_QUIET_MS)
            assertEquals("$label plaintext reported more than one error", 1, errors.get())
            assertFalse("$label callbacks left the main thread", offMain.get())
        } finally {
            player?.close()
        }
    }

    private fun writeRecording(file: File) {
        val samples = ShortArray(480)
        AacRecordingFile(file).use { writer -> repeat(8_000) { writer.write(samples) } }
        assertTrue("Generated recording is too small", file.length() > TRUNCATED_BYTES)
    }

    private fun temporaryDirectory(label: String) =
        File(context.cacheDir, "isolated-player-$label-${System.nanoTime()}").apply { check(mkdirs()) }

    private fun assumeDisposableEmulator() {
        Assume.assumeTrue("Disposable emulator only", Build.HARDWARE in setOf("ranchu", "goldfish"))
    }

    private fun awaitUntil(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(STATE_TIMEOUT_SECONDS)
        while (!condition() && System.nanoTime() < deadline) SystemClock.sleep(POLL_MS)
        assertTrue(description, condition())
    }

    private fun assertAppAlive() {
        val mainResponded = AtomicBoolean()
        instrumentation.runOnMainSync { mainResponded.set(true) }
        assertTrue("Application main thread stopped responding", mainResponded.get())
        assertTrue(context.packageManager.getApplicationInfo(context.packageName, 0).enabled)
    }

    private companion object {
        const val PREPARE_TIMEOUT_SECONDS = 15L
        const val STATE_TIMEOUT_SECONDS = 10L
        const val ERROR_TIMEOUT_SECONDS = 40L
        const val NO_AUTOPLAY_MS = 750L
        const val PAUSED_QUIET_MS = 500L
        const val CLOSED_QUIET_MS = 350L
        const val POLL_MS = 50L
        const val TRUNCATED_BYTES = 128
    }
}
