package dev.forgesworn.kithmoot.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.statusBarsPadding

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription

import android.Manifest
import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.forgesworn.kithmoot.ui.room.AddDeviceSheet
import dev.forgesworn.kithmoot.ui.room.ChatPane
import dev.forgesworn.kithmoot.ui.room.ContactCardsSheet
import dev.forgesworn.kithmoot.ui.room.RoomScreen
import dev.forgesworn.kithmoot.ui.start.ProjectsScreen
import dev.forgesworn.kithmoot.ui.start.SettingsScreen
import dev.forgesworn.kithmoot.ui.start.SignInSheet
import dev.forgesworn.kithmoot.ui.start.StartScreen
import kotlinx.coroutines.launch

/** Which page home shows: the rooms list, or one of the two full-screen
 *  pages reached from it (design-home-rooms.md section 4). */
/** How often the foreground tick runs: the restore witness's retiring duty, and its fresh read of the banner (D1 C2). */
private const val RETIRING_DUTY_INTERVAL_MILLIS = 15 * 60 * 1000L

/** How often VMLS rooms are driven while the app is in the foreground (P3-03b-3; the room screens tighten it). */
private const val VMLS_ROUND_INTERVAL_MILLIS = 20 * 1000L

/** While a VMLS room is on screen its rounds run this often instead. */
private const val VMLS_ROOM_ROUND_INTERVAL_MILLIS = 3 * 1000L

/** Home's pages. ROOMS, PEOPLE and BOARDS are the list-level destinations
 *  (Chats, People, Boards; design-calm-shell section 5.1); the rest are pushed
 *  over them, and back returns to Chats. */
enum class HomePage { ROOMS, PEOPLE, BOARDS, SETTINGS, PROJECTS, RESTORE_WITNESS, VMLS_BOXES, VMLS_ROOM }

/** The list-level destination a home page is, or null for a pushed page. */
internal fun HomePage.destination(): dev.forgesworn.kithmoot.ui.start.Destination? = when (this) {
    HomePage.ROOMS -> dev.forgesworn.kithmoot.ui.start.Destination.CHATS
    HomePage.PEOPLE -> dev.forgesworn.kithmoot.ui.start.Destination.PEOPLE
    HomePage.BOARDS -> dev.forgesworn.kithmoot.ui.start.Destination.BOARDS
    else -> null
}

internal fun dev.forgesworn.kithmoot.ui.start.Destination.homePage(): HomePage = when (this) {
    dev.forgesworn.kithmoot.ui.start.Destination.CHATS -> HomePage.ROOMS
    dev.forgesworn.kithmoot.ui.start.Destination.PEOPLE -> HomePage.PEOPLE
    dev.forgesworn.kithmoot.ui.start.Destination.BOARDS -> HomePage.BOARDS
}

/**
 * The whole application: two screens, two sheets, and the permission asks.
 *
 * Every permission is requested at the moment the thing it is for is asked for,
 * with a sentence saying why, and never at launch. A microphone permission
 * granted at first run to an application that has not yet joined anything is a
 * permission granted for no stated reason, which is how people end up with
 * applications they do not trust.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun KithMootApp(
    model: RoomViewModel,
    inPictureInPicture: Boolean = false,
    onPopOut: (() -> Unit)? = null,
    /** Who the account belongs to: the call's instance, when `model` is the
     *  chat-only one beside it. */
    accountModel: RoomViewModel = model,
    /** The call, docked above a chat-only room. See MainActivity. */
    dock: (@Composable () -> Unit)? = null,
    /** The docked call's room, which opens by going back to the call. */
    callRoomId: String? = null,
    onBackToCall: () -> Unit = {},
    /** The room's back arrow while on its call: to the rooms, call kept. */
    onRoomsKeepingCall: (() -> Unit)? = null,
    onWorkspaceOriginKeepingCall: ((dev.forgesworn.kithmoot.session.WorkspaceOrigin) -> Unit)? = null,
    onWorkspaceProjectsKeepingCall: (() -> Unit)? = null,
    /** MainActivity keeps an existing call while opening the recording's chat. */
    onOpenRecordingChat: ((String) -> Unit)? = null,
    /** A call answered on a phone still locked: its call view and nothing
     *  else - no rooms list, chat or settings - until the phone is unlocked. */
    lockedCallOnly: Boolean = false,
    onUnlock: () -> Unit = {},
    /** An answered call is opening or joining, over the lock screen or not: no prompt may cover it. */
    callAnswering: Boolean = false,
) {
    val destructSequence by model.destructEffect.collectAsState()
    var showingDestruct by remember { mutableStateOf(false) }
    LaunchedEffect(destructSequence) { if (destructSequence > 0) showingDestruct = true }
    val stage by model.stage.collectAsState()
    val startState by model.start.collectAsState()
    // ⋮ › Notifications in a room and a row's Notifications in Chats: one sheet, one set of actions.
    val roomNotify = remember(model) {
        dev.forgesworn.kithmoot.ui.room.RoomNotifyActions(model::roomNotifyMode, model::setRoomNotifyMode, model::muteRoom, model::unmuteRoom)
    }
    val guestAdmission by model.guestAdmission.collectAsState()
    val actualRoomState by model.room.collectAsState()
    val recordingModel = accountModel
    val recordingExport by recordingModel.recordingExport.collectAsState()
    val recordingExportFailed by recordingModel.recordingExportFailed.collectAsState()
    val recordingExportBusy by recordingModel.recordingExportBusy.collectAsState()
    val recordingExportError by recordingModel.recordingExportError.collectAsState()
    val recordingAdded by recordingModel.recordingAdded.collectAsState()
    val recordingDetails = recordingExport?.let { runCatching { recordingModel.recordingExportDetails(it) }.getOrNull() }
    val recordingFormat = recordingDetails?.format ?: dev.forgesworn.kithmoot.media.recording.RecordingFormat.AUDIO
    var savingRecordingName by rememberSaveable { mutableStateOf<String?>(null) }
    var deferredRecordingName by rememberSaveable { mutableStateOf<String?>(null) }
    val recordingPromptAllowed = !lockedCallOnly && !inPictureInPicture && !callAnswering
    if (recordingExportFailed && recordingPromptAllowed) {
        AlertDialog(
            onDismissRequest = recordingModel::dismissRecordingExportFailure,
            title = { Text("Recording not saved") },
            text = { Text("This recording could not be finalised. Start another recording when you are ready.") },
            confirmButton = { TextButton(onClick = recordingModel::dismissRecordingExportFailure) { Text("Close") } },
        )
    }
    LaunchedEffect(recordingAdded, recordingPromptAllowed) {
        val added = recordingAdded ?: return@LaunchedEffect
        // Add can finish after the call view becomes restricted. Keep the
        // pending navigation until that view permits chat, including when the
        // event itself remains unchanged across unlock or leaving PiP.
        if (!recordingPromptAllowed) return@LaunchedEffect
        if (recordingExport?.name == added.sourceName) {
            deferredRecordingName = added.sourceName
            if (onOpenRecordingChat != null) onOpenRecordingChat(added.room) else model.openNotificationRoom(added.room)
        }
        recordingModel.acknowledgeRecordingAdded(added.request)
    }
    val saveRecording = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(recordingFormat.mime)) { uri ->
        val original = savingRecordingName
        savingRecordingName = null
        if (uri != null && original != null) recordingModel.saveRecording(original, uri)
    }
    if (recordingExport != null && deferredRecordingName != recordingExport?.name && recordingPromptAllowed) {
        AlertDialog(
            onDismissRequest = { if (!recordingExportBusy) deferredRecordingName = recordingExport?.name },
            title = { Text("Recording ready") },
            text = { Column {
                Text("Kept privately on this device. Save a copy, add an encrypted draft to the original chat, or discard this local export. Add uploads nothing and sends no message. Saved copies remain until you remove them." +
                    if (recordingDetails?.discardAt != null) " Unsaved copies follow the original room's self-destruct settings." else "")
                recordingExportError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = {
                Column {
                    TextButton(enabled = !recordingExportBusy && recordingDetails?.origin != null, onClick = {
                        recordingExport?.name?.let(recordingModel::addRecordingToOriginalChat)
                    }) { Text("Add to original chat") }
                    TextButton(enabled = !recordingExportBusy && recordingDetails != null, onClick = {
                        savingRecordingName = recordingExport?.name
                        saveRecording.launch("KithMoot-call-${java.time.LocalDate.now()}.${recordingFormat.extension}")
                    }) { Text(if (recordingExportBusy) "Working…" else "Save") }
                }
            },
            dismissButton = { Column {
                TextButton(enabled = !recordingExportBusy, onClick = { deferredRecordingName = recordingExport?.name }) { Text("Later") }
                TextButton(enabled = !recordingExportBusy, onClick = recordingModel::discardRecordingExport) { Text("Discard") }
            } },
        )
    }
    val workspaceSnapshot by accountModel.workspace.collectAsState()
    val workspaceAccountState by accountModel.start.collectAsState()
    val roomState = actualRoomState.copy(projectBrand = dev.forgesworn.kithmoot.ui.room.currentRoomProjectBrand(actualRoomState, workspaceAccountState))
    val workspaceOrigin by model.workspaceOrigin.collectAsState()
    val workspaceProjectsRequest by model.workspaceProjectsRequest.collectAsState()
    val videos by model.videos.collectAsState()

    // System back inside a room does what the room's own back arrow does,
    // rather than sending the app to the background: on Android 12 and
    // later a root activity is moved back rather than finished, so nothing
    // was lost, but back did not go up a level (design-home-rooms.md Q11).
    val roomBack = { if (roomState.onCall && onRoomsKeepingCall != null) onRoomsKeepingCall() else model.leave() }
    androidx.activity.compose.BackHandler(enabled = stage == Stage.ROOM && !inPictureInPicture, onBack = roomBack)
    // Locked: back goes nowhere the lock screen should be covering.
    androidx.activity.compose.BackHandler(enabled = lockedCallOnly) { }

    // Settings and Projects are pushed over home; back returns to the rooms
    // list rather than leaving the app (design-home-rooms.md section 7).
    var homePage by rememberSaveable { mutableStateOf(HomePage.ROOMS) }
    LaunchedEffect(workspaceProjectsRequest) { if (workspaceProjectsRequest > 0) homePage = HomePage.PROJECTS }
    val workspaceLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val anonymousWorkspace = roomState.anonymous || stage == Stage.START && startState.anonymousMode
    // The destinations on offer: People only with a Nostr sign-in (Q14), and
    // nothing but Chats in a Tor-only profile. A destination that stops being
    // offered (signing out on People) falls back to Chats.
    val shellDestinations = dev.forgesworn.kithmoot.ui.start.destinationsFor(startState.account != null, anonymousWorkspace)
    LaunchedEffect(shellDestinations, homePage) {
        val current = homePage.destination()
        if (current != null && current !in shellDestinations) homePage = HomePage.ROOMS
    }
    // Boards is today's workspace (Needs you and All) as a destination; its
    // readers run only while it is on screen, as they did behind the old dialog.
    val boardsOpen = stage == Stage.START && homePage == HomePage.BOARDS
    LaunchedEffect(boardsOpen, accountModel, startState.account?.pubkey, lockedCallOnly, anonymousWorkspace, inPictureInPicture) {
        if (!boardsOpen || lockedCallOnly || anonymousWorkspace || inPictureInPicture) {
            accountModel.closeWorkspaceActivity()
            return@LaunchedEffect
        }
        workspaceLifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            accountModel.openWorkspaceActivity()
            try { kotlinx.coroutines.awaitCancellation() } finally { accountModel.closeWorkspaceActivity() }
        }
    }
    var projectRoomToAdd by rememberSaveable { mutableStateOf<String?>(null) }
    // Restore witness opens from Settings or from its banner; back returns there.
    var witnessFrom by rememberSaveable { mutableStateOf(HomePage.SETTINGS) }
    androidx.activity.compose.BackHandler(enabled = stage == Stage.START && homePage != HomePage.ROOMS) {
        homePage = when (homePage) {
            HomePage.RESTORE_WITNESS -> witnessFrom
            HomePage.VMLS_BOXES -> HomePage.SETTINGS
            else -> HomePage.ROOMS
        }
    }
    // Which page of Settings is open, kept here so it survives the developer pages and rotation;
    // leaving Settings for the rooms list starts it at the list next time.
    var settingsPage by rememberSaveable { mutableStateOf(dev.forgesworn.kithmoot.ui.settings.SettingsPage.ROOT) }
    val fileChoiceActions = remember(model) {
        dev.forgesworn.kithmoot.ui.room.FileChoiceActions(model::choosePublicFileServer, model::chooseOwnFileServer, model::chooseNoFileUploads)
    }
    androidx.compose.runtime.LaunchedEffect(homePage) {
        if (homePage.destination() != null) settingsPage = dev.forgesworn.kithmoot.ui.settings.SettingsPage.ROOT
    }
    var signInSheetOpen by remember { mutableStateOf(false) }
    val homeCoroutines = rememberCoroutineScope()

    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle, model, stage) {
        fun apply() {
            val resumed = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
            model.notificationForeground(resumed)
            // Ringing is redundant for a call in the room already on screen;
            // see IncomingCallRingCoordinator.foreground.
            model.setCallRingForeground(resumed && stage == Stage.ROOM)
        }
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ -> apply() }
        lifecycle.addObserver(observer)
        apply()
        onDispose { lifecycle.removeObserver(observer); model.notificationForeground(false); model.setCallRingForeground(false) }
    }
    val context = LocalContext.current
    if (stage == Stage.START) guestAdmission?.let { guest ->
        GuestAdmissionDialog(guest, startState.busy, devicesAvailable = callRoomId == null,
            onName = model::onGuestAdmissionNameChanged, onRequest = model::requestGuestAdmission,
            onCancel = model::cancelGuestAdmission, onClose = model::closeGuestAdmission,
            onRetry = model::retryGuestAdmission,
            projectBrand = if (startState.anonymousMode || startState.account?.pubkey != workspaceAccountState.account?.pubkey) null
                else dev.forgesworn.kithmoot.ui.room.currentInvitationProjectBrand(startState.joinUrl, workspaceAccountState,
                    guest.projectInvitation?.project)?.takeIf { brand -> guest.projectInvitation?.let {
                        it.account == workspaceAccountState.account?.pubkey && it.project == brand.reference && it.room == brand.room
                    } != false })
    }
    var nearbyInviteRequest by remember { mutableStateOf<Triple<String, String, dev.forgesworn.kithmoot.relay.RoomRoute>?>(null) }
    var nearbyCreateRequest by remember { mutableStateOf<dev.forgesworn.kithmoot.relay.RoomRoute?>(null) }
    var nearbyRoomRequest by rememberSaveable { mutableStateOf<String?>(null) }
    val nearbyPermissions = remember { arrayOf(Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT) }
    val nearbyPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val create = nearbyCreateRequest
        nearbyCreateRequest = null
        val invite = nearbyInviteRequest
        nearbyInviteRequest = null
        val id = nearbyRoomRequest
        nearbyRoomRequest = null
        if (create != null) {
            if (nearbyPermissions.all { grants[it] == true }) model.startNearbyRoom(create)
            else model.nearbyPermissionDenied()
        } else if (invite != null) {
            if (nearbyPermissions.all { grants[it] == true }) model.joinNearbyFromUrl(invite.first, invite.second, invite.third)
            else model.nearbyPermissionDenied()
        } else if (id != null) {
            if (nearbyPermissions.all { grants[it] == true }) model.reopenRoom(id)
            else model.nearbyPermissionDenied()
        }
    }
    fun reopen(id: String) {
        if (id == callRoomId) { onBackToCall(); return }
        val nearby = startState.savedRooms.firstOrNull { it.id == id }?.route?.nearby == true
        if (nearby && nearbyPermissions.any { androidx.core.content.ContextCompat.checkSelfPermission(context, it) != android.content.pm.PackageManager.PERMISSION_GRANTED }) {
            nearbyCreateRequest = null; nearbyInviteRequest = null
            nearbyRoomRequest = id
            nearbyPermissionLauncher.launch(nearbyPermissions)
        } else model.reopenRoom(id)
    }
    // The restore witness (P3-03b-2). No witness traffic
    // while a Tor-only room is open (C7); the retiring duty runs at open and
    // on a timer while the app is in the foreground.
    val app = remember(context) { context.applicationContext as? dev.forgesworn.kithmoot.KithMootApplication }
    // VMLS rooms and the restore witness are off until turned on in Settings: while off, neither runs nor shows.
    val vmlsPreview = app?.vmlsPreview?.collectAsState()?.value == true
    val restoreWitness = remember(app, vmlsPreview) { app?.restoreWitness }
    val witnessPersona = startState.account?.pubkey
    val witnessStatus = restoreWitness?.banner?.collectAsState()?.value
    if (restoreWitness != null) {
        val torOnlyOpen = stage == Stage.ROOM && roomState.anonymous
        SideEffect { restoreWitness.torOnlyRoomOpen(torOnlyOpen) }
        LaunchedEffect(restoreWitness, witnessPersona, lifecycle) {
            lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                while (true) {
                    restoreWitness.foregroundTick(witnessPersona)
                    kotlinx.coroutines.delay(RETIRING_DUTY_INTERVAL_MILLIS)
                }
            }
        }
    }
    // VMLS rooms (P3-03b-3). Driven while the app is in the foreground and paused,
    // as witness traffic is, while a Tor-only room is open (the shared quiet flag, C7).
    val vmlsBoxes = remember(app, vmlsPreview) { app?.vmlsBoxes }
    val invitationNetwork = app?.accountNetwork?.state?.collectAsState()?.value
    // The open VMLS room (P3-03b-3), by session.
    var vmlsSession by rememberSaveable { mutableStateOf<String?>(null) }
    val vmlsRoomOnScreen by androidx.compose.runtime.rememberUpdatedState(homePage == HomePage.VMLS_ROOM)
    if (vmlsBoxes != null) {
        LaunchedEffect(vmlsBoxes, witnessPersona, lifecycle) {
            lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                while (true) {
                    try { vmlsBoxes.foregroundRounds(witnessPersona, accountModel.vmlsSigner()?.takeIf { it.pubkey == witnessPersona }) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { }
                    kotlinx.coroutines.delay(if (vmlsRoomOnScreen) VMLS_ROOM_ROUND_INTERVAL_MILLIS else VMLS_ROUND_INTERVAL_MILLIS)
                }
            }
        }
        // Public invitation serving belongs to this network generation. Re-enabling Nostr
        // replaces the stale carrier; private Link and witness rounds remain independent.
        LaunchedEffect(vmlsBoxes, witnessPersona, lifecycle, invitationNetwork) {
            if (invitationNetwork?.mode != dev.forgesworn.kithmoot.account.AccountNetworkMode.NOSTR) return@LaunchedEffect
            lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                // A failure (an unreadable store, a relay) is tried again, never brought down on the app.
                while (true) {
                    try { vmlsBoxes.serveInvites(witnessPersona) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { }
                    kotlinx.coroutines.delay(VMLS_ROUND_INTERVAL_MILLIS)
                }
            }
        }
        // The vault's consent ask, then a join request: no prompt may cover an answered call or the lock screen's call.
        val keeperAsking = vmlsBoxes.keeperConsent.collectAsState().value
        val asking = vmlsBoxes.consent.collectAsState().value
        val joinAsk = vmlsBoxes.joinAsk.collectAsState().value
        val revocationAsk = vmlsBoxes.revocationAsks.collectAsState().value.firstOrNull { it.persona == witnessPersona }
        val requestDirectories = accountModel.accountRelayChoices().filter { it.read }.map { it.url } + PROFILE_RELAYS
        LaunchedEffect(vmlsBoxes, requestDirectories) { vmlsBoxes.requestDirectory(requestDirectories) }
        // Only the signed-in account's ask is shown: another account's is never put to this one.
        if (keeperAsking != null && keeperAsking.persona == witnessPersona && !lockedCallOnly && !callAnswering) {
            dev.forgesworn.kithmoot.ui.start.KeeperDeviceAuthDialog(keeperAsking) { vmlsBoxes.answerKeeperConsent(keeperAsking, it) }
        } else if (asking != null && asking.persona == witnessPersona && !lockedCallOnly && !callAnswering) {
            var askedBox by remember(asking) { mutableStateOf<String?>(null) }
            LaunchedEffect(asking) { askedBox = vmlsBoxes.boxName(asking.persona, asking.homeBox) }
            dev.forgesworn.kithmoot.ui.start.VaultConsentDialog(asking, askedBox) { vmlsBoxes.answer(asking, it) }
        } else if (revocationAsk != null && !lockedCallOnly && !callAnswering) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { vmlsBoxes.deferRevocation(revocationAsk.persona, revocationAsk.key) },
                title = { androidx.compose.material3.Text(if (revocationAsk.pending) "Revocation awaiting the box" else "Compromised-device request") },
                text = { androidx.compose.material3.Text(
                    "${revocationAsk.sender.take(12)}… asks you to remove their device ${revocationAsk.device.take(12)}… as compromised. " +
                        "The identity signed this request; which device sent it is not proven. Verify it with the person before accepting.\n\n" +
                        (if (revocationAsk.conflict) "Conflicting requests name these devices of this person: ${revocationAsk.conflictDevices.joinToString { it.take(12) + "…" }}. Consider removing both and rejoining with new devices.\n\n" else "") +
                        revocationAsk.effects.joinToString("\n")
                ) },
                confirmButton = { androidx.compose.material3.TextButton({ vmlsBoxes.answerRevocation(revocationAsk.persona, accountModel.vmlsSigner(), revocationAsk.key, true) }) {
                    androidx.compose.material3.Text(if (revocationAsk.pending) "Retry" else "Revoke and remove")
                } },
                dismissButton = { androidx.compose.foundation.layout.Row {
                    if (!revocationAsk.pending) androidx.compose.material3.TextButton({
                        vmlsBoxes.answerRevocation(revocationAsk.persona, accountModel.vmlsSigner(), revocationAsk.key, false)
                    }) { androidx.compose.material3.Text("Decline") }
                    androidx.compose.material3.TextButton({
                        vmlsBoxes.deferRevocation(revocationAsk.persona, revocationAsk.key)
                    }) { androidx.compose.material3.Text("Later") }
                } },
            )
        } else if (joinAsk != null && !lockedCallOnly && !callAnswering) {
            dev.forgesworn.kithmoot.ui.start.VmlsJoinDialog(joinAsk) { approve ->
                accountModel.vmlsSigner()?.let { vmlsBoxes.admit(it, joinAsk, approve) }
            }
        }
    }
    val snackbars = remember { SnackbarHostState() }
    val roomUiState = rememberSaveableStateHolder()
    var searchOpen by remember { mutableStateOf(false) }
    var shareOptions by remember { mutableStateOf(false) }
    var shareDeviceSound by remember { mutableStateOf(true) }
    var requestedScreenAudio by remember { mutableStateOf(true) }
    var cardsOpen by remember { mutableStateOf(false) }
    var expandedScreen by remember { mutableStateOf<dev.forgesworn.kithmoot.ui.room.SharedScreen?>(null) }

    LaunchedEffect(roomState.notice) {
        val notice = roomState.notice ?: return@LaunchedEffect
        snackbars.showSnackbar(notice)
        model.dismissNotice()
    }

    val asker = rememberPermissionAsker(onRefused = model::showNotice)

    // People's Voice and Video (design-calm-shell 6.5): the permission first,
    // then the private chat opens (or is made), then the call starts the way
    // Answer does: the microphone on, and the camera on only for Video.
    // Nothing here rings anyone: the other phone rings only if it already
    // has this private chat (dependency D4), so nothing claims to.
    var contactCallCamera by rememberSaveable { mutableStateOf(false) }
    fun callContact(peer: String, video: Boolean) {
        val open = { model.messageContact(peer, call = video) }
        asker.ask(PermissionAsk(
            permission = Manifest.permission.RECORD_AUDIO,
            title = "Microphone",
            why = "So the person you call can hear you. Only one of your devices has a live microphone at a time.",
            refused = "No microphone, so nobody can hear you. The call was not started.",
            onGranted = {
                if (!video) open() else asker.ask(PermissionAsk(
                    permission = Manifest.permission.CAMERA,
                    title = "Camera",
                    why = "So the person you call can see you. A notice tells you when KithMoot is recording the call.",
                    refused = "No camera, so the video call was not started.",
                    onGranted = open,
                ))
            },
        ))
    }
    // The view model says which room opened for the call; it starts once that room is on screen.
    val contactCallReady by model.contactCallReady.collectAsState()
    LaunchedEffect(contactCallReady, stage, roomState.roomId) {
        val ready = contactCallReady ?: return@LaunchedEffect
        if (System.currentTimeMillis() - ready.at > CONTACT_CALL_WINDOW_MILLIS) { model.contactCallStarted(); return@LaunchedEffect }
        if (stage != Stage.ROOM || roomState.roomId != ready.roomId) return@LaunchedEffect
        model.contactCallStarted()
        contactCallCamera = ready.video
        model.showCallView()
        model.joinCall(micOn = true)
    }
    // People's Invite: the invite is sent once the private chat it rides in is on screen.
    val contactInviteReady by model.contactInviteReady.collectAsState()
    LaunchedEffect(contactInviteReady, stage, roomState.roomId) {
        val ready = contactInviteReady ?: return@LaunchedEffect
        if (System.currentTimeMillis() - ready.at > CONTACT_CALL_WINDOW_MILLIS) { model.contactInviteTaken(); return@LaunchedEffect }
        if (stage != Stage.ROOM || roomState.roomId != ready.chat) return@LaunchedEffect
        model.contactInviteTaken()
        model.inviteToRoom(ready.peer, ready.room)
    }
    // The room-invite picker, from People or from somebody's member sheet in a room.
    val roomInvitePicker by model.roomInvitePicker.collectAsState()
    LaunchedEffect(roomInvitePicker?.fromPeople, stage) {
        if (roomInvitePicker?.fromPeople == false && stage != Stage.ROOM) model.dismissRoomInvite()
    }
    roomInvitePicker?.let { picker ->
        val account = startState.account?.pubkey
        if (account != null && (picker.fromPeople || stage == Stage.ROOM)) {
            val rooms = invitableRooms(startState.savedRooms, account,
                current = if (!picker.fromPeople && stage == Stage.ROOM) roomState.roomId else null)
            RoomInviteSheet(picker, rooms, onSend = model::sendRoomInvite, onDismiss = model::dismissRoomInvite)
        }
    }
    // The camera can start only once the call's media is running.
    LaunchedEffect(contactCallCamera, roomState.mediaRunning, stage) {
        if (!contactCallCamera) return@LaunchedEffect
        if (stage != Stage.ROOM) { contactCallCamera = false; return@LaunchedEffect }
        if (!roomState.mediaRunning) return@LaunchedEffect
        contactCallCamera = false
        if (!roomState.cameraOn) model.toggleCamera()
    }

    // Messages arrive as notifications by default, which Android 13 and later
    // shows only with permission. Asked once, the first time a room opens:
    // the moment there is a conversation for it to be about. Granted, the
    // battery exemption follows, without which Android suspends the
    // background connection soon after the screen goes off.
    LaunchedEffect(stage) {
        if (stage != Stage.ROOM || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@LaunchedEffect
        if (isGranted(context, Manifest.permission.POST_NOTIFICATIONS)) return@LaunchedEffect
        val prefs = context.getSharedPreferences("kithmoot.notifications", android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean("permissionAsked", false)) return@LaunchedEffect
        prefs.edit().putBoolean("permissionAsked", true).apply()
        asker.ask(PermissionAsk(
            permission = Manifest.permission.POST_NOTIFICATIONS,
            title = "Know when someone writes",
            why = "KithMoot shows new messages as notifications, even when it is closed. " +
                "The lock screen says only that a message came.",
            refused = "Without notifications you will see new messages only when you open KithMoot.",
            onGranted = {
                dev.forgesworn.kithmoot.service.BackgroundCallListenerService.start(context)
                if (dev.forgesworn.kithmoot.service.BackgroundRingSettings(context).takeBatteryAsk()) {
                    dev.forgesworn.kithmoot.service.requestIgnoreBatteryOptimizations(context)
                }
            },
        ))
    }

    // A call rings full-screen, over the lock screen, only with Android 14's
    // full-screen permission, which a sideloaded app does not get by default.
    // Asked in a room, once notifications are allowed, and never over a call:
    // not while one is answered, joining or on, nor over the lock screen,
    // where a dialog beside the microphone prompt left a person unable to
    // press anything. A call that ends brings the ask back if it is due.
    // Asked again, at most weekly, after a call rang without the screen;
    // Notifications & sound offers it for as long as it is missing.
    val callBusy = roomState.onCall || roomState.callJoinPending || roomState.callChanging ||
        lockedCallOnly || callAnswering || inPictureInPicture || callRoomId != null
    var fullScreenAsk by remember { mutableStateOf(false) }
    LaunchedEffect(stage, callBusy) {
        if (stage != Stage.ROOM || callBusy) return@LaunchedEffect
        if (!androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) return@LaunchedEffect
        if (dev.forgesworn.kithmoot.notifications.canRingFullScreen(context)) return@LaunchedEffect
        if (!dev.forgesworn.kithmoot.service.BackgroundRingSettings(context).takeFullScreenAsk()) return@LaunchedEffect
        fullScreenAsk = true
    }
    // After Allow: back from Android's page with the permission still off,
    // say why it may have been greyed out there, and where to fix it.
    var fullScreenHelp by remember { mutableStateOf(false) }
    var fullScreenPageOpened by remember { mutableStateOf(false) }
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && fullScreenPageOpened) {
                fullScreenPageOpened = false
                fullScreenHelp = !dev.forgesworn.kithmoot.notifications.canRingFullScreen(context)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    if (fullScreenAsk && !callBusy) AlertDialog(
        onDismissRequest = { fullScreenAsk = false },
        title = { Text("Ring like a phone call") },
        text = { Text("Let a call take the screen, even when the phone is locked. Android asks you to allow this for KithMoot on the next page. Without it, calls ring as a notification.") },
        confirmButton = { TextButton({
            fullScreenAsk = false
            fullScreenPageOpened = true
            dev.forgesworn.kithmoot.notifications.openFullScreenCallSettings(context)
        }) { Text("Allow") } },
        dismissButton = { TextButton({ fullScreenAsk = false }) { Text("Not now") } },
    )
    if (fullScreenHelp && !callBusy) AlertDialog(
        onDismissRequest = { fullScreenHelp = false },
        title = { Text("Full-screen calls are still off") },
        text = { Text(dev.forgesworn.kithmoot.notifications.FULL_SCREEN_STILL_OFF_HELP) },
        confirmButton = { TextButton({ fullScreenHelp = false; dev.forgesworn.kithmoot.notifications.openAppInfo(context) }) { Text("Open App info") } },
        dismissButton = { TextButton({ fullScreenHelp = false }) { Text("Not now") } },
    )

    val projection = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            model.startScreenShare(data, requestedScreenAudio)
        } else {
            model.screenShareDeclined()
        }
    }

    /** Asks Android for screen-capture consent. Notifications first, or the service cannot show one. */
    fun beginScreenShare() {
        requestedScreenAudio = shareDeviceSound
        val capture = {
            val manager = context.getSystemService(MediaProjectionManager::class.java)
            if (manager == null) {
                model.showNotice("This device has no screen capture.")
            } else {
                projection.launch(manager.createScreenCaptureIntent())
            }
        }
        val launch = {
            if (!requestedScreenAudio) capture() else asker.ask(PermissionAsk(
                permission = Manifest.permission.RECORD_AUDIO,
                title = "Share app sound",
                why = "Android requires audio recording permission to share sound from apps that allow capture. Your microphone stays under its own call control.",
                refused = "Audio permission was refused. Allow it in Settings to share your screen with app sound.",
                onGranted = capture,
            ))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            asker.ask(
                PermissionAsk(
                    permission = Manifest.permission.POST_NOTIFICATIONS,
                    title = "Notifications",
                    why = "Android keeps a screen share running only while there is a notification " +
                        "showing it. Without one the capture is killed within seconds.",
                    refused = "Without the notification, Android will stop the share.",
                    onGranted = launch,
                ),
            )
        } else {
            launch()
        }
    }

    fun requestScreenShare() { shareOptions = true }
    if (shareOptions && stage == Stage.ROOM) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { shareOptions = false },
            title = { androidx.compose.material3.Text("Share your screen") },
            text = {
                androidx.compose.foundation.layout.Column {
                    androidx.compose.material3.Text("Include device sound from apps that allow capture. This can include other apps' sound when you share a single app. Your microphone has its own call control.")
                    androidx.compose.material3.Switch(checked = shareDeviceSound, onCheckedChange = { shareDeviceSound = it },
                        modifier = Modifier.semantics { contentDescription = "Share device sound" })
                }
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { shareOptions = false; beginScreenShare() }) { androidx.compose.material3.Text("Choose screen or app") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { shareOptions = false }) { androidx.compose.material3.Text("Cancel") } },
        )
    }

    LaunchedEffect(stage) { if (stage != Stage.ROOM) { expandedScreen = null; searchOpen = false } }
    val expanded = expandedScreen
    if (expanded != null && stage == Stage.ROOM) {
        val tile = roomState.tiles.find { it.participant == expanded.participant }
        val meta = tile?.videos?.find { it.device == expanded.device && it.role == dev.forgesworn.kithmoot.session.Roles.SCREEN }
        dev.forgesworn.kithmoot.ui.room.ScreenShareViewer(
            track = meta?.let { videos["${it.device}|${it.role}"] }, eglBase = model.eglBase,
            title = if (tile?.isSelf == true) "Your screen" else "${tile?.let { dev.forgesworn.kithmoot.ui.room.ownName(it, roomState.profiles, it.participant) } ?: dev.forgesworn.kithmoot.ui.room.NAMELESS}’s screen",
            inPictureInPicture = inPictureInPicture, onPopOut = onPopOut,
            shareId = meta?.trackId, marks = roomState.shareMarks[meta?.trackId].orEmpty(),
            onAnnotation = model::drawOnShare,
            onClose = { expandedScreen = null },
        )
        return
    }

    val updates = (context.applicationContext as dev.forgesworn.kithmoot.KithMootApplication).updates
    val atRiskRooms by model.reachability.collectAsState()
    val confirmingCalls by model.renewingCalls.collectAsState()
    val ringingOff by model.ringingOff.collectAsState()
    // What is stopping calls from ringing, and the one press that fixes it: ringing switched off,
    // or the signer having to confirm this phone again. Never both: with ringing off no credential matters.
    fun promptFor(inRoom: String?): dev.forgesworn.kithmoot.service.ReachabilityPrompt? = when {
        ringingOff -> dev.forgesworn.kithmoot.service.ReachabilityPrompt(
            dev.forgesworn.kithmoot.service.ringingOffBanner(), busy = false, onAction = { model.turnBackgroundRingOn() })
        startState.account == null -> null
        else -> dev.forgesworn.kithmoot.service.reachabilityBanner(atRiskRooms, startState.account?.signerLabel, inRoom)?.let {
            dev.forgesworn.kithmoot.service.ReachabilityPrompt(it, confirmingCalls) { model.renewCallCredentials() }
        }
    }
    val accountMenu: @Composable () -> Unit = {
                val account = accountModel
                val accountState by account.start.collectAsState()
                dev.forgesworn.kithmoot.ui.start.AccountMenu(accountState, account.accountRelayChoices(), stage == Stage.ROOM,
                    dev.forgesworn.kithmoot.ui.start.AccountActions(
                        account::refreshSigners, account::signInWithSignerApp, account::signInWithSignet,
                        account::signInWithBunker, account::cancelSignIn, account::signOut, account::provisionRendezvous, account::dismissSignInError),
                    dev.forgesworn.kithmoot.ui.start.AccountSettingsActions(
                        contacts = dev.forgesworn.kithmoot.ui.start.SignetContactActions(
                            account::linkSignetContacts, account::refreshSignetContacts, account::cancelContactPairing, account::confirmContactPairing,
                            account::messageSignetContact, account::openContactInvitation, account::dismissContactInvitation, account::retryContactInvitations),
                        loadProfile = account::loadEditableProfile, publishProfile = account::publishProfile,
                        saveRelays = account::saveAccountRelays, publishRelays = account::publishAccountRelayList,
                        roomRelayAuthentication = account::setRoomRelayAuthentication,
                        loadDmRelays = account::loadDmRelays, publishDmRelays = account::publishDmRelays,
                        retrySync = { account.refreshRoomBookmarks(); account.refreshSharedProjects() },
                        circleBoxes = account::onCircleBoxesChanged, signOut = account::signOutFromAccountMenu,
                        leaveRoom = model::leave,
                    ) , showProfilePicture = accountState.publicProfiles && (stage != Stage.ROOM || !roomState.anonymous),
                    torOnlyRoom = stage == Stage.ROOM && roomState.anonymous,
                    notificationSettings = {
                        val silencedText = roomState.mute?.let { dev.forgesworn.kithmoot.ui.room.mutedText(it) }.orEmpty()
                        val ringRoom = if (stage == Stage.ROOM) object : dev.forgesworn.kithmoot.notifications.CallRingRoom {
                            override val roomId = roomState.roomId
                            override fun mode() = model.callRingMode(roomState.roomId)
                            override fun setMode(mode: dev.forgesworn.kithmoot.notifications.CallRingMode) = model.setCallRingMode(roomState.roomId, mode)
                            override fun silenced() = roomState.mute?.let { dev.forgesworn.kithmoot.notifications.silencedByMute(it, silencedText) }
                        } else null
                        dev.forgesworn.kithmoot.notifications.NotificationSettings(account.notifications, ringRoom, showHeading = false, prompt = promptFor(null), savedRoomCount = accountState.savedRooms.size)
                    })
    }

    // Calls cannot be answered until the signer confirms this phone again: said on the
    // rooms list and in the room, for as long as it is true, whatever became of the notification.
    val reachBanner = if (lockedCallOnly || inPictureInPicture) null else when (stage) {
        Stage.START -> if (homePage.destination() != null) promptFor(null) ?: dev.forgesworn.kithmoot.account.witnessBanner(witnessStatus)?.let {
            dev.forgesworn.kithmoot.service.ReachabilityPrompt(it, busy = false) { witnessFrom = HomePage.ROOMS; homePage = HomePage.RESTORE_WITNESS }
        } else null
        // A room that is not set to Ring me has nothing to say about ringing being off.
        Stage.ROOM -> if (roomState.onCall || (ringingOff && model.callRingMode(roomState.roomId) != dev.forgesworn.kithmoot.notifications.CallRingMode.RING)) null
            else promptFor(roomState.roomId)
    }
    // Under a dock or the banner the status bar is already cleared.
    val recordingActionsVisible = recordingExport != null && recordingPromptAllowed &&
        (stage == Stage.ROOM || stage == Stage.START && homePage.destination() != null)
    val topCleared = dock != null || reachBanner != null || recordingActionsVisible

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            Column {
                dock?.invoke()
                reachBanner?.let {
                    ReachabilityBannerView(it.banner, it.busy, onConfirm = it.onAction, clearStatusBar = dock == null)
                }
                // Each destination brings its own app bar (Chats, People,
                // Boards), under whatever this column holds.
                if (recordingActionsVisible) TextButton(onClick = { deferredRecordingName = null },
                    modifier = Modifier.fillMaxWidth().then(
                        if (dock == null && reachBanner == null) Modifier.statusBarsPadding() else Modifier)) {
                    Text("Recording ready")
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
        // Insets are handled per screen: the room's header runs under the status
        // bar and its control bar under the navigation bar, which a scaffold-wide
        // inset would prevent.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        contentColor = MaterialTheme.colorScheme.onBackground,
        snackbarHost = {
            SnackbarHost(snackbars, modifier = Modifier.navigationBarsPadding()) { data ->
                Snackbar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    Text(data.visuals.message, style = MaterialTheme.typography.bodyLarge)
                }
            }
        },
    ) { scaffoldPadding ->
      // Under a dock the room's own header must not clear the status bar again.
      Box(if (topCleared) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier) {
        val padding = scaffoldPadding
        val homeAccountActions = dev.forgesworn.kithmoot.ui.start.AccountActions(
            onRefreshSigners = model::refreshSigners,
            onSignInWithApp = model::signInWithSignerApp,
            onSignInWithSignet = model::signInWithSignet,
            onSignInWithBunker = model::signInWithBunker,
            onCancelSignIn = model::cancelSignIn,
            onSignOut = model::signOut,
            onProvisionRendezvous = model::provisionRendezvous,
            onDismissError = model::dismissSignInError,
        )
        val homeAccountSettingsActions = dev.forgesworn.kithmoot.ui.start.AccountSettingsActions(
            contacts = dev.forgesworn.kithmoot.ui.start.SignetContactActions(
                model::linkSignetContacts, model::refreshSignetContacts, model::cancelContactPairing, model::confirmContactPairing,
                model::messageSignetContact, model::openContactInvitation, model::dismissContactInvitation, model::retryContactInvitations),
            loadProfile = model::loadEditableProfile, publishProfile = model::publishProfile,
            saveRelays = model::saveAccountRelays, publishRelays = model::publishAccountRelayList,
            roomRelayAuthentication = model::setRoomRelayAuthentication,
            loadDmRelays = model::loadDmRelays, publishDmRelays = model::publishDmRelays,
            retrySync = { model.refreshRoomBookmarks(); model.refreshSharedProjects() },
            circleBoxes = model::onCircleBoxesChanged, signOut = model::signOutFromAccountMenu,
        )
        val homeProjectActions = dev.forgesworn.kithmoot.ui.start.ProjectActions(
            refresh = accountModel::refreshSharedProjects,
            retry = accountModel::retryProjectSends,
            follow = accountModel::followSharedProject,
            open = model::openSharedProjectRoom,
            save = accountModel::saveSharedProject,
            rooms = accountModel::availableProjectRooms,
            editLogo = accountModel::openProjectLogoEditor,
        )
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        // One measure for the whole shell (design-calm-shell section 7): the
        // bottom bar or the rail, and whether the list stays beside an open
        // room. The existing home measure, so home and shell agree.
        val shellLayout = dev.forgesworn.kithmoot.ui.start.homeLayout(maxWidth.value.roundToInt(), maxHeight.value.roundToInt())
        val wide = shellLayout != dev.forgesworn.kithmoot.ui.start.HomeLayout.COMPACT
        // A call is full screen (Q7), and a locked phone or picture in
        // picture shows the call and nothing else.
        val roomCallActive = roomState.onCall || roomState.callJoinPending || roomState.callChanging
        val shellChrome = !lockedCallOnly && !inPictureInPicture
        val roomRail = stage == Stage.ROOM && wide && shellChrome && !roomCallActive
        // The list beside an open room, on expanded widths. Never beside a
        // Tor-only room: the list's lookups are clearnet traffic.
        val twoPane = roomRail && shellLayout == dev.forgesworn.kithmoot.ui.start.HomeLayout.EXPANDED && !roomState.anonymous
        SideEffect { model.setHomeListVisible(twoPane) }
        /** Opening another room from the list while one is open: the open one is left first. */
        fun leaveForList() { if (stage == Stage.ROOM) model.leave() }
        fun openFromList(id: String) {
            if (stage == Stage.ROOM) {
                if (id == roomState.roomId) return
                model.leave()
            }
            reopen(id)
        }
        fun selectDestination(destination: dev.forgesworn.kithmoot.ui.start.Destination) {
            projectRoomToAdd = null
            if (stage == Stage.ROOM) {
                if (destination == dev.forgesworn.kithmoot.ui.start.Destination.CHATS && twoPane) return
                roomBack()
            }
            homePage = destination.homePage()
        }
        val shellBadges = dev.forgesworn.kithmoot.ui.start.shellBadges(startState)
        val accountButton: @Composable () -> Unit = {
            dev.forgesworn.kithmoot.ui.start.ShellAccountButton(
                account = workspaceAccountState.account ?: startState.account,
                showPicture = workspaceAccountState.publicProfiles && !(stage == Stage.ROOM && roomState.anonymous),
                busy = startState.busy || startState.profileBusy || startState.roomSyncBusy || startState.projectsBusy,
                onSettings = { if (stage == Stage.ROOM) roomBack(); homePage = HomePage.SETTINGS },
                onSignIn = { if (stage == Stage.ROOM) roomBack(); signInSheetOpen = true },
                onSignOut = model::signOutFromAccountMenu,
                inRoom = stage == Stage.ROOM,
            )
        }
        val rail: @Composable (dev.forgesworn.kithmoot.ui.start.Destination?) -> Unit = { current ->
            dev.forgesworn.kithmoot.ui.start.ShellNavigationRail(
                destinations = shellDestinations, current = current, onSelect = ::selectDestination,
                settingsSelected = stage == Stage.START && homePage == HomePage.SETTINGS,
                onSettings = { if (stage == Stage.ROOM) roomBack(); homePage = HomePage.SETTINGS },
                badges = shellBadges, account = accountButton,
            )
        }
        /** The Chats list: the whole page on a phone, the list pane beside a room on a tablet. */
        val chatsList: @Composable (Modifier, String?) -> Unit = { listModifier, openRoomId ->
            val updateState by updates.state.collectAsState()
            val dismissedUpdate by updates.dismissed.collectAsState()
            val updateShown = dev.forgesworn.kithmoot.update.updateNoticeShown(updateState, dismissedUpdate)
            // The first visible item clears both the status bar and the camera
            // cut-out. Consume that inset for the Chats bar below the notice.
            Column(listModifier.then(if (updateShown) Modifier.windowInsetsPadding(
                WindowInsets.statusBars.union(WindowInsets.displayCutout).only(
                    androidx.compose.foundation.layout.WindowInsetsSides.Top +
                        androidx.compose.foundation.layout.WindowInsetsSides.Horizontal)) else Modifier)) {
                dev.forgesworn.kithmoot.update.UpdateNotice(updates)
                StartScreen(
                        state = startState.copy(projects = workspaceAccountState.projects.takeIf {
                            workspaceAccountState.account?.pubkey == startState.account?.pubkey
                        } ?: dev.forgesworn.kithmoot.account.ProjectAccountSnapshot()),
                        onRoomNameChanged = model::onRoomNameChanged,
                        onJoinUrlChanged = model::onJoinUrlChanged,
                        onRelaysChanged = model::onRelaysChanged,
                        onAnonymousModeChanged = model::onAnonymousModeChanged,
                        onPersistentGroupChanged = model::onPersistentGroupChanged,
                        onStartRoom = { leaveForList(); model.startRoom() },
                        onStartNearby = { route ->
                            if (nearbyPermissions.any { androidx.core.content.ContextCompat.checkSelfPermission(context, it) != android.content.pm.PackageManager.PERMISSION_GRANTED }) {
                                nearbyRoomRequest = null; nearbyInviteRequest = null; nearbyCreateRequest = route
                                nearbyPermissionLauncher.launch(nearbyPermissions)
                            } else model.startNearbyRoom(route)
                        },
                        onRecoverNativeCreation = model::recoverNativeRoomCreation,
                        onConferenceLengthChanged = model::onConferenceLengthChanged,
                        onRoomDestructChanged = model::onRoomDestructChanged,
                        onRoomDurationChanged = model::onRoomDurationChanged,
                        onJoin = { leaveForList(); model.joinFromUrl(startState.joinUrl) },
                        onJoinNearby = { code, route ->
                            val request = Triple(startState.joinUrl, code, route)
                            if (nearbyPermissions.any { androidx.core.content.ContextCompat.checkSelfPermission(context, it) != android.content.pm.PackageManager.PERMISSION_GRANTED }) {
                                nearbyRoomRequest = null; nearbyCreateRequest = null
                                nearbyInviteRequest = request
                                nearbyPermissionLauncher.launch(nearbyPermissions)
                            } else model.joinNearbyFromUrl(request.first, request.second, request.third)
                        },
                        onReopen = ::openFromList,
                        onForget = model::forgetRoom,
                        onProject = model::setRoomProject,
                        onPin = model::setRoomPinned,
                        roomNotify = roomNotify,
                        onRoomRoute = model::setRoomRoute,
                        onPairBothy = model::pairBothy,
                        onDisconnectBothy = model::disconnectBothy,
                        onRevokeBothyGuests = model::revokeBothyGuests,
                        onRetryStorage = model::refreshSavedRooms,
                        onAddOfferedCard = model::addOfferedCard,
                        onDismissCardOffer = model::dismissCardOffer,
                        onResetStorage = model::resetSavedRooms,
                        accountRooms = dev.forgesworn.kithmoot.ui.start.AccountRoomActions(
                            refresh = model::refreshRoomBookmarks,
                            open = model::openAccountRoom,
                            remove = model::removeAccountRoom,
                            importRooms = model::importAccountRooms,
                        ),
                        projects = homeProjectActions,
                        modifier = Modifier.weight(1f),
                        callRoomId = callRoomId,
                        onStopOpening = model::stopOpening,
                        onOpenProjects = { projectRoomToAdd = null; homePage = HomePage.PROJECTS },
                        onAddRoomToProject = { id -> projectRoomToAdd = id; homePage = HomePage.PROJECTS },
                        onSignIn = { signInSheetOpen = true },
                        onShareInvite = { id ->
                            homeCoroutines.launch {
                                model.inviteLinkFor(id)?.let { link -> share(context, link, "Send invite link") }
                            }
                        },
                        onDismissTombstone = model::dismissTombstone,
                        vmlsRooms = vmlsBoxes?.let { boxes -> {
                            val vmlsRooms by boxes.rooms.collectAsState()
                            val vmlsState by boxes.state.collectAsState()
                            LaunchedEffect(witnessPersona) { boxes.open(witnessPersona) }
                            val persona = witnessPersona
                            if (persona != null) dev.forgesworn.kithmoot.ui.start.VmlsRoomsSection(
                                vmlsRooms, vmlsState.boxes, vmlsState.busy,
                                onOpen = { vmlsSession = it; homePage = HomePage.VMLS_ROOM },
                                onCreate = { box, name -> boxes.createRoom(persona, box, name) },
                            )
                        } },
                        selectedRoomId = openRoomId,
                        accountButton = if (wide && shellChrome) null else accountButton,
                        topBarInsets = if (topCleared || updateShown) WindowInsets(0, 0, 0, 0) else TopAppBarDefaults.windowInsets,
                        onMessageContact = if (dev.forgesworn.kithmoot.ui.start.Destination.PEOPLE in shellDestinations) ({ selectDestination(dev.forgesworn.kithmoot.ui.start.Destination.PEOPLE) }) else null,
                        onAcceptInvitation = model::openContactInvitation,
                        onDeclineInvitation = model::dismissContactInvitation,
                        onDeclineAndBlockInvitation = model::declineAndBlockInvitation,
                    )
            }
        }
        if (lockedCallOnly && stage != Stage.ROOM) {
            // The room is still opening: never the rooms list over the lock screen.
            Column(Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center, horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                Text("Joining the call…", style = MaterialTheme.typography.headlineSmall)
            }
        } else when (stage) {
            Stage.START -> {
                val listDestination = homePage.destination()
                // The same nesting at every width, so a rotation or a fold
                // never rebuilds the page underneath with fresh state.
                Row(Modifier.fillMaxSize().padding(padding)) {
                    if (wide && shellChrome) rail(listDestination)
                    Column(Modifier.weight(1f).fillMaxHeight().then(if (wide && shellChrome) Modifier.consumeWindowInsets(RAIL_SIDE) else Modifier)) {
                        val bar = !wide && shellChrome && listDestination != null
                        Box(Modifier.weight(1f).fillMaxWidth().then(if (bar) Modifier.consumeWindowInsets(WindowInsets.navigationBars) else Modifier)) {
                when (homePage) {
                    HomePage.ROOMS -> if (shellLayout == dev.forgesworn.kithmoot.ui.start.HomeLayout.EXPANDED) Row(Modifier.fillMaxSize()) {
                        chatsList(Modifier.width(LIST_PANE_WIDTH).fillMaxHeight(), null)
                        androidx.compose.material3.VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        NoChatOpen(Modifier.weight(1f).fillMaxHeight())
                    } else chatsList(Modifier.fillMaxSize(), null)
                    HomePage.PEOPLE -> dev.forgesworn.kithmoot.ui.start.PeopleDestination(
                        state = startState,
                        actions = dev.forgesworn.kithmoot.ui.start.PeopleActions(
                            contacts = homeAccountSettingsActions.contacts,
                            lookUpFollows = model::lookUpFollows, lookUpProfiles = model::lookUpPeopleProfiles,
                            refreshCards = model::refreshContactCards, addCard = model::addContactCard, forgetCard = model::forgetContact,
                            checkBox = model::checkContactBox, showMyCard = model::showAccountCard, dismissMyCard = model::dismissAccountCard,
                            call = ::callContact,
                            block = { peer -> model.setBlocked(peer, true) },
                            invite = model::openContactRoomInvite,
                        ),
                        insets = if (topCleared) WindowInsets(0, 0, 0, 0) else TopAppBarDefaults.windowInsets,
                        accountButton = if (wide && shellChrome) null else accountButton,
                    )
                    HomePage.BOARDS -> dev.forgesworn.kithmoot.ui.start.WorkspaceScreen(
                        snapshot = workspaceSnapshot.takeIf { it.account == startState.account?.pubkey }
                            ?: dev.forgesworn.kithmoot.session.WorkspaceSnapshot(),
                        initialInbox = true, onClose = null,
                        onRefresh = accountModel::openWorkspaceActivity,
                        onProjects = { homePage = HomePage.PROJECTS },
                        onOrigin = { target ->
                            when {
                                target.room == callRoomId -> { accountModel.openWorkspaceOrigin(target); onBackToCall() }
                                else -> model.openWorkspaceOrigin(target)
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        insets = if (topCleared) WindowInsets(0, 0, 0, 0) else TopAppBarDefaults.windowInsets,
                        accountButton = if (wide && shellChrome) null else accountButton,
                    )
                    HomePage.SETTINGS -> SettingsScreen(
                        state = startState,
                        signIn = homeAccountActions,
                        accountSettings = homeAccountSettingsActions,
                        accountRooms = dev.forgesworn.kithmoot.ui.start.AccountRoomActions(
                            refresh = model::refreshRoomBookmarks,
                            open = model::openAccountRoom,
                            remove = model::removeAccountRoom,
                            importRooms = model::importAccountRooms,
                        ),
                        relayChoices = model.accountRelayChoices(),
                        onWebAppAddressChanged = model::onWebAppAddressChanged,
                        onSyncSpry = model::syncSpryAccount,
                        onUnpairSpry = model::unpairSpryAccount,
                        publicCallRelays = model.publicCallRelaysPanel,
                        notificationSettings = { dev.forgesworn.kithmoot.notifications.NotificationSettings(model.notifications, null, showHeading = false, prompt = promptFor(null), savedRoomCount = startState.savedRooms.size) },
                        page = settingsPage,
                        onPageChange = { settingsPage = it },
                        onBack = { homePage = HomePage.ROOMS },
                        notificationsAttention = dev.forgesworn.kithmoot.notifications.rememberNotificationsAttention(
                            model.notifications, ringingOff, needsSigner = promptFor(null) != null),
                        onPublicProfiles = model::setProfilesEnabled,
                        onAccountNetworkMode = model::setAccountNetworkMode,
                        onHideIpOnCalls = model::setHideIpOnCalls,
                        onUnblock = { peer -> model.setBlocked(peer, false) },
                        fileChoiceActions = fileChoiceActions,
                        onMirrorSelf = model::setMirrorSelf,
                        vmlsPreview = vmlsPreview.takeIf { app != null },
                        onVmlsPreview = { app?.setVmlsPreview(it) },
                        onRestoreWitness = restoreWitness?.let { { witnessFrom = HomePage.SETTINGS; homePage = HomePage.RESTORE_WITNESS } },
                        onVmlsBoxes = vmlsBoxes?.let { { homePage = HomePage.VMLS_BOXES } },
                        updates = updates,
                    )
                    HomePage.RESTORE_WITNESS -> restoreWitness?.let {
                        dev.forgesworn.kithmoot.ui.start.RestoreWitnessScreen(it, witnessPersona, onBack = { homePage = witnessFrom })
                    }
                    HomePage.VMLS_BOXES -> vmlsBoxes?.let {
                        dev.forgesworn.kithmoot.ui.start.VmlsBoxesScreen(it, witnessPersona, signer = accountModel::vmlsSigner, onBack = { homePage = HomePage.SETTINGS })
                    }
                    HomePage.VMLS_ROOM -> vmlsBoxes?.let { boxes ->
                        val vmlsRooms by boxes.rooms.collectAsState()
                        val vmlsState by boxes.state.collectAsState()
                        val persona = witnessPersona
                        val session = vmlsSession
                        val room = vmlsRooms.firstOrNull { it.session == session }
                        dev.forgesworn.kithmoot.ui.start.VmlsRoomScreen(
                            room = room, error = vmlsState.error, quiet = restoreWitness?.quiet?.get() == true,
                            onBack = { homePage = HomePage.ROOMS },
                            onSay = { text -> if (persona != null && session != null) boxes.say(persona, session, text) },
                            onInvite = {
                                if (persona != null && session != null) homeCoroutines.launch {
                                    runCatching { boxes.inviteLink(persona, session, accountModel.vmlsJoinBase(), DEFAULT_RELAYS) }
                                        .onSuccess { link -> share(context, link, "Send invite link") }
                                }
                            },
                            onRetire = { if (persona != null && session != null) boxes.retireInvite(persona, session) },
                            onRemove = { target, person, compromised ->
                                if (persona != null && session != null) when {
                                    // The immediate revocation is signed by the keeper: without its signer, nothing starts and the error says so.
                                    compromised -> boxes.removeCompromised(persona, accountModel.vmlsSigner(), session, target, person)
                                    person -> boxes.removePerson(persona, session, target)
                                    else -> boxes.removeMember(persona, session, target)
                                }
                            },
                            plan = { target, person, compromised ->
                                if (persona != null && session != null) boxes.removalPlan(persona, session, target, person, compromised) else null
                            },
                            onRetryRemoval = { key -> if (persona != null) boxes.retryRemoval(persona, key) },
                            onRequestDevice = { device -> if (persona != null && session != null) { boxes.requestOwnDevice(persona, accountModel.vmlsSigner(), session, device); homePage = HomePage.VMLS_BOXES } },
                            onLeave = { if (persona != null && session != null) boxes.leave(persona, session) },
                            onClose = { force -> if (persona != null && session != null) boxes.close(persona, accountModel.vmlsSigner(), session, force) },
                            onForget = { if (persona != null && session != null) boxes.forgetRoom(persona, session) },
                        )
                    }
                    HomePage.PROJECTS -> ProjectsScreen(startState.copy(projects = workspaceAccountState.projects,
                        projectsBusy = workspaceAccountState.projectsBusy, projectError = workspaceAccountState.projectError), homeProjectActions,
                        onBack = { projectRoomToAdd = null; homePage = HomePage.ROOMS },
                        roomToAdd = projectRoomToAdd, onSignIn = { signInSheetOpen = true })
                }
                        }
                        if (bar) dev.forgesworn.kithmoot.ui.start.ShellNavigationBar(shellDestinations, listDestination, ::selectDestination, shellBadges)
                    }
                }
                if (signInSheetOpen) SignInSheet(startState, homeAccountActions, onDismiss = { signInSheetOpen = false })
            }

            Stage.ROOM -> Row(Modifier.fillMaxSize().padding(padding)) {
                // Rail, list and room in one row whose nesting never changes, so
                // the room keeps its draft and scroll as panes come and go.
                // A room belongs to Chats, so Chats stays selected while it is open.
                if (roomRail) rail(dev.forgesworn.kithmoot.ui.start.Destination.CHATS)
                if (twoPane) {
                    chatsList(Modifier.width(LIST_PANE_WIDTH).fillMaxHeight().consumeWindowInsets(RAIL_SIDE), roomState.roomId)
                    androidx.compose.material3.VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                Box(Modifier.weight(1f).fillMaxHeight().then(if (roomRail) Modifier.consumeWindowInsets(RAIL_SIDE) else Modifier)) {
            roomUiState.SaveableStateProvider("${roomState.selfParticipant}:${roomState.roomId}") {
                RoomScreen(
                    filesLine = roomState.roomFilesLine ?: startState.files.roomLine(),
                    state = roomState,
                    workspaceTarget = workspaceOrigin?.takeIf { it.account == startState.account?.pubkey && it.room == roomState.roomId },
                    accountMenu = accountMenu,
                    videos = videos,
                    eglBase = model.eglBase,
                    onToggleMic = {
                        if (!roomState.micOn && !roomState.meetingSpeaker) {
                            // Locked: said why, and no permission asked for a
                            // microphone that cannot be used.
                            model.noteMeetingLocked()
                        } else if (roomState.micOn) {
                            model.toggleMicrophone()
                        } else if (lockedCallOnly && androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) {
                            // Android's permission prompt cannot show over the lock screen.
                            onUnlock()
                        } else {
                            asker.ask(
                                PermissionAsk(
                                    permission = Manifest.permission.RECORD_AUDIO,
                                    title = "Microphone",
                                    why = "So the room can hear you. Only one of your devices " +
                                        "has a live microphone at a time.",
                                    refused = "Microphone blocked. Allow it in Settings.",
                                    onGranted = model::toggleMicrophone,
                                ),
                            )
                        }
                    },
                    onToggleAgentsMayHear = { model.setAgentsMayHear(!roomState.agentsMayHear) },
                    onToggleCamera = {
                        if (!roomState.cameraOn && !roomState.meetingSpeaker) {
                            model.noteMeetingLocked()
                        } else if (roomState.cameraOn) {
                            model.toggleCamera()
                        } else {
                            asker.ask(
                                PermissionAsk(
                                    permission = Manifest.permission.CAMERA,
                                    title = "Camera",
                                    why = "So the room can see you. A notice tells you when KithMoot is recording " +
                                        "the call. Other participants may also record with another app.",
                                    refused = "No camera, so your tile stays a placeholder.",
                                    onGranted = model::toggleCamera,
                                ),
                            )
                        }
                    },
                    onSwitchCamera = model::switchCamera,
                    onChooseBackground = model::chooseBackground,
                    onChooseFacePrivacy = model::chooseFacePrivacy,
                    onToggleScreenShare = {
                        if (roomState.screenOn) model.stopScreenShare() else if (model.mayShareScreen()) requestScreenShare()
                    },
                    onExpandScreen = { expandedScreen = it },
                    onAddDevice = model::mintPairingLink,
                    onStartPrivateConversation = model::startPrivateConversation,
                    onSelectSharingParticipant = model::selectSharingParticipant,
                    onStartRoomSharing = model::startRoomSharing,
                    onStopRoomSharing = model::stopRoomSharing,
                    onRefreshCadence = model::refreshCadence,
                    onCompareRoomHistory = model::compareRoomHistoryWithBothy,
                    onFetchRoomHistory = model::fetchComparedHistoryFromBothy,
                    onOfferRoomHistory = model::offerComparedHistoryToBothy,
                    onStartCadence = model::startCadence,
                    onStopCadence = model::stopCadence,
                    onRenewCadence = model::renewCadence,
                    onRetryRoomUpdate = model::retryRoomUpdate,
                    onDismissEpochTrouble = model::dismissEpochTrouble,
                    onAnswerLetIn = model::answerLetIn,
                    onAnswerInvitationAdmission = model::answerInvitationAdmission,
                    onDismissInvitationAdmission = model::dismissInvitationAdmission,
                    onRenameRoom = model::renameRoomForEveryone,
                    onEditRoomLogo = model::openRoomLogoEditor,
                    onOpenCards = { cardsOpen = true },
                    onSearch = { searchOpen = !searchOpen },
                    onProfilesEnabled = model::setProfilesEnabled,
                    onMirrorSelf = model::setMirrorSelf,
                    onLoudspeaker = model::setLoudspeakerOn,
                    onSetVolume = model::setCallVolume,
                    onListenHere = model::listenOnThisDevice,
                    onLeaveCall = model::leaveCall,
                    onJoinCall = model::joinCall,
                    onRaiseHand = model::raiseHand,
                    onSetMeetingMode = model::setMeetingMode,
                    onSetSpeaker = model::setSpeaker,
                    onAnswerRecordingConsent = model::answerRecordingConsent,
                    onStartAudioRecording = model::startNativeAudioRecording,
                    onStartVideoRecording = model::startNativeVideoRecording,
                    onStopRecording = model::stopNativeRecording,
                    onToggleRecordingPause = model::toggleNativeRecordingPause,
                    onRetryRecordingStop = model::retryRecordingStop,
                    onRotateInvitation = model::rotateInvitation,
                    onMintOwnInvitation = model::mintOwnInvitation,
                    onChangeNativeRoomKey = model::changeNativeRoomKey,
                    onRemoveNativeRoomMember = model::removeNativeRoomMember,
                    onRetireNativeInvitation = model::retireNativeInvitation,
                    onReplaceNativeInvitation = model::replaceNativeInvitation,
                    onResendNativeRetirement = model::resendNativeRetirement,
                    onRecoverNativePending = model::recoverNativePendingUpdate,
                    onCanShareInvitation = model::canShareRoomInvitation,
                    inPictureInPicture = inPictureInPicture,
                    onPopOut = onPopOut,
                    lockedCallOnly = lockedCallOnly,
                    onUnlock = onUnlock,
                    onLeave = model::leave,
                    onBack = roomBack,
                    showBack = !twoPane,
                    pinned = startState.savedRooms.firstOrNull { it.id == roomState.roomId }?.pinned,
                    onPin = { pin -> model.setRoomPinned(roomState.roomId, pin) },
                    // As from its row in Chats: the room goes to Projects, ready to add.
                    onAddToProject = startState.savedRooms.firstOrNull { it.id == roomState.roomId }?.let { saved ->
                        { projectRoomToAdd = saved.id; homePage = HomePage.PROJECTS; roomBack() }
                    },
                    notify = roomNotify,
                    modifier = Modifier.fillMaxSize(),
                    work = { dev.forgesworn.kithmoot.ui.room.WorkPane(roomState,model::submitWork,model::retryWork,model::refreshWorkActions,
                        targetAssignment = workspaceOrigin?.takeIf { it.room == roomState.roomId && it.account == startState.account?.pubkey }?.assignment,
                        targetRequest = workspaceOrigin?.request ?: 0,
                        onClearTarget = model::clearWorkspaceOrigin) },
                    chat = {
                        ChatPane(
                            targetMessage = workspaceOrigin?.takeIf { it.room == roomState.roomId && it.account == startState.account?.pubkey }?.message,
                            targetRequest = workspaceOrigin?.request ?: 0,
                            onClearTarget = model::clearWorkspaceOrigin,
                            messages = roomState.chat,
                            notes = roomState.roomNotes,
                            agentParticipants = roomState.agentParticipants,
                            onReadingChanged = model::notificationReading,
                            latestRequest = roomState.notificationChatRequest,
                            selfParticipant = roomState.selfParticipant,
                            onSend = model::sendChat,
                            onReply = model::sendReply,
                            onReact = model::react,
                            attachments = roomState.chatAttachments,
                            recordingDrafts = roomState.recordingDrafts,
                            recordingHandoffStates = roomState.recordingHandoffStates,
                            onRetryRecordingWithdrawal = model::retryRecordingWithdrawal,
                            onRemoveRecordingDraft = model::removeRecordingDraft,
                            onSendRecordingDraft = model::sendRecordingDraft,
                            recordingStorageChoice = roomState.recordingStorageChoice,
                            onPrepareRecordingStorage = model::prepareRecordingStorage,
                            onUploadRecordingDraft = model::uploadRecordingDraft,
                            recordingUploadRunning = roomState.recordingUploadRunning,
                            onCancelRecordingUpload = model::cancelRecordingUpload,
                            artwork = roomState.chatArtwork,
                            onAddArtwork = model::addChatArtwork,
                            onRemoveArtwork = model::removeChatArtwork,
                            mediaBusy = roomState.mediaBusy,
                            recordingScheduleBusy = roomState.cadence?.busy == true,
                            files = dev.forgesworn.kithmoot.ui.room.ComposerFiles(
                                choice = startState.files, notice = roomState.fileNotice, off = roomState.filesOff,
                                onPick = model::pickChatFile, onAcceptNotice = model::acceptFileNotice,
                                onDismissNotice = model::dismissFileNotice, onDismissOff = model::dismissFilesOff,
                                actions = fileChoiceActions,
                                roomLine = roomState.roomFilesLine, waiting = roomState.waitingFiles, onRemoveWaiting = model::removeWaitingFile,
                            ),
                            fetchGroupAttachment = model::fetchGroupAttachment,
                            onRemoveAttachment = model::removeChatAttachment,
                            memberPackAvailable = model::memberPackAvailable,
                            unlockMemberPacks = model::unlockMemberPacks,
                            onOpenPrivateConversation = model::openPrivateConversation,
                            privateConversationPeers = if (roomState.privateConversationBusy) emptyList() else roomState.privateConversationPeers,
                            onMessagePrivately = model::startPrivateConversation,
                            roomInvitePeers = roomState.roomInvitePeers,
                            onInviteToRoom = model::openRoomInvite,
                            savedRoomName = { id -> startState.savedRooms.firstOrNull { it.id == id }?.let { dev.forgesworn.kithmoot.ui.start.roomLabel(it.name, it.id) } },
                            profilesEnabled = roomState.profilesEnabled,
                            profiles = roomState.profiles,
                            onProfilesEnabled = model::setProfilesEnabled,
                            lane = roomState.lane,
                            torOnly = roomState.anonymous,
                            internetAllowed = roomState.route.internet,
                            relaysUp = roomState.relaysUp,
                            quiet = roomState.quiet,
                            quietCanSend = roomState.quietCanSend,
                            canSend = roomState.movedOn == null && !roomState.conferenceEnded,
                            sending = roomState.chatSending,
                            waitingForSigner = roomState.chatWaitingForSigner,
                            pendingChats = roomState.pendingChats,
                            destructEndsAt = roomState.endsAt?.takeIf { roomState.destruct },
                            onRetryPending = { model.retryPendingChat() },
                            onEditPending = model::editPendingChat,
                            onDeletePending = model::deletePendingChat,
                            onRemovePending = model::removePendingChat,
                            sendError = roomState.chatSendError,
                            modifier = Modifier.fillMaxSize(),
                            showTitle = false,
                            searchOpen = searchOpen,
                            onCloseSearch = { searchOpen = false },
                        )
                    },
                )
            }
                }
            }
        }
        }
        if (showingDestruct) dev.forgesworn.kithmoot.ui.room.DestructEffect { showingDestruct = false }
        if (stage == Stage.ROOM) {
            val ringBanner by model.callRingBanner.collectAsState()
            // Answering here is the same as answering the notification:
            // straight in, microphone live. A refused mic joins muted.
            val answerWithMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                model.joinCall(micOn = granted)
            }
            ringBanner?.let { call ->
                var callerName by remember(call.caller) { mutableStateOf(dev.forgesworn.kithmoot.ui.room.callerLabel(call.caller)) }
                LaunchedEffect(call.caller) {
                    val name = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        dev.forgesworn.kithmoot.notifications.CallerNames.label(context, call.caller)
                    }
                    callerName = dev.forgesworn.kithmoot.ui.room.callerLabel(name)
                }
                dev.forgesworn.kithmoot.notifications.IncomingCallBanner(
                    callerLabel = callerName,
                    onAnswer = {
                        model.dismissCallRingBanner()
                        model.showCallView()
                        if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) model.joinCall(micOn = true)
                        else answerWithMic.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onDismiss = model::dismissCallRingBanner,
                    // Clear of the status bar and the camera cutout, or Join sits
                    // under them where it cannot be tapped.
                    modifier = Modifier.align(androidx.compose.ui.Alignment.TopCenter)
                        .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))
                        .padding(top = 12.dp),
                )
            }
        }
      }
    }

    LaunchedEffect(stage) { if (stage == Stage.START) cardsOpen = false }

    if (cardsOpen) {
        ModalBottomSheet(
            onDismissRequest = { cardsOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            ContactCardsSheet(
                contacts = roomState.contacts,
                status = roomState.cardStatus,
                myCard = roomState.myCard,
                canShowCard = roomState.canShowCard,
                onAdd = model::addContactCard,
                onForget = model::forgetContact,
                readRelays = startState.relays,
                onCheckBox = model::checkContactBox,
                onShowMyCard = model::showMyCard,
                onDone = { cardsOpen = false },
            )
        }
    }

    val pairing = roomState.pairingLink
    if (pairing != null) {
        ModalBottomSheet(
            onDismissRequest = model::dismissPairingLink,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            AddDeviceSheet(link = pairing, onDone = model::dismissPairingLink)
        }
    }
}

/** How long after its private chat opened a Voice or Video press may still start its call. */
private const val CONTACT_CALL_WINDOW_MILLIS = 30_000L

/** The list pane beside an open room on expanded widths (design-calm-shell 4.4: 300 on the web; 360 dp here, for 56 dp rows at Android's sizes). */
private val LIST_PANE_WIDTH = 360.dp

/** The start-side system inset a rail beside the content has already cleared. */
private val RAIL_SIDE: WindowInsets
    @Composable get() = WindowInsets.systemBars.union(WindowInsets.displayCutout).only(androidx.compose.foundation.layout.WindowInsetsSides.Start)

/** The empty conversation pane beside the list on a tablet, before a chat is chosen: the logo, quietly, and nothing to read. */
@Composable
private fun NoChatOpen(modifier: Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.background), contentAlignment = androidx.compose.ui.Alignment.Center) {
        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(dev.forgesworn.kithmoot.R.drawable.brand_artwork), null,
            Modifier.size(96.dp).graphicsLayer { alpha = 0.35f })
    }
}
