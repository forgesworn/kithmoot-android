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
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
@Composable
fun ParticipantTileView(
    tile: ParticipantTile,
    videoFor: (TileTrack) -> VideoTrack?,
    eglBase: EglBase?,
    modifier: Modifier = Modifier,
) {
    val speaking = tile.hasMic
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
            val panes = tile.videos.mapNotNull { track -> videoFor(track)?.let { track to it } }
            if (panes.isEmpty() || eglBase == null) {
                Placeholder(tile)
            } else {
                Row(Modifier.fillMaxSize()) {
                    panes.forEachIndexed { index, (meta, track) ->
                        if (index > 0) Spacer(Modifier.width(2.dp))
                        Box(Modifier.weight(1f).fillMaxSize()) {
                            VideoSurface(
                                track = track,
                                eglBase = eglBase,
                                modifier = Modifier.fillMaxSize(),
                                mirror = tile.isSelf && meta.role == Roles.CAMERA,
                                // A shared screen is fitted, not cropped: the
                                // edges of a slide are usually where the point is.
                                fill = meta.role != Roles.SCREEN,
                            )
                            if (meta.role == Roles.SCREEN) {
                                PaneLabel("Screen", Modifier.align(Alignment.TopStart))
                            } else if (panes.size > 1) {
                                PaneLabel("Camera", Modifier.align(Alignment.TopStart))
                            }
                        }
                    }
                }
            }
        }

        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = if (tile.isSelf) "You" else shortId(tile.participant),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MicChip(tile)
                if (tile.deviceCount > 1) {
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
            }
            if (tile.isSelf && tile.deviceCount > 1) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Both devices are in. You're one person to everyone else.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MicChip(tile: ParticipantTile) {
    val label = when {
        // Which machine of yours the room is hearing is worth saying plainly.
        // It is the one thing about being on two devices that people get wrong.
        tile.isSelf && tile.micIsThisDevice -> "Mic on this device"
        tile.isSelf && tile.hasMic -> "Mic on your other device"
        tile.hasMic -> "Mic on"
        else -> "Mic off"
    }
    Chip(
        icon = if (tile.hasMic) Icons.Filled.Mic else Icons.Filled.MicOff,
        label = label,
        tone = if (tile.hasMic) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
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
private fun Placeholder(tile: ParticipantTile) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(48.dp))
                    .background(avatarColour(tile.participant)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = initials(tile),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Camera off",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun initials(tile: ParticipantTile): String =
    if (tile.isSelf) "YOU" else tile.participant.take(2).uppercase()

/**
 * A colour for a person, derived from their key.
 *
 * Lightness is fixed low enough that white text on it always clears 4.5:1, so
 * the hue can be anything the key hashes to without the label going grey on
 * grey.
 */
private fun avatarColour(pubkey: String): Color {
    val hue = (pubkey.hashCode().toLong() and 0xFFFF).toFloat() / 0xFFFF * 360f
    return Color.hsl(hue, 0.5f, 0.32f)
}
