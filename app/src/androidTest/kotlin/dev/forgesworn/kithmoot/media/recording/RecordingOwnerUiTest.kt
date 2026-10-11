package dev.forgesworn.kithmoot.media.recording

import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.projects.ProjectTestRelay
import dev.forgesworn.kithmoot.protocol.RecordingView
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.session.ChatAttachment
import dev.forgesworn.kithmoot.session.KIND_CHAT
import dev.forgesworn.kithmoot.storage.RecoveryUi
import dev.forgesworn.kithmoot.ui.RoomViewModel
import dev.forgesworn.kithmoot.ui.Stage
import dev.forgesworn.kithmoot.ui.KithMootApp
import dev.forgesworn.kithmoot.ui.theme.KithMootTheme
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
        // A real Add completion must remain pending in each restricted call
        // view, then navigate exactly once when that same event is allowed.
        val restricted = mutableStateOf(Triple(true, false, false))
        var openedChats = 0
        activity.scenario.onActivity { owner ->
            owner.setContent { KithMootTheme {
                val (locked, pip, answering) = restricted.value
                KithMootApp(model, lockedCallOnly = locked, inPictureInPicture = pip,
                    callAnswering = answering, onOpenRecordingChat = { room ->
                        assertEquals(originalRoom, room)
                        openedChats++
                        model.openNotificationRoom(room)
                    })
            } }
        }
        for (mode in listOf(Triple(true, false, false), Triple(false, true, false), Triple(false, false, true))) {
            activity.scenario.onActivity { restricted.value = mode }
            SystemClock.sleep(250)
            activity.scenario.onActivity { model.addRecordingToOriginalChat(file.name) }
            ui.await("late Add completed in restricted call view") {
                model.recordingAdded.value != null && !model.recordingExportBusy.value
            }
            val pending = requireNotNull(model.recordingAdded.value)
            val before = openedChats
            SystemClock.sleep(350)
            assertEquals("Restricted call must not navigate", before, openedChats)
            assertEquals("Restricted call must not acknowledge the pending Add", pending, model.recordingAdded.value)
            assertFalse("Restricted call must not show the recording prompt", ui.hasText("Add to original chat"))
            activity.scenario.onActivity { restricted.value = Triple(false, false, false) }
            ui.await("deferred original-chat navigation") { model.recordingAdded.value == null && openedChats == before + 1 }
            activity.scenario.onActivity { restricted.value = mode }
            activity.scenario.onActivity { restricted.value = Triple(false, false, false) }
            SystemClock.sleep(250)
            assertEquals("Acknowledged Add must not navigate twice", before + 1, openedChats)
        }
        assertEquals(3, openedChats)
        assertEquals("Repeated Add must reuse the same encrypted draft", listOf(draft), app.recordingShareDrafts.list(originalRoom))
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
        // Seed a verified Upload receipt: this qualifies explicit UI Send over
        // the real loopback relay, not UI-driven HTTPS transfer to a node.
        val storage = "https://private.example"
        val receipt = ChatAttachment("$storage/${draft.sealed.hash}", draft.sealed.hash, draft.sealed.key,
            draft.sealed.name, draft.sealed.type, draft.sealed.file.length())
        val upload = app.recordingUploadJournal.begin(originalRoom, storage, draft.sealed.hash, draft.discardAt)
        app.recordingShareDrafts.bindOrigin(draft.id, originalRoom, storage)
        app.recordingShareDrafts.retainUpload(draft.id, originalRoom, storage, receipt)
        assertTrue(app.recordingUploadJournal.finish(upload, true))
        val beforeSend = relay.writes.filter { it.kind == KIND_CHAT }.map { it.id }.toSet()
        ui.await("separate explicit Send") { ui.hasText("Send recording") }
        assertTrue("Upload receipt must not send chat", model.room.value.chat.none { receipt in it.attachments })
        ui.click("Send recording")
        ui.await("original chat receives recording and exact handoff removes only its draft") {
            model.room.value.chat.any { receipt in it.attachments } && app.recordingShareDrafts.list(originalRoom).isEmpty() &&
                !model.room.value.chatSending && !model.room.value.mediaBusy
        }
        val sent = model.room.value.chat.single { receipt in it.attachments }
        assertEquals(listOf(receipt), sent.attachments)
        val newEvents = relay.writes.filter { it.kind == KIND_CHAT && it.id !in beforeSend }.distinctBy { it.id }
        assertEquals("One signed recording message reached the loopback relay", 1, newEvents.size)
        assertTrue(Events.verify(newEvents.single()))
        assertEquals(originalRoom, model.room.value.roomId)
        assertEquals(file, app.recordings.export.value)
        assertTrue("Send preserves independent local Save/Discard", file.exists())
        assertTrue("Send must not schedule remote deletion", app.recordingUploadJournal.readyToSend(originalRoom, storage, draft.sealed.hash))
        ui.click("Recording ready")
        ui.click("Add to original chat")
        ui.await("explicit new Add retains an independent draft") { app.recordingShareDrafts.list(originalRoom).size == 1 && ui.hasText("Remove draft") }
        val secondDraft = app.recordingShareDrafts.list(originalRoom).single()
        assertNotEquals(draft.id, secondDraft.id)
        assertNotEquals(draft.sealed.key, secondDraft.sealed.key)
        assertNull(secondDraft.uploaded)
        assertEquals("Add sends no further message", newEvents.map { it.id }.toSet(),
            relay.writes.filter { it.kind == KIND_CHAT && it.id !in beforeSend }.map { it.id }.toSet())
        ui.click("Recording ready")
        ui.click("Discard")
        ui.await("explicit discard") { app.recordings.export.value == null }
        assertFalse(file.exists())
        assertEquals("Discard affects only the local export", secondDraft, app.recordingShareDrafts.list(originalRoom).single())
        assertTrue(secondDraft.sealed.file.exists())
        ui.click("Remove draft")
        ui.await("explicit draft removal") { app.recordingShareDrafts.list(originalRoom).isEmpty() }
        assertFalse(secondDraft.sealed.file.exists())
    }
}
