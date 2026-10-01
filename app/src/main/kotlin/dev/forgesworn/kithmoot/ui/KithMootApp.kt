package dev.forgesworn.kithmoot.ui

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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.rememberCoroutineScope
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
enum class HomePage { ROOMS, SETTINGS, PROJECTS }

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
) {
    val stage by model.stage.collectAsState()
    val startState by model.start.collectAsState()
    val roomState by model.room.collectAsState()
    val videos by model.videos.collectAsState()

    // System back inside a room does what the room's own back arrow does,
    // rather than sending the app to the background: on Android 12 and
    // later a root activity is moved back rather than finished, so nothing
    // was lost, but back did not go up a level (design-home-rooms.md Q11).
    val roomBack = { if (roomState.onCall && onRoomsKeepingCall != null) onRoomsKeepingCall() else model.leave() }
    androidx.activity.compose.BackHandler(enabled = stage == Stage.ROOM && !inPictureInPicture, onBack = roomBack)

    // Settings and Projects are pushed over home; back returns to the rooms
    // list rather than leaving the app (design-home-rooms.md section 7).
    var homePage by rememberSaveable { mutableStateOf(HomePage.ROOMS) }
    androidx.activity.compose.BackHandler(enabled = stage == Stage.START && homePage != HomePage.ROOMS) { homePage = HomePage.ROOMS }
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
            title = if (tile?.isSelf == true) "Your screen" else "${dev.forgesworn.kithmoot.ui.room.shortId(expanded.participant)}’s screen",
            inPictureInPicture = inPictureInPicture, onPopOut = onPopOut,
            shareId = meta?.trackId, marks = roomState.shareMarks[meta?.trackId].orEmpty(),
            onAnnotation = model::drawOnShare,
            onClose = { expandedScreen = null },
        )
        return
    }

    val accountMenu: @Composable () -> Unit = {
                val account = accountModel
                val accountState by account.start.collectAsState()
                dev.forgesworn.kithmoot.ui.start.AccountMenu(accountState, account.accountRelayChoices(), stage == Stage.ROOM,
                    dev.forgesworn.kithmoot.ui.start.AccountActions(
                        account::refreshSigners, account::signInWithSignerApp, account::signInWithSignet,
                        account::signInWithBunker, account::cancelSignIn, account::signOut, account::provisionRendezvous, account::dismissSignInError),
                    dev.forgesworn.kithmoot.ui.start.AccountSettingsActions(
                        loadProfile = account::loadEditableProfile, publishProfile = account::publishProfile,
                        saveRelays = account::saveAccountRelays, publishRelays = account::publishAccountRelayList,
                        loadDmRelays = account::loadDmRelays, publishDmRelays = account::publishDmRelays,
                        retrySync = { account.refreshRoomBookmarks(); account.refreshSharedProjects() },
                        circleBoxes = account::onCircleBoxesChanged, signOut = account::signOutFromAccountMenu,
                    ) , showProfilePicture = stage != Stage.ROOM || !roomState.anonymous,
                    notificationSettings = {
                        val ringRoom = if (stage == Stage.ROOM) object : dev.forgesworn.kithmoot.notifications.CallRingRoom {
                            override val roomId = roomState.roomId
                            override fun mode() = model.callRingMode(roomState.roomId)
                            override fun setMode(mode: dev.forgesworn.kithmoot.notifications.CallRingMode) = model.setCallRingMode(roomState.roomId, mode)
                        } else null
                        dev.forgesworn.kithmoot.notifications.NotificationSettings(account.notifications, ringRoom)
                    })
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            Column {
                dock?.invoke()
                // Settings and Projects bring their own app bar; home's stays
                // hidden underneath so there is only ever one visible.
                if (stage == Stage.START && homePage == HomePage.ROOMS) TopAppBar(
                    title = {
                        // Capped so the in-app text size (up to 1.5x) never
                        // clips inside the 64 dp bar at the top of its range (F13).
                        val density = androidx.compose.ui.platform.LocalDensity.current
                        val cappedSize = (22f * minOf(density.fontScale, 1.5f) / density.fontScale)
                        Text("KithMoot", style = MaterialTheme.typography.titleLarge.copy(fontSize = cappedSize.sp), maxLines = 1)
                    },
                    actions = {
                        androidx.compose.material3.IconButton({ homePage = HomePage.SETTINGS }) {
                            androidx.compose.material3.Icon(Icons.Filled.Settings, "Settings")
                        }
                    },
                    // The dock above has already cleared the status bar.
                    windowInsets = if (dock != null) WindowInsets(0, 0, 0, 0) else TopAppBarDefaults.windowInsets,
                )
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
      Box(if (dock != null) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier) {
        val padding = scaffoldPadding
        when (stage) {
            Stage.START -> {
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
                    loadProfile = model::loadEditableProfile, publishProfile = model::publishProfile,
                    saveRelays = model::saveAccountRelays, publishRelays = model::publishAccountRelayList,
                    loadDmRelays = model::loadDmRelays, publishDmRelays = model::publishDmRelays,
                    retrySync = { model.refreshRoomBookmarks(); model.refreshSharedProjects() },
                    circleBoxes = model::onCircleBoxesChanged, signOut = model::signOutFromAccountMenu,
                )
                val homeProjectActions = dev.forgesworn.kithmoot.ui.start.ProjectActions(
                    refresh = model::refreshSharedProjects,
                    retry = model::retryProjectSends,
                    follow = model::followSharedProject,
                    open = model::openSharedProjectRoom,
                    save = model::saveSharedProject,
                    rooms = model::availableProjectRooms,
                )
                when (homePage) {
                    HomePage.ROOMS -> StartScreen(
                        state = startState,
                        onRoomNameChanged = model::onRoomNameChanged,
                        onJoinUrlChanged = model::onJoinUrlChanged,
                        onRelaysChanged = model::onRelaysChanged,
                        onAnonymousModeChanged = model::onAnonymousModeChanged,
                        onPersistentGroupChanged = model::onPersistentGroupChanged,
                        onStartRoom = model::startRoom,
                        onJoin = { model.joinFromUrl(startState.joinUrl) },
                        onReopen = { id -> if (id == callRoomId) onBackToCall() else model.reopenRoom(id) },
                        onForget = model::forgetRoom,
                        onRename = model::renameRoom,
                        onProject = model::setRoomProject,
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
                        modifier = Modifier.padding(padding),
                        callRoomId = callRoomId,
                        onStopOpening = model::stopOpening,
                        onOpenProjects = { homePage = HomePage.PROJECTS },
                        onSignIn = { signInSheetOpen = true },
                        onShareInvite = { id ->
                            homeCoroutines.launch {
                                model.inviteLinkFor(id)?.let { link -> share(context, link, "Send invite link") }
                            }
                        },
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
                        notificationSettings = { dev.forgesworn.kithmoot.notifications.NotificationSettings(model.notifications, null, showHeading = false) },
                        onBack = { homePage = HomePage.ROOMS },
                    )
                    HomePage.PROJECTS -> ProjectsScreen(startState, homeProjectActions, onBack = { homePage = HomePage.ROOMS })
                }
                if (signInSheetOpen) SignInSheet(startState, homeAccountActions, onDismiss = { signInSheetOpen = false })
            }

            Stage.ROOM -> roomUiState.SaveableStateProvider("${roomState.selfParticipant}:${roomState.roomId}") {
                RoomScreen(
                    state = roomState,
                    accountMenu = accountMenu,
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
                    onChooseBackground = model::chooseBackground,
                    onToggleScreenShare = {
                        if (roomState.screenOn) model.stopScreenShare() else requestScreenShare()
                    },
                    onExpandScreen = { expandedScreen = it },
                    onAddDevice = model::mintPairingLink,
                    onStartPrivateConversation = model::startPrivateConversation,
                    onRefreshCadence = model::refreshCadence,
                    onCompareRoomHistory = model::compareRoomHistoryWithBothy,
                    onFetchRoomHistory = model::fetchComparedHistoryFromBothy,
                    onOfferRoomHistory = model::offerComparedHistoryToBothy,
                    onStartCadence = model::startCadence,
                    onStopCadence = model::stopCadence,
                    onRetryRoomUpdate = model::retryRoomUpdate,
                    onOpenCards = { cardsOpen = true },
                    onSearch = { searchOpen = !searchOpen },
                    onProfilesEnabled = model::setProfilesEnabled,
                    onMirrorSelf = model::setMirrorSelf,
                    onSetVolume = model::setCallVolume,
                    onListenHere = model::listenOnThisDevice,
                    onLeaveCall = model::leaveCall,
                    onJoinCall = model::joinCall,
                    onRotateInvitation = model::rotateInvitation,
                    inPictureInPicture = inPictureInPicture,
                    onPopOut = onPopOut,
                    onLeave = model::leave,
                    onBack = roomBack,
                    modifier = Modifier.padding(padding),
                    work = { dev.forgesworn.kithmoot.ui.room.WorkPane(roomState,model::submitWork,model::retryWork,model::refreshWorkActions) },
                    chat = {
                        ChatPane(
                            messages = roomState.chat,
                            onReadingChanged = model::notificationReading,
                            latestRequest = roomState.notificationChatRequest,
                            selfParticipant = roomState.selfParticipant,
                            onSend = model::sendChat,
                            onReact = model::react,
                            onOpenPrivateConversation = model::openPrivateConversation,
                            profilesEnabled = roomState.profilesEnabled,
                            profiles = roomState.profiles,
                            onProfilesEnabled = model::setProfilesEnabled,
                            lane = roomState.lane,
                            quiet = roomState.quiet,
                            quietCanSend = roomState.quietCanSend,
                            canSend = roomState.movedOn == null,
                            sending = roomState.chatSending,
                            pending = roomState.chatPending,
                            onRetryPending = model::retryPendingChat,
                            onDiscardPending = model::discardPendingChat,
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
        if (stage == Stage.ROOM) {
            val ringBanner by model.callRingBanner.collectAsState()
            // Answering here is the same as answering the notification:
            // straight in, microphone live. A refused mic joins muted.
            val answerWithMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                model.joinCall(micOn = granted)
            }
            ringBanner?.let { call ->
                dev.forgesworn.kithmoot.notifications.IncomingCallBanner(
                    callerLabel = dev.forgesworn.kithmoot.ui.room.shortId(call.caller),
                    onAnswer = {
                        model.dismissCallRingBanner()
                        if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) model.joinCall(micOn = true)
                        else answerWithMic.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onDismiss = model::dismissCallRingBanner,
                    modifier = Modifier.align(androidx.compose.ui.Alignment.TopCenter).padding(top = 12.dp),
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
