package dev.forgesworn.kithmoot.media

import android.media.AudioManager
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.*
import dev.forgesworn.kithmoot.session.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class LeaveCallTest {
    @Test fun leavingStopsMediaButRosterAndChatKeepWorkingUntilExplicitRejoin() = runBlocking {
        assertTrue(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.RECORD_AUDIO)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val events = MutableSharedFlow<NostrEvent>(extraBufferCapacity = 256)
        val transport = object : RoomTransport {
            override fun publish(event: NostrEvent) { check(events.tryEmit(event)) }
            override fun subscribe(filters: List<Filter>): Flow<NostrEvent> = events.filter { event -> filters.any { it.kinds == null || event.kind in it.kinds!! } }
        }
        val room = deriveRoom(ByteArray(32) { 17 })
        val now = System.currentTimeMillis() / 1000
        fun identity(seed: Byte) = PrimaryIdentity.create(room.roomId, now + 3600, now, ByteArray(32) { seed }, ByteArray(32) { (seed + 10).toByte() })
        val mine = RoomSession(room, identity(1), transport, scope)
        val other = RoomSession(room, identity(2), transport, scope)
        val third = RoomSession(room, identity(3), transport, scope)
        val engine = WebRtcEngine(context, mine, scope, emptyList())
        try {
            mine.join(); other.join(); engine.start()
            withTimeout(10_000) { engine.connections.first { it.isNotEmpty() } }
            assertNotNull(engine.localMedia.startMicrophone())
            engine.audioRouting.setActive(true)
            assertFalse(engine.localMedia.tracks.value.isEmpty())
            engine.setCallActive(false)
            assertTrue(engine.localMedia.tracks.value.isEmpty())
            assertTrue(engine.connections.value.isEmpty())
            assertTrue(engine.remoteTracks.value.isEmpty())
            assertEquals(AudioManager.MODE_NORMAL, context.getSystemService(AudioManager::class.java).mode)
            third.join()
            withTimeout(10_000) { mine.participants.first { it.size == 3 } }
            assertTrue(engine.connections.value.isEmpty())
            assertFalse(engine.callActive)
            mine.sendChat("Still in the room after leaving the call")
            withTimeout(10_000) { other.chat.first { messages -> messages.any { it.body == "Still in the room after leaving the call" } } }
            engine.setCallActive(true)
            withTimeout(10_000) { engine.connections.first { it.size == 2 } }
            assertTrue(engine.localMedia.tracks.value.isEmpty()) // Rejoining does not turn the mic on.
        } finally { engine.stop(); scope.cancel(); engine.dispose() }
    }
}
