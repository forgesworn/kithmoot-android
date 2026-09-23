package dev.forgesworn.kithmoot.ui.room

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import dev.forgesworn.kithmoot.account.npubOf
import dev.forgesworn.kithmoot.account.shortNpub
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.media.CallVolume
import dev.forgesworn.kithmoot.session.Roles
import org.webrtc.EglBase
import org.webrtc.VideoTrack

/**
 * One person's tile group.
 *
 * The whole point of this file is that the outer card is per **person**. Their
 * laptop camera and their phone's screen share are two panes inside one card
 * with one name on it, and the room is never told how many machines they are
 * sitting at unless it is their own card, where it is useful.
 */
/**
 * Whether a pane is drawn as a mirror. Only this person's own cameras, and
 * only while the device keeps its mirrored self-view: a phone filming you,
 * seen on the tablet beside it, is a self-view too, or left and right swap.
 * Screens and everybody else's cameras keep their true orientation.
 */
internal fun mirroredPane(isSelf: Boolean, role: String, mirrorSelf: Boolean): Boolean =
    mirrorSelf && isSelf && role == Roles.CAMERA

@Composable
fun ParticipantTileView(
    tile: ParticipantTile,
    videoFor: (TileTrack) -> VideoTrack?,
    eglBase: EglBase?,
    modifier: Modifier = Modifier,
    onExpandScreen: (TileTrack) -> Unit = {},
    /** This share's fading drawing, keyed by the advertised share track id -
     *  see ui/RoomViewModel.kt `RoomState.shareMarks`. Painted over the
     *  matching screen pane below, including this device's own share
     *  preview when `tile.isSelf` and somebody else has drawn on it. */
    shareMarks: Map<String, List<LiveMark>> = emptyMap(),
    onSetVolume: (String, Float) -> Unit = { _, _ -> },
    profile: PublicProfile? = null,
    selfDevice: String = "",
    connectionStates: Map<String, String> = emptyMap(),
    /** Show this person's own cameras as a mirror. See `RoomState.mirrorSelf`. */
    mirrorSelf: Boolean = true,
) {
    // A muted microphone is still live - hasMic stays true - but disabled at
    // the source, so it never reads as speaking. There is no audio-level
    // meter here; "speaking" is standing in for "on air".
    val speaking = tile.hasMic && !tile.micMuted
    val name = profile?.name ?: tile.cardName?.takeIf { it.isNotBlank() } ?: tile.name ?: shortNpub(tile.participant)
    var identityOpen by remember { mutableStateOf(false) }
    if (identityOpen) AlertDialog(onDismissRequest = { identityOpen = false }, title = { Text(name) },
        text = { SelectionContainer { Text(npubOf(tile.participant)) } },
        confirmButton = { TextButton({ identityOpen = false }) { Text("Done") } })
    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (speaking) {
                    Modifier.border(
                        width = 3.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(18.dp),
                    )
                } else {
                    Modifier
                },
            ),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            val panes = tile.videos.map { meta -> meta to videoFor(meta) }
            if (panes.isEmpty() || eglBase == null) {
                Placeholder(tile, name, profile, if (tile.hasVideo) "Connecting video…" else "Camera off")
            } else {
                Row(Modifier.fillMaxSize()) {
                    panes.forEachIndexed { index, (meta, track) ->
                        if (index > 0) Spacer(Modifier.width(2.dp))
                        Box(Modifier.weight(1f).fillMaxSize()) {
                            var receivedFrame by remember(meta.device, meta.trackId, track) { mutableStateOf(false) }
                            if (track != null) VideoSurface(
                                track = track,
                                eglBase = eglBase,
                                modifier = Modifier.fillMaxSize(),
                                mirror = mirroredPane(tile.isSelf, meta.role, mirrorSelf),
                                onFirstFrame = { receivedFrame = true },
                                // A shared screen is fitted, not cropped: the
                                // edges of a slide are usually where the point is.
                                fill = meta.role != Roles.SCREEN,
                            )
                            if (!receivedFrame) Placeholder(tile, name, profile, if (connectionStates[meta.device] in setOf("failed", "closed")) "Video connection failed" else "Connecting video…")
                            if (meta.role == Roles.SCREEN) {
                                ShareMarksOverlay(
                                    marks = shareMarks[meta.trackId] ?: emptyList(),
                                    modifier = Modifier.fillMaxSize(),
                                )
                                androidx.compose.material3.TextButton(onClick = { onExpandScreen(meta) }, modifier = Modifier.align(Alignment.TopStart).background(MaterialTheme.colorScheme.surface)) {
                                    Text("Expand screen share")
                                }
                            } else if (panes.size > 1) {
                                PaneLabel(if (tile.isSelf) { if (meta.device == selfDevice) "This phone" else "Your other camera" } else "Camera", Modifier.align(Alignment.TopStart))
                            }
                        }
                    }
                }
            }
        }

        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth().clickable { identityOpen = true }, verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ProfileAvatar(tile.participant, name, profile, Modifier.size(40.dp))
                Column {
                    Text(if (tile.isSelf) "You" else name, style = MaterialTheme.typography.titleMedium)
                    Text(shortNpub(tile.participant), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MicChip(tile)
                if (tile.isSilencedForYou) SilencedChip()
                if (tile.isSelf && tile.deviceCount > 1) {
                    Chip(
                        icon = Icons.Filled.Devices,
                        label = "${tile.deviceCount} devices",
                        tone = MaterialTheme.colorScheme.secondary,
                    )
                }
                if (tile.isSharingScreen) {
                    Chip(
                        icon = Icons.AutoMirrored.Filled.ScreenShare,
                        label = "Sharing",
                        tone = MaterialTheme.colorScheme.tertiary,
                    )
                }
                if (tile.hasScreenAudio) {
                    Chip(
                        icon = Icons.AutoMirrored.Filled.VolumeUp,
                        label = "Sound",
                        tone = MaterialTheme.colorScheme.tertiary,
                    )
                }
                // A card held for this person: their box is one of the
                // circle's relays, and the name is the one they wrote on it.
                val cardName = tile.cardName
                if (cardName != null && !tile.isSelf) {
                    Chip(
                        icon = Icons.Filled.Badge,
                        label = if (cardName.isEmpty()) "card" else "card: $cardName",
                        tone = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (tile.isSelf && tile.deviceCount > 1) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Your ${tile.deviceCount} devices are in. You're one person to everyone else.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!tile.isSelf) {
                Spacer(Modifier.height(4.dp))
                VolumeRow(tile, onSetVolume)
            }
        }
    }
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (tile.isSilencedForYou) "Silenced for you" else "Volume for you: $percent%",
                style = MaterialTheme.typography.labelMedium,
                color = if (tile.isSilencedForYou) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Slider(
            value = tile.callVolume,
            onValueChange = { onSetVolume(tile.participant, it) },
            valueRange = CallVolume.MIN_GAIN..CallVolume.MAX_GAIN,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Volume for ${shortId(tile.participant)}, this device only" },
        )
    }
}

@Composable
private fun MicChip(tile: ParticipantTile) {
    val label = when {
        // A muted microphone is still live - the room can turn it back on
        // without a renegotiation - so it gets its own badge rather than
        // reading as "off". See RoomViewModel.toggleMicrophone.
        tile.hasMic && tile.micMuted -> "🎙️ Muted"
        // Which machine of yours the room is hearing is worth saying plainly.
        // It is the one thing about being on two devices that people get wrong.
        tile.isSelf && tile.micIsThisDevice -> "Mic on this device"
        tile.isSelf && tile.hasMic -> "Mic on your other device"
        tile.hasMic -> "Mic on"
        else -> "Mic off"
    }
    Chip(
        icon = if (tile.hasMic && !tile.micMuted) Icons.Filled.Mic else Icons.Filled.MicOff,
        label = label,
        tone = when {
            tile.hasMic && tile.micMuted -> MaterialTheme.colorScheme.error
            tile.hasMic -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

/**
 * "Silenced for you" as its own badge, distinct from [MicChip].
 *
 * Deliberately never folded into one icon with the mute badge above: a
 * person can be muted at the source AND silenced on this device at once, and
 * those are two different facts about two different people's choices.
 */
@Composable
private fun SilencedChip() {
    Chip(
        icon = Icons.AutoMirrored.Filled.VolumeOff,
        label = "🔇 Silenced for you",
        tone = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun Chip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tone: Color,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tone, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun PaneLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier
            .padding(8.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xCC000000))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
    )
}

/** What a person looks like before their camera is on, or when they never turn it on. */
@Composable
private fun Placeholder(tile: ParticipantTile, name: String, profile: PublicProfile?, status: String = "Camera off") {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ProfileAvatar(tile.participant, name, profile, Modifier.size(72.dp))
            Spacer(Modifier.height(12.dp))
            Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
