package dev.forgesworn.kithmoot.media

import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.ui.RoomViewModel
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.webrtc.PeerConnectionFactory
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import java.io.File
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicLong

/** Opt-in diagnostics for an authorised physical phone. No PCM, picture,
 * storage, room link, identity or credentials are exported. Completing this
 * collector does not establish call or echo acceptance. */
class PhysicalCallProbeTest {
    @Test fun speakerRouteRecoversAfterPlatformSelectsEarpiece() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Explicit physical probe only", InstrumentationRegistry.getArguments().getString("physicalCallProbe") == "true")
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".callprobe") && Process.myUid() / 100_000 != 0)
        val audio = context.getSystemService(AudioManager::class.java)
        check(audio.mode == AudioManager.MODE_NORMAL) { "Another call owns audio; leave it undisturbed" }
        val earpiece = audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
        assumeTrue("Earpiece required for this route regression", earpiece != null)
        ActivityScenario.launch(MainActivity::class.java).use {
            val routing = CallAudioRouting(context)
            fun awaitSpeaker() {
                val end = SystemClock.elapsedRealtime() + 5_000
                while (audio.communicationDevice?.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER && SystemClock.elapsedRealtime() < end) SystemClock.sleep(25)
                assertTrue("The call must use the phone speaker", audio.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
            }
            try {
                instrumentation.runOnMainSync { routing.setActive(true) }
                awaitSpeaker()
                assertTrue("Select the platform fallback to reproduce the regression", audio.setCommunicationDevice(earpiece!!))
                awaitSpeaker()
            } finally { instrumentation.runOnMainSync { routing.close() } }
        }
    }

    @Test fun collectAuthorisedPhysicalCallCounters() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit physical probe only", args.getString("physicalCallProbe") == "true")
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".callprobe")) { "Use the separate probe application" }
        check(Process.myUid() / 100_000 != 0) { "Use a disposable secondary user" }
        check(android.os.Build.HARDWARE !in setOf("ranchu", "goldfish")) { "Physical phone required" }
        val seconds = (args.getString("probeSeconds")?.toLongOrNull() ?: 600).coerceIn(30, 900)
        val stop = File(context.filesDir, "call-probe-stop").also { it.delete() }
        val engineField = RoomViewModel::class.java.getDeclaredField("engine").apply { isAccessible = true }
        val factoryField = WebRtcEngine::class.java.getDeclaredField("factory").apply { isAccessible = true }
        val frames = IdentityHashMap<VideoTrack, AtomicLong>()
        val sinks = IdentityHashMap<VideoTrack, VideoSink>()
        var sawEngine = false
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            lateinit var model: RoomViewModel
            activity.onActivity { model = ViewModelProvider(it)[RoomViewModel::class.java] }
            val began = SystemClock.elapsedRealtime()
            try {
                while (!stop.exists() && SystemClock.elapsedRealtime() - began < seconds * 1_000) {
                    val engine = engineField.get(model) as? WebRtcEngine
                    val row = JSONObject().put("elapsedMs", SystemClock.elapsedRealtime() - began)
                    if (engine != null) {
                        sawEngine = true
                        row.put("states", engine.connections.value.values.sorted().joinToString(","))
                        row.put("microphoneOn", engine.localMedia.microphoneTrack != null)
                        val local = engine.localMedia.localCameraTrack
                        val remote = engine.remoteTracks.value.mapNotNull { it.track as? VideoTrack }
                        val wanted = listOfNotNull(local) + remote
                        for (track in sinks.keys.toList().filter { it !in wanted }) {
                            runCatching { track.removeSink(sinks.getValue(track)) }
                            sinks.remove(track); frames.remove(track)
                        }
                        for (track in wanted) if (!sinks.containsKey(track)) {
                            val count = AtomicLong()
                            val sink = VideoSink { count.incrementAndGet() }
                            if (runCatching { track.addSink(sink) }.isSuccess) {
                                frames[track] = count; sinks[track] = sink
                            }
                        }
                        row.put("localCameraFrames", local?.let { frames[it]?.get() } ?: 0)
                        row.put("remoteVideoFrames", remote.sumOf { frames[it]?.get() ?: 0 })
                        runCatching {
                            val state = (factoryField.get(engine) as PeerConnectionFactory).audioProcessingState
                            row.put("hasAudioProcessingModule", state.hasAudioProcessingModule)
                            row.put("echoImplementation", state.echoCancellation.effective.name)
                            row.put("softwareEchoActive", state.echoCancellation.isSoftwareActive)
                            row.put("platformEchoActive", state.echoCancellation.isPlatformActive)
                        }.onFailure { row.put("processingStateUnavailable", it.javaClass.simpleName) }
                    }
                    val audio = context.getSystemService(AudioManager::class.java)
                    row.put("audioMode", audio.mode)
                    row.put("communicationDeviceType", audio.communicationDevice?.type ?: -1)
                    instrumentation.sendStatus(0, Bundle().apply { putString("stream", "CALL_PROBE $row\n") })
                    SystemClock.sleep(1_000)
                }
            } finally {
                for ((track, sink) in sinks) runCatching { track.removeSink(sink) }
                stop.delete()
            }
        }
        assertTrue("The probe did not observe a call engine", sawEngine)
    }
}
