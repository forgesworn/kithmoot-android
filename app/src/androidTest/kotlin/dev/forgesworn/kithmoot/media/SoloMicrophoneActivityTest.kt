package dev.forgesworn.kithmoot.media

import android.Manifest
import android.media.AudioManager
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.*
import dev.forgesworn.kithmoot.session.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

/** Real ADM callbacks on a disposable emulator; no phone or public relay. */
class SoloMicrophoneActivityTest {
    @Test fun microphone_has_samples_before_a_peer_joins_and_stops_on_mute_off_and_leave() = runBlocking {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val events = MutableSharedFlow<NostrEvent>(extraBufferCapacity = 256)
        val transport = object : RoomTransport {
            override fun publish(event: NostrEvent) { check(events.tryEmit(event)) }
            override fun subscribe(filters: List<Filter>): Flow<NostrEvent> = events.filter { event ->
                filters.any { it.kinds == null || event.kind in it.kinds!! }
            }
        }
        val room = deriveRoom(ByteArray(32) { 23 })
        val now = System.currentTimeMillis() / 1000
        val identity = PrimaryIdentity.create(room.roomId, now + 3600, now, ByteArray(32) { 7 }, ByteArray(32) { 8 })
        val session = RoomSession(room, identity, transport, scope)
        val engine = WebRtcEngine(context, session, scope, emptyList())
        val peerIdentity = PrimaryIdentity.create(room.roomId, now + 3600, now, ByteArray(32) { 9 }, ByteArray(32) { 10 })
        val peerSession = RoomSession(room, peerIdentity, transport, scope)
        val peerEngine = WebRtcEngine(context, peerSession, scope, emptyList())
        suspend fun samples() = withTimeout(5_000) { engine.audioActivity.first { it.microphone != null && it.microphoneWaveform.isNotEmpty() } }
        suspend fun noSamples() = withTimeout(5_000) { engine.audioActivity.first { it.microphone == null && it.microphoneWaveform.isEmpty() } }
        try {
            session.join(); session.claim(Roles.MIC); engine.start(); engine.audioRouting.setActive(true)
            assertNotNull(engine.localMedia.startMicrophone())
            assertTrue(engine.connections.value.isEmpty())
            assertNotNull("A solo call must receive actual microphone PCM", samples().microphone)
            engine.localMedia.setMicrophoneMuted(true)
            noSamples()
            delay(200)
            assertNull(engine.audioActivity.value.microphone)
            engine.localMedia.setMicrophoneMuted(false)
            samples()
            // A native sender takes over the same ADM, then releases it.
            // Neither transition may strand the solo preview or mic packets.
            peerSession.join(); peerEngine.start()
            withTimeout(15_000) { engine.connections.first { it.values.any { state -> state == "connected" } } }
            withTimeout(10_000) { peerEngine.audioActivity.first { it.microphones[identity.devicePubkey]?.waveform?.isNotEmpty() == true } }
            peerEngine.stop(); peerSession.leave()
            withTimeout(10_000) { engine.connections.first { it.isEmpty() } }
            val afterPeerLeft = engine.audioActivity.value.sample
            withTimeout(5_000) { engine.audioActivity.first { it.sample > afterPeerLeft && it.microphoneWaveform.isNotEmpty() } }
            engine.localMedia.stopMicrophone()
            noSamples()
            engine.localMedia.startMicrophone()
            samples()
            engine.stop()
            noSamples()
            assertTrue(engine.localMedia.tracks.value.isEmpty())
            assertEquals(AudioManager.MODE_NORMAL, context.getSystemService(AudioManager::class.java).mode)
            assertTrue("Leaving must release the microphone recorder",
                context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.isEmpty())
        } finally {
            engine.stop(); peerEngine.stop(); session.leave(); peerSession.leave(); scope.cancel(); engine.dispose(); peerEngine.dispose()
        }
    }
}
