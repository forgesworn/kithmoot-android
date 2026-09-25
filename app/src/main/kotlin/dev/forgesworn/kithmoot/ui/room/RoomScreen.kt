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
    onRetryRoomUpdate: () -> Unit = {},
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
    var inviteOpen by rememberSaveable(state.roomId, state.selfParticipant) { mutableStateOf(false) }
    var privateOpen by rememberSaveable(state.roomId, state.selfParticipant) { mutableStateOf(false) }
    var detailsOpen by rememberSaveable(state.roomId) { mutableStateOf(false) }
    var backgroundOpen by rememberSaveable(state.roomId, state.selfParticipant) { mutableStateOf(false) }
    val chatState = rememberSaveableStateHolder()

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
    val callShowing = callOpen && !state.anonymous && !state.chatOnly && state.mediaRunning && state.movedOn == null
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
        AlertDialog(
            onDismissRequest = { inviteOpen = false },
            title = { Text("Invite people") },
            text = { ShareRoomRow(state.joinUrl) },
            confirmButton = { TextButton(onClick = { inviteOpen = false }) { Text("Done") } },
        )
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
                Text(relayLine(state), style = MaterialTheme.typography.bodyMedium)
                if (state.privateConversation) Text("Two-person room", style = MaterialTheme.typography.bodyMedium)
                if (state.secondary) Text("You are here as another of your own devices.")
                if (state.anonymous) Text("Anonymous carrier: this room uses only Orbot and v3 onion relays. Accounts, Bothy, profiles, agents and audio/video are unavailable here.")
                if (!state.privateConversation) TextButton(onClick = { detailsOpen = false; inviteOpen = true },
                    enabled = state.movedOn == null && state.joinUrl.isNotBlank() && !state.privateConversationBusy) { Text("Invite people") }
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
                    state.cadence?.let { CadencePanel(it, onRefreshCadence, onStartCadence, onStopCadence) }
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
        Header(state, onBack, { detailsOpen = true }, { callOpen = false; workOpen = false; onSearch() }, accountMenu)
        TabRow(selectedTabIndex = if (state.anonymous) 0 else if (callOpen) 2 else if (workOpen) 1 else 0) {
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
        if (!barLeaves && !state.anonymous && !state.chatOnly && (state.mediaRunning || callOpen || state.callChanging || state.mediaStarting || state.callOtherDevices > 0)) {
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
        if (state.movedOn != null) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                RoomUpdatePanel(state.roomUpdate, state.notice, onRetryRoomUpdate)
            }
        }
       }
      }

        if (!state.anonymous && workOpen) {
            Box(Modifier.weight(1f).navigationBarsPadding()) {
                chatState.SaveableStateProvider("work:${state.selfParticipant}:${state.roomId}") { work() }
            }
        } else if (state.anonymous || state.chatOnly || !callOpen) {
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
                    alone = { AlonePanel(state, onRotateInvitation) },
                )
                Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.mediaRunning) SpeakingLine(state, Modifier.align(Alignment.CenterHorizontally))
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
                    onOpenChat = { callOpen = false },
                    onMore = { moreOpen = true },
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
            }
            when (cadence.state) {
                "off" -> TextButton(onClick = onStart, enabled = cadence.eligible && !cadence.busy) { Text("Schedule") }
                "staged", "active" -> TextButton(onClick = onStop, enabled = !cadence.busy) { Text("Stop") }
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
private fun Header(state: RoomState, onLeave: () -> Unit, onDetails: () -> Unit, onSearch: () -> Unit, accountMenu: @Composable () -> Unit) {
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

@Composable
private fun AlonePanel(state: RoomState, onRotateInvitation: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(20.dp),
    ) {
        Text(
            text = "Room's open. Send the link.",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (state.deviceCount > 1) {
                // Your second device is not company. Saying so here is the same
                // claim the tile makes, at the moment it would otherwise look
                // like the room miscounted.
                "Nobody else is here yet. Your other device is still you."
            } else {
                "Nobody else is here yet. Anyone forwarded the current link can walk in. " +
                    "It is an invitation, not the room's traffic key. Keep this device " +
                    "online so it can answer new arrivals."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        ShareRoomRow(state.joinUrl)
        if (state.canRotateInvitation) {
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onRotateInvitation,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                Text("Rotate link", style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "The old link stops admitting new people. Anyone already in the room stays.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
        "updating" -> "Updating this secure room"
        "recovery" -> "Room update needs attention"
        else -> "This room has moved on"
    }
    val message = detail ?: when (state) {
        "removed" -> "This device was not given the successor key and cannot rejoin or publish."
        "closed" -> "The authority ended this room. This device will not rejoin or publish."
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
