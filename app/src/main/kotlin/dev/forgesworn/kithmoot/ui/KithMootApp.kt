package dev.forgesworn.kithmoot.ui

import android.Manifest
import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.forgesworn.kithmoot.ui.room.AddDeviceSheet
import dev.forgesworn.kithmoot.ui.room.ChatPane
import dev.forgesworn.kithmoot.ui.room.RoomScreen
import dev.forgesworn.kithmoot.ui.start.StartScreen

/**
 * The whole application: two screens, two sheets, and the permission asks.
 *
 * Every permission is requested at the moment the thing it is for is asked for,
 * with a sentence saying why, and never at launch. A microphone permission
 * granted at first run to an application that has not yet joined anything is a
 * permission granted for no stated reason, which is how people end up with
 * applications they do not trust.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KithMootApp(model: RoomViewModel, inPictureInPicture: Boolean = false, onPopOut: (() -> Unit)? = null) {
    val stage by model.stage.collectAsState()
    val startState by model.start.collectAsState()
    val roomState by model.room.collectAsState()
    val videos by model.videos.collectAsState()

    val context = LocalContext.current
    val snackbars = remember { SnackbarHostState() }
    var chatOpen by remember { mutableStateOf(false) }
    var expandedScreen by remember { mutableStateOf<dev.forgesworn.kithmoot.ui.room.SharedScreen?>(null) }

    LaunchedEffect(roomState.notice) {
        val notice = roomState.notice ?: return@LaunchedEffect
        snackbars.showSnackbar(notice)
        model.dismissNotice()
    }

    val asker = rememberPermissionAsker(onRefused = model::showNotice)

    val projection = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            model.startScreenShare(data)
        } else {
            model.screenShareDeclined()
        }
    }

    /** Asks Android for screen-capture consent. Notifications first, or the service cannot show one. */
    fun requestScreenShare() {
        val launch = {
            val manager = context.getSystemService(MediaProjectionManager::class.java)
            if (manager == null) {
                model.showNotice("This device has no screen capture.")
            } else {
                projection.launch(manager.createScreenCaptureIntent())
            }
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

    LaunchedEffect(stage) { if (stage != Stage.ROOM) expandedScreen = null }
    val expanded = expandedScreen
    if (expanded != null && stage == Stage.ROOM) {
        val tile = roomState.tiles.find { it.participant == expanded.participant }
        val meta = tile?.videos?.find { it.device == expanded.device && it.role == dev.forgesworn.kithmoot.session.Roles.SCREEN }
        dev.forgesworn.kithmoot.ui.room.ScreenShareViewer(
            track = meta?.let { videos["${it.device}|${it.trackId}"] }, eglBase = model.eglBase,
            title = if (tile?.isSelf == true) "Your screen" else "${dev.forgesworn.kithmoot.ui.room.shortId(expanded.participant)}’s screen",
            inPictureInPicture = inPictureInPicture, onPopOut = onPopOut,
            onClose = { expandedScreen = null },
        )
        return
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
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
    ) { padding ->
        when (stage) {
            Stage.START -> StartScreen(
                state = startState,
                onRoomNameChanged = model::onRoomNameChanged,
                onJoinUrlChanged = model::onJoinUrlChanged,
                onRelaysChanged = model::onRelaysChanged,
                onStartRoom = model::startRoom,
                onJoin = { model.joinFromUrl(startState.joinUrl) },
                onReopen = model::reopenRoom,
                onForget = model::forgetRoom,
                onRename = model::renameRoom,
                onRetryStorage = model::refreshSavedRooms,
                onResetStorage = model::resetSavedRooms,
                modifier = Modifier.padding(padding),
            )

            Stage.ROOM -> RoomScreen(
                state = roomState,
                videos = videos,
                eglBase = model.eglBase,
                onToggleMic = {
                    if (roomState.micOn) {
                        model.toggleMicrophone()
                    } else {
                        asker.ask(
                            PermissionAsk(
                                permission = Manifest.permission.RECORD_AUDIO,
                                title = "Microphone",
                                why = "So the room can hear you. Only one of your devices " +
                                    "has a live microphone at a time.",
                                refused = "No microphone, so nobody can hear you.",
                                onGranted = model::toggleMicrophone,
                            ),
                        )
                    }
                },
                onToggleAgentsMayHear = { model.setAgentsMayHear(!roomState.agentsMayHear) },
                onToggleCamera = {
                    if (roomState.cameraOn) {
                        model.toggleCamera()
                    } else {
                        asker.ask(
                            PermissionAsk(
                                permission = Manifest.permission.CAMERA,
                                title = "Camera",
                                why = "So the room can see you. Nothing is recorded and " +
                                    "the video does not pass through a server.",
                                refused = "No camera, so your tile stays a placeholder.",
                                onGranted = model::toggleCamera,
                            ),
                        )
                    }
                },
                onSwitchCamera = model::switchCamera,
                onToggleScreenShare = {
                    if (roomState.screenOn) model.stopScreenShare() else requestScreenShare()
                },
                onOpenChat = { chatOpen = true },
                onExpandScreen = { expandedScreen = it; chatOpen = false },
                onAddDevice = model::mintPairingLink,
                onRotateInvitation = model::rotateInvitation,
                onLeave = model::leave,
                modifier = Modifier.padding(padding),
            )
        }
    }

    LaunchedEffect(stage) { if (stage == Stage.START) chatOpen = false }

    if (chatOpen) {
        ModalBottomSheet(
            onDismissRequest = { chatOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            ChatPane(
                messages = roomState.chat,
                selfParticipant = roomState.selfParticipant,
                onSend = model::sendChat,
                onReact = model::react,
                profilesEnabled = roomState.profilesEnabled,
                profiles = roomState.profiles,
                onProfilesEnabled = model::setProfilesEnabled,
                modifier = Modifier.fillMaxHeight(0.9f),
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
