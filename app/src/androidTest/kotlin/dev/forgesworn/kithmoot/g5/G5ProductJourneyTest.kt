package dev.forgesworn.kithmoot.g5

import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.ui.RoomViewModel
import dev.forgesworn.kithmoot.ui.Stage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

/**
 * One action per isolated emulator process for the composed G5 rehearsal.
 *
 * The fixture control endpoint is loopback-only and may briefly hold an
 * invitation capability. This test never logs that value or puts it in an
 * instrumentation argument. MainActivity supplies the real NIP-55
 * intent/result bridge to the separately installed fixture signer.
 */
@RunWith(AndroidJUnit4::class)
class G5ProductJourneyTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)

    private val arguments get() = InstrumentationRegistry.getArguments()
    private val control get() = requireNotNull(arguments.getString("fixture_control")).removeSuffix("/")
    private val action get() = requireNotNull(arguments.getString("g5_action"))
    private val signerPackage get() = "dev.forgesworn.kithmoot.g5signer"

    @Test fun runs_the_requested_product_admission_action() {
        when (action) {
            "alice-introduction" -> aliceIntroduction()
            "bob-introduction" -> bobIntroduction()
            "alice-invitation" -> aliceInvitation()
            "bob-invitation" -> bobInvitation()
            "alice-pair" -> pairAlice()
            "bob-pair" -> pairBob()
            "alice-send" -> aliceSend()
            "bob-receive-reply" -> bobReceiveAndReply()
            "alice-restored" -> aliceRestored()
            else -> throw AssertionError("unknown G5 product action")
        }
    }

    private fun aliceIntroduction() {
        val model = model()
        signIn(model)
        val intro = ready().getValue("introduction_relay_url").jsonPrimitive.content
        activity.scenario.onActivity {
            model.onRelaysChanged(intro)
            model.onRoomNameChanged("G5 introduction")
            model.startRoom()
        }
        await("Alice's introduction room") { model.stage.value == Stage.ROOM && model.room.value.roomId.isNotBlank() }
        put("introduction", buildJsonObject {
            put("url", model.room.value.joinUrl)
            put("room", model.room.value.roomId)
            put("participant", model.room.value.selfParticipant)
        })
    }

    private fun bobIntroduction() {
        val model = model()
        signIn(model)
        val introduction = awaitValue("introduction")
        activity.scenario.onActivity { model.joinFromUrl(introduction.getValue("url").jsonPrimitive.content) }
        await("Bob's introduction room", details = {
            "stage=${model.stage.value}; busy=${model.start.value.busy}; error=${model.start.value.error}; roomMatches=${model.room.value.roomId == introduction.getValue("room").jsonPrimitive.content}"
        }) {
            model.stage.value == Stage.ROOM &&
                model.room.value.roomId == introduction.getValue("room").jsonPrimitive.content &&
                !model.room.value.privateConversation
        }
        assertTrue(
            "G5 fixture signer personas must be distinct",
            model.room.value.selfParticipant != introduction.getValue("participant").jsonPrimitive.content,
        )
        put("bob-introduction", buildJsonObject { put("room", model.room.value.roomId) })
    }

    private fun aliceInvitation() {
        val model = model()
        restoreSignIn(model)
        val room = awaitValue("bob-introduction").getValue("room").jsonPrimitive.content
        open(model, room, privateConversation = false)
        await("Bob's signed room device", details = {
            "tiles=${model.room.value.tiles.size}; peers=${model.room.value.privateConversationPeers.size}; signedIn=${model.start.value.account != null}"
        }) { model.room.value.privateConversationPeers.size == 1 }
        val bob = model.room.value.privateConversationPeers.single()
        activity.scenario.onActivity { model.startPrivateConversation(bob) }
        await("Alice's signer-sealed private conversation") {
            model.stage.value == Stage.ROOM && model.room.value.privateConversation && !model.room.value.privateConversationBusy
        }
        put("alice-dm", buildJsonObject { put("room", model.room.value.roomId); put("participant", model.room.value.selfParticipant) })
    }

    private fun bobInvitation() {
        val model = model()
        restoreSignIn(model)
        val room = awaitValue("bob-introduction").getValue("room").jsonPrimitive.content
        open(model, room, privateConversation = false)
        await("Alice's sealed private invitation") { model.room.value.chat.any { it.invite != null } }
        val message = model.room.value.chat.first { it.invite != null }
        activity.scenario.onActivity {
            model.openPrivateConversation(message)
            assertTrue(
                "Bob's private invitation action must start (notice=${model.room.value.notice})",
                model.room.value.privateConversationBusy,
            )
        }
        await("Bob's deliberately opened private conversation", details = {
            "stage=${model.stage.value}; private=${model.room.value.privateConversation}; busy=${model.room.value.privateConversationBusy}; notice=${model.room.value.notice}; startError=${model.start.value.error}; savedRooms=${model.start.value.savedRooms.size}; targetSaved=${model.start.value.savedRooms.any { it.id == message.invite?.room }}"
        }) {
            model.stage.value == Stage.ROOM && model.room.value.privateConversation && !model.room.value.privateConversationBusy
        }
        val alice = awaitValue("alice-dm")
        assertEquals(alice.getValue("room").jsonPrimitive.content, model.room.value.roomId)
        put("bob-dm", buildJsonObject { put("room", model.room.value.roomId); put("participant", model.room.value.selfParticipant); put("device", model.room.value.selfDevice) })
        await("Bob's current private-room roster is retained") {
            get("journey-roster/bob-dm").getValue("stored").jsonPrimitive.content == "true"
        }
    }

    private fun pairAlice() = pair("alice", expectGrants = true)

    private fun pairBob() = pair("bob", expectGrants = false)

    private fun pair(who: String, expectGrants: Boolean) {
        val model = model()
        restoreSignIn(model)
        val room = awaitValue("$who-dm").getValue("room").jsonPrimitive.content
        val pairing = post("pairing").getValue("uri").jsonPrimitive.content
        activity.scenario.onActivity { model.pairBothy(room, pairing) }
        await("$who Bothy pairing", details = {
            "busy=${model.start.value.busy}; error=${model.start.value.error}; notice=${model.start.value.notice}; connected=${model.start.value.linkConnectedRooms.contains(room)}; grantOwner=${model.start.value.linkGrantOwnerRooms.contains(room)}"
        }) {
            !model.start.value.busy && model.start.value.linkConnectedRooms.contains(room)
        }
        assertEquals(expectGrants, model.start.value.linkGrantOwnerRooms.contains(room))
        put("$who-paired", buildJsonObject { put("room", room) })
    }

    private fun aliceSend() {
        val model = model()
        restoreSignIn(model)
        val room = awaitValue("alice-paired").getValue("room").jsonPrimitive.content
        open(model, room)
        activity.scenario.onActivity { model.sendChat(ALICE_MESSAGE) }
        await("Alice's retained encrypted message") { model.room.value.chat.any { it.body == ALICE_MESSAGE } }
        put("alice-sent", buildJsonObject { put("room", room) })
    }

    private fun bobReceiveAndReply() {
        val model = model()
        restoreSignIn(model)
        val room = awaitValue("bob-paired").getValue("room").jsonPrimitive.content
        open(model, room)
        await("Bob's received encrypted message") { model.room.value.chat.any { it.body == ALICE_MESSAGE } }
        activity.scenario.onActivity { model.sendChat(BOB_REPLY) }
        await("Bob's retained encrypted reply") { model.room.value.chat.any { it.body == BOB_REPLY } }
        put("bob-replied", buildJsonObject { put("room", room) })
    }

    private fun aliceRestored() {
        val model = model()
        restoreSignIn(model)
        val room = awaitValue("alice-sent").getValue("room").jsonPrimitive.content
        open(model, room)
        await("Alice's retained reply after process restart") { model.room.value.chat.any { it.body == BOB_REPLY } }
        put("alice-restored", buildJsonObject { put("room", room) })
    }

    private fun open(model: RoomViewModel, room: String, privateConversation: Boolean = true) {
        activity.scenario.onActivity { model.reopenRoom(room) }
        await("saved room") {
            model.stage.value == Stage.ROOM && model.room.value.roomId == room &&
                model.room.value.privateConversation == privateConversation
        }
    }

    private fun signIn(model: RoomViewModel) {
        activity.scenario.onActivity { model.refreshSigners() }
        await("fixture signer discovery") { model.start.value.signers.any { it.packageName == signerPackage } }
        activity.scenario.onActivity { model.signInWithSignerApp(signerPackage) }
        await("NIP-55 account sign-in") { model.start.value.account != null && !model.start.value.signingIn }
        assertEquals("nip55", model.start.value.account?.method)
    }

    private fun restoreSignIn(model: RoomViewModel) {
        await("saved NIP-55 account restore") { model.start.value.account != null }
        assertEquals("nip55", model.start.value.account?.method)
    }

    private fun model(): RoomViewModel {
        lateinit var model: RoomViewModel
        activity.scenario.onActivity { model = ViewModelProvider(it)[RoomViewModel::class.java] }
        return model
    }

    private fun ready() = get("ready")

    private fun post(path: String): kotlinx.serialization.json.JsonObject {
        val connection = URL("$control/$path").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            require(connection.responseCode in 200..299) { "fixture control rejected $path" }
            return Json.parseToJsonElement(connection.inputStream.bufferedReader().use { it.readText() }).jsonObject
        } finally { connection.disconnect() }
    }

    private fun awaitValue(key: String): kotlinx.serialization.json.JsonObject {
        var value: kotlinx.serialization.json.JsonObject? = null
        await("G5 fixture action $key") { value = getOrNull("journey/$key"); value != null }
        return requireNotNull(value)
    }

    private fun get(path: String): kotlinx.serialization.json.JsonObject = requireNotNull(getOrNull(path))

    private fun getOrNull(path: String): kotlinx.serialization.json.JsonObject? = URL("$control/$path").openConnection().let { raw ->
        val connection = raw as HttpURLConnection
        try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            if (connection.responseCode == HttpURLConnection.HTTP_NOT_FOUND) return@let null
            require(connection.responseCode in 200..299) { "fixture control rejected $path" }
            Json.parseToJsonElement(connection.inputStream.bufferedReader().use { it.readText() }).jsonObject
        } finally { connection.disconnect() }
    }

    private fun put(key: String, value: kotlinx.serialization.json.JsonObject) {
        val connection = URL("$control/journey/$key").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.outputStream.bufferedWriter().use { it.write(value.toString()) }
            require(connection.responseCode == HttpURLConnection.HTTP_NO_CONTENT) { "fixture control rejected $key" }
        } finally { connection.disconnect() }
    }

    private fun await(description: String, details: () -> String = { "" }, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 60_000
        while (!predicate()) {
            if (SystemClock.uptimeMillis() >= deadline) throw AssertionError("Timed out waiting for $description (${details()})")
            SystemClock.sleep(50)
        }
    }

    private companion object {
        const val ALICE_MESSAGE = "g5 retained message from Alice"
        const val BOB_REPLY = "g5 retained reply from Bob"
    }
}
