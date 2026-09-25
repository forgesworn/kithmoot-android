package dev.forgesworn.kithmoot.ui.room

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.account.npubOf
import dev.forgesworn.kithmoot.account.shortNpub
import dev.forgesworn.kithmoot.media.CallVolume
import dev.forgesworn.kithmoot.session.Roles
import kotlinx.coroutines.launch
import org.webrtc.EglBase
import org.webrtc.VideoTrack
import kotlin.math.roundToInt

/**
 * Whether a pane is drawn as a mirror. Only this person's own cameras, and
 * only while the device keeps its mirrored self-view: a phone filming you,
 * seen on the tablet beside it, is a self-view too, or left and right swap.
 * Screens and everybody else's cameras keep their true orientation.
 */
internal fun mirroredPane(isSelf: Boolean, role: String, mirrorSelf: Boolean): Boolean =
    mirrorSelf && isSelf && role == Roles.CAMERA

/** What every box on the call view needs to draw a person, gathered once. */
internal class CallTileContext(
    val videos: Map<String, VideoTrack>,
    val eglBase: EglBase?,
    val profiles: Map<String, PublicProfile>,
    val connectionStates: Map<String, String>,
    val mirrorSelf: Boolean,
    val selfDevice: String,
    val speaking: Set<String>,
    val shareMarks: Map<String, List<LiveMark>>,
) {
    fun videoFor(track: TileTrack): VideoTrack? = videos["${track.device}|${track.role}"]
    fun nameOf(tile: ParticipantTile): String =
        profiles[tile.participant]?.name ?: tile.cardName?.takeIf { it.isNotBlank() } ?: tile.name ?: shortNpub(tile.participant)
}

/**
 * One box on the call view: a person's camera, or their shared screen.
 *
 * Every box is drawn by this, whatever its size, so a camera-off tile is the
 * same box as a camera-on one with initials in it, and the speaking cue is
 * the same everywhere.
 *
 * @param large the stage: a bigger avatar, a status line, and the short key
 *   under the name, because names are self-reported.
 * @param overlay drawn over another video, as the floating picture is.
 * @param onTap the box itself was tapped: controls, swap or pin, by caller.
 * @param onTapLabel what [onTap] does, for TalkBack.
 */
@Composable
internal fun CallTile(
    tile: ParticipantTile,
    item: CallItem,
    context: CallTileContext,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    overlay: Boolean = false,
    shape: Shape = RoundedCornerShape(14.dp),
    onTap: (() -> Unit)? = null,
    onTapLabel: String? = null,
    onExpandScreen: (TileTrack) -> Unit = {},
    onSetVolume: (String, Float) -> Unit = { _, _ -> },
    showLabel: Boolean = true,
    /** Offers the zoomable viewer on a shared screen. Not in picture-in-picture. */
    showExpand: Boolean = true,
    /** Keeps the label and buttons clear of the system bars on a full-bleed stage. */
    insets: WindowInsets = WindowInsets(0, 0, 0, 0),
) {
    val name = context.nameOf(tile)
    val speaking = tile.participant in context.speaking && !item.isScreen
    val cue = speakingCue(speaking)
    var personOpen by remember { mutableStateOf(false) }
    if (personOpen) PersonDialog(tile, name, context, onSetVolume) { personOpen = false }

    val border = if (cue.solidLabel) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.18f)
    Box(
        modifier
            .background(Color(0xFF1B1B1F), shape)
            .border(cue.borderWidth, border, shape)
            .then(
                if (onTap != null) Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = onTapLabel,
                    onClick = onTap,
                ) else Modifier,
            )
            .semantics { cue.stateDescription?.let { stateDescription = it } },
    ) {
        val pane = item.screen ?: tile.videos.firstOrNull { it.role == Roles.CAMERA && context.videoFor(it) != null }
            ?: tile.videos.firstOrNull { it.role == Roles.CAMERA }
        val track = pane?.let(context::videoFor)
        val eglBase = context.eglBase
        var receivedFrame by remember(pane?.device, pane?.trackId, track) { mutableStateOf(false) }
        if (pane != null && track != null && eglBase != null) {
            VideoSurface(
                track = track,
                eglBase = eglBase,
                modifier = Modifier.fillMaxSize(),
                mirror = mirroredPane(tile.isSelf, pane.role, context.mirrorSelf),
                // A shared screen is fitted, not cropped: the edges of a slide
                // are usually where the point is. Faces are cropped to fill.
                fill = !item.isScreen,
                overlay = overlay,
                onFirstFrame = { receivedFrame = true },
            )
        }
        if (!receivedFrame) {
            val status = when {
                pane == null -> if (item.isScreen) "Screen share" else "Camera off"
                context.connectionStates[pane.device] in setOf("failed", "closed") -> "Video connection failed"
                else -> "Connecting video…"
            }
            Initials(tile, name, context.profiles[tile.participant], large, status.takeIf { large })
        }
        if (item.isScreen && pane != null) {
            ShareMarksOverlay(context.shareMarks[pane.trackId] ?: emptyList(), Modifier.fillMaxSize())
            if (showExpand) TextButton(
                onClick = { onExpandScreen(pane) },
                modifier = Modifier.align(Alignment.TopStart).windowInsetsPadding(insets).padding(8.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp)),
            ) { Text("Expand screen share") }
        }
        if (showLabel) NameLabel(
            name = when {
                tile.isSelf && item.isScreen -> "Your screen"
                tile.isSelf -> "You"
                item.isScreen -> "$name’s screen"
                else -> name
            },
            detail = shortNpub(tile.participant).takeIf { large && !tile.isSelf && !item.isScreen },
            muted = tile.hasMic && tile.micMuted,
            silenced = tile.isSilencedForYou,
            solid = cue.solidLabel,
            compact = !large,
            onClick = { personOpen = true },
            modifier = Modifier.align(Alignment.BottomStart).windowInsetsPadding(insets).padding(if (large) 12.dp else 6.dp),
        )
    }
}

/** The person's initials or picture, centred, with an optional status line. */
@Composable
private fun Initials(tile: ParticipantTile, name: String, profile: PublicProfile?, large: Boolean, status: String?) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val size: Dp = if (large) minOf(maxWidth, maxHeight) * 0.32f else minOf(maxWidth, maxHeight) * 0.42f
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ProfileAvatar(tile.participant, name, profile, Modifier.size(size.coerceIn(28.dp, 160.dp)))
            if (status != null) {
                Spacer(Modifier.height(12.dp))
                Text(status, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f))
            }
        }
    }
}

/**
 * The name on a box. Tapping it opens who the person is and how loud they
 * are here. See [speakingCue]: solid while they talk, see-through otherwise.
 */
@Composable
private fun NameLabel(
    name: String,
    detail: String?,
    muted: Boolean,
    silenced: Boolean,
    solid: Boolean,
    compact: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = if (solid) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.55f)
    val content = if (solid) MaterialTheme.colorScheme.onPrimary else Color.White
    Row(
        modifier
            .widthIn(max = 280.dp)
            .background(background, RoundedCornerShape(8.dp))
            .clickable(onClickLabel = "Who this is and their volume", onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (muted) Icon(Icons.Filled.MicOff, "Muted", tint = content, modifier = Modifier.size(16.dp))
        if (silenced) Icon(Icons.AutoMirrored.Filled.VolumeOff, "Silenced for you", tint = content, modifier = Modifier.size(16.dp))
        Column {
            Text(
                name,
                style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleSmall,
                color = content, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (detail != null) Text(detail, style = MaterialTheme.typography.labelSmall, color = content.copy(alpha = 0.85f), maxLines = 1)
        }
    }
}

/** Who this is, their key in full, and how loud they are on this device. */
@Composable
private fun PersonDialog(
    tile: ParticipantTile,
    name: String,
    context: CallTileContext,
    onSetVolume: (String, Float) -> Unit,
    onDone: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(if (tile.isSelf) "You" else name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectionContainer { Text(npubOf(tile.participant)) }
                Text(micLine(tile), style = MaterialTheme.typography.bodyMedium)
                if (tile.isSelf && tile.deviceCount > 1) {
                    Text("Your ${tile.deviceCount} devices are in. You're one person to everyone else.", style = MaterialTheme.typography.bodyMedium)
                }
                val cardName = tile.cardName
                if (cardName != null && !tile.isSelf) Text(if (cardName.isEmpty()) "You hold their card." else "You hold their card: $cardName", style = MaterialTheme.typography.bodyMedium)
                if (!tile.isSelf) VolumeRow(tile, onSetVolume)
            }
        },
        confirmButton = { TextButton(onDone) { Text("Done") } },
    )
}

private fun micLine(tile: ParticipantTile): String = when {
    // A muted microphone is still live - the room can turn it back on
    // without a renegotiation - so it reads differently from "off".
    tile.hasMic && tile.micMuted -> "Microphone muted"
    tile.isSelf && tile.micIsThisDevice -> "Mic on this device"
    tile.isSelf && tile.hasMic -> "Mic on your other device"
    tile.hasMic -> "Mic on"
    else -> "Mic off"
}

/**
 * How loud this one person is, on this device only - never published, never
 * seen by anyone else in the room. A level of zero shows the same "silenced
 * for you" wording the web client uses, so it never reads as if they left.
 */
@Composable
private fun VolumeRow(tile: ParticipantTile, onSetVolume: (String, Float) -> Unit) {
    val percent = CallVolume.gainToPercent(tile.callVolume)
    Column {
        Text(
            text = if (tile.isSilencedForYou) "Silenced for you" else "Volume for you: $percent%",
            style = MaterialTheme.typography.labelMedium,
            color = if (tile.isSilencedForYou) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = tile.callVolume,
            onValueChange = { onSetVolume(tile.participant, it) },
            valueRange = CallVolume.MIN_GAIN..CallVolume.MAX_GAIN,
            modifier = Modifier.fillMaxWidth()
                .semantics { contentDescription = "Volume for ${shortId(tile.participant)}, this device only" },
        )
    }
}

private const val CORNER_KEY = "selfTileCorner"

/**
 * The small picture that can be moved out of the way.
 *
 * Dragged anywhere, it settles in the nearest corner of [area] - which the
 * caller has already kept clear of the controls and the system bars - and
 * this device remembers the corner. TalkBack gets a "move to" action per
 * corner, since dragging is not something everybody can do.
 */
@Composable
internal fun FloatingPicture(
    landscape: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("kithmoot.display", android.content.Context.MODE_PRIVATE) }
    var corner by remember { mutableStateOf(Corner.parse(prefs.getString(CORNER_KEY, null))) }
    fun settle(to: Corner) {
        corner = to
        prefs.edit().putString(CORNER_KEY, to.name).apply()
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val tileWidth = with(density) { (if (landscape) 150.dp else 110.dp).toPx() }
        val tileHeight = with(density) { (if (landscape) 110.dp else 150.dp).toPx() }
        val margin = with(density) { 12.dp.toPx() }
        val areaWidth = constraints.maxWidth.toFloat()
        val areaHeight = constraints.maxHeight.toFloat()
        fun rest(c: Corner) = cornerPosition(c, tileWidth, tileHeight, areaWidth, areaHeight, margin).let { Offset(it.first, it.second) }
        val position = remember { Animatable(rest(corner), Offset.VectorConverter) }
        var dragging by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        // The area changes when the controls come and go, or the phone turns:
        // the picture follows its corner rather than staying where it was.
        LaunchedEffect(corner, areaWidth, areaHeight, tileWidth, tileHeight) {
            if (!dragging) position.animateTo(rest(corner), spring(stiffness = Spring.StiffnessMediumLow))
        }
        val moves = Corner.entries.map { target ->
            CustomAccessibilityAction("Move my picture to the ${target.name.lowercase().replace('_', ' ')}") { settle(target); true }
        }
        content(
            Modifier
                .absoluteOffset { IntOffset(position.value.x.roundToInt(), position.value.y.roundToInt()) }
                .size(with(density) { tileWidth.toDp() }, with(density) { tileHeight.toDp() })
                .semantics { customActions = moves }
                .pointerInput(areaWidth, areaHeight, tileWidth, tileHeight) {
                    val tracker = VelocityTracker()
                    detectDragGestures(
                        onDragStart = { dragging = true; tracker.resetTracking() },
                        onDragEnd = {
                            val velocity = tracker.calculateVelocity()
                            val here = position.value
                            val target = nearestCorner(here.x, here.y, tileWidth, tileHeight, areaWidth, areaHeight, velocity.x, velocity.y)
                            settle(target)
                            dragging = false
                            scope.launch {
                                position.animateTo(rest(target), spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow), Offset(velocity.x, velocity.y))
                            }
                        },
                        onDragCancel = {
                            dragging = false
                            scope.launch { position.animateTo(rest(corner)) }
                        },
                    ) { change, drag ->
                        change.consume()
                        val next = Offset(
                            (position.value.x + drag.x).coerceIn(0f, (areaWidth - tileWidth).coerceAtLeast(0f)),
                            (position.value.y + drag.y).coerceIn(0f, (areaHeight - tileHeight).coerceAtLeast(0f)),
                        )
                        tracker.addPosition(change.uptimeMillis, next)
                        scope.launch { position.snapTo(next) }
                    }
                },
        )
    }
}
