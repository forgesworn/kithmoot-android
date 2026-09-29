package dev.forgesworn.kithmoot.g5

import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.relay.LinkConsentState
import dev.forgesworn.kithmoot.relay.RelaySocketListener
import dev.forgesworn.kithmoot.storage.PendingChatVault
import dev.forgesworn.kithmoot.ui.RoomViewModel
import dev.forgesworn.kithmoot.ui.Stage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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
            "bob-roster-refresh" -> bobRosterRefresh()
            "alice-pair" -> pairAlice()
            "bob-pair" -> pairBob()
            "alice-send" -> aliceSend()
            "bob-receive-reply" -> bobReceiveAndReply()
            "alice-restored" -> aliceRestored()
            "alice-pending-stage" -> alicePendingStage()
            "alice-pending-reopen" -> alicePendingReopen()
            "bob-pending-receive" -> bobPendingReceive()
            "bob-pending-reopen" -> bobPendingReceive()
            "alice-withdraw-outage" -> aliceWithdrawDuringOutage()
            "alice-withdraw-recover" -> aliceWithdrawalRecovers()
            "alice-route-restored" -> routeRestored("alice")
            "bob-route-restored" -> bobRouteRestored()
            "bob-background-arm" -> bobBackgroundArm()
            "alice-background-send" -> aliceBackgroundSend()
            "bob-background-pending-stage" -> bobBackgroundPendingStage()
            "alice-background-pending-receive" -> aliceBackgroundPendingReceive()
            "bob-background-open" -> bobBackgroundOpen()
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
        await("Alice's signer-sealed private conversation", details = {
            "stage=${model.stage.value}; private=${model.room.value.privateConversation}; busy=${model.room.value.privateConversationBusy}; notice=${model.room.value.notice}; startError=${model.start.value.error}; savedRooms=${model.start.value.savedRooms.size}"
        }) {
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

    private fun bobRosterRefresh() {
        val model = model()
        restoreSignIn(model)
        val room = awaitValue("bob-dm").getValue("room").jsonPrimitive.content
        open(model, room)
        await("Bob's current private-room roster refresh", details = {
            "relaysUp=${model.room.value.relaysUp}; notice=${model.room.value.notice}"
        }) { model.room.value.relaysUp > 0 }
        SystemClock.sleep(2_000)
        put("bob-roster-refreshed", buildJsonObject { put("room", room) })
    }

    private fun pair(who: String, expectGrants: Boolean) {
        val model = model()
        restoreSignIn(model)
        val room = awaitValue("$who-dm").getValue("room").jsonPrimitive.content
        val pairing = post("pairing").getValue("uri").jsonPrimitive.content
        activity.scenario.onActivity { model.pairBothy(room, pairing) }
        await("$who Bothy pairing", details = {
            "busy=${model.start.value.busy}; error=${model.start.value.error}; notice=${model.start.value.notice}; connected=${model.start.value.linkConnectedRooms.contains(room)}; grantOwner=${model.start.value.linkGrantOwnerRooms.contains(room)}"
        }) {
            model.start.value.error?.let { throw AssertionError("$who Bothy pairing failed: $it") }
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
        activity.scenario.onActivity {
            model.sendChat(ALICE_MESSAGE)
            assertTrue(
                "Alice's send must start from the reopened room",
                model.room.value.chatSending || model.room.value.chat.any { it.body == ALICE_MESSAGE },
            )
        }
        await("Alice's retained encrypted message", details = {
            "stage=${model.stage.value}; room=${model.room.value.roomId}; relaysUp=${model.room.value.relaysUp}; " +
                "sending=${model.room.value.chatSending}; error=${model.room.value.chatSendError}; notice=${model.room.value.notice}; " +
                "connected=${model.start.value.linkConnectedRooms.contains(room)}"
        }) { !model.room.value.chatSending && model.room.value.chat.any { it.body == ALICE_MESSAGE } }
        put("alice-sent", buildJsonObject { put("room", room) })
    }

    private fun bobReceiveAndReply() {
        val model = model()
        restoreSignIn(model)
        val room = awaitValue("bob-paired").getValue("room").jsonPrimitive.content
        open(model, room)
        await("Bob's received encrypted message") { model.room.value.chat.any { it.body == ALICE_MESSAGE } }
        activity.scenario.onActivity { model.sendChat(BOB_REPLY) }
        await("Bob's retained encrypted reply", details = {
            "sending=${model.room.value.chatSending}; error=${model.room.value.chatSendError}; notice=${model.room.value.notice}"
        }) { !model.room.value.chatSending && model.room.value.chat.any { it.body == BOB_REPLY } }
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

    /** Stage through the real saved account and ViewModel while its paired box is offline. */
    private fun alicePendingStage() {
        val model = model()
        restoreSignIn(model)
        val room = awaitValue("alice-paired").getValue("room").jsonPrimitive.content
        open(model, room)
        await("Alice's paired private room connected before outage") { model.room.value.relaysUp > 0 }
        val saved = requireNotNull(application().savedRooms.get(room))
        val outbox = PendingChatVault(application(), room, saved.participant, saved.devicePubkey).outbox
        assertTrue("the fixture must start without an older pending message", runBlocking { outbox.pending() } == null)

        post("pause")
        await("Alice's paired route to disconnect") { model.room.value.relaysUp == 0 }
        activity.scenario.onActivity { model.sendChat(PENDING_MESSAGE) }
        var retained: dev.forgesworn.kithmoot.session.PendingChatOutbox.Pending? = null
        await("the signed message to reach Alice's encrypted journal", details = {
            "sending=${model.room.value.chatSending}; pending=${model.room.value.chatPending}; " +
                "displayed=${model.room.value.chat.count { it.body == PENDING_MESSAGE }}"
        }) {
            retained = runBlocking { outbox.pending() }
            retained != null
        }
        val exact = requireNotNull(retained).event
        assertEquals(room, model.room.value.roomId)
        assertEquals(0, model.room.value.chat.count { it.body == PENDING_MESSAGE })
        put("alice-pending-staged", buildJsonObject {
            put("room", room)
            put("outerEventId", exact.id)
            put("ciphertextSha256", Digests.sha256(exact.content.toByteArray(Charsets.UTF_8)).toHex())
            put("createdAt", exact.createdAt)
            put("address", requireNotNull(exact.tagValue("d")))
        })
    }

    /** The runner force-stops Alice between this and stage, then restarts the same box. */
    private fun alicePendingReopen() {
        val model = model()
        restoreSignIn(model)
        val staged = awaitValue("alice-pending-staged")
        val room = staged.getValue("room").jsonPrimitive.content
        val saved = requireNotNull(application().savedRooms.get(room))
        val outbox = PendingChatVault(application(), room, saved.participant, saved.devicePubkey).outbox
        val exact = requireNotNull(runBlocking { outbox.pending() }) { "force-stop lost Alice's pending event" }.event
        assertEquals(staged.getValue("outerEventId").jsonPrimitive.content, exact.id)
        assertEquals(staged.getValue("ciphertextSha256").jsonPrimitive.content,
            Digests.sha256(exact.content.toByteArray(Charsets.UTF_8)).toHex())

        open(model, room)
        await("Alice's exact pending message to be confirmed", details = {
            "relaysUp=${model.room.value.relaysUp}; sending=${model.room.value.chatSending}; " +
                "pending=${model.room.value.chatPending}; error=${model.room.value.chatSendError}; " +
                "displayed=${model.room.value.chat.count { it.body == PENDING_MESSAGE }}"
        }) {
            !model.room.value.chatPending && model.room.value.chat.count { it.body == PENDING_MESSAGE } == 1 &&
                runBlocking { outbox.pending() } == null
        }
        await("the confirmed outer event to reach the NIP-77 index") {
            application().nip77Events.records(saved.participant, room,
                staged.getValue("address").jsonPrimitive.content,
                exact.createdAt, exact.createdAt).any { it.id.toHex() == exact.id }
        }
        SystemClock.sleep(2_000)
        assertEquals(1, model.room.value.chat.count { it.body == PENDING_MESSAGE })
        put("alice-pending-confirmed", buildJsonObject {
            put("room", room); put("sameOuterEvent", true); put("displayedOnce", true)
        })
    }

    private fun bobPendingReceive() {
        val model = model()
        restoreSignIn(model)
        awaitValue("alice-pending-confirmed")
        val room = awaitValue("bob-paired").getValue("room").jsonPrimitive.content
        open(model, room)
        await("Bob's one decrypted pending message", details = {
            "relaysUp=${model.room.value.relaysUp}; matches=${model.room.value.chat.count { it.body == PENDING_MESSAGE }}"
        }) { model.room.value.chat.count { it.body == PENDING_MESSAGE } == 1 }
        SystemClock.sleep(2_000)
        assertEquals(1, model.room.value.chat.count { it.body == PENDING_MESSAGE })
        put("bob-pending-received", buildJsonObject { put("room", room); put("displayedOnce", true) })
    }

    private fun aliceWithdrawDuringOutage() {
        val model = model()
        restoreSignIn(model)
        val room = awaitValue("alice-paired").getValue("room").jsonPrimitive.content
        activity.scenario.onActivity { model.disconnectBothy(room) }
        await("Alice's withdrawal to remain pending during the outage", details = {
            "busy=${model.start.value.busy}; error=${model.start.value.error}; connected=${model.start.value.linkConnectedRooms.contains(room)}"
        }) { !model.start.value.busy && model.start.value.error != null }
        val consent = application().linkConsents.all().single { it.roomId == room }
        assertEquals("withdrawal error: ${model.start.value.error}", LinkConsentState.WITHDRAWING, consent.state)
        assertTrue(application().linkEngine.routeIds().contains(consent.routeId))
        assertTrue(model.start.value.linkConnectedRooms.contains(room))
        put("alice-withdraw-outage", buildJsonObject {
            put("pending", true)
            put("localRouteRetained", true)
        })
    }

    private fun aliceWithdrawalRecovers() {
        val model = model()
        restoreSignIn(model)
        val room = awaitValue("alice-paired").getValue("room").jsonPrimitive.content
        repeat(4) { attempt ->
            if (application().linkConsents.all().none { it.roomId == room }) return@repeat
            activity.scenario.onActivity { model.refreshSavedRooms() }
            await("Alice's withdrawal recovery attempt ${attempt + 1}", details = {
                "loading=${model.start.value.loadingRooms}; error=${model.start.value.error}; connected=${model.start.value.linkConnectedRooms.contains(room)}; consents=${application().linkConsents.all().size}"
            }) { !model.start.value.loadingRooms }
            if (application().linkConsents.all().any { it.roomId == room }) SystemClock.sleep(3_000)
        }
        assertTrue("Alice's pending withdrawal did not converge after bounded refresh retries", application().linkConsents.all().none { it.roomId == room })
        assertTrue(!model.start.value.linkConnectedRooms.contains(room))
        assertTrue(application().linkEngine.routeIds().isEmpty())
        val introduction = ready().getValue("introduction_relay_url").jsonPrimitive.content
        assertEquals(listOf(introduction), application().savedRooms.get(room)?.relays)
        put("alice-withdraw-recover", buildJsonObject {
            put("completed", true)
            put("localRouteRemoved", true)
            put("previousRelayRestored", true)
        })
    }

    private fun bobRouteRestored() = routeRestored("bob")

    private fun routeRestored(who: String) {
        val room = awaitValue("$who-paired").getValue("room").jsonPrimitive.content
        val consent = application().linkConsents.all().single { it.roomId == room }
        val opened = AtomicBoolean(false)
        val failure = AtomicReference<String?>(null)
        val socket = application().linkEngine.open(consent.canonicalUrl, consent.routeId, object : RelaySocketListener {
            override fun onOpen() { opened.set(true) }
            override fun onMessage(text: String) = Unit
            override fun onClosed(reason: String) { failure.set(reason) }
        })
        try {
            await("$who's retained Link route after Bothy restart", details = { "failure=${failure.get()}" }) {
                failure.get()?.let { throw AssertionError("$who's retained Link route failed: $it") }
                opened.get()
            }
        } finally { socket.close() }
        put("$who-route-restored", buildJsonObject { put("connected", true) })
    }

    /**
     * P4-01: Bob turns on background delivery and leaves without opening the
     * room. The runner uses `am instrument --no-restart`, so the process and
     * its service outlive this action.
     */
    private fun bobBackgroundArm() {
        val model = model()
        restoreSignIn(model)
        val room = awaitValue("bob-paired").getValue("room").jsonPrimitive.content
        val context = application()
        val settings = dev.forgesworn.kithmoot.service.BackgroundDeliverySettings(context)
        settings.setEnabled(true)
        dev.forgesworn.kithmoot.service.BackgroundCallListenerService.start(context)
        await("Bob's background delivery to be receiving", details = { "state=${settings.state()}" }) {
            settings.state() == dev.forgesworn.kithmoot.service.DeliveryState.LIVE
        }
        assertTrue("the room must not be open", model.stage.value != Stage.ROOM)
        put("bob-background-armed", buildJsonObject { put("room", room); put("state", settings.state().name) })
    }

    private fun aliceBackgroundSend() {
        val index = requireNotNull(arguments.getString("background_index"))
        val body = "$BACKGROUND_MESSAGE $index"
        val model = model()
        restoreSignIn(model)
        val room = awaitValue("alice-paired").getValue("room").jsonPrimitive.content
        open(model, room)
        activity.scenario.onActivity { model.sendChat(body) }
        await("Alice's background message $index", details = {
            "relaysUp=${model.room.value.relaysUp}; sending=${model.room.value.chatSending}; error=${model.room.value.chatSendError}"
        }) { !model.room.value.chatSending && !model.room.value.chatPending && model.room.value.chat.any { it.body == body } }
        put("alice-background-sent-$index", buildJsonObject { put("room", room) })
    }

    /** Bob's exact pending message, retained while the box is paused; the runner then closes the app. */
    private fun bobBackgroundPendingStage() {
        val model = model()
        restoreSignIn(model)
        val room = awaitValue("bob-paired").getValue("room").jsonPrimitive.content
        open(model, room)
        await("Bob's paired room connected before outage") { model.room.value.relaysUp > 0 }
        val saved = requireNotNull(application().savedRooms.get(room))
        val outbox = PendingChatVault(application(), room, saved.participant, saved.devicePubkey).outbox
        assertTrue("Bob must start without an older pending message", runBlocking { outbox.pending() } == null)
        post("pause")
        await("Bob's paired route to disconnect") { model.room.value.relaysUp == 0 }
        activity.scenario.onActivity { model.sendChat(BACKGROUND_PENDING) }
        var retained: dev.forgesworn.kithmoot.session.PendingChatOutbox.Pending? = null
        await("Bob's signed message to reach his encrypted journal") {
            retained = runBlocking { outbox.pending() }
            retained != null
        }
        put("bob-background-pending-staged", buildJsonObject { put("room", room); put("outerEventId", requireNotNull(retained).event.id) })
    }

    private fun aliceBackgroundPendingReceive() {
        val model = model()
        restoreSignIn(model)
        val room = awaitValue("alice-paired").getValue("room").jsonPrimitive.content
        open(model, room)
        await("Bob's background-sent pending message", details = {
            "relaysUp=${model.room.value.relaysUp}; matches=${model.room.value.chat.count { it.body == BACKGROUND_PENDING }}"
        }) { model.room.value.chat.count { it.body == BACKGROUND_PENDING } == 1 }
        SystemClock.sleep(2_000)
        assertEquals(1, model.room.value.chat.count { it.body == BACKGROUND_PENDING })
        put("alice-background-pending-received", buildJsonObject { put("room", room); put("displayedOnce", true) })
    }

    /** Bob finally opens the room: every background message shows once and the inbox reads as read. */
    private fun bobBackgroundOpen() {
        val count = requireNotNull(arguments.getString("background_count")).toInt()
        val model = model()
        restoreSignIn(model)
        val room = awaitValue("bob-paired").getValue("room").jsonPrimitive.content
        val saved = requireNotNull(application().savedRooms.get(room))
        val inbox = dev.forgesworn.kithmoot.storage.BackgroundInboxVault(application(), room, saved.participant, saved.devicePubkey).inbox
        val unreadBefore = inbox.state().unread.size
        open(model, room)
        val bodies = (1..count).map { "$BACKGROUND_MESSAGE $it" }
        await("every background message once", details = {
            bodies.joinToString { body -> "$body=${model.room.value.chat.count { it.body == body }}" }
        }) { bodies.all { body -> model.room.value.chat.count { it.body == body } == 1 } }
        SystemClock.sleep(2_000)
        bodies.forEach { body -> assertEquals(body, 1, model.room.value.chat.count { it.body == body }) }
        await("the background inbox to read as read") { inbox.state().unread.isEmpty() }
        put("bob-background-opened", buildJsonObject {
            put("room", room); put("unreadBeforeOpen", unreadBefore); put("displayedOnce", true)
        })
    }

    private fun application() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as KithMootApplication

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
        val deadline = SystemClock.uptimeMillis() + 120_000
        while (!predicate()) {
            if (SystemClock.uptimeMillis() >= deadline) throw AssertionError("Timed out waiting for $description (${details()})")
            SystemClock.sleep(50)
        }
    }

    private companion object {
        const val ALICE_MESSAGE = "g5 retained message from Alice"
        const val BOB_REPLY = "g5 retained reply from Bob"
        const val PENDING_MESSAGE = "g5 exact pending message after Alice force stop"
        const val BACKGROUND_MESSAGE = "p4 background message"
        const val BACKGROUND_PENDING = "p4 pending message sent while Bob was closed"
    }
}
