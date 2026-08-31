package dev.forgesworn.kithmoot.ui.room

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.automirrored.filled.StopScreenShare
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.ui.RoomState
import org.webrtc.EglBase
import org.webrtc.VideoTrack

/**
 * The room.
 *
 * One grid of tile groups, one bar of controls, and a header that says only what
 * is actually useful mid-call: whether the room is reachable, and how to get
 * somebody else into it.
 */
@Composable
fun RoomScreen(
    state: RoomState,
    videos: Map<String, VideoTrack>,
    eglBase: EglBase?,
    onToggleMic: () -> Unit,
    onToggleCamera: () -> Unit,
    onSwitchCamera: () -> Unit,
    onToggleScreenShare: () -> Unit,
    onOpenChat: () -> Unit,
    onAddDevice: () -> Unit,
    onRotateInvitation: () -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Header(state, onLeave)

        Box(Modifier.weight(1f)) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 300.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (state.tiles.size == 1) {
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                        AlonePanel(state, onRotateInvitation)
                    }
                }
                items(state.tiles, key = { it.participant }) { tile ->
                    ParticipantTileView(
                        tile = tile,
                        videoFor = { track -> videos["${track.device}|${track.trackId}"] },
                        eglBase = eglBase,
                    )
                }
                if (state.mediaFault != null) {
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                        FaultPanel(state.mediaFault)
                    }
                }
            }
        }

        Controls(
            state = state,
            onToggleMic = onToggleMic,
            onToggleCamera = onToggleCamera,
            onSwitchCamera = onSwitchCamera,
            onToggleScreenShare = onToggleScreenShare,
            onOpenChat = onOpenChat,
            onAddDevice = onAddDevice,
        )
    }
}

@Composable
private fun Header(state: RoomState, onLeave: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Room ${shortId(state.roomId)}",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = relayLine(state),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.relaysUp == 0) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (state.relaysUp > 0) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    ),
            )
            Spacer(Modifier.width(16.dp))
            // Leave sits here rather than in the control bar. Hanging up is not
            // a media toggle and does not want to be a thumb's width from one.
            Surface(
                onClick = onLeave,
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.CallEnd, contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Leave", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        if (state.secondary) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "You are here as another of your own devices.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
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

@Composable
private fun Controls(
    state: RoomState,
    onToggleMic: () -> Unit,
    onToggleCamera: () -> Unit,
    onSwitchCamera: () -> Unit,
    onToggleScreenShare: () -> Unit,
    onOpenChat: () -> Unit,
    onAddDevice: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ControlButton(
                icon = if (state.micOn) Icons.Filled.Mic else Icons.Filled.MicOff,
                label = "Mic",
                active = state.micOn,
                onClick = onToggleMic,
            )
            ControlButton(
                icon = if (state.cameraOn) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                label = "Camera",
                active = state.cameraOn,
                onClick = onToggleCamera,
            )
            if (state.cameraOn) {
                ControlButton(
                    icon = Icons.Filled.Cameraswitch,
                    label = "Flip",
                    active = false,
                    onClick = onSwitchCamera,
                )
            }
            ControlButton(
                icon = if (state.screenOn) Icons.AutoMirrored.Filled.StopScreenShare else Icons.AutoMirrored.Filled.ScreenShare,
                label = "Share",
                active = state.screenOn,
                onClick = onToggleScreenShare,
            )
            ControlButton(
                icon = Icons.AutoMirrored.Filled.Chat,
                label = "Chat",
                active = false,
                badge = state.chat.size.takeIf { it > 0 },
                onClick = onOpenChat,
            )
            if (state.canAddDevice) {
                ControlButton(
                    icon = Icons.Filled.PersonAdd,
                    label = "Device",
                    active = false,
                    onClick = onAddDevice,
                )
            }
        }
    }
}

@Composable
private fun ControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    danger: Boolean = false,
    badge: Int? = null,
) {
    val container = when {
        danger -> MaterialTheme.colorScheme.error
        active -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val content = when {
        danger -> MaterialTheme.colorScheme.onError
        active -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(64.dp)
            .heightIn(min = 72.dp),
    ) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(16.dp),
            color = container,
            contentColor = content,
            modifier = Modifier.size(width = 58.dp, height = 50.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                BadgedBox(
                    badge = {
                        if (badge != null) {
                            Badge(
                                containerColor = MaterialTheme.colorScheme.secondary,
                                contentColor = MaterialTheme.colorScheme.onSecondary,
                            ) { Text("$badge") }
                        }
                    },
                ) {
                    Icon(icon, contentDescription = label, modifier = Modifier.size(28.dp))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
