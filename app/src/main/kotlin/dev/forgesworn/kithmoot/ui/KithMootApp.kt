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
/** How often the restore witness's retiring duty runs while the app is in the foreground. */
private const val RETIRING_DUTY_INTERVAL_MILLIS = 15 * 60 * 1000L

enum class HomePage { ROOMS, SETTINGS, PROJECTS, RESTORE_WITNESS }

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
    /** A call answered on a phone still locked: its call view and nothing
     *  else - no rooms list, chat or settings - until the phone is unlocked. */
    lockedCallOnly: Boolean = false,
    onUnlock: () -> Unit = {},
    /** An answered call is opening or joining, over the lock screen or not: no prompt may cover it. */
    callAnswering: Boolean = false,
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
    // Locked: back goes nowhere the lock screen should be covering.
    androidx.activity.compose.BackHandler(enabled = lockedCallOnly) { }

    // Settings and Projects are pushed over home; back returns to the rooms
    // list rather than leaving the app (design-home-rooms.md section 7).
    var homePage by rememberSaveable { mutableStateOf(HomePage.ROOMS) }
    // Restore witness opens from Settings or from its banner; back returns there.
    var witnessFrom by rememberSaveable { mutableStateOf(HomePage.SETTINGS) }
    androidx.activity.compose.BackHandler(enabled = stage == Stage.START && homePage != HomePage.ROOMS) {
        homePage = if (homePage == HomePage.RESTORE_WITNESS) witnessFrom else HomePage.ROOMS
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
    // The restore witness (P3-03b-2): debug builds only. No witness traffic
    // while a Tor-only room is open (C7); the retiring duty runs at open and
    // on a timer while the app is in the foreground.
    val restoreWitness = remember(context) { (context.applicationContext as? dev.forgesworn.kithmoot.KithMootApplication)?.restoreWitness }
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
            title = if (tile?.isSelf == true) "Your screen" else "${dev.forgesworn.kithmoot.ui.room.shortId(expanded.participant)}’s screen",
            inPictureInPicture = inPictureInPicture, onPopOut = onPopOut,
            shareId = meta?.trackId, marks = roomState.shareMarks[meta?.trackId].orEmpty(),
            onAnnotation = model::drawOnShare,
            onClose = { expandedScreen = null },
        )
        return
    }

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
                        loadProfile = account::loadEditableProfile, publishProfile = account::publishProfile,
                        saveRelays = account::saveAccountRelays, publishRelays = account::publishAccountRelayList,
                        loadDmRelays = account::loadDmRelays, publishDmRelays = account::publishDmRelays,
                        retrySync = { account.refreshRoomBookmarks(); account.refreshSharedProjects() },
                        circleBoxes = account::onCircleBoxesChanged, signOut = account::signOutFromAccountMenu,
                        leaveRoom = model::leave,
                    ) , showProfilePicture = stage != Stage.ROOM || !roomState.anonymous,
                    torOnlyRoom = stage == Stage.ROOM && roomState.anonymous,
                    notificationSettings = {
                        val ringRoom = if (stage == Stage.ROOM) object : dev.forgesworn.kithmoot.notifications.CallRingRoom {
                            override val roomId = roomState.roomId
                            override fun mode() = model.callRingMode(roomState.roomId)
                            override fun setMode(mode: dev.forgesworn.kithmoot.notifications.CallRingMode) = model.setCallRingMode(roomState.roomId, mode)
                        } else null
                        dev.forgesworn.kithmoot.notifications.NotificationSettings(account.notifications, ringRoom, prompt = promptFor(null))
                    })
    }

    // Calls cannot be answered until the signer confirms this phone again: said on the
    // rooms list and in the room, for as long as it is true, whatever became of the notification.
    val reachBanner = if (lockedCallOnly || inPictureInPicture) null else when (stage) {
        Stage.START -> if (homePage == HomePage.ROOMS) promptFor(null) ?: dev.forgesworn.kithmoot.account.witnessBanner(witnessStatus)?.let {
            dev.forgesworn.kithmoot.service.ReachabilityPrompt(it, busy = false) { witnessFrom = HomePage.ROOMS; homePage = HomePage.RESTORE_WITNESS }
        } else null
        // A room that is not set to Ring me has nothing to say about ringing being off.
        Stage.ROOM -> if (roomState.onCall || (ringingOff && model.callRingMode(roomState.roomId) != dev.forgesworn.kithmoot.notifications.CallRingMode.RING)) null
            else promptFor(roomState.roomId)
    }
    // Under a dock or the banner the status bar is already cleared.
    val topCleared = dock != null || reachBanner != null

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            Column {
                dock?.invoke()
                reachBanner?.let {
                    ReachabilityBannerView(it.banner, it.busy, onConfirm = it.onAction, clearStatusBar = dock == null)
                }
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
                    // The dock or banner above has already cleared the status bar.
                    windowInsets = if (topCleared) WindowInsets(0, 0, 0, 0) else TopAppBarDefaults.windowInsets,
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
      Box(if (topCleared) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier) {
        val padding = scaffoldPadding
        if (lockedCallOnly && stage != Stage.ROOM) {
            // The room is still opening: never the rooms list over the lock screen.
            Column(Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center, horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                Text("Joining the call…", style = MaterialTheme.typography.headlineSmall)
            }
        } else when (stage) {
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
                        onConferenceLengthChanged = model::onConferenceLengthChanged,
                        onJoin = { model.joinFromUrl(startState.joinUrl) },
                        onReopen = { id -> if (id == callRoomId) onBackToCall() else model.reopenRoom(id) },
                        onForget = model::forgetRoom,
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
                        notificationSettings = { dev.forgesworn.kithmoot.notifications.NotificationSettings(model.notifications, null, showHeading = false, prompt = promptFor(null)) },
                        onBack = { homePage = HomePage.ROOMS },
                        onRestoreWitness = restoreWitness?.let { { witnessFrom = HomePage.SETTINGS; homePage = HomePage.RESTORE_WITNESS } },
                    )
                    HomePage.RESTORE_WITNESS -> restoreWitness?.let {
                        dev.forgesworn.kithmoot.ui.start.RestoreWitnessScreen(it, witnessPersona, onBack = { homePage = witnessFrom })
                    }
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
                                    refused = "No microphone, so nobody can hear you.",
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
                        if (roomState.screenOn) model.stopScreenShare() else if (model.mayShareScreen()) requestScreenShare()
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
                    onRenewCadence = model::renewCadence,
                    onRetryRoomUpdate = model::retryRoomUpdate,
                    onDismissEpochTrouble = model::dismissEpochTrouble,
                    onAnswerLetIn = model::answerLetIn,
                    onRenameRoom = model::renameRoomForEveryone,
                    onOpenCards = { cardsOpen = true },
                    onSearch = { searchOpen = !searchOpen },
                    onProfilesEnabled = model::setProfilesEnabled,
                    onMirrorSelf = model::setMirrorSelf,
                    onSetVolume = model::setCallVolume,
                    onListenHere = model::listenOnThisDevice,
                    onLeaveCall = model::leaveCall,
                    onJoinCall = model::joinCall,
                    onRaiseHand = model::raiseHand,
                    onAnswerRecordingConsent = model::answerRecordingConsent,
                    onRotateInvitation = model::rotateInvitation,
                    inPictureInPicture = inPictureInPicture,
                    onPopOut = onPopOut,
                    lockedCallOnly = lockedCallOnly,
                    onUnlock = onUnlock,
                    onLeave = model::leave,
                    onBack = roomBack,
                    modifier = Modifier.padding(padding),
                    work = { dev.forgesworn.kithmoot.ui.room.WorkPane(roomState,model::submitWork,model::retryWork,model::refreshWorkActions) },
                    chat = {
                        ChatPane(
                            messages = roomState.chat,
                            notes = roomState.roomNotes,
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
                            torOnly = roomState.anonymous,
                            relaysUp = roomState.relaysUp,
                            quiet = roomState.quiet,
                            quietCanSend = roomState.quietCanSend,
                            canSend = roomState.movedOn == null && !roomState.conferenceEnded,
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
