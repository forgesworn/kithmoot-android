package dev.forgesworn.kithmoot.media

import android.app.Instrumentation
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CallAudioRoutingDeviceTest {
    @Test fun speakerFollowsCommunicationModeLifecycle() {
        assumeTrue("Disposable emulator only", android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        exerciseSpeakerRouteRecovery(instrumentation.targetContext, instrumentation, requireEarpiece = false)
    }
}

/** Actual AudioManager events, shared with the explicitly authorised physical
 * probe. No account, room, microphone, camera or recording is used here. */
internal fun exerciseSpeakerRouteRecovery(context: Context, instrumentation: Instrumentation, requireEarpiece: Boolean = true) {
    val audio = context.getSystemService(AudioManager::class.java)
    check(audio.mode == AudioManager.MODE_NORMAL) { "Another call owns audio; leave it undisturbed" }
    val earpiece = audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
    if (requireEarpiece) checkNotNull(earpiece) { "The physical fallback probe requires an earpiece" }
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
            assertTrue("Joining takes communication mode", audio.mode == AudioManager.MODE_IN_COMMUNICATION)
            if (requireEarpiece) {
                val sawEarpiece = CountDownLatch(1)
                val observer = AudioManager.OnCommunicationDeviceChangedListener { device ->
                    if (device?.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) sawEarpiece.countDown()
                }
                audio.addOnCommunicationDeviceChangedListener(context.mainExecutor, observer)
                try {
                    assertTrue("Select the platform fallback to reproduce the regression", audio.setCommunicationDevice(earpiece))
                    // Selection is asynchronous. Without observing the fallback,
                    // checking immediately could pass against the old speaker route.
                    assertTrue("The platform must actually report the fallback", sawEarpiece.await(5, TimeUnit.SECONDS))
                    awaitSpeaker()
                } finally { audio.removeOnCommunicationDeviceChangedListener(observer) }
            }
            instrumentation.runOnMainSync {
                audio.mode = AudioManager.MODE_NORMAL
                audio.clearCommunicationDevice()
                if (requireEarpiece) audio.setCommunicationDevice(checkNotNull(earpiece))
                audio.mode = AudioManager.MODE_IN_COMMUNICATION
            }
            awaitSpeaker()
        } finally { instrumentation.runOnMainSync { routing.close() } }
        assertTrue("Leaving releases communication mode", audio.mode == AudioManager.MODE_NORMAL)
    }
}
