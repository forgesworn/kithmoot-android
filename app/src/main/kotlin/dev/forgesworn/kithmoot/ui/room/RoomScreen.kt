package dev.forgesworn.kithmoot.ui.room

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.QrCode2
import dev.forgesworn.kithmoot.session.conferenceEndedMessage
import dev.forgesworn.kithmoot.session.conferenceEndsLine
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Checkbox
import androidx.compose.material.icons.filled.CallEnd
import dev.forgesworn.kithmoot.media.effects.SeaScene
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TextButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.ui.RoomState
import org.webrtc.EglBase
import org.webrtc.VideoTrack

/**
 * Conversation is the default room surface. Opening the call view reveals
 * media controls without changing capture or agent-listening permissions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomScreen(
    state: RoomState,
    videos: Map<String, VideoTrack>,
    eglBase: EglBase?,
    onToggleMic: () -> Unit,
    onToggleAgentsMayHear: () -> Unit,
    onToggleCamera: () -> Unit,
    onSwitchCamera: () -> Unit,
    onToggleScreenShare: () -> Unit,
    onAddDevice: () -> Unit,
    onRotateInvitation: () -> Unit,
    onLeave: () -> Unit,
    /** The header's back arrow. Leaves, unless a call should be kept. */
    onBack: () -> Unit = onLeave,
    modifier: Modifier = Modifier,
    onExpandScreen: (SharedScreen) -> Unit = {},
    /** Choose what is drawn behind this device's camera, or nothing. Defaulted
     *  so the positional call sites in the instrumented tests keep working. */
    onChooseBackground: (SeaScene?, Boolean) -> Unit = { _, _ -> },
    onOpenCards: () -> Unit = {},
    onSetVolume: (String, Float) -> Unit = { _, _ -> },
    work: @Composable () -> Unit = {},
    chat: @Composable () -> Unit,
    onStartPrivateConversation: (String) -> Unit = {},
    onRefreshCadence: () -> Unit = {},
    onCompareRoomHistory: () -> Unit = {},
    onFetchRoomHistory: () -> Unit = {},
    onOfferRoomHistory: () -> Unit = {},
    onStartCadence: () -> Unit = {},
    onStopCadence: () -> Unit = {},
    onRenewCadence: () -> Unit = {},
    onRetryRoomUpdate: () -> Unit = {},
    /** Rename the room for everybody in it. */
    onRenameRoom: (String) -> Unit = {},
    accountMenu: @Composable () -> Unit = {},
    onSearch: () -> Unit = {},
    onProfilesEnabled: (Boolean) -> Unit = {},
    onMirrorSelf: (Boolean) -> Unit = {},
    onListenHere: () -> Unit = {},
    onLeaveCall: () -> Unit = {},
    onJoinCall: () -> Unit = {},
    /** The activity is in system picture-in-picture: only the call's picture. */
    inPictureInPicture: Boolean = false,
    /** Opens system picture-in-picture, where the device has it. */
    onPopOut: (() -> Unit)? = null,
    /** A call answered on a phone that is still locked: the call view alone,
     *  with no header, tabs, chat or other rooms until it is unlocked. */
    lockedCallOnly: Boolean = false,
    /** Asks Android to unlock, from anything the locked call view leaves out. */
    onUnlock: () -> Unit = {},
) {
    var callOpen by rememberSaveable(state.roomId, state.selfParticipant) { mutableStateOf(false) }
    var moreOpen by rememberSaveable(state.roomId, state.selfParticipant) { mutableStateOf(false) }
    var preferGrid by rememberSaveable(state.roomId) { mutableStateOf(false) }
    var swapped by rememberSaveable(state.roomId) { mutableStateOf(false) }
    var selfHidden by rememberSaveable(state.roomId) { mutableStateOf(false) }
    var workOpen by rememberSaveable(state.roomId, state.selfParticipant) { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(state.notificationChatRequest) {
        if (state.notificationChatRequest > 0) { callOpen = false; workOpen = false }
    }
    androidx.compose.runtime.LaunchedEffect(state.callViewRequest, lockedCallOnly) {
        if (state.callViewRequest > 0 || lockedCallOnly) { callOpen = true; workOpen = false }
    }
    // Read through these, never the raw flags: while locked nothing but the
    // call shows, not even for the frame before the effect above runs.
    val showCall = callOpen || lockedCallOnly
    val showWork = workOpen && !lockedCallOnly
    var inviteOpen by rememberSaveable(state.roomId, state.selfParticipant) { mutableStateOf(false) }
    var privateOpen by rememberSaveable(state.roomId, state.selfParticipant) { mutableStateOf(false) }
    var detailsOpen by rememberSaveable(state.roomId) { mutableStateOf(false) }
    var backgroundOpen by rememberSaveable(state.roomId, state.selfParticipant) { mutableStateOf(false) }
    val chatState = rememberSaveableStateHolder()
    // Every member can share the room's link; a two-person conversation's
    // went to the other person sealed, and an ended room's no longer works.
    val canInvite = !state.privateConversation && state.movedOn == null && !state.conferenceEnded &&
        state.joinUrl.isNotBlank() && !state.privateConversationBusy

    // Keep the screen awake while this device is actually on the call, so a
    // dark timeout does not drop the video or make the mic button hard to
    // find mid-conversation. Cleared the moment the call ends or this screen
    // leaves composition, whichever comes first.
    val view = LocalView.current
    val onCall = inACall(
        onCall = state.onCall,
        mediaRunning = state.mediaRunning,
        micOn = state.micOn,
        cameraOn = state.cameraOn,
        screenOn = state.screenOn,
        connected = state.mediaConnections.values.any { it == "connected" || it == "completed" },
    )
    DisposableEffect(view, onCall) {
        view.keepScreenOn = onCall
        onDispose { view.keepScreenOn = false }
    }
    if (inPictureInPicture && (state.onCall || callOpen) && state.mediaRunning && !state.chatOnly) {
        PipCall(state, videos, eglBase, modifier)
        return
    }
    val callShowing = showCall && !state.anonymous && !state.chatOnly && state.mediaRunning && state.movedOn == null && !state.conferenceEnded
    val chrome = rememberCallChrome(
        mayHide = callShowing && controlsMayAutoHide(
            videoShowing = videoShowing(state, videos),
            // A sheet or dialog takes the window's focus: the controls
            // stay put under it and are there when it closes.
            windowFocused = LocalWindowInfo.current.isWindowFocused,
            accessibilityOn = rememberAccessibilityOn(),
        ),
    )
    val chromeVisible = !callShowing || chrome.visible
    if (moreOpen && callShowing) {
        val others = state.tiles.count { !it.isSelf }
        MoreCallSheet(
            state = state,
            layoutToggle = others >= SPEAKER_LAYOUT_FROM,
            preferGrid = preferGrid,
            selfHidden = selfHidden,
            canHideSelf = others > 0,
            onDismiss = { moreOpen = false },
            onSwitchCamera = onSwitchCamera,
            onOpenBackground = { backgroundOpen = true },
            onToggleScreenShare = onToggleScreenShare,
            onAddDevice = onAddDevice,
            onOpenCards = onOpenCards,
            onToggleLayout = { preferGrid = !preferGrid },
            onToggleSelfHidden = { selfHidden = !selfHidden; swapped = false },
            onToggleAgentsMayHear = onToggleAgentsMayHear,
            onPopOut = onPopOut,
            onInviteByQr = if (canInvite) ({ inviteOpen = true }) else null,
        )
    }
    if (backgroundOpen) {
        ModalBottomSheet(
            onDismissRequest = { backgroundOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            BackgroundSheet(
                choice = state.background,
                onChoose = onChooseBackground,
                onDone = { backgroundOpen = false },
            )
        }
    }
    if (inviteOpen) {
        // What the link is and what keeps it working is said here, to the
        // person inviting, not over their own picture on the call. The QR is
        // for somebody across the table: one tap from the details or the
        // call's More sheet.
        ModalBottomSheet(
            onDismissRequest = { inviteOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            InviteSheet(
                joinUrl = state.joinUrl,
                endsAt = state.endsAt,
                canRotateInvitation = state.canRotateInvitation,
                onRotateInvitation = onRotateInvitation,
                onDone = { inviteOpen = false },
            )
        }
    }
    if (privateOpen) {
        AlertDialog(
            onDismissRequest = { if (!state.privateConversationBusy) privateOpen = false },
            title = { Text("Start a private conversation") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Choose someone who is here. KithMoot creates a separate two-person room and asks your signer to seal its invitation to that account.")
                    state.privateConversationPeers.forEach { peer ->
                        OutlinedButton(
                            onClick = { privateOpen = false; onStartPrivateConversation(peer) },
                            enabled = !state.privateConversationBusy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(shortId(peer)) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { privateOpen = false }, enabled = !state.privateConversationBusy) { Text("Cancel") } },
        )
    }
    if (detailsOpen) {
        ModalBottomSheet(onDismissRequest = { detailsOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Room details", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { detailsOpen = false }) { Text("Done") }
            }
            Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.name.ifBlank { "Room" }, style = MaterialTheme.typography.titleMedium)
                // Any member may rename the room, and the name changes for
                // everybody in it: there is no private nickname beside it. Not
                // in a two-person room, whose title is the other person, nor in
                // an anonymous one, which follows no shared room state.
                if (!state.privateConversation && !state.anonymous && state.movedOn == null && !state.conferenceEnded) {
                    var newName by rememberSaveable(state.name) { mutableStateOf(state.name) }
                    Text("Rename this room for everyone in it.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(newName, { if (it.codePointCount(0, it.length) <= dev.forgesworn.kithmoot.protocol.DisplayName.MAX_LENGTH) newName = it }, Modifier.fillMaxWidth(), label = { Text("Room name") }, singleLine = true)
                    val clean = dev.forgesworn.kithmoot.protocol.DisplayName.sanitise(newName)
                    TextButton(onClick = { onRenameRoom(newName) }, enabled = clean != null && clean != state.name) { Text("Rename for everyone") }
                }
                Text(relayLine(state), style = MaterialTheme.typography.bodyMedium)
                if (state.privateConversation) Text("Two-person room", style = MaterialTheme.typography.bodyMedium)
                state.endsAt?.let {
                    Text(if (state.conferenceEnded) conferenceEndedMessage(it) else "Conference room. ${conferenceEndsLine(it)}",
                        style = MaterialTheme.typography.bodyMedium)
                }
                if (state.secondary) Text("You are here as another of your own devices.")
                if (state.anonymous) Text("Anonymous carrier: this room uses only Orbot and v3 onion relays. Accounts, Bothy, profiles, agents and audio/video are unavailable here.")
                if (!state.privateConversation) {
                    TextButton(onClick = { detailsOpen = false; inviteOpen = true }, enabled = canInvite) { Text("Invite people") }
                    TextButton(onClick = { detailsOpen = false; inviteOpen = true }, enabled = canInvite) {
                        Icon(Icons.Filled.QrCode2, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Invite by QR")
                    }
                }
                if (!state.anonymous) TextButton(onClick = { detailsOpen = false; onOpenCards() }) { Text("People") }
                TextButton(onClick = { detailsOpen = false; onAddDevice() }, enabled = state.canAddDevice && !state.privateConversationBusy) { Text("Add your device") }
                if (state.privateConversationPeers.isNotEmpty()) TextButton(onClick = { detailsOpen = false; privateOpen = true }, enabled = !state.privateConversationBusy) { Text("Start a private conversation") }
                if (!state.anonymous) {
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(state.profilesEnabled, onProfilesEnabled)
                        Text("Show public profiles")
                    }
                    Text("Profile lookups share participant keys with room relays and fetch pictures from their hosts. Names and pictures are self-reported.", style = MaterialTheme.typography.bodySmall)
                    // This device's own view of you, from its camera or your
                    // other device's. Everybody else always sees it the right way round.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(state.mirrorSelf, onMirrorSelf)
                        Text("Mirror my view")
                    }
                    Text("Show your own camera like a mirror. Others always see it the right way round.", style = MaterialTheme.typography.bodySmall)
                }
                if (state.movedOn == null && (state.cadence != null || state.nip77 != null)) {
                    HorizontalDivider()
                    state.cadence?.let { CadencePanel(it, onRefreshCadence, onStartCadence, onStopCadence, onRenewCadence) }
                    state.nip77?.let { Nip77Panel(it, onCompareRoomHistory, onFetchRoomHistory, onOfferRoomHistory) }
                }
                TextButton(onClick = { detailsOpen = false; onLeave() }) { Text("Leave room", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // Any touch on the call, handled or not, starts the controls'
            // timer again. Observed on the way down, never consumed.
            .pointerInput(callShowing) {
                if (!callShowing) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        chrome.touched()
                    }
                }
            },
    ) {
      AnimatedVisibility(chromeVisible, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
       Column {
        if (!lockedCallOnly) Header(state, onBack, { detailsOpen = true }, { callOpen = false; workOpen = false; onSearch() }, accountMenu,
            onInviteByQr = if (canInvite) ({ inviteOpen = true }) else null)
        if (!lockedCallOnly) TabRow(selectedTabIndex = if (state.anonymous) 0 else if (callOpen) 2 else if (workOpen) 1 else 0) {
            Tab(selected = state.anonymous || (!callOpen && !workOpen), onClick = { callOpen = false; workOpen = false }, text = { Text("Chat") })
            if (!state.anonymous) Tab(selected = workOpen, onClick = { callOpen = false; workOpen = true }, text = {
                val decisions=state.work.assignments.count{it.creator==state.selfParticipant&&it.needsDecision}
                Text(if(decisions>0)"Work · $decisions" else "Work")
            })
            if (!state.anonymous && !state.chatOnly) Tab(selected = callOpen, onClick = { callOpen = true; workOpen = false }, text = {
                // "A call is on" is what the roster says, not what this phone
                // happens to have negotiated: somebody on the call with
                // everything switched off is still a call worth a badge.
                Text(
                    when {
                        state.mediaConnections.values.any { it == "connected" || it == "completed" } -> "Call · live"
                        state.callOtherDevices > 0 -> "Call · on"
                        else -> "Call"
                    },
                )
            })
        }
        // What the control is for, computed the way the web client computes it
        // - from what OTHER devices say, never from the roster's count of
        // calls - so "Start call" and "Join call" mean the same thing on both.
        val stance = callStance(
            mineOn = state.onCall,
            otherDevicesOn = state.callOtherDevices,
            leaving = state.callChanging,
        )
        // On the call view the control bar carries Leave, so the row is only
        // for joining, and for saying what is happening while it changes.
        val barLeaves = callShowing && state.onCall && !state.callChanging
        if (!barLeaves && !state.anonymous && !state.chatOnly && !state.conferenceEnded && (state.mediaRunning || callOpen || state.callChanging || state.mediaStarting || state.callOtherDevices > 0)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        state.callChanging -> "Leaving call…"
                        state.callJoinPending -> JOIN_PENDING_LABEL
                        state.mediaStarting -> "Audio and video are starting…"
                        else -> callStanceTitle(stance)
                    },
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = { if (state.onCall) { onLeaveCall(); callOpen = false; workOpen = false } else onJoinCall() },
                    enabled = !state.callChanging && !state.callJoinPending,
                    colors = if (state.onCall) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError) else ButtonDefaults.buttonColors(),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    if (state.onCall) { Icon(Icons.Filled.CallEnd, null); Spacer(Modifier.width(8.dp)) }
                    Text(callStanceLabel(stance))
                }
            }
        }
        if (state.movedOn != null || state.conferenceEnded) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                RoomUpdatePanel(if (state.conferenceEnded) "ended" else state.roomUpdate, state.notice, onRetryRoomUpdate)
            }
        }
       }
      }

        if (!state.anonymous && showWork) {
            Box(Modifier.weight(1f).navigationBarsPadding()) {
                chatState.SaveableStateProvider("work:${state.selfParticipant}:${state.roomId}") { work() }
            }
        } else if (state.anonymous || state.chatOnly || !showCall) {
            if (state.privateConversationBusy) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
            Box(Modifier.weight(1f).navigationBarsPadding()) {
                chatState.SaveableStateProvider("${state.selfParticipant}:${state.roomId}") { chat() }
            }
        } else {

            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (!state.mediaRunning) Text("Join the call to see and hear everyone.", Modifier.align(Alignment.Center).padding(24.dp))
                else CallView(
                    state = state,
                    videos = videos,
                    eglBase = eglBase,
                    chrome = chrome,
                    preferGrid = preferGrid,
                    swapped = swapped,
                    onSwap = { swapped = !swapped },
                    hideSelf = selfHidden,
                    onExpandScreen = onExpandScreen,
                    onSetVolume = onSetVolume,
                    alone = { AloneLine(state) },
                )
                // Bottom centre, clear of the front camera's cutout and above
                // the name plate each tile draws in its bottom corner.
                if (state.mediaRunning) SpeakingLine(
                    state,
                    Modifier.align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(start = 12.dp, end = 12.dp, bottom = 72.dp),
                )
                Column(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Only when it is not here: which device plays the call
                    // is worth a banner when it is surprising, not all day.
                    if (state.mediaRunning && !state.listeningHere) Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Call audio is on another of your devices", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(onListenHere) { Text("Listen here") }
                        }
                    }
                    if (state.mediaFault != null) FaultPanel(state.mediaFault)
                }
            }
            AnimatedVisibility(chromeVisible && state.movedOn == null && state.mediaRunning, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                CallControlsBar(
                    state = state,
                    onToggleMic = onToggleMic,
                    onToggleCamera = onToggleCamera,
                    onOpenChat = { if (lockedCallOnly) onUnlock() else callOpen = false },
                    onMore = { if (lockedCallOnly) onUnlock() else moreOpen = true },
                    onLeaveCall = { onLeaveCall(); callOpen = false; workOpen = false },
                )
            }
        }
    }
}

@Composable
private fun Nip77Panel(
    state: dev.forgesworn.kithmoot.ui.Nip77ViewState,
    onCompare: () -> Unit,
    onFetch: () -> Unit,
    onOffer: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Private history check", style = MaterialTheme.typography.titleSmall)
        Text(state.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onCompare, enabled = state.available && !state.busy) {
            Text(if (state.busy) "Comparing IDs…" else "Compare with Bothy")
        }
        if (state.fetchAvailable) {
            TextButton(onClick = onFetch, enabled = !state.busy) {
                Text("Fetch Bothy-only events")
            }
        }
        if (state.offerAvailable) {
            TextButton(onClick = onOffer, enabled = !state.busy) {
                Text("Offer phone-only events to Bothy")
            }
        }
    }
}

@Composable
private fun CadencePanel(
    cadence: dev.forgesworn.kithmoot.ui.CadenceViewState,
    onRefresh: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRenew: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Quiet schedule · ${cadence.state}", style = MaterialTheme.typography.titleSmall)
                Text(cadence.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (cadence.startEpoch != null && cadence.endEpoch != null) {
                    Text(
                        "${cadenceTime(cadence.startEpoch)} to ${cadenceTime(cadence.endEpoch)} · ${cadence.queueCount} queued · ${cadence.sentCount} sent · ${cadence.failedCount} failed",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                cadence.renewedUntilEpoch?.let {
                    Text(
                        "Renewed to ${cadenceTime(it)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when (cadence.state) {
                "off" -> TextButton(onClick = onStart, enabled = cadence.eligible && !cadence.busy) { Text("Schedule") }
                "staged", "active" -> Row {
                    if (cadence.renewable) TextButton(onClick = onRenew, enabled = !cadence.busy) { Text("Renew") }
                    TextButton(onClick = onStop, enabled = !cadence.busy) { Text("Stop") }
                }
                else -> TextButton(onClick = onRefresh, enabled = cadence.eligible && !cadence.busy) { Text("Retry") }
            }
        }
        if (cadence.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

private fun cadenceTime(epoch: Long): String = java.text.DateFormat.getDateTimeInstance(
    java.text.DateFormat.SHORT,
    java.text.DateFormat.SHORT,
).format(java.util.Date(Math.multiplyExact(epoch, 3_600_000L)))

@Composable
private fun Header(
    state: RoomState,
    onLeave: () -> Unit,
    onDetails: () -> Unit,
    onSearch: () -> Unit,
    accountMenu: @Composable () -> Unit,
    /** Shows the room's join link as a QR, with Share and Copy under it.
     *  Null where this person cannot invite, so the action is not shown. */
    onInviteByQr: (() -> Unit)? = null,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp).padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onLeave) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Leave room") }
            Column(Modifier.weight(1f).clickable(onClickLabel = "Room details", onClick = onDetails).padding(vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(state.name.ifBlank { "Room" }, Modifier.weight(1f, fill = false), maxLines = 1,
                        overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    Icon(Icons.Filled.ExpandMore, "Room details", Modifier.size(18.dp))
                }
                Text(if (state.relaysUp == 0) "Connecting…" else if (state.micOn || state.cameraOn || state.screenOn) { if (state.mediaConnections.values.any { it == "connected" || it == "completed" }) "Call connected" else "Connecting call…" } else if (state.privateConversation) "Private conversation" else "Room conversation",
                    style = MaterialTheme.typography.labelSmall, maxLines = 1,
                    color = if (state.relaysUp == 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onInviteByQr != null) IconButton(onClick = onInviteByQr) { Icon(Icons.Filled.QrCode2, "Invite by QR") }
            IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, "Search messages") }
            accountMenu()
        }
    }
}

private fun relayLine(state: RoomState): String = when {
    state.relaysTotal == 0 -> "No relays configured"
    state.relaysUp == 0 -> "No relay reachable. Nobody can see you yet"
    else -> "${state.relaysUp} of ${state.relaysTotal} relays up"
}

/**
 * Nobody else on the call yet: one line over your own picture, the way a
 * phone says "Ringing…", and nothing more. A two-person conversation has
 * nobody to invite, so it offers no link at all; its invitation already went
 * to the other person sealed. Any other room gets one way to send its link;
 * copying it, a new link and what a link is all live under Invite people in
 * the room's details.
 */
@Composable
private fun AloneLine(state: RoomState) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val offerLink = !state.privateConversation && state.movedOn == null && !state.conferenceEnded && state.joinUrl.isNotBlank()
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
    ) {
        Row(
            Modifier.heightIn(min = 48.dp).padding(start = 18.dp, end = if (offerLink) 6.dp else 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when {
                    state.privateConversation -> "Waiting for them to join…"
                    // Your second device is not company. Saying so here is the
                    // same claim the tile makes, at the moment it would
                    // otherwise look like the room miscounted.
                    state.deviceCount > 1 -> "Only your own devices are here"
                    else -> "Nobody else is here yet"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (offerLink) {
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = { dev.forgesworn.kithmoot.ui.share(context, state.joinUrl) }) { Text("Send link") }
            }
        }
    }
}

@Composable
private fun FaultPanel(message: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(20.dp),
    ) {
        Text(
            text = "No audio or video here",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "$message Presence and chat still work.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

/** A visible, retryable or terminal state while ordinary room traffic is blocked. */
@Composable
private fun RoomUpdatePanel(state: String?, detail: String?, onRetry: () -> Unit) {
    val title = when (state) {
        "removed" -> "You were removed from this room"
        "closed" -> "This room was closed"
        "ended" -> "This conference room has ended"
        "updating" -> "Updating this secure room"
        "recovery" -> "Room update needs attention"
        else -> "This room has moved on"
    }
    val message = detail ?: when (state) {
        "removed" -> "This device was not given the successor key and cannot rejoin or publish."
        "closed" -> "The authority ended this room. This device will not rejoin or publish."
        "ended" -> "Nothing more can be sent, and relays delete what was said."
        "updating" -> "Nothing will be sent under the previous room key while Bothy retires its old schedule."
        "recovery" -> "Nothing will be sent under the previous room key. Retry when the authority and Bothy are reachable."
        else -> "The room changed its key and this device cannot safely continue."
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(20.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        if (state == "updating" || state == "recovery") {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onRetry) { Text("Retry secure update") }
        }
    }
}
