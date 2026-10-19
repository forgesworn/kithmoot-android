package dev.forgesworn.kithmoot.ui.room

import androidx.compose.animation.AnimatedVisibility
import dev.forgesworn.kithmoot.epoch.NativeHostingState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.runtime.remember
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
    onDismissEpochTrouble: () -> Unit = {},
    onAnswerLetIn: (String, Boolean) -> Unit = { _, _ -> },
    onAnswerInvitationAdmission: (String, Boolean) -> Unit = { _, _ -> },
    onDismissInvitationAdmission: (String) -> Unit = {},
    /** Rename the room for everybody in it. */
    onRenameRoom: (String) -> Unit = {},
    onEditRoomLogo: () -> RoomLogoEditScope? = { null },
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
    /** Raise or lower this person's hand in meeting mode. */
    onRaiseHand: (Boolean) -> Unit = {},
    /** The answer to "This call is being recorded": join, or not now. */
    onAnswerRecordingConsent: (Boolean) -> Unit = {},
    onStartAudioRecording: () -> Unit = {},
    onStartVideoRecording: (dev.forgesworn.kithmoot.media.recording.RecordingVideoLayout,
        dev.forgesworn.kithmoot.media.recording.RecordingEndpointKey?) -> Unit = { _, _ -> },
    onStopRecording: () -> Unit = {},
    onToggleRecordingPause: () -> Unit = {},
    onRetryRecordingStop: () -> Unit = {},
    /** The host turns meeting mode on or off. */
    onSetMeetingMode: (Boolean) -> Unit = {},
    /** The host puts somebody on the meeting's stage, or takes them off it. */
    onSetSpeaker: (String, Boolean) -> Unit = { _, _ -> },
    onSelectSharingParticipant: (String, Boolean) -> Unit = { _, _ -> },
    onStartRoomSharing: () -> Unit = {},
    onStopRoomSharing: () -> Unit = {},
    workspaceTarget: dev.forgesworn.kithmoot.session.WorkspaceOrigin? = null,
    onChangeNativeRoomKey: ((NativeHostingState) -> Unit)? = null,
    onRemoveNativeRoomMember: ((NativeHostingState, String) -> Unit)? = null,
    onRetireNativeInvitation: ((NativeHostingState) -> Unit)? = null,
    onResendNativeRetirement: ((NativeHostingState, String) -> Unit)? = null,
    onRecoverNativePending: ((NativeHostingState) -> Unit)? = null,
    onCanShareInvitation: (NativeHostingState?) -> Boolean = { it == null },
    onReplaceNativeInvitation: ((NativeHostingState) -> Unit)? = null,
    onChooseFacePrivacy: (dev.forgesworn.kithmoot.media.effects.FacePrivacyMode) -> Unit = {},
) {
    RoomScreenContent(state, videos, eglBase, modifier, RoomScreenActions(
        onToggleMic = onToggleMic,
        onToggleAgentsMayHear = onToggleAgentsMayHear,
        onToggleCamera = onToggleCamera,
        onSwitchCamera = onSwitchCamera,
        onToggleScreenShare = onToggleScreenShare,
        onAddDevice = onAddDevice,
        onRotateInvitation = onRotateInvitation,
        onLeave = onLeave,
        onBack = onBack,
        onExpandScreen = onExpandScreen,
        onChooseBackground = onChooseBackground,
        onOpenCards = onOpenCards,
        onSetVolume = onSetVolume,
        work = work,
        chat = chat,
        onStartPrivateConversation = onStartPrivateConversation,
        onRefreshCadence = onRefreshCadence,
        onCompareRoomHistory = onCompareRoomHistory,
        onFetchRoomHistory = onFetchRoomHistory,
        onOfferRoomHistory = onOfferRoomHistory,
        onStartCadence = onStartCadence,
        onStopCadence = onStopCadence,
        onRenewCadence = onRenewCadence,
        onRetryRoomUpdate = onRetryRoomUpdate,
        onDismissEpochTrouble = onDismissEpochTrouble,
        onAnswerLetIn = onAnswerLetIn,
        onAnswerInvitationAdmission = onAnswerInvitationAdmission,
        onDismissInvitationAdmission = onDismissInvitationAdmission,
        onRenameRoom = onRenameRoom,
        onEditRoomLogo = onEditRoomLogo,
        accountMenu = accountMenu,
        onSearch = onSearch,
        onProfilesEnabled = onProfilesEnabled,
        onMirrorSelf = onMirrorSelf,
        onListenHere = onListenHere,
        onLeaveCall = onLeaveCall,
        onJoinCall = onJoinCall,
        inPictureInPicture = inPictureInPicture,
        onPopOut = onPopOut,
        lockedCallOnly = lockedCallOnly,
        onUnlock = onUnlock,
        onRaiseHand = onRaiseHand,
        onAnswerRecordingConsent = onAnswerRecordingConsent,
        onStartAudioRecording = onStartAudioRecording,
        onStartVideoRecording = onStartVideoRecording,
        onStopRecording = onStopRecording,
        onToggleRecordingPause = onToggleRecordingPause,
        onRetryRecordingStop = onRetryRecordingStop,
        onSetMeetingMode = onSetMeetingMode,
        onSetSpeaker = onSetSpeaker,
        onSelectSharingParticipant = onSelectSharingParticipant,
        onStartRoomSharing = onStartRoomSharing,
        onStopRoomSharing = onStopRoomSharing,
        workspaceTarget = workspaceTarget,
        onChangeNativeRoomKey = onChangeNativeRoomKey,
        onRemoveNativeRoomMember = onRemoveNativeRoomMember,
        onRetireNativeInvitation = onRetireNativeInvitation,
        onResendNativeRetirement = onResendNativeRetirement,
        onRecoverNativePending = onRecoverNativePending,
        onCanShareInvitation = onCanShareInvitation,
        onReplaceNativeInvitation = onReplaceNativeInvitation,
        onChooseFacePrivacy = onChooseFacePrivacy,
    ))
}

private class RoomScreenActions(
    val onToggleMic: () -> Unit,
    val onToggleAgentsMayHear: () -> Unit,
    val onToggleCamera: () -> Unit,
    val onSwitchCamera: () -> Unit,
    val onToggleScreenShare: () -> Unit,
    val onAddDevice: () -> Unit,
    val onRotateInvitation: () -> Unit,
    val onLeave: () -> Unit,
    val onBack: () -> Unit,
    val onExpandScreen: (SharedScreen) -> Unit,
    val onChooseBackground: (SeaScene?, Boolean) -> Unit,
    val onOpenCards: () -> Unit,
    val onSetVolume: (String, Float) -> Unit,
    val work: @Composable () -> Unit,
    val chat: @Composable () -> Unit,
    val onStartPrivateConversation: (String) -> Unit,
    val onRefreshCadence: () -> Unit,
    val onCompareRoomHistory: () -> Unit,
    val onFetchRoomHistory: () -> Unit,
    val onOfferRoomHistory: () -> Unit,
    val onStartCadence: () -> Unit,
    val onStopCadence: () -> Unit,
    val onRenewCadence: () -> Unit,
    val onRetryRoomUpdate: () -> Unit,
    val onDismissEpochTrouble: () -> Unit,
    val onAnswerLetIn: (String, Boolean) -> Unit,
    val onAnswerInvitationAdmission: (String, Boolean) -> Unit,
    val onDismissInvitationAdmission: (String) -> Unit,
    val onRenameRoom: (String) -> Unit,
    val onEditRoomLogo: () -> RoomLogoEditScope?,
    val accountMenu: @Composable () -> Unit,
    val onSearch: () -> Unit,
    val onProfilesEnabled: (Boolean) -> Unit,
    val onMirrorSelf: (Boolean) -> Unit,
    val onListenHere: () -> Unit,
    val onLeaveCall: () -> Unit,
    val onJoinCall: () -> Unit,
    val inPictureInPicture: Boolean,
    val onPopOut: (() -> Unit)?,
    val lockedCallOnly: Boolean,
    val onUnlock: () -> Unit,
    val onRaiseHand: (Boolean) -> Unit,
    val onAnswerRecordingConsent: (Boolean) -> Unit,
    val onStartAudioRecording: () -> Unit,
    val onStartVideoRecording: (dev.forgesworn.kithmoot.media.recording.RecordingVideoLayout,
        dev.forgesworn.kithmoot.media.recording.RecordingEndpointKey?) -> Unit,
    val onStopRecording: () -> Unit,
    val onToggleRecordingPause: () -> Unit,
    val onRetryRecordingStop: () -> Unit,
    val onSetMeetingMode: (Boolean) -> Unit,
    val onSetSpeaker: (String, Boolean) -> Unit,
    val onSelectSharingParticipant: (String, Boolean) -> Unit,
    val onStartRoomSharing: () -> Unit,
    val onStopRoomSharing: () -> Unit,
    val workspaceTarget: dev.forgesworn.kithmoot.session.WorkspaceOrigin?,
    val onChangeNativeRoomKey: ((NativeHostingState) -> Unit)?,
    val onRemoveNativeRoomMember: ((NativeHostingState, String) -> Unit)?,
    val onRetireNativeInvitation: ((NativeHostingState) -> Unit)?,
    val onResendNativeRetirement: ((NativeHostingState, String) -> Unit)?,
    val onRecoverNativePending: ((NativeHostingState) -> Unit)?,
    val onCanShareInvitation: (NativeHostingState?) -> Boolean,
    val onReplaceNativeInvitation: ((NativeHostingState) -> Unit)?,
    val onChooseFacePrivacy: (dev.forgesworn.kithmoot.media.effects.FacePrivacyMode) -> Unit,
)

/** Keep the public callback API out of the large Compose-generated method. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoomScreenContent(
    state: RoomState,
    videos: Map<String, VideoTrack>,
    eglBase: EglBase?,
    modifier: Modifier,
    actions: RoomScreenActions,
) {
    if (state.recordingConsent != null) RecordingConsentDialog(actions.onAnswerRecordingConsent, recordingCaptureDescription(state))
    var confirmRecording by rememberSaveable(state.roomId) { mutableStateOf(false) }
    RecordingStartDialog(
        state = state,
        open = confirmRecording,
        onDismiss = { confirmRecording = false },
        onStartAudioRecording = actions.onStartAudioRecording,
        onStartVideoRecording = actions.onStartVideoRecording,
    )
    var callOpen by rememberSaveable(state.roomId, state.selfParticipant) { mutableStateOf(false) }
    var moreOpen by rememberSaveable(state.roomId, state.selfParticipant) { mutableStateOf(false) }
    var preferGrid by rememberSaveable(state.roomId) { mutableStateOf(false) }
    var swapped by rememberSaveable(state.roomId) { mutableStateOf(false) }
    var selfHidden by rememberSaveable(state.roomId) { mutableStateOf(false) }
    var workOpen by rememberSaveable(state.roomId, state.selfParticipant) { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(actions.workspaceTarget) {
        if (actions.workspaceTarget != null) { callOpen = false; workOpen = actions.workspaceTarget.assignment != null }
    }
    androidx.compose.runtime.LaunchedEffect(state.notificationChatRequest) {
        if (state.notificationChatRequest > 0) { callOpen = false; workOpen = false }
    }
    androidx.compose.runtime.LaunchedEffect(state.callViewRequest, actions.lockedCallOnly) {
        if (state.callViewRequest > 0 || actions.lockedCallOnly) { callOpen = true; workOpen = false }
    }
    // Read through these, never the raw flags: while locked nothing but the
    // call shows, not even for the frame before the effect above runs.
    val showCall = callOpen || actions.lockedCallOnly
    val showWork = workOpen && !actions.lockedCallOnly
    // A native invitation sheet belongs to this verified foreground owner.
    // Never restore its open flag into another visit or process.
    val inviteState = if (state.nativeHosting == null)
        rememberSaveable(state.roomId, state.selfParticipant) { mutableStateOf(false) }
    else remember(state.roomId, state.selfParticipant, state.nativeHosting.ownerGeneration) { mutableStateOf(false) }
    var inviteOpen by inviteState
    var privateOpen by rememberSaveable(state.roomId, state.selfParticipant) { mutableStateOf(false) }
    var detailsOpen by rememberSaveable(state.roomId) { mutableStateOf(false) }
    var logoEdit by remember(state.roomId, state.selfParticipant, state.selfDevice) { mutableStateOf<RoomLogoEditScope?>(null) }
    if (!actions.lockedCallOnly && !actions.inPictureInPicture) logoEdit?.let { edit -> RoomLogoEditor(edit) { logoEdit = null } }
    var sharingOpen by rememberSaveable(state.roomId, state.selfParticipant) { mutableStateOf(false) }
    if (sharingOpen && !actions.lockedCallOnly) state.sharing?.let {
        RoomSharingSheet(it, actions.onSelectSharingParticipant, actions.onStartRoomSharing, actions.onStopRoomSharing, { sharingOpen = false })
    }
    var backgroundOpen by rememberSaveable(state.roomId, state.selfParticipant) { mutableStateOf(false) }
    var meetingOpen by rememberSaveable(state.roomId, state.selfParticipant) { mutableStateOf(false) }
    if (meetingOpen && state.meetingModerator) MeetingSheet(state, { meetingOpen = false }, actions.onSetMeetingMode, actions.onSetSpeaker)
    val chatState = rememberSaveableStateHolder()
    // Every member can share the room's link; a two-person conversation's
    // went to the other person sealed, and an ended room's no longer works.
    val canInvite = state.canShareInvitation
    fun shareAllowed() = canInvite && actions.onCanShareInvitation(state.nativeHosting)
    androidx.compose.runtime.LaunchedEffect(canInvite) { if (!canInvite) inviteOpen = false }

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
    if (actions.inPictureInPicture && (state.onCall || callOpen) && state.mediaRunning && !state.chatOnly) {
        PipCall(state, videos, eglBase, modifier)
        return
    }
    val callShowing = showCall && !state.route.nearby && !state.anonymous && !state.chatOnly && state.mediaRunning && state.movedOn == null && !state.conferenceEnded
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
            onSwitchCamera = actions.onSwitchCamera,
            onOpenBackground = { backgroundOpen = true },
            onToggleScreenShare = actions.onToggleScreenShare,
            onAddDevice = actions.onAddDevice,
            onOpenCards = actions.onOpenCards,
            onToggleLayout = { preferGrid = !preferGrid },
            onToggleSelfHidden = { selfHidden = !selfHidden; swapped = false },
            onToggleAgentsMayHear = actions.onToggleAgentsMayHear,
            onPopOut = actions.onPopOut,
            onInviteByQr = if (canInvite) ({ if (shareAllowed()) inviteOpen = true }) else null,
            onOpenMeeting = if (state.meetingModerator) ({ meetingOpen = true }) else null,
        )
    }
    if (backgroundOpen) {
        ModalBottomSheet(
            onDismissRequest = { backgroundOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            BackgroundSheet(
                choice = state.background,
                onChoose = actions.onChooseBackground,
                onDone = { backgroundOpen = false },
                onChooseFace = actions.onChooseFacePrivacy,
                faceStatus = state.facePrivacyStatus,
            )
        }
    }
    if (inviteOpen && shareAllowed()) {
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
                onRotateInvitation = actions.onRotateInvitation,
                onDone = { inviteOpen = false },
                canShare = ::shareAllowed,
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
                            onClick = { privateOpen = false; actions.onStartPrivateConversation(peer) },
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
                if (!state.privateConversation && !state.anonymous && !state.quiet && state.movedOn == null && !state.conferenceEnded) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PrivateLogo(state.logo, state.name, Modifier.size(48.dp))
                        TextButton(onClick = { actions.onEditRoomLogo()?.let { logoEdit = it; detailsOpen = false } }) {
                            Text(if (state.logo == null) "Choose room logo" else "Change room logo")
                        }
                    }
                }
                // Any member may rename the room, and the name changes for
                // everybody in it: there is no private nickname beside it. Not
                // in a two-person room, whose title is the other person, nor in
                // an anonymous one, which follows no shared room state.
                if (!state.privateConversation && !state.anonymous && state.movedOn == null && !state.conferenceEnded) {
                    var newName by rememberSaveable(state.name) { mutableStateOf(state.name) }
                    Text("Rename this room for everyone in it.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(newName, { if (it.codePointCount(0, it.length) <= dev.forgesworn.kithmoot.protocol.DisplayName.MAX_LENGTH) newName = it }, Modifier.fillMaxWidth(), label = { Text("Room name") }, singleLine = true)
                    val clean = dev.forgesworn.kithmoot.protocol.DisplayName.sanitise(newName)
                    TextButton(onClick = { actions.onRenameRoom(newName) }, enabled = clean != null && clean != state.name) { Text("Rename for everyone") }
                }
                Text(relayLine(state), style = MaterialTheme.typography.bodyMedium)
                state.nativeHosting?.let { NativeHostingPanel(it, state.nativeHostingBusy,
                    actions.onChangeNativeRoomKey, actions.onRemoveNativeRoomMember, actions.onRetireNativeInvitation, actions.onResendNativeRetirement,
                    actions.onRecoverNativePending, actions.onReplaceNativeInvitation) }
                state.sharing?.let { sharing ->
                    Text(if (sharing.enabled) "Connection sharing is on" else "Connection sharing is off")
                    TextButton(onClick = { detailsOpen = false; sharingOpen = true }) { Text("Share connection") }
                }
                if (state.privateConversation) Text("Two-person room", style = MaterialTheme.typography.bodyMedium)
                if (state.destruct) {
                    // Said once, here, and never more: what self-destruct does, and what it cannot.
                    Text(dev.forgesworn.kithmoot.session.destructDetails(state.endsAt), style = MaterialTheme.typography.bodyMedium)
                } else state.endsAt?.let {
                    Text(if (state.conferenceEnded) conferenceEndedMessage(it) else "Conference room. ${conferenceEndsLine(it)}",
                        style = MaterialTheme.typography.bodyMedium)
                }
                if (state.secondary) Text("You are here as another of your own devices.")
                if (state.anonymous) Text("Anonymous carrier: this room uses only Orbot and v3 onion relays. Accounts, Bothy, profiles, agents and audio/video are unavailable here. " +
                    "Your account and your other rooms stay connected outside Tor. Account changes wait until a few minutes after you leave, or until you next use your account.")
                if (!state.privateConversation) {
                    TextButton(onClick = { if (shareAllowed()) { detailsOpen = false; inviteOpen = true } }, enabled = canInvite) { Text("Invite people") }
                    TextButton(onClick = { if (shareAllowed()) { detailsOpen = false; inviteOpen = true } }, enabled = canInvite) {
                        Icon(Icons.Filled.QrCode2, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Invite by QR")
                    }
                }
                if (!state.anonymous) TextButton(onClick = { detailsOpen = false; actions.onOpenCards() }) { Text("People") }
                TextButton(onClick = { detailsOpen = false; actions.onAddDevice() }, enabled = state.canAddDevice && !state.privateConversationBusy) { Text("Add your device") }
                if (state.privateConversationPeers.isNotEmpty()) TextButton(onClick = { detailsOpen = false; privateOpen = true }, enabled = !state.privateConversationBusy) { Text("Start a private conversation") }
                if (state.route.internet && !state.anonymous) {
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(state.profilesEnabled, actions.onProfilesEnabled)
                        Text("Show public profiles")
                    }
                    Text("Profile lookups share participant keys with room relays and fetch pictures from their hosts. Names and pictures are self-reported.", style = MaterialTheme.typography.bodySmall)
                    // This device's own view of you, from its camera or your
                    // other device's. Everybody else always sees it the right way round.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(state.mirrorSelf, actions.onMirrorSelf)
                        Text("Mirror my view")
                    }
                    Text("Show your own camera like a mirror. Others always see it the right way round.", style = MaterialTheme.typography.bodySmall)
                }
                if (state.movedOn == null && (state.cadence != null || state.nip77 != null)) {
                    HorizontalDivider()
                    state.cadence?.let { CadencePanel(it, actions.onRefreshCadence, actions.onStartCadence, actions.onStopCadence, actions.onRenewCadence) }
                    state.nip77?.let { Nip77Panel(it, actions.onCompareRoomHistory, actions.onFetchRoomHistory, actions.onOfferRoomHistory) }
                }
                TextButton(onClick = { detailsOpen = false; actions.onLeave() }) { Text("Leave room", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    val countdownEnds = state.endsAt?.takeIf { !state.conferenceEnded && state.movedOn == null }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .then(if (countdownEnds != null) Modifier.windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout)) else Modifier)
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
        // The countdown to a room's end, under its name: green, amber, red
        // for one that self-destructs, grey for one that keeps a copy.
        if (countdownEnds != null) RoomCountdownLine(countdownEnds, state.startsAt, state.destruct)
      AnimatedVisibility(chromeVisible, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
       Column {
        if (!actions.lockedCallOnly) Header(state, actions.onBack, { detailsOpen = true }, { callOpen = false; workOpen = false; actions.onSearch() }, actions.accountMenu,
            onInviteByQr = if (canInvite) ({ inviteOpen = true }) else null)
        if (!actions.lockedCallOnly) TabRow(selectedTabIndex = if (state.anonymous) 0 else if (callOpen) 2 else if (workOpen) 1 else 0) {
            Tab(selected = state.anonymous || (!callOpen && !workOpen), onClick = { callOpen = false; workOpen = false }, text = { Text("Chat") })
            if (state.route.internet && !state.anonymous) Tab(selected = workOpen, onClick = { callOpen = false; workOpen = true }, text = {
                val decisions=state.work.assignments.count{it.creator==state.selfParticipant&&it.needsDecision}
                Text(if(decisions>0)"Work · $decisions" else "Work")
            })
            if (!state.route.nearby && !state.anonymous && !state.chatOnly) Tab(selected = callOpen, onClick = { callOpen = true; workOpen = false }, text = {
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
        if (!barLeaves && !state.route.nearby && !state.anonymous && !state.chatOnly && !state.conferenceEnded && (state.mediaRunning || callOpen || state.callChanging || state.mediaStarting || state.callOtherDevices > 0)) {
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
                    onClick = { if (state.onCall) { actions.onLeaveCall(); callOpen = false; workOpen = false } else actions.onJoinCall() },
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
                RoomUpdatePanel(if (state.conferenceEnded) "ended" else state.roomUpdate, state.notice, actions.onRetryRoomUpdate)
            }
        } else if (state.epochTrouble.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                EpochTroublePanel(state.epochTrouble, actions.onDismissEpochTrouble)
            }
        }
        if (state.invitationAdmissions.isNotEmpty()) {
            Text("Waiting to join (${state.invitationAdmissions.size})",
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.titleSmall)
            Column(Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                for (request in state.invitationAdmissions) androidx.compose.runtime.key(request.requestId) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                        InvitationAdmissionPanel(request, onDismiss = actions.onDismissInvitationAdmission, onAnswer = actions.onAnswerInvitationAdmission)
                    }
                }
            }
        }
        for (ask in state.letInAsks) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                LetInPanel(ask, actions.onAnswerLetIn)
            }
        }
       }
      }
        // The final minute of a room that self-destructs, across the chat or the call.
        if (state.destruct && !state.conferenceEnded && state.movedOn == null) state.endsAt?.let { FinalMinuteBanner(it) }
        // Outside the controls that hide on a call: a recording, and why a
        // microphone is locked, are said for as long as they are true.
        if (state.recording != dev.forgesworn.kithmoot.protocol.RecordingView.Off || state.recordingStopPending || state.meetingOn ||
            (state.onCall && state.recordingAllowed && !actions.lockedCallOnly && !actions.inPictureInPicture)) {
            // With the header hidden on a call, nothing else keeps these
            // clear of the status bar and the camera cutout.
            val insets = if (chromeVisible && !actions.lockedCallOnly) Modifier else Modifier.windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))
            Column(Modifier.fillMaxWidth().then(insets).padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // The notice and this device's record controls share one row,
                // so recording a call does not push the picture down.
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (state.recordingWarnings.isEmpty()) {
                            RecordingBanner(state.recording, description = recordingCaptureDescription(state), paused = state.nativeRecordingPaused)
                        } else state.recordingWarnings.forEach { warning ->
                            RecordingBanner(warning.view, description = recordingCaptureDescription(state.copy(recordingCapture = warning.capture)),
                                inRoom = warning.call != null, paused = state.nativeRecordingPaused)
                        }
                    }
                    if (state.onCall && state.recordingAllowed && !actions.lockedCallOnly && !actions.inPictureInPicture) RecordControls(
                        recording = state.nativeRecording,
                        paused = state.nativeRecordingPaused,
                        preparing = state.nativeRecordingPreparing,
                        busy = state.nativeRecordingBusy,
                        stopPending = state.recordingStopPending,
                        onRecord = { confirmRecording = true },
                        onStop = actions.onStopRecording,
                        onTogglePause = actions.onToggleRecordingPause,
                    )
                }
                if (state.recordingStopPending && !actions.lockedCallOnly && !actions.inPictureInPicture) {
                    Text("Capture stopped on this device. The room's stop notice needs confirmation.")
                    TextButton(onClick = actions.onRetryRecordingStop, enabled = !state.recordingStopRetrying) {
                        Text(if (state.recordingStopRetrying) "Confirming stop…" else "Retry stop notice")
                    }
                }
                MeetingNotice(state, actions.onRaiseHand, onOpenMeeting = { meetingOpen = true })
            }
        }

        if (state.route.internet && !state.anonymous && showWork) {
            Box(Modifier.weight(1f).navigationBarsPadding()) {
                chatState.SaveableStateProvider("work:${state.selfParticipant}:${state.roomId}") { actions.work() }
            }
        } else if (!state.route.internet || state.anonymous || state.chatOnly || !showCall) {
            if (state.privateConversationBusy) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
            Box(Modifier.weight(1f).navigationBarsPadding()) {
                chatState.SaveableStateProvider("${state.selfParticipant}:${state.roomId}") { actions.chat() }
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
                    onExpandScreen = actions.onExpandScreen,
                    onSetVolume = actions.onSetVolume,
                    alone = { AloneLine(state, ::shareAllowed) },
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
                            TextButton(actions.onListenHere) { Text("Listen here") }
                        }
                    }
                    if (state.mediaFault != null) FaultPanel(state.mediaFault)
                }
            }
            AnimatedVisibility(chromeVisible && state.movedOn == null && state.mediaRunning, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                CallControlsBar(
                    state = state,
                    onToggleMic = actions.onToggleMic,
                    onToggleCamera = actions.onToggleCamera,
                    onOpenChat = { if (actions.lockedCallOnly) actions.onUnlock() else callOpen = false },
                    onMore = { if (actions.lockedCallOnly) actions.onUnlock() else moreOpen = true },
                    onLeaveCall = { actions.onLeaveCall(); callOpen = false; workOpen = false },
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
    onBack: () -> Unit,
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
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to chats") }
            if (!state.privateConversation && !state.quiet && !state.anonymous) {
                val logo = if (state.endsAt != null && dev.forgesworn.kithmoot.protocol.conferenceEnded(state.endsAt, rememberNow())) null else state.logo ?: state.projectBrand?.image
                PrivateLogo(logo,
                    state.name.ifBlank { "Room" }, Modifier.size(28.dp))
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f).clickable(onClickLabel = "Room details", onClick = onDetails).padding(vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(state.name.ifBlank { "Room" }, Modifier.weight(1f, fill = false), maxLines = 1,
                        overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    Icon(Icons.Filled.ExpandMore, "Room details", Modifier.size(18.dp))
                }
                Text(if (state.route.nearby) nearbyLine(state) else if (state.relaysUp == 0) "Connecting…" else if (state.micOn || state.cameraOn || state.screenOn) { if (state.mediaConnections.values.any { it == "connected" || it == "completed" }) "Call connected" else "Connecting call…" } else if (state.privateConversation) "Private chat" else "Room conversation",
                    style = MaterialTheme.typography.labelSmall, maxLines = 1,
                    color = if (state.relaysUp == 0 && !state.route.nearby) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                state.projectBrand?.takeUnless { state.anonymous || state.quiet || state.privateConversation || state.conferenceEnded }?.let { Text(it.projectName, style = MaterialTheme.typography.labelSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                state.nativeHosting?.let { hosting ->
                    Text(nativeHostingLine(hosting), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (onInviteByQr != null) IconButton(onClick = onInviteByQr) { Icon(Icons.Filled.QrCode2, "Invite by QR") }
            IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, "Search messages") }
            accountMenu()
        }
    }
}

private fun nearbyLine(state: RoomState): String {
    val nearby = state.nearby
    val bluetooth = when {
        nearby?.error != null -> "Bluetooth unavailable: ${nearby.error}"
        nearby?.phase == dev.forgesworn.kithmoot.relay.RoomBlePhase.CLOSED -> "Bluetooth paused"
        (nearby?.writablePeers ?: 0) > 0 -> "${nearby!!.writablePeers} nearby links · delivery unconfirmed"
        else -> "Looking for nearby room members"
    }
    val connection = if (state.route.internet) "$bluetooth · ${state.relaysUp} relays up" else bluetooth
    return if (state.sharing?.enabled == true) "$connection · sharing on" else connection
}

private fun relayLine(state: RoomState): String = when {
    state.route.nearby -> nearbyLine(state)
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
private fun AloneLine(state: RoomState, canShare: () -> Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val offerLink = state.canShareInvitation
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
                TextButton(onClick = { if (canShare()) dev.forgesworn.kithmoot.ui.share(context, state.joinUrl) }) { Text("Send link") }
                if (state.route.nearby) {
                    val invitation = dev.forgesworn.kithmoot.protocol.decodeInvitationUrl(state.joinUrl)?.invitation
                    val roomId = state.roomId
                    if (invitation?.persistent == true) TextButton(onClick = {
                        if (!canShare()) return@TextButton
                        val code = dev.forgesworn.kithmoot.protocol.encodeLivePersistentDescriptor(
                            dev.forgesworn.kithmoot.protocol.LivePersistentContext(invitation, roomId))
                        dev.forgesworn.kithmoot.ui.share(context, code)
                    }) { Text("Send nearby code") }
                }
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
/** A gap or conflict in the room's epochs: it does not stop the room, so it says so once and can be put away. */
@Composable
private fun EpochTroublePanel(lines: List<String>, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        for (line in lines.distinct()) {
            Text(
                text = line,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
        TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Got it") }
    }
}

/** Somebody the room does not know asking to come in, after a removal (kithmoot#207). */
@Composable
private fun LetInPanel(ask: dev.forgesworn.kithmoot.ui.LetInAsk, onAnswer: (String, Boolean) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Text(
            text = "${ask.label} wants to join.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Row(modifier = Modifier.align(Alignment.End)) {
            TextButton(onClick = { onAnswer(ask.participant, false) }) { Text("Decline") }
            Button(onClick = { onAnswer(ask.participant, true) }) { Text("Let in") }
        }
    }
}

@Composable
private fun RoomUpdatePanel(state: String?, detail: String?, onRetry: () -> Unit) {
    val title = when (state) {
        "removed" -> "You were removed from this room"
        "closed" -> "This room was closed"
        "ended" -> "This conference room has ended"
        "updating" -> "Updating this secure room"
        "recovery" -> "Room update needs attention"
        "letin" -> "Waiting to be let in"
        else -> "This room has moved on"
    }
    val message = detail ?: when (state) {
        "removed" -> "This device was not given the successor key and cannot rejoin or publish."
        "closed" -> "The authority ended this room. This device will not rejoin or publish."
        "ended" -> "Nothing more can be sent, and relays delete what was said."
        "updating" -> "Nothing will be sent under the previous room key while Bothy retires its old schedule."
        "recovery" -> "Nothing will be sent under the previous room key. Retry when the authority and Bothy are reachable."
        "letin" -> "Waiting for somebody in this room to let you in. Retry once they have."
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

/** Keep dialog composition separate from the room's many callbacks and states. */
@Composable
private fun RecordingStartDialog(
    state: RoomState,
    open: Boolean,
    onDismiss: () -> Unit,
    onStartAudioRecording: () -> Unit,
    onStartVideoRecording: (dev.forgesworn.kithmoot.media.recording.RecordingVideoLayout,
        dev.forgesworn.kithmoot.media.recording.RecordingEndpointKey?) -> Unit,
) {
    var recordingMode by rememberSaveable(state.roomId) { mutableStateOf("audio") }
    var recordingDevice by rememberSaveable(state.roomId) { mutableStateOf<String?>(null) }
    val recordingLayout = dev.forgesworn.kithmoot.media.recording.RecordingVideoLayout.entries.firstOrNull { it.wire == recordingMode }
    val selectedRecordingDevice = state.recordingVideoDevices.firstOrNull { "${it.key.participant}/${it.key.device}" == recordingDevice }
    if (!open) return
    AlertDialog(
        onDismissRequest = { onDismiss() },
        title = { Text("Record this call?") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Everyone in the room receives a recording notice. Audio you hear and share on this device will be included. Recording stays on this device until you choose Save or Discard; nothing is uploaded automatically. Unsaved recordings follow the original room's self-destruct settings.")
            val modes = listOf("audio" to "Audio only") + dev.forgesworn.kithmoot.media.recording.RecordingVideoLayout.entries.map { it.wire to it.label }
            modes.forEach { (wire, label) -> Row(Modifier.fillMaxWidth().selectable(selected = recordingMode == wire,
                enabled = wire == "audio" || state.recordingVideoSupported, role = androidx.compose.ui.semantics.Role.RadioButton,
                onClick = { recordingMode = wire }), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.RadioButton(selected = recordingMode == wire, enabled = wire == "audio" || state.recordingVideoSupported, onClick = null)
                Text(label)
            } }
            if (!state.recordingVideoSupported) Text("Video recording is unavailable on this device. Audio only is available.")
            if (recordingLayout != null) Text("Video from compatible clients is included. Unavailable cameras and legacy clients have named placeholders; the export is independent of the tiles on your screen.")
            if (recordingLayout == dev.forgesworn.kithmoot.media.recording.RecordingVideoLayout.SPEAKER)
                Text("The recording follows the active speaker. A new speaker takes the picture after speaking for 1.5 seconds while the current speaker is quiet.")
            if (recordingLayout == dev.forgesworn.kithmoot.media.recording.RecordingVideoLayout.SCREEN_CAMERA) {
                Text("Choose the screen sharer's device")
                state.recordingVideoDevices.filter { it.allowed && it.recordingProfile in listOf(2, 3, 4) }.forEach { endpoint ->
                    val key = "${endpoint.key.participant}/${endpoint.key.device}"
                    Row(Modifier.fillMaxWidth().selectable(selected = recordingDevice == key, role = androidx.compose.ui.semantics.Role.RadioButton,
                        onClick = { recordingDevice = key }), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(selected = recordingDevice == key, onClick = null)
                        Column { Text(endpoint.name); Text("${shortId(endpoint.key.participant)} · device ${shortId(endpoint.key.device)}", style = MaterialTheme.typography.bodySmall) }
                    }
                }
                Text("The selected device stays pinned. If its camera or share ends, its name remains in the recording.")
            }
        } },
        confirmButton = { TextButton(enabled = (recordingLayout == null || state.recordingVideoSupported) &&
            (recordingLayout != dev.forgesworn.kithmoot.media.recording.RecordingVideoLayout.SCREEN_CAMERA ||
            selectedRecordingDevice?.let { it.allowed && it.recordingProfile in listOf(2, 3, 4) } == true), onClick = {
            // Read the selected state at the action, rather than capturing a
            // previous composition's derived mode during rapid/accessible taps.
            val confirmedLayout = dev.forgesworn.kithmoot.media.recording.RecordingVideoLayout.entries.firstOrNull { it.wire == recordingMode }
            val confirmedDevice = state.recordingVideoDevices.firstOrNull { "${it.key.participant}/${it.key.device}" == recordingDevice }
            onDismiss()
            if (confirmedLayout == null) onStartAudioRecording() else onStartVideoRecording(confirmedLayout, confirmedDevice?.key)
        }) { Text("Start recording") } },
        dismissButton = { TextButton(onClick = { onDismiss() }) { Text("Cancel") } },
    )
}
