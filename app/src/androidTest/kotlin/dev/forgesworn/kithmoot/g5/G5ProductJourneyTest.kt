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
    private val signerPackage get() = InstrumentationRegistry.getInstrumentation().context.packageName

    @Test fun runs_the_requested_product_admission_action() {
        when (action) {
            "alice-introduction" -> aliceIntroduction()
            "bob-invitation" -> bobInvitation()
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
        put("introduction", buildJsonObject { put("url", model.room.value.joinUrl) })
        await("Bob's signed room device") { model.room.value.privateConversationPeers.size == 1 }
        val bob = model.room.value.privateConversationPeers.single()
        activity.scenario.onActivity { model.startPrivateConversation(bob) }
        await("Alice's signer-sealed private conversation") {
            model.stage.value == Stage.ROOM && model.room.value.privateConversation && !model.room.value.privateConversationBusy
        }
        put("alice-dm", buildJsonObject { put("room", model.room.value.roomId); put("participant", model.room.value.selfParticipant) })
    }

    private fun bobInvitation() {
        val model = model()
        signIn(model)
        val invitation = awaitValue("introduction").getValue("url").jsonPrimitive.content
        activity.scenario.onActivity { model.joinFromUrl(invitation) }
        await("Bob's introduction room") { model.stage.value == Stage.ROOM && !model.room.value.privateConversation }
        await("Alice's sealed private invitation") { model.room.value.chat.any { it.invite != null } }
        val message = model.room.value.chat.first { it.invite != null }
        activity.scenario.onActivity { model.openPrivateConversation(message) }
        await("Bob's deliberately opened private conversation") {
            model.stage.value == Stage.ROOM && model.room.value.privateConversation && !model.room.value.privateConversationBusy
        }
        val alice = awaitValue("alice-dm")
        assertEquals(alice.getValue("room").jsonPrimitive.content, model.room.value.roomId)
        put("bob-dm", buildJsonObject { put("room", model.room.value.roomId); put("participant", model.room.value.selfParticipant); put("device", model.room.value.selfDevice) })
    }

    private fun signIn(model: RoomViewModel) {
        activity.scenario.onActivity { model.refreshSigners() }
        await("fixture signer discovery") { model.start.value.signers.any { it.packageName == signerPackage } }
        activity.scenario.onActivity { model.signInWithSignerApp(signerPackage) }
        await("NIP-55 account sign-in") { model.start.value.account != null && !model.start.value.signingIn }
        assertEquals("nip55", model.start.value.account?.method)
    }

    private fun model(): RoomViewModel {
        lateinit var model: RoomViewModel
        activity.scenario.onActivity { model = ViewModelProvider(it)[RoomViewModel::class.java] }
        return model
    }

    private fun ready() = get("ready")

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
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.outputStream.bufferedWriter().use { it.write(value.toString()) }
            require(connection.responseCode == HttpURLConnection.HTTP_NO_CONTENT) { "fixture control rejected $key" }
        } finally { connection.disconnect() }
    }

    private fun await(description: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 60_000
        while (!predicate()) {
            if (SystemClock.uptimeMillis() >= deadline) throw AssertionError("Timed out waiting for $description")
            SystemClock.sleep(50)
        }
    }
}
