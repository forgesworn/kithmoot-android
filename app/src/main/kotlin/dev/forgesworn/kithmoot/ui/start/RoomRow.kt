package dev.forgesworn.kithmoot.ui.start

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlternateEmail
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
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/** A menu item on a row's overflow: a visible label the accessible name
 *  never differs from (F6), and whether it reads in the destructive colour. */
internal data class ConversationAction(val label: String, val destructive: Boolean = false, val run: () -> Unit)

/**
 * One row of Chats (design-calm-shell 4.5 and 9.5): an avatar, then the name
 * and time, then the preview and the unread count. No card, no border, no
 * visible menu button: a long press opens the row's menu, and TalkBack offers
 * the same items as custom actions. The whole row is one merged node, so
 * TalkBack reads the name, the status line and the time, then the state
 * (pinned, unread, mentions you).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RoomRow(roomId: String, name: String, status: String?, time: String, timeSpoken: String, enabled: Boolean,
    open: () -> Unit, actions: List<ConversationAction>, pinned: Boolean = false, ended: Boolean = false, unread: Int = 0,
    /** The room's countdown pill, for a room with an end still to come. */
    countdown: (@Composable () -> Unit)? = null,
    privatePeer: String? = null, profile: dev.forgesworn.kithmoot.ui.room.PublicProfile? = null,
    logo: dev.forgesworn.kithmoot.protocol.LogoImage? = null,
    /** The room open beside the list on a tablet. */
    selected: Boolean = false,
    /** An unread message here mentions this person. */
    mentioned: Boolean = false) {
    var menu by remember { mutableStateOf(false) }
    val stacked = LocalDensity.current.fontScale >= 1.5f
    // "No messages yet" says nothing the row does not already show, so the
    // row is one line; TalkBack still hears it, through the node's state.
    val preview = status?.takeIf { it != NO_MESSAGES_YET }
    val weight = if (unread > 0) FontWeight.Bold else null
    val spoken = listOfNotNull(
        "Pinned".takeIf { pinned }, "$unread unread".takeIf { unread > 0 }, "Mentions you".takeIf { mentioned },
        NO_MESSAGES_YET.takeIf { status == NO_MESSAGES_YET },
    ).joinToString(". ")
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .then(if (selected) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier)
                .combinedClickable(
                    enabled = enabled,
                    onClick = open, onClickLabel = "Open",
                    onLongClick = if (actions.isEmpty()) null else ({ menu = true }), onLongClickLabel = "More options",
                )
                .semantics(mergeDescendants = true) {
                    if (spoken.isNotEmpty()) stateDescription = spoken
                    if (selected) this.selected = true
                    if (enabled) customActions = actions.map { action -> CustomAccessibilityAction(action.label) { action.run(); true } }
                }
                .heightIn(min = 64.dp)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            if (privatePeer != null && profile?.picture != null) {
                dev.forgesworn.kithmoot.ui.room.ProfileAvatar(privatePeer, name, profile,
                    Modifier.size(40.dp).clearAndSetSemantics {})
            } else RoomAvatar(roomId, name, ended, logo.takeIf { privatePeer == null })
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
                if (stacked) {
                    Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = weight, color = MaterialTheme.colorScheme.onSurface)
                    preview?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2) }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (pinned) PinGlyph()
                        RowTime(time, timeSpoken, weight)
                        if (mentioned) MentionGlyph()
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
                    if (preview != null || unread > 0 || mentioned) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(preview.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (mentioned) MentionGlyph()
                        if (unread > 0) UnreadPill(unread)
                    }
                    countdown?.invoke()
                }
            }
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

/** The @ beside a row's count: an unread message mentions this person (spoken through the row's state). */
@Composable
private fun MentionGlyph() {
    Icon(Icons.Filled.AlternateEmail, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
}

/** A 40dp circle with the room's first letter or digit. Colour comes from the
 *  room id so a room looks the same on every device; an ended room goes muted. */
@Composable
private fun RoomAvatar(roomId: String, name: String, ended: Boolean, logo: dev.forgesworn.kithmoot.protocol.LogoImage?) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    val fill = if (ended) scheme.surfaceVariant else Color(avatarColour(roomId, dark))
    val text = if (ended) scheme.onSurfaceVariant else Color.White
    dev.forgesworn.kithmoot.ui.room.PrivateLogo(logo.takeUnless { ended }, avatarLetter(name), Modifier.size(40.dp), fill, text)
}

/** A person's avatar: their public picture when there is one, else their
 *  initial on the same colour a room gets from its id, here from their key. */
@Composable
internal fun PersonAvatar(pubkey: String, label: String, profile: dev.forgesworn.kithmoot.ui.room.PublicProfile?, size: androidx.compose.ui.unit.Dp = 40.dp) {
    if (profile?.picture != null) {
        dev.forgesworn.kithmoot.ui.room.ProfileAvatar(pubkey, label, profile, Modifier.size(size).clearAndSetSemantics {})
    } else {
        val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
        dev.forgesworn.kithmoot.ui.room.PrivateLogo(null, avatarLetter(label), Modifier.size(size).clearAndSetSemantics {},
            Color(avatarColour(pubkey, dark)), Color.White)
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
