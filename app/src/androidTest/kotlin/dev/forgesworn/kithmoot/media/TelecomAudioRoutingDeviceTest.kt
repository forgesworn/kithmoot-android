package dev.forgesworn.kithmoot.media

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.telecom.CallTelecom
import dev.forgesworn.kithmoot.telecom.TelecomSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** A local self-managed Telecom call: no room, account, peer or capture. */
class TelecomAudioRoutingDeviceTest {
    @Test fun loudspeakerMovesRealTelecomCallFromEarpieceToSpeaker() = exerciseRouting(requireEarpiece = true)

    @Test fun loudspeakerWorksThroughTheRealTelecomHandoff() = exerciseRouting(requireEarpiece = false)

    private fun exerciseRouting(requireEarpiece: Boolean) {
        assumeTrue("Disposable emulator only", android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val audio = context.getSystemService(AudioManager::class.java)
        check(audio.mode == AudioManager.MODE_NORMAL && CallTelecom.audio.value == null) {
            "Another call owns audio; leave it undisturbed"
        }
        val settings = TelecomSettings(context)
        val previousTelecom = settings.enabled()
        val routing = CallAudioRouting(context)
        val previousSpeaker = routing.loudspeakerOn.value
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        fun await(description: String, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(25)
            assertTrue("$description; mode=${audio.mode}, device=${audio.communicationDevice?.type}, button=${routing.loudspeakerOn.value}", condition())
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            try {
                instrumentation.runOnMainSync {
                    settings.setEnabled(true)
                    routing.setLoudspeakerOn(false)
                    routing.setActive(true)
                    scope.launch { CallTelecom.audio.collect { routing.handToTelecom(it) } }
                    CallTelecom.callJoined(context, "Local speaker test")
                }
                await("The system must create the self-managed call") { CallTelecom.audio.value != null }
                val handOff = checkNotNull(CallTelecom.audio.value)
                assertTrue("Only the live active call permits a matching device request", handOff.permitsDeviceRequest())
                val types = audio.availableCommunicationDevices.map { it.type }
                Log.i("KithMootRouteTest", "Telecom devices=$types")
                if (requireEarpiece) assumeTrue("An earpiece is required to reproduce the reported transition: $types",
                    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE in types)
                val offDevice = if (AudioDeviceInfo.TYPE_BUILTIN_EARPIECE in types) AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                    else AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                await("Loudspeaker off must use the earpiece or speaker fallback") {
                    audio.communicationDevice?.type == offDevice && routing.loudspeakerOn.value == (offDevice == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
                }
                instrumentation.runOnMainSync { routing.setLoudspeakerOn(true) }
                await("An explicit press must move Telecom audio to speaker") {
                    audio.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER && routing.loudspeakerOn.value
                }
                instrumentation.runOnMainSync { routing.setLoudspeakerOn(false) }
                await("Switching back must restore the earpiece or speaker fallback") {
                    audio.communicationDevice?.type == offDevice && routing.loudspeakerOn.value == (offDevice == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
                }
                instrumentation.runOnMainSync { CallTelecom.callLeft() }
                assertFalse("A stale hand-off cannot change a later call's device", handOff.permitsDeviceRequest())
            } finally {
                instrumentation.runOnMainSync {
                    scope.cancel()
                    routing.setLoudspeakerOn(previousSpeaker)
                    routing.close()
                    CallTelecom.callLeft()
                    routing.handToTelecom(null)
                    settings.setEnabled(previousTelecom)
                }
                await("Leaving must release communication mode") { audio.mode == AudioManager.MODE_NORMAL }
            }
        }
    }
}
