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
    private val ui = RecoveryUi(useSwipeFallback = false, freshNodes = true)
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

    @Test fun participant_without_room_owner_key_can_record_and_export_call_audio() {
        val link = dev.forgesworn.kithmoot.protocol.encodeJoinUrl("https://kithmoot.app/j/",
            ByteArray(32) { 63 }, listOf(relay.url))
        activity.scenario.onActivity { model.joinFromUrl(link) }
        ui.room()
        ui.await("member media ready without room ownership") {
            !model.room.value.mediaStarting && model.room.value.recordingAllowed
        }
        assertFalse(model.room.value.meetingModerator)
        recordingRoom = model.room.value.roomId
        ui.click("Start call"); ui.await("member call joined") { model.room.value.onCall }
        ui.click("Record call"); ui.click("Start recording")
        ui.await("member warning confirmed before capture") {
            model.room.value.nativeRecording && !model.room.value.nativeRecordingBusy && model.room.value.recording is RecordingView.On
        }
        assertEquals("audio", model.room.value.recordingCapture?.capture)
        ui.click("Leave room")
        ui.await("originating call and gallery remain while browsing") {
            ui.hasText("Back to the call") && ui.hasDescription("Call gallery") && model.room.value.nativeRecording
        }
        SystemClock.sleep(1200)
        assertTrue(ui.hasText("Hide gallery"))
        assertTrue(ui.hasDescription("Call gallery"))
        assertTrue(model.room.value.onCall)
        ui.click("Hide gallery"); ui.await("explicit gallery collapse") { ui.hasText("Show gallery") }
        ui.click("Show gallery"); ui.await("explicit gallery expansion") { ui.hasText("Hide gallery") }
        ui.click("Back to the call")
        ui.await("returned to original member recording") { !ui.hasText("Back to the call") && model.room.value.nativeRecording }
        ui.click("Stop recording")
        ui.await("member clip retained and own warning stopped") {
            app.recordings.export.value?.isFile == true && !model.room.value.nativeRecordingBusy &&
                model.room.value.recording == RecordingView.Off && !model.room.value.recordingStopPending
        }
        val export = requireNotNull(app.recordings.export.value)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(export.absolutePath)
            assertEquals(1, extractor.trackCount)
            assertEquals("audio/mp4a-latm", extractor.getTrackFormat(0).getString(MediaFormat.KEY_MIME))
            extractor.selectTrack(0)
            var samples = 0
            val packet = java.nio.ByteBuffer.allocate(64 * 1024)
            // Playback edits can give priming access units negative timestamps.
            // End-of-stream is the absence of packet data, not a negative PTS.
            while (true) {
                packet.clear()
                if (extractor.readSampleData(packet, 0) < 0) break
                samples++
                if (!extractor.advance()) break
            }
            assertTrue("The actual AAC export must contain recorded samples", samples > 3)
        } finally { extractor.release() }
        var decodedFrames = 0
        AvRecordingTest().decode(export, MediaFormat.MIMETYPE_AUDIO_AAC, expectedDurationUs = null, expectedTrackCount = 1) { _, _, info ->
            decodedFrames += info.size / 2
        }
        assertTrue("Original participant PCM decodes after priming and padding are removed", decodedFrames > 3 * 1024)
        assertFalse(model.room.value.meetingModerator)
    }

    @Test fun unavailable_save_destination_shows_the_error_in_the_prompt_and_keeps_the_export() {
        val source = app.recordings.begin().apply { writeText("synthetic retained export") }
        val export = app.recordings.complete(source)
        ui.await("retained export offers Save") { ui.hasText("Recording ready") && ui.enabled("Save") }
        activity.scenario.onActivity {
            model.saveRecording(export.name, android.net.Uri.parse(
                "content://dev.forgesworn.kithmoot.missing-test-provider/recording"))
        }
        ui.await("failed Save is visible inside the recording prompt") {
            val error = model.recordingExportError.value
            error != null && ui.hasText(error) && !model.recordingExportBusy.value && ui.enabled("Save")
        }
        assertEquals(export, app.recordings.export.value)
        assertEquals("synthetic retained export", export.readText())
        assertTrue(requireNotNull(model.recordingExportError.value).contains("The local export is still available."))
    }

    @Test fun recording_send_waits_for_schedule_readiness_before_accepting_the_explicit_action() {
        val export = app.recordings.complete(app.recordings.begin().apply { writeText("synthetic retained export") })
        val room = java.util.UUID.randomUUID().toString().replace("-", "").repeat(2)
        recordingRoom = room
        val origin = RecordingOrigin(room, "b".repeat(32), "Synthetic recording")
        val ticket = app.recordingShareDrafts.begin(origin, export.name, null)
        val sealed = dev.forgesworn.kithmoot.session.sealFile(export, ticket.destination, "recording.m4a", "audio/mp4")
        val draft = app.recordingShareDrafts.complete(ticket, sealed)
        val receipt = ChatAttachment("https://private.example/${sealed.hash}", sealed.hash,
            sealed.key, sealed.name, sealed.type, sealed.file.length())
        // Control readiness only: this synthetic receipt claims no upload.
        val busy = mutableStateOf(true)
        val clicks = java.util.concurrent.atomic.AtomicInteger()
        activity.scenario.onActivity { owner -> owner.setContent { KithMootTheme {
            dev.forgesworn.kithmoot.ui.room.ChatPane(messages = emptyList(), selfParticipant = "synthetic",
                onSend = { _, _ -> }, recordingDrafts = listOf(draft.copy(uploaded = receipt)),
                recordingScheduleBusy = busy.value, onSendRecordingDraft = { clicks.incrementAndGet() })
        } } }
        ui.await("Send is shown while the original schedule is loading") { ui.hasText("Send recording") }
        assertFalse("An updating schedule must not accept Send", ui.enabled("Send recording"))
        assertEquals(0, clicks.get())
        activity.scenario.onActivity { busy.value = false }
        ui.await("the same Send becomes available after schedule readiness") { ui.enabled("Send recording") }
        ui.click("Send recording")
        ui.await("one explicit recording action accepted") { clicks.get() == 1 }
        assertEquals(draft, app.recordingShareDrafts.selected(draft.id, room))
        assertTrue(export.isFile)
    }

    @Test fun pending_cancellation_offers_cleanup_only_and_withdrawn_recording_cannot_send() {
        val export = app.recordings.complete(app.recordings.begin().apply { writeText("synthetic retained export") })
        val room = java.util.UUID.randomUUID().toString().replace("-", "").repeat(2)
        recordingRoom = room
        val ticket = app.recordingShareDrafts.begin(RecordingOrigin(room, "b".repeat(32), "Synthetic recording"), export.name, null)
        val sealed = dev.forgesworn.kithmoot.session.sealFile(export, ticket.destination, "recording.m4a", "audio/mp4")
        val draft = app.recordingShareDrafts.complete(ticket, sealed)
        val receipt = ChatAttachment("https://private.example/${sealed.hash}", sealed.hash,
            sealed.key, sealed.name, sealed.type, sealed.file.length())
        // Synthetic receipt and state exercise controls only, not node custody.
        val status = mutableStateOf(dev.forgesworn.kithmoot.cadence.RecordingNodeHandoffState.CANCEL_PENDING)
        val cancellations = java.util.concurrent.atomic.AtomicInteger()
        val sends = java.util.concurrent.atomic.AtomicInteger()
        activity.scenario.onActivity { owner -> owner.setContent { KithMootTheme {
            dev.forgesworn.kithmoot.ui.room.ChatPane(messages = emptyList(), selfParticipant = "synthetic",
                onSend = { _, _ -> }, recordingDrafts = listOf(draft.copy(uploaded = receipt)),
                recordingHandoffStates = mapOf(draft.id to status.value),
                onSendRecordingDraft = { sends.incrementAndGet() },
                onRetryRecordingWithdrawal = { assertEquals(draft.id, it); cancellations.incrementAndGet() })
        } } }
        ui.await("pending custody exposes explicit cancellation retry") { ui.enabled("Retry cancellation") }
        assertFalse(ui.hasText("Send recording")); assertFalse(ui.hasText("Retry Send"))
        ui.click("Retry cancellation")
        ui.await("one explicit cleanup action") { cancellations.get() == 1 }
        assertEquals(0, sends.get())
        activity.scenario.onActivity { status.value = dev.forgesworn.kithmoot.cadence.RecordingNodeHandoffState.WITHDRAWN }
        ui.await("confirmed cancellation retains recording without retrying publication") {
            !ui.hasText("Retry cancellation") && ui.hasText("Recording draft: recording.m4a. Sending cancelled. Your recording is retained.")
        }
        assertFalse(ui.hasText("Send recording")); assertFalse(ui.hasText("Retry Send"))
        assertEquals(0, sends.get()); assertTrue(export.isFile)
        assertEquals(draft, app.recordingShareDrafts.selected(draft.id, room))
    }

    @Test fun local_export_finishes_while_stop_announcement_waits_for_the_relay() {
        ui.click("New room"); ui.replace("Room name (optional)", "Synthetic local export"); ui.click("Start a room"); ui.room()
        ui.await("authority and media ready") { !model.room.value.mediaStarting && model.room.value.meetingModerator }
        ui.click("Start call"); ui.await("original call joined") { model.room.value.onCall }
        recordingRoom = model.room.value.roomId
        ui.click("Record call"); ui.click("Start recording")
        ui.await("signed On and capture installed") { model.room.value.nativeRecording && !model.room.value.nativeRecordingBusy }
        SystemClock.sleep(600)
        relay.acknowledge = false
        ui.click("Stop recording")
        ui.await("local file ready despite unconfirmed relay stop", timeoutMs = 10_000) {
            app.recordings.export.value?.isFile == true && !model.room.value.nativeRecordingBusy &&
                !model.room.value.nativeRecording && model.room.value.recordingStopPending
        }
        assertTrue(ui.hasText("Recording ready"))
        val export = requireNotNull(app.recordings.export.value)
        assertTrue(export.length() > 0)
        // The withheld receipt must not gate the independent local Save dialog.
        ui.await("local Save/Discard available while Off is pending") { ui.enabled("Save") && ui.enabled("Discard") }
        assertTrue(model.room.value.recordingStopPending)
        ui.click("Later")
        // The ordinary acknowledgement timeout ends this fixture's stop attempt;
        // keep the export independent of subsequent exact-stop retry.
        SystemClock.sleep(15_500)
        relay.acknowledge = true
        ui.click("Retry stop notice")
        ui.await("stop confirmed independently") { !model.room.value.recordingStopPending && !model.room.value.recordingStopRetrying }
        assertEquals(export,app.recordings.export.value)
    }

    @Test fun cancel_waiting_announcement_prevents_capture_and_retains_stop_until_confirmed() {
        ui.click("New room"); ui.replace("Room name (optional)", "Synthetic cancelled recorder"); ui.click("Start a room"); ui.room()
        ui.await("authority and media ready") { !model.room.value.mediaStarting && model.room.value.meetingModerator }
        ui.click("Start call")
        ui.await("original call joined") { model.room.value.onCall }
        recordingRoom = model.room.value.roomId
        val saved = requireNotNull(app.savedRooms.get(requireNotNull(recordingRoom)))
        val secret = saved.secret
        val original = try { dev.forgesworn.kithmoot.protocol.deriveRoom(secret) } finally { secret.fill(0) }
        fun memberNotice(event: dev.forgesworn.kithmoot.protocol.NostrEvent): dev.forgesworn.kithmoot.protocol.LiveRecordingState? {
            val entry = dev.forgesworn.kithmoot.protocol.decodeRosterEvent(event, original.roomId,
                original.roomKey, System.currentTimeMillis() / 1000) ?: return null
            val state = entry.liveRecording ?: return null
            return state.takeIf { dev.forgesworn.kithmoot.protocol.verifyParticipantRecordingState(
                original.roomId, it, entry.participant, entry.device, entry.call?.id) }
        }
        try {
            relay.acknowledge = false
            ui.click("Record call"); ui.click("Start recording")
            ui.await("notice wait exposes cancellation before capture") {
                model.room.value.nativeRecordingPreparing && ui.hasText("Cancel recording start")
            }
            assertFalse(model.room.value.nativeRecording)
            // Preparing is exposed before the IO worker arms its notice. This
            // case cancels a published announcement, so observe the actual
            // authenticated On with its acknowledgement withheld first.
            ui.await("relay has withheld the authenticated On acknowledgement") {
                relay.writes.any { event -> event.id in relay.withheldAcknowledgements &&
                    memberNotice(event)?.recording?.notice?.on == true
                }
            }
            assertFalse(model.room.value.nativeRecording)
            ui.click("Cancel recording start")
            ui.await("cancelled start detaches and removes unused source") {
                !model.room.value.nativeRecordingPreparing && !model.room.value.nativeRecording && app.recordings.pending() == null
            }
            ui.await("signed stop retained after cancelled announcement") { model.room.value.recordingStopPending }
            assertNull(app.recordings.export.value)
            ui.await("relay has withheld the exact Off acknowledgement") {
                relay.writes.any { event -> event.id in relay.withheldAcknowledgements &&
                    memberNotice(event)?.recording?.notice?.on == false
                }
            }
            relay.acknowledge = true
            ui.click("Retry stop notice")
            ui.await("explicit retry confirms exact Off") { !model.room.value.recordingStopPending && !model.room.value.recordingStopRetrying }
            assertFalse(model.room.value.nativeRecording)
            val notices = relay.writes.mapNotNull(::memberNotice).filter { !it.recording.notice.on }
            assertTrue("Cancellation publishes an authenticated participant Off", notices.isNotEmpty())
            assertTrue("Off never advertises capture", notices.all { it.capture == null })
            assertEquals("Retries retain one exact device-signed Off", 1, notices.distinct().size)
            assertNull(app.recordings.export.value)
        } finally { relay.acknowledge = true; original.roomKey.fill(0) }
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
            val durations = formats.map(AvRecordingTest()::playbackDurationUs)
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
