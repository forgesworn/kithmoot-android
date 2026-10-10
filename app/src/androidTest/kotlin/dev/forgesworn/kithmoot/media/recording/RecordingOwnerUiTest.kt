package dev.forgesworn.kithmoot.media.recording

import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.projects.ProjectTestRelay
import dev.forgesworn.kithmoot.protocol.RecordingView
import dev.forgesworn.kithmoot.storage.RecoveryUi
import dev.forgesworn.kithmoot.ui.RoomViewModel
import dev.forgesworn.kithmoot.ui.Stage
import org.junit.*
import org.junit.Assert.*

/** Real app owner/Compose and loopback signed notices. This deliberately uses
 * no camera/mic or public account; native coloured inputs are qualified by the
 * scene suite. No system document-provider or physical-phone claim is made. */
class RecordingOwnerUiTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    private val ui = RecoveryUi(useSwipeFallback = false)
    private val app get() = ApplicationProvider.getApplicationContext<KithMootApplication>()
    private lateinit var model: RoomViewModel
    private lateinit var relay: ProjectTestRelay
    private var recordingRoom: String? = null

    @Before fun setup() {
        Assume.assumeTrue("Disposable emulator only", android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        relay = ProjectTestRelay()
        activity.scenario.onActivity { model = ViewModelProvider(it)[RoomViewModel::class.java] }
        ui.home()
        if (app.accounts.load() != null) {
            ui.await("saved account") { model.start.value.account != null }
            activity.scenario.onActivity { model.signOut() }
            ui.await("signed out") { model.start.value.account == null }
        }
        app.recordings.export.value?.let { app.recordings.discard(it) }
        app.savedRooms.reset()
        activity.scenario.onActivity {
            model.onRelaysChanged(relay.url)
            model.installLocalTestAccount(ByteArray(32) { 57 }, emulateExternalSigner = true)
        }
        ui.await("synthetic loopback account") { model.start.value.account != null && model.start.value.roomBookmarks.ready }
    }

    @After fun cleanup() {
        if (::model.isInitialized) {
            activity.scenario.moveToState(Lifecycle.State.RESUMED)
            activity.scenario.onActivity { model.stopNativeRecording() }
            ui.await("capture finalisation") { !model.room.value.nativeRecordingBusy }
            app.recordings.export.value?.let { app.recordings.discard(it) }
            recordingRoom?.let { app.recordingShareDrafts.forgetRoom(it) }
            if (model.stage.value == Stage.ROOM) {
                activity.scenario.onActivity { model.leave() }
                ui.await("room closed") { model.stage.value == Stage.START && !model.start.value.busy }
            }
            activity.scenario.onActivity { model.signOut() }
            ui.await("synthetic account closed") { model.start.value.account == null }
        }
        if (::relay.isInitialized) relay.close()
    }

    @Test fun gallery_controls_keep_original_origin_pause_while_hidden_and_retain_a_two_track_mp4() {
        ui.click("New room")
        ui.replace("Room name (optional)", "Synthetic recording owner")
        ui.click("Start a room")
        ui.room()
        ui.await("media ready and authority") { !model.room.value.mediaStarting && model.room.value.meetingModerator }
        ui.click("Start call")
        ui.await("video options on original call") { model.room.value.onCall && model.room.value.recordingVideoDevices.isNotEmpty() && model.room.value.recordingVideoSupported }
        val originalRoom = model.room.value.roomId
        recordingRoom = originalRoom
        ui.click("Record call")
        ui.click("Gallery with audio")
        ui.await("gallery radio selection") { ui.checked("Gallery with audio") }
        ui.click("Start recording")
        ui.await("signed running notice and local capture") { model.room.value.nativeRecording && !model.room.value.nativeRecordingBusy && model.room.value.recording is RecordingView.On }
        assertEquals("gallery", model.room.value.recordingCapture?.capture)
        SystemClock.sleep(600)
        activity.scenario.moveToState(Lifecycle.State.CREATED)
        try {
            ui.await("hidden app pauses original video capture") {
                model.room.value.nativeRecordingPaused && !model.room.value.nativeRecordingBusy
            }
        } catch (failure: AssertionError) {
            val state = model.room.value
            throw AssertionError("Background recording state: active=${state.nativeRecording}, " +
                "paused=${state.nativeRecordingPaused}, busy=${state.nativeRecordingBusy}, " +
                "onCall=${state.onCall}, notice=${state.notice}", failure)
        }
        SystemClock.sleep(1200)
        activity.scenario.moveToState(Lifecycle.State.RESUMED)
        ui.await("returned recording remains paused") { ui.hasText("Resume recording") }
        assertTrue(model.room.value.nativeRecordingPaused)
        assertTrue(model.room.value.recording is RecordingView.On)
        ui.click("Resume recording")
        ui.await("explicit resume") { !model.room.value.nativeRecordingPaused && !model.room.value.nativeRecordingBusy }
        SystemClock.sleep(600)
        ui.click("Stop recording")
        ui.await("retained video export and confirmed stop") { app.recordings.export.value != null && model.room.value.recording == RecordingView.Off && !model.room.value.nativeRecordingBusy }
        val file = requireNotNull(app.recordings.export.value)
        val details = app.recordings.details(file)
        assertEquals(RecordingFormat.VIDEO, details.format)
        assertEquals("mp4", file.extension)
        assertEquals(originalRoom, details.origin?.room)
        assertEquals("Synthetic recording owner", details.origin?.name)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            assertEquals(2, extractor.trackCount)
            val formats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
            assertEquals(setOf("audio/mp4a-latm", "video/avc"), formats.map { it.getString(MediaFormat.KEY_MIME) }.toSet())
            val durations = formats.map { it.getLong(MediaFormat.KEY_DURATION) }
            assertTrue(durations.all { it > 0 })
            assertTrue(kotlin.math.abs(durations[0] - durations[1]) <= 999)
        } finally { extractor.release() }
        ui.click("Add to original chat")
        ui.await("encrypted draft in original chat") {
            app.recordingShareDrafts.list(originalRoom).size == 1 && ui.hasText("Remove draft")
        }
        val draft = app.recordingShareDrafts.list(originalRoom).single()
        assertEquals(details.origin, draft.origin)
        assertEquals(file.name, draft.sourceName)
        assertEquals(details.discardAt, draft.discardAt)
        assertNull("Add must leave the storage server unselected", draft.storageOrigin)
        assertNull("Add must not upload", draft.uploaded)
        assertTrue(draft.sealed.file.isFile)
        assertTrue(draft.sealed.file.canonicalPath.startsWith(app.noBackupFilesDir.canonicalPath + "/"))
        assertEquals(file, app.recordings.export.value)
        assertTrue(file.exists())
        ui.click("Upload recording")
        ui.await("explicit storage setup") { ui.hasText("Recording storage server") }
        assertNull(model.room.value.recordingStorageChoice)
        ui.replace("Recording storage server", "https://private.example")
        ui.click("Get storage key")
        ui.await("public storage identity, no automatic upload") { ui.hasText("Copy storage key") && ui.hasText("Upload") }
        val choice = requireNotNull(model.room.value.recordingStorageChoice)
        assertEquals(draft.id, choice.draft)
        assertEquals(originalRoom, choice.room)
        assertEquals("https://private.example", choice.origin)
        assertTrue(choice.publicKey.matches(Regex("[0-9a-f]{64}")))
        assertFalse("Upload needs separate consent", ui.enabled("Upload"))
        assertNull(app.recordingShareDrafts.selected(draft.id, originalRoom).storageOrigin)
        assertNull(app.recordingShareDrafts.selected(draft.id, originalRoom).uploaded)
        ui.click("Copy storage key")
        activity.scenario.onActivity {
            val clipboard = it.getSystemService(android.content.ClipboardManager::class.java)
            assertEquals(choice.publicKey, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        }
        ui.click("Cancel")
        ui.click("Recording ready")
        ui.click("Discard")
        ui.await("explicit discard") { app.recordings.export.value == null }
        assertFalse(file.exists())
        assertEquals("Discard affects only the local export", draft, app.recordingShareDrafts.list(originalRoom).single())
        assertTrue(draft.sealed.file.exists())
        ui.click("Remove draft")
        ui.await("explicit draft removal") { app.recordingShareDrafts.list(originalRoom).isEmpty() }
        assertFalse(draft.sealed.file.exists())
    }
}
