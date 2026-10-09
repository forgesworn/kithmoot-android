package dev.forgesworn.kithmoot.ui

import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.*
import dev.forgesworn.kithmoot.session.PrimaryIdentity
import dev.forgesworn.kithmoot.storage.SavedRoom
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/** Real room entry, encrypted storage and foreground lifecycle; only the radio
 * boundary is simulated. No GATT or physical BLE claim follows. */
@RunWith(AndroidJUnit4::class)
class NearbyRoomEntryTest {
    @Test fun nearby_entry_and_fast_foreground_return_never_connect_the_saved_relay() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<KithMootApplication>()
        val main = InstrumentationRegistry.getInstrumentation()
        val store = ViewModelStore()
        val radios = CopyOnWriteArrayList<TestRadio>()
        val server = MockWebServer().also { it.start() }
        val secret = Entropy.bytes(32)
        val now = System.currentTimeMillis() / 1000
        val room = deriveRoom(secret)
        val who = PrimaryIdentity.create(room.roomId, now + 86400, now)
        val relays = listOf(server.url("/").toString().replace("http:", "ws:"))
        val saved = SavedRoom.create(secret, who, encodeJoinUrl("https://fixture.invalid/j/", secret, relays),
            relays, "Nearby fixture", now, null, null).withRoute(RoomRoute.NEARBY)
        app.savedRooms.save(saved)
        lateinit var model: RoomViewModel
        main.runOnMainSync {
            model = RoomViewModel(app, nearbyLinkFactory = {
                NativeRoomMeshLink({ receive -> TestRadio(receive).also { radios += it } }, Dispatchers.Main)
            })
            store.put("nearby-fixture", model)
            model.reopenRoom(saved.id)
        }
        suspend fun await(description: String, predicate: () -> Boolean) {
            withTimeout(60_000) { while (!predicate()) delay(50) }
            assertTrue(description, predicate())
        }
        try {
            await("nearby room opens") { model.stage.value == Stage.ROOM }
            assertEquals(RoomRoute.NEARBY, model.room.value.route)
            assertEquals(saved.participant, model.room.value.selfParticipant)
            assertEquals(saved.devicePubkey, model.room.value.selfDevice)
            assertFalse(model.room.value.profilesEnabled)
            assertFalse(model.room.value.mediaRunning)
            assertEquals(0, server.requestCount)
            assertEquals(1, radios.size)
            main.runOnMainSync { model.setAppVisible(false); model.setAppVisible(true) }
            await("closed radio replaced on fast return") { radios.size == 2 && model.stage.value == Stage.ROOM }
            assertTrue(radios.first().closed)
            assertFalse(radios.last().closed)
            assertEquals(0, server.requestCount)
            main.runOnMainSync { model.leave() }
            await("room fully closed") { !model.start.value.busy && radios.last().closed }
            assertEquals(RoomRoute.NEARBY, app.savedRooms.get(saved.id)!!.route)
            main.runOnMainSync { model.setRoomRoute(saved.id, RoomRoute.INTERNET) }
            await("explicit route persisted") { app.savedRooms.get(saved.id)?.route == RoomRoute.INTERNET && !model.start.value.busy }
            assertEquals(0, server.requestCount) // Saving a route does not open the room.
        } finally {
            main.runOnMainSync { store.clear() }
            withTimeout(10_000) { while (radios.any { !it.closed }) delay(20) }
            app.savedRooms.forget(saved.id)
            dev.forgesworn.kithmoot.storage.PendingChatVault(app, saved.id, saved.participant, saved.devicePubkey).outbox.clear()
            server.shutdown()
            secret.fill(0)
        }
    }

    private class TestRadio(private val receive: (RoomBleEvent) -> Unit) : RoomBleRadio {
        @Volatile var closed = false
        override fun start(config: RoomBleConfig) { receive(RoomBleEvent.Status(true, 1)) }
        override fun offer(bytes: ByteArray, to: String?) = 1
        override fun close() { closed = true }
    }
}
