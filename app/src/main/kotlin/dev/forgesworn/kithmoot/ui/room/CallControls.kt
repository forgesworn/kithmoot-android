package dev.forgesworn.kithmoot.ui.room

import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.HideImage
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.ViewSidebar
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VoiceOverOff
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
        if (!mayHide) chrome.show()
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
 * The four things a call needs at hand, and a way to everything else.
 *
 * Mic, camera, chat and leave; "More" opens a sheet for the rest. Each
 * button is one touch target with its label inside it, so the label is
 * what TalkBack and a test both find.
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
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 4.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Top,
        ) {
            CallControlButton(
                // Live-and-muted keeps the icon crossed out, same as off, but
                // is coloured as a warning: the mic is running, just silenced.
                icon = if (state.micOn && !state.micMuted) Icons.Filled.Mic else Icons.Filled.MicOff,
                label = "Mic",
                tone = when {
                    state.micOn && state.micMuted -> ControlTone.WARNING
                    state.micOn -> ControlTone.ACTIVE
                    else -> ControlTone.PLAIN
                },
                description = when {
                    !state.micOn -> "Microphone off"
                    state.micMuted -> "Microphone muted"
                    else -> "Microphone on"
                },
                onClick = onToggleMic,
                modifier = Modifier.weight(1f),
            )
            CallControlButton(
                icon = if (state.cameraOn) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                label = "Camera",
                tone = if (state.cameraOn) ControlTone.ACTIVE else ControlTone.PLAIN,
                description = if (state.cameraOn) "Camera on" else "Camera off",
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
                label = "Leave call",
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

/** Everything that is not one of the four: one tap away, out of the way. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MoreCallSheet(
    state: RoomState,
    layoutToggle: Boolean,
    preferGrid: Boolean,
    selfHidden: Boolean,
    canHideSelf: Boolean,
    onDismiss: () -> Unit,
    onSwitchCamera: () -> Unit,
    onOpenBackground: () -> Unit,
    onToggleScreenShare: () -> Unit,
    onAddDevice: () -> Unit,
    onOpenCards: () -> Unit,
    onToggleLayout: () -> Unit,
    onToggleSelfHidden: () -> Unit,
    onToggleAgentsMayHear: () -> Unit,
    onPopOut: (() -> Unit)?,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            fun closing(action: () -> Unit): () -> Unit = { onDismiss(); action() }
            // Only while the camera is on. A control for what is behind you,
            // offered when nothing is being published, teaches somebody the
            // wrong thing about when it is doing anything.
            if (state.cameraOn) {
                SheetRow(Icons.Filled.Cameraswitch, "Flip camera", closing(onSwitchCamera))
                SheetRow(if (state.background.on) Icons.Filled.Landscape else Icons.Filled.HideImage, "Backdrop", closing(onOpenBackground))
            }
            SheetRow(
                if (state.screenOn) Icons.AutoMirrored.Filled.StopScreenShare else Icons.AutoMirrored.Filled.ScreenShare,
                if (state.screenOn) "Stop sharing screen" else "Share screen",
                closing(onToggleScreenShare),
            )
            if (layoutToggle) SheetRow(
                if (preferGrid) Icons.Filled.ViewSidebar else Icons.Filled.GridView,
                if (preferGrid) "Show who is speaking" else "Show everyone the same size",
                closing(onToggleLayout),
            )
            if (canHideSelf) SheetRow(
                if (selfHidden) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                if (selfHidden) "Show my picture" else "Hide my picture",
                closing(onToggleSelfHidden),
            )
            if (onPopOut != null) SheetRow(Icons.Filled.PictureInPictureAlt, "Picture in picture", closing(onPopOut))
            // Only when there is an agent in the room: a switch that governs
            // nothing teaches the wrong thing. Off means this device's camera
            // and microphone never leave the phone for them.
            if (state.agentCount > 0) SheetRow(
                if (state.agentsMayHear) Icons.Filled.SmartToy else Icons.Filled.VoiceOverOff,
                if (state.agentsMayHear) "Agents can hear and see you: stop" else "Let agents hear and see you",
                closing(onToggleAgentsMayHear),
            )
            if (state.canAddDevice) SheetRow(Icons.Filled.PersonAdd, "Add your device", closing(onAddDevice))
            SheetRow(Icons.Filled.Contacts, if (state.contacts.isEmpty()) "Cards" else "Cards (${state.contacts.size})", closing(onOpenCards))
        }
    }
}

@Composable
private fun SheetRow(icon: ImageVector, label: String, onClick: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Icon(icon, contentDescription = null)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
