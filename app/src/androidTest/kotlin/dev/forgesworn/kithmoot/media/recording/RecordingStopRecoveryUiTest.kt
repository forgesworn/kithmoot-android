package dev.forgesworn.kithmoot.media.recording

import android.os.Process
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.projects.ProjectTestRelay
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.storage.RecoveryUi
import dev.forgesworn.kithmoot.ui.RoomViewModel
import dev.forgesworn.kithmoot.ui.Stage
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.net.URI
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest

/** Explicit prepare/force-stop/verify driver. Production MainActivity,
 * saved-room/account restoration, local export and actual Retry stop UI.
 * Synthetic account and loopback relay; no public account or physical claim. */
class RecordingStopRecoveryUiTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    private val app get() = ApplicationProvider.getApplicationContext<KithMootApplication>()
    private val ui = RecoveryUi(useSwipeFallback = false, freshNodes = true)
    private lateinit var model: RoomViewModel
    private var relay: ProjectTestRelay? = null

    @After fun closeRelay() { relay?.close() }

    @Test fun saved_room_reopens_and_retries_exact_stop_after_process_death() {
        Assume.assumeTrue("Disposable emulator only", android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val args = InstrumentationRegistry.getArguments()
        val name = args.getString("recordingStopRecoveryFixture")
        Assume.assumeTrue("Run guarded stop recovery driver", name?.matches(Regex("recording-stop-ui-[0-9a-f]{12}")) == true)
        val stage = args.getString("recordingStopRecoveryStage")
        require(stage in setOf("prepare", "verify", "cleanup"))
        // As LeaveCallTest grants, up front and before any UI: Start call asks
        // for the microphone before joining, and an unanswered sheet hangs the
        // call-joined wait. The grant holds across the staged process death.
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(InstrumentationRegistry.getInstrumentation().targetContext.packageName, android.Manifest.permission.RECORD_AUDIO)
        val title = "Synthetic stop ${requireNotNull(name).takeLast(12)}"
        val marker = File(app.noBackupFilesDir, "$name.json")
        activity.scenario.onActivity { model = ViewModelProvider(it)[RoomViewModel::class.java] }
        if (stage == "cleanup") {
            if (marker.isFile) {
                val saved = JSONObject(marker.readText())
                app.forgetRecordingsForRoom(saved.getString("room"))
                app.savedRooms.get(saved.getString("room"))?.let { app.savedRooms.forget(it.id) }
                check(marker.delete())
            }
            return
        }
        // A saved export may reopen its own dialog before the start screen.
        ui.await("start or retained export") { ui.hasText("Chats") || ui.hasText("Recording ready") }
        if (ui.hasText("Recording ready")) ui.click("Later")
        ui.home()
        if (stage == "prepare") {
            if (app.accounts.load() != null) {
                val retained = requireNotNull(app.accounts.load())
                check(retained.method == "local" && retained.pubkey in listOf(57, 59).map {
                    Schnorr.publicKeyHex(ByteArray(32) { _ -> it.toByte() })
                }) { "Stop recovery fixture refuses an unrelated retained account" }
                ui.await("existing synthetic account restored") { model.start.value.account != null }
                activity.scenario.onActivity { model.signOut() }
                ui.await("existing account closed") { model.start.value.account == null }
            }
            app.recordings.export.value?.let(app.recordings::discard)
            app.savedRooms.reset()
            // MockWebServer enables reuse only for a nonzero requested port.
            // Both processes need it, including the first listener's sockets.
            val port = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { it.localPort }
            val wire = ProjectTestRelay(port).also { relay = it }
            activity.scenario.onActivity {
                model.onRelaysChanged(wire.url)
                model.installLocalTestAccount(ByteArray(32) { 57 }, emulateExternalSigner = true)
            }
            ui.await("synthetic account ready") { model.start.value.account != null && model.start.value.roomBookmarks.ready }
            ui.newRoom(); ui.replace("Room name (optional)", title); ui.click("Start a room"); ui.room()
            ui.await("authority and media ready") { model.room.value.meetingModerator && !model.room.value.mediaStarting }
            ui.click("Start call"); ui.await("call joined") { model.room.value.onCall }
            ui.click("More"); ui.click("Record call…"); ui.click("Start recording")
            ui.await("confirmed On, local capture and rendered Stop control") {
                model.room.value.nativeRecording && !model.room.value.nativeRecordingBusy &&
                    !model.room.value.nativeRecordingPreparing && ui.enabled("Stop recording")
            }
            val roomId = model.room.value.roomId
            val saved = requireNotNull(app.savedRooms.get(roomId))
            val off = requireNotNull(app.recordingStops.pending(roomId, saved.devicePubkey, saved.devicePubkey))
            assertFalse(off.notice.on)
            SystemClock.sleep(600)
            wire.acknowledge = false
            ui.click("Stop recording")
            ui.await("local export with retained unconfirmed stop") {
                !model.room.value.nativeRecording && !model.room.value.nativeRecordingBusy &&
                    model.room.value.recordingStopPending && app.recordings.export.value?.isFile == true
            }
            val export = requireNotNull(app.recordings.export.value)
            ui.click("Later")
            SystemClock.sleep(15_500)
            assertTrue(ui.enabled("Retry stop notice"))
            assertEquals(off, app.recordingStops.pending(roomId, saved.devicePubkey, saved.devicePubkey))
            marker.writeText(JSONObject().put("pid", Process.myPid()).put("room", roomId)
                .put("port", URI(wire.url).port).put("off", encodeRecordingOp(off))
                .put("export", export.name).put("exportSha256", digest(export))
                .put("events", JSONArray(wire.events.map { it.toJson().toString() })).toString())
        } else {
            val before = JSONObject(marker.readText())
            assertNotEquals(before.getInt("pid"), Process.myPid())
            val roomId = before.getString("room")
            val saved = requireNotNull(app.savedRooms.get(roomId))
            val expected = requireNotNull(decodeRecordingOp(before.getString("off")))
            assertEquals(expected, app.recordingStops.pending(roomId, saved.devicePubkey, saved.devicePubkey))
            val export = requireNotNull(app.recordings.export.value)
            assertEquals(before.getString("export"), export.name)
            assertEquals(before.getString("exportSha256"), digest(export))
            val wire = ProjectTestRelay(before.getInt("port")).also { relay = it }
            val events = before.getJSONArray("events")
            for (i in 0 until events.length()) wire.publish(NostrEvent.fromJson(Json.parseToJsonElement(events.getString(i))))
            ui.await("account restored with saved room") { model.start.value.account != null && !model.start.value.loadingRooms }
            ui.click(title); ui.room()
            assertEquals("Open the saved original room, not another bookmark", roomId, model.room.value.roomId)
            ui.await("retained stop shown after saved-room reopening") { model.room.value.recordingStopPending && ui.enabled("Retry stop notice") }
            assertFalse(model.room.value.nativeRecording)
            ui.click("Retry stop notice")
            ui.await("explicit stop retry confirmed") { !model.room.value.recordingStopPending && !model.room.value.recordingStopRetrying }
            val secret = saved.secret
            val original = try { deriveRoom(secret) } finally { secret.fill(0) }
            try {
                val notices = wire.writes.mapNotNull { event ->
                    decodeRosterEvent(event, original.roomId, original.roomKey, System.currentTimeMillis() / 1000)?.let { entry ->
                        entry.liveRecording?.takeIf { state -> verifyParticipantRecordingState(
                            original.roomId, state, entry.participant, entry.device, entry.call?.id) }?.recording
                    }
                }
                assertEquals(listOf(expected), notices.distinct())
                assertTrue(wire.writes.all(Events::verify))
                assertTrue(verifyRecordingNotice(roomId, expected.notice, expected.sig, saved.devicePubkey))
                assertNull(app.recordingStops.pending(roomId, saved.devicePubkey, saved.devicePubkey))
                assertEquals(export, app.recordings.export.value)
                assertEquals(before.getString("exportSha256"), digest(export))
            } finally { original.roomKey.fill(0) }
            activity.scenario.onActivity { model.leave() }
            ui.await("recovered room closed") { model.stage.value == Stage.START && !model.start.value.busy }
            activity.scenario.onActivity { model.signOut() }
            ui.await("recovered account signed out") { model.start.value.account == null }
        }
    }

    private fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }
}
