package dev.forgesworn.kithmoot.ui.room

import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.automirrored.filled.StopScreenShare
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.HideImage
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.ui.RoomState
import kotlinx.coroutines.delay

/**
 * Whether the controls and the top bar are showing.
 *
 * They fade after [CONTROLS_HIDE_AFTER_MS] without a touch, but only when
 * [controlsMayAutoHide] says so, and a tap on the picture brings them back.
 */
@Stable
internal class CallChrome {
    var visible by mutableStateOf(true)
        private set
    internal var mayHide by mutableStateOf(false)
    internal var touches by mutableIntStateOf(0)
        private set

    /** Any touch on the call view: the timer starts again. */
    fun touched() {
        if (visible) touches++
    }

    /** A tap on the picture: show, or tuck away if they may be. */
    fun toggle() {
        visible = !visible || !mayHide
        touches++
    }

    fun show() {
        visible = true
        touches++
    }

    internal fun hide() {
        visible = false
    }

    /** Shows without restarting the timer. The timer's own effect is keyed
     *  on [touches], so bumping it from there would relaunch it forever and
     *  keep Compose busy on every frame. */
    internal fun reveal() {
        visible = true
    }
}

@Composable
internal fun rememberCallChrome(mayHide: Boolean): CallChrome {
    val chrome = remember { CallChrome() }
    chrome.mayHide = mayHide
    val context = LocalContext.current
    // Android's "Time to take action" setting: somebody who asked for longer
    // gets longer before the controls fade.
    val hideAfter = remember(context) {
        context.getSystemService(AccessibilityManager::class.java)
            ?.getRecommendedTimeoutMillis(
                CONTROLS_HIDE_AFTER_MS.toInt(),
                AccessibilityManager.FLAG_CONTENT_CONTROLS or AccessibilityManager.FLAG_CONTENT_ICONS or AccessibilityManager.FLAG_CONTENT_TEXT,
            )?.toLong() ?: CONTROLS_HIDE_AFTER_MS
    }
    LaunchedEffect(mayHide, chrome.touches) {
        if (!mayHide) chrome.reveal()
        else if (chrome.visible) {
            delay(hideAfter)
            chrome.hide()
        }
    }
    return chrome
}

/**
 * A screen reader is running: touch exploration (TalkBack) or any service
 * giving spoken or braille feedback. Not every accessibility service, since
 * password managers and automation tools run as one and would pin the
 * controls for good; people with other needs get a longer fade instead,
 * through the "Time to take action" setting in [rememberCallChrome].
 */
@Composable
internal fun rememberAccessibilityOn(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    fun read() = manager != null && (
        manager.isTouchExplorationEnabled ||
            manager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_SPOKEN or AccessibilityServiceInfo.FEEDBACK_BRAILLE,
            ).isNotEmpty()
        )
    var on by remember { mutableStateOf(read()) }
    DisposableEffect(manager) {
        if (manager == null) return@DisposableEffect onDispose { }
        val state = AccessibilityManager.AccessibilityStateChangeListener { on = read() }
        val touch = AccessibilityManager.TouchExplorationStateChangeListener { on = read() }
        manager.addAccessibilityStateChangeListener(state)
        manager.addTouchExplorationStateChangeListener(touch)
        onDispose {
            manager.removeAccessibilityStateChangeListener(state)
            manager.removeTouchExplorationStateChangeListener(touch)
        }
    }
    return on
}

/**
 * The call's controls (design-calm-shell 9.5): Mute, Camera, Chat, More and
 * Leave, each a picture with its word under it; More opens a sheet for the
 * rest, Share screen among it on a phone. A toggle's word is what pressing
 * it does, and TalkBack hears its name and whether it is on.
 */
@Composable
internal fun CallControlsBar(
    state: RoomState,
    onToggleMic: () -> Unit,
    onToggleCamera: () -> Unit,
    onOpenChat: () -> Unit,
    onMore: () -> Unit,
    onLeaveCall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainer, contentColor = MaterialTheme.colorScheme.onSurface) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 4.dp, vertical = 10.dp)
                .semantics { paneTitle = "Call controls" },
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Top,
        ) {
            CallControlButton(
                // Live-and-muted keeps the icon crossed out, same as off, but
                // is coloured as a warning: the mic is running, just silenced.
                icon = if (state.micOn && !state.micMuted) Icons.Filled.Mic else Icons.Filled.MicOff,
                label = if (state.micOn && !state.micMuted) "Mute" else "Unmute",
                tone = when {
                    state.micOn && state.micMuted -> ControlTone.WARNING
                    state.micOn -> ControlTone.ACTIVE
                    else -> ControlTone.PLAIN
                },
                description = "Microphone",
                status = when {
                    !state.micOn && !state.meetingSpeaker -> "Locked: meeting mode"
                    !state.micOn -> "Off"
                    state.micMuted -> "Muted"
                    else -> "On"
                },
                onClick = onToggleMic,
                modifier = Modifier.weight(1f),
            )
            CallControlButton(
                icon = if (state.cameraOn) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                label = "Camera",
                tone = if (state.cameraOn) ControlTone.ACTIVE else ControlTone.PLAIN,
                description = "Camera",
                status = when {
                    state.cameraOn -> "On"
                    !state.meetingSpeaker -> "Locked: meeting mode"
                    else -> "Off"
                },
                onClick = onToggleCamera,
                modifier = Modifier.weight(1f),
            )
            CallControlButton(
                icon = Icons.AutoMirrored.Filled.Chat,
                label = "Chat",
                badge = state.chat.size.takeIf { it > 0 },
                onClick = onOpenChat,
                modifier = Modifier.weight(1f),
            )
            CallControlButton(
                icon = Icons.Filled.MoreHoriz,
                label = "More",
                description = "More call options",
                onClick = onMore,
                modifier = Modifier.weight(1f),
            )
            if (state.onCall) CallControlButton(
                icon = Icons.Filled.CallEnd,
                label = "Leave",
                description = "Leave call",
                tone = ControlTone.END,
                enabled = !state.callChanging,
                onClick = onLeaveCall,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

internal enum class ControlTone { PLAIN, ACTIVE, WARNING, END }

@Composable
private fun CallControlButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: ControlTone = ControlTone.PLAIN,
    description: String? = null,
    /** On, Off, Muted: said after the name, for a control that is a toggle. */
    status: String? = null,
    badge: Int? = null,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (tone) {
        ControlTone.PLAIN -> scheme.surfaceContainerHighest to scheme.onSurface
        ControlTone.ACTIVE -> scheme.primary to scheme.onPrimary
        ControlTone.WARNING -> scheme.error to scheme.onError
        ControlTone.END -> androidx.compose.ui.graphics.Color(0xFFD32F2F) to androidx.compose.ui.graphics.Color.White
    }
    Column(
        modifier
            .heightIn(min = 72.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
            .then(if (status != null) Modifier.semantics { stateDescription = status } else Modifier)
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = RoundedCornerShape(if (tone == ControlTone.END) 26.dp else 16.dp),
            color = container,
            contentColor = content,
            modifier = Modifier.size(width = 56.dp, height = 48.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                BadgedBox(badge = {
                    if (badge != null) Badge(containerColor = scheme.secondary, contentColor = scheme.onSecondary) { Text("$badge") }
                }) { Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp)) }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Everything that is not in the bar, in the web's order and each only when
 * it applies (design-calm-shell 5.5): Share screen, Effects…, Layout,
 * Loudspeaker, Picture in picture, Record call…, Run as a meeting…, Mirror my view, Let
 * agents hear me, Invite people…. Rows that open something close the sheet;
 * switches change in place. Invite by QR, Add your device and contact cards
 * live in Room info.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MoreCallSheet(
    state: RoomState,
    layoutToggle: Boolean,
    preferGrid: Boolean,
    selfHidden: Boolean,
    canHideSelf: Boolean,
    onDismiss: () -> Unit,
    onOpenEffects: () -> Unit,
    onToggleScreenShare: () -> Unit,
    onSetGrid: (Boolean) -> Unit,
    onToggleSelfHidden: () -> Unit,
    onToggleAgentsMayHear: () -> Unit,
    onMirrorSelf: (Boolean) -> Unit,
    onLoudspeaker: (Boolean) -> Unit,
    onPopOut: (() -> Unit)?,
    /** Record call…: asks first; null where this device may not record. */
    onRecord: (() -> Unit)? = null,
    /** The host's meeting controls; null on every device but the one that made the room. */
    onOpenMeeting: (() -> Unit)? = null,
    /** Invite people…; null where there is nobody to invite or no link to send. */
    onInvite: (() -> Unit)? = null,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            fun closing(action: () -> Unit): () -> Unit = { onDismiss(); action() }
            SheetRow(
                if (state.screenOn) Icons.AutoMirrored.Filled.StopScreenShare else Icons.AutoMirrored.Filled.ScreenShare,
                when {
                    state.screenOn -> "Stop sharing screen"
                    !state.meetingSpeaker -> "Share screen (only speakers, in meeting mode)"
                    else -> "Share screen"
                },
                closing(onToggleScreenShare),
            )
            // A face choice can be made before the first camera frame.
            SheetRow(Icons.Filled.HideImage, "Effects…", closing(onOpenEffects))
            if (state.cameraOn && state.background.face != dev.forgesworn.kithmoot.media.effects.FacePrivacyMode.OFF) {
                Text(state.facePrivacyStatus.ifBlank { "Picture covered while face masking starts." },
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp))
            }
            if (layoutToggle || canHideSelf) {
                var layoutOpen by remember { mutableStateOf(false) }
                SheetRow(Icons.Filled.GridView, "Layout", { layoutOpen = !layoutOpen },
                    trailing = { Icon(if (layoutOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null) })
                if (layoutOpen) {
                    if (layoutToggle) {
                        SheetChoiceRow("Gallery", selected = preferGrid) { onSetGrid(true) }
                        SheetChoiceRow("Speaker", selected = !preferGrid) { onSetGrid(false) }
                    }
                    if (canHideSelf) SheetSwitchRow(null, "Hide my picture", selfHidden) { onToggleSelfHidden() }
                }
            }
            SheetSwitchRow(Icons.AutoMirrored.Filled.VolumeUp, "Loudspeaker", state.loudspeakerOn) { onLoudspeaker(!state.loudspeakerOn) }
            if (onPopOut != null) SheetRow(Icons.Filled.PictureInPictureAlt, "Picture in picture", closing(onPopOut))
            if (onRecord != null) SheetRow(Icons.Filled.FiberManualRecord, "Record call…", closing(onRecord))
            if (onOpenMeeting != null) SheetRow(
                Icons.Filled.RecordVoiceOver,
                if (state.meetingOn) "Meeting: speakers and hands" else "Run as a meeting…",
                closing(onOpenMeeting),
            )
            if (state.cameraOn) SheetSwitchRow(Icons.Filled.Flip, "Mirror my view", state.mirrorSelf) { onMirrorSelf(!state.mirrorSelf) }
            // Only when there is an agent in the room: a switch that governs
            // nothing teaches the wrong thing. Off means this device's camera
            // and microphone never leave the phone for them.
            if (state.agentCount > 0) SheetSwitchRow(Icons.Filled.SmartToy, "Let agents hear me", state.agentsMayHear) { onToggleAgentsMayHear() }
            if (onInvite != null) SheetRow(Icons.Filled.PersonAdd, "Invite people…", closing(onInvite))
        }
    }
}

/** A switch row: the whole row toggles, and TalkBack hears it as a switch. */
@Composable
private fun SheetSwitchRow(icon: ImageVector?, label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = { onToggle() })
            .padding(start = if (icon == null) 68.dp else 24.dp, end = 24.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null)
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** One of a set of choices, such as Gallery or Speaker. */
@Composable
private fun SheetChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(start = 68.dp, end = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SheetRow(icon: ImageVector, label: String, onClick: (() -> Unit)?, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Icon(icon, contentDescription = null)
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        trailing?.invoke()
    }
}
