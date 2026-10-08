package dev.forgesworn.kithmoot.ui.start

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/** A menu item on a row's overflow: a visible label the accessible name
 *  never differs from (F6), and whether it reads in the destructive colour. */
internal data class ConversationAction(val label: String, val destructive: Boolean = false, val run: () -> Unit)

/**
 * One row of the merged home rooms list (design-home-rooms.md section 5, and
 * room-list-sections.md section 2): an avatar, then the name and time, then
 * the preview and unread pill. No card, no border, no divider - rows are
 * separated by their own padding, and the whole open area is one merged
 * accessibility node so TalkBack reads the name, the status line, then the
 * time, then "Double-tap to open, double-tap and hold for more options".
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RoomRow(roomId: String, name: String, status: String?, time: String, timeSpoken: String, enabled: Boolean,
    open: () -> Unit, actions: List<ConversationAction>, pinned: Boolean = false, ended: Boolean = false, unread: Int = 0,
    /** The room's countdown pill, for a room with an end still to come. */
    countdown: (@Composable () -> Unit)? = null,
    privatePeer: String? = null, profile: dev.forgesworn.kithmoot.ui.room.PublicProfile? = null) {
    var menu by remember { mutableStateOf(false) }
    val stacked = LocalDensity.current.fontScale >= 1.5f
    // "No messages yet" says nothing the row does not already show, so the
    // row is one line; TalkBack still hears it, through the node's state.
    val preview = status?.takeIf { it != NO_MESSAGES_YET }
    val weight = if (unread > 0) FontWeight.Bold else null
    val spoken = listOfNotNull(
        "Pinned".takeIf { pinned }, "$unread unread".takeIf { unread > 0 }, NO_MESSAGES_YET.takeIf { status == NO_MESSAGES_YET },
    ).joinToString(". ")
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .combinedClickable(
                    enabled = enabled,
                    onClick = open, onClickLabel = "Open",
                    onLongClick = { menu = true }, onLongClickLabel = "More options",
                )
                .semantics(mergeDescendants = true) { if (spoken.isNotEmpty()) stateDescription = spoken }
                .heightIn(min = 56.dp)
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            if (privatePeer != null && profile?.picture != null) {
                dev.forgesworn.kithmoot.ui.room.ProfileAvatar(privatePeer, name, profile,
                    Modifier.size(40.dp).clearAndSetSemantics {})
            } else RoomAvatar(roomId, name, ended)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
                if (stacked) {
                    Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = weight, color = MaterialTheme.colorScheme.onSurface)
                    preview?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2) }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (pinned) PinGlyph()
                        RowTime(time, timeSpoken, weight)
                        if (unread > 0) UnreadPill(unread)
                    }
                    countdown?.invoke()
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = weight, color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (pinned) PinGlyph()
                        RowTime(time, timeSpoken, weight)
                    }
                    if (preview != null || unread > 0) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(preview.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (unread > 0) UnreadPill(unread)
                    }
                    countdown?.invoke()
                }
            }
        }
        Box {
            IconButton({ menu = true }, enabled = enabled, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.MoreVert, "More options for $name")
            }
            DropdownMenu(menu, { menu = false }) {
                actions.forEach { action ->
                    DropdownMenuItem(
                        text = { Text(action.label, color = if (action.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) },
                        onClick = { menu = false; action.run() },
                    )
                }
            }
        }
    }
}

/** A 40dp circle with the room's first letter or digit. Colour comes from the
 *  room id so a room looks the same on every device; an ended room goes muted. */
@Composable
private fun RoomAvatar(roomId: String, name: String, ended: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    val fill = if (ended) scheme.surfaceVariant else Color(avatarColour(roomId, dark))
    val text = if (ended) scheme.onSurfaceVariant else Color.White
    Box(Modifier.size(40.dp).clip(CircleShape).background(fill).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        Text(avatarLetter(name), color = text, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun PinGlyph() {
    Icon(Icons.Filled.PushPin, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun UnreadPill(count: Int) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
        Text(count.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp))
    }
}

@Composable
private fun RowTime(time: String, timeSpoken: String, weight: FontWeight?) {
    Text(time, style = MaterialTheme.typography.bodyMedium, fontWeight = weight, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { contentDescription = timeSpoken })
}

/**
 * A self-destructed room's row: greyed, the time it went, and Dismiss. It
 * names no room (owner decision D2), so nobody is left wondering where a room
 * went and nothing here says which it was.
 */
@Composable
internal fun TombstoneRow(at: Long, zone: java.time.ZoneId, locale: java.util.Locale, onDismiss: () -> Unit) {
    val text = dev.forgesworn.kithmoot.session.tombstoneText(at, zone, locale)
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).clearAndSetSemantics {})
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onDismiss, Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Dismiss: $text" }) { Text("Dismiss") }
    }
}
