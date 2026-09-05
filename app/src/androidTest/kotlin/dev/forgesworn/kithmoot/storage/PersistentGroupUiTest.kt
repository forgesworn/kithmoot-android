package dev.forgesworn.kithmoot.storage

import android.os.Process
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.ui.RoomViewModel
import kotlinx.serialization.json.*
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.Properties
import java.util.Collections

/** Real installed app, Keystore and WebSockets. All rooms and relay data are synthetic. */
@RunWith(AndroidJUnit4::class)
class PersistentGroupUiTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    private val ui = RecoveryUi()
    private val app get() = ApplicationProvider.getApplicationContext<KithMootApplication>()
    private val marker get() = File(app.noBackupFilesDir, "group-restart-test.properties")
    private val fixture get() = Json.parseToJsonElement(InstrumentationRegistry.getInstrumentation().context.assets
        .open("persistent-group-web.json").bufferedReader().use { it.readText() }).jsonObject
    private var relay: StoredGroupRelay? = null

    @After fun closeRelay() { relay?.close() }

    private fun reset() {
        ui.home()
        app.savedRooms.reset()
        activity.scenario.onActivity { ViewModelProvider(it)[RoomViewModel::class.java].refreshSavedRooms() }
        ui.home()
    }

    private fun webLink(server: StoredGroupRelay): String {
        val invitation = decodeInvitationUrl(fixture.getValue("url").jsonPrimitive.content)!!.invitation
        return encodeInvitationUrl("https://kithmoot.forgesworn.dev/j/", invitation, listOf(server.url))
    }

    @Test fun a_create_and_join_web_group() {
        val server = StoredGroupRelay().also { relay = it }
        reset()
        ui.click("Relay settings")
        ui.replace("Relays, one per line", server.url)
        ui.replace("Room name (optional)", "Native persistent group")
        ui.click("Start a room")
        ui.room()
        val created = app.savedRooms.get(app.savedRooms.list().single().id)!!
        assertTrue(created.invitation!!.invitation.persistent)
        assertNotNull(created.host(System.currentTimeMillis() / 1000))
        assertTrue(server.snapshot().any { decodePersistentInvitation(it, created.invitation!!.invitation) != null })
        ui.click("Leave")
        reset()
        // Nobody serves the web fixture. Admission can only come from relay storage.
        server.events.add(NostrEvent.fromJson(fixture.getValue("event")))
        activity.scenario.onActivity { ViewModelProvider(it)[RoomViewModel::class.java].joinFromUrl(webLink(server)) }
        ui.room()
        val saved = app.savedRooms.get(app.savedRooms.list().single().id)!!
        assertEquals(fixture.getValue("room").jsonPrimitive.content, saved.id)
        assertNull(saved.host(System.currentTimeMillis() / 1000))
        assertFalse(server.snapshot().any { it.kind == KIND_INVITATION_REQUEST })
        val identity = saved.identity(System.currentTimeMillis() / 1000 + 4 * 24 * 60 * 60)
        Properties().apply {
            setProperty("id", saved.id)
            setProperty("name", saved.name)
            setProperty("participant", identity.participant)
            setProperty("device", identity.devicePubkey)
            setProperty("pid", Process.myPid().toString())
        }.also { expected -> marker.outputStream().use { expected.store(it, "Synthetic group identifiers only") } }
    }

    @Test fun b_reopen_without_relay_or_creator() {
        assertTrue("Run a_create_and_join_web_group first", marker.isFile)
        val expected = Properties().apply { marker.inputStream().use { load(it) } }
        if (InstrumentationRegistry.getArguments().getString("requireRestart") == "true") {
            assertNotEquals(expected.getProperty("pid"), Process.myPid().toString())
        }
        ui.home()
        ui.click(expected.getProperty("name"))
        ui.room()
        activity.scenario.onActivity {
            val state = ViewModelProvider(it)[RoomViewModel::class.java].room.value
            assertEquals(expected.getProperty("id"), state.roomId)
            assertEquals(expected.getProperty("participant"), state.selfParticipant)
            assertEquals(expected.getProperty("device"), state.selfDevice)
            assertFalse(state.canRotateInvitation)
            assertFalse(state.micOn)
            assertFalse(state.cameraOn)
            assertFalse(state.screenOn)
        }
    }

    @Test fun c_refused_publication_and_retired_web_link_stay_outside_room() {
        val server = StoredGroupRelay().also { relay = it; it.rejectPublications = true }
        reset()
        ui.click("Relay settings")
        ui.replace("Relays, one per line", server.url)
        ui.click("Start a room")
        ui.await("publication rejection") { ui.hasText("The relays refused this group invitation. Try again or choose another relay.") }
        assertTrue(app.savedRooms.list().isEmpty())
        ui.assertEnabled("Start a room", true)
        server.events.add(NostrEvent.fromJson(fixture.getValue("event")))
        server.events.add(NostrEvent.fromJson(fixture.getValue("retirement")))
        activity.scenario.onActivity { ViewModelProvider(it)[RoomViewModel::class.java].joinFromUrl(webLink(server)) }
        ui.await("retired invitation") { ui.hasText("This invitation was retired. Ask for the current room link.") }
        assertTrue(app.savedRooms.list().isEmpty())
    }
}

private class StoredGroupRelay : AutoCloseable {
    val events: MutableList<NostrEvent> = Collections.synchronizedList(mutableListOf())
    @Volatile var rejectPublications = false
    private val server = MockWebServer()
    val url: String get() = server.url("/").toString().replace("http://", "ws://")
    fun snapshot(): List<NostrEvent> = synchronized(events) { events.toList() }
    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val frame = Json.parseToJsonElement(text).jsonArray
                    when (frame[0].jsonPrimitive.content) {
                        "EVENT" -> {
                            val event = NostrEvent.fromJson(frame[1])
                            val accepted = !rejectPublications && Events.verify(event)
                            if (accepted) events.add(event)
                            webSocket.send("""["OK","${event.id}",$accepted,""]""")
                        }
                        "REQ" -> {
                            val id = frame[1].jsonPrimitive.content
                            val filters = frame.drop(2).map { it.jsonObject }
                            snapshot().filter { event -> filters.any { matches(it, event) } }.forEach {
                                webSocket.send("""["EVENT","$id",${it.toJson()}]""")
                            }
                            webSocket.send("""["EOSE","$id"]""")
                        }
                    }
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            })
        }
        server.start()
    }
    override fun close() = server.close()
    private fun matches(filter: JsonObject, event: NostrEvent): Boolean {
        if (filter["kinds"]?.jsonArray?.none { it.jsonPrimitive.int == event.kind } == true) return false
        if (filter["authors"]?.jsonArray?.none { it.jsonPrimitive.content == event.pubkey } == true) return false
        return filter.filterKeys { it.startsWith("#") }.all { (key, values) ->
            event.tags.any { it.size >= 2 && it[0] == key.drop(1) && values.jsonArray.any { value -> value.jsonPrimitive.content == it[1] } }
        }
    }
}
