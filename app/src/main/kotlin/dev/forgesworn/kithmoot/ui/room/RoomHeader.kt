package dev.forgesworn.kithmoot.ui.room

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.account.shortNpub
import dev.forgesworn.kithmoot.ui.RoomState

/*
 * The room screen's header pieces (design-calm-shell 5.2, 5.3): the line
 * under the name, the ⋮ menu, the call banner that replaced the Call tab,
 * the bar for reading the chat while on a call, and the empty room's one
 * way in. Words match the web app's, less what Android cannot honour yet.
 */

/** Who is here, under the room's name: "3 here", or "2 people, 1 agent"
 *  when agents are among them. The web says the same after its
 *  conversation's name, which Android, with one conversation, leaves off. */
internal fun presenceLine(participants: Collection<String>, agents: Set<String>): String {
    if (participants.isEmpty()) return ""
    val agentCount = participants.count { it in agents }
    if (agentCount == 0) return "${participants.size} here"
    val people = participants.size - agentCount
    return "$people ${if (people == 1) "person" else "people"}, $agentCount agent${if (agentCount == 1) "" else "s"}"
}

/** The ⋮ menu's items, in its three groups, as the web's room menu has them. */
internal enum class RoomMenuItem(val label: String) {
    ROOM_INFO("Room info"), SEARCH("Search in room"), INVITE("Invite people…"),
    NEW_TASK("New task…"), PIN("Pin"), UNPIN("Unpin"), ADD_TO_PROJECT("Add to project…"),
    LEAVE_ROOM("Leave room…"), LEAVE_PRIVATE("Leave private chat…"),
}

/**
 * What the ⋮ menu offers here, grouped as the dividers fall. An item shows
 * only when it applies. Not yet on Android, so not shown: Add an agent…,
 * Notifications and End room for everyone.
 *
 * @param pinned null when the room is not saved on this phone, so cannot be pinned.
 */
internal fun roomMenuGroups(
    privateChat: Boolean,
    canInvite: Boolean,
    narrow: Boolean,
    board: Boolean,
    pinned: Boolean?,
    canAddToProject: Boolean,
): List<List<RoomMenuItem>> = listOf(
    buildList {
        add(RoomMenuItem.ROOM_INFO)
        if (narrow) add(RoomMenuItem.SEARCH)
        if (canInvite && !privateChat) add(RoomMenuItem.INVITE)
    },
    buildList {
        if (board) add(RoomMenuItem.NEW_TASK)
        if (pinned != null) add(if (pinned) RoomMenuItem.UNPIN else RoomMenuItem.PIN)
        if (canAddToProject && !privateChat) add(RoomMenuItem.ADD_TO_PROJECT)
    },
    listOf(if (privateChat) RoomMenuItem.LEAVE_PRIVATE else RoomMenuItem.LEAVE_ROOM),
).filter { it.isNotEmpty() }

private fun RoomMenuItem.icon(): ImageVector = when (this) {
    RoomMenuItem.ROOM_INFO -> Icons.Outlined.Info
    RoomMenuItem.SEARCH -> Icons.Filled.Search
    RoomMenuItem.INVITE -> Icons.Filled.PersonAdd
    RoomMenuItem.NEW_TASK -> Icons.Outlined.TaskAlt
    RoomMenuItem.PIN, RoomMenuItem.UNPIN -> Icons.Outlined.PushPin
    RoomMenuItem.ADD_TO_PROJECT -> Icons.Outlined.CreateNewFolder
    RoomMenuItem.LEAVE_ROOM, RoomMenuItem.LEAVE_PRIVATE -> Icons.AutoMirrored.Filled.Logout
}

/** ⋮ and its menu. Leave is said in the danger colour, as on the web. */
@Composable
internal fun RoomMoreMenu(groups: List<List<RoomMenuItem>>, onChoose: (RoomMenuItem) -> Unit) {
    var open by remember { mutableStateOf(false) }
    // In its own box, so the menu drops from ⋮ rather than from the bar's start.
    Box {
    IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, "More for this room") }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.widthIn(min = 220.dp)) {
        groups.forEachIndexed { index, group ->
            if (index > 0) HorizontalDivider()
            for (item in group) {
                val danger = item == RoomMenuItem.LEAVE_ROOM || item == RoomMenuItem.LEAVE_PRIVATE
                val colour = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                DropdownMenuItem(
                    text = { Text(item.label, color = colour, style = MaterialTheme.typography.bodyLarge) },
                    leadingIcon = { Icon(item.icon(), null, tint = if (danger) colour else MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = { open = false; onChoose(item) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
    }
    }
}

/** Whoever started the room's current call, named the way its tiles name them. */
internal fun callStarterName(state: RoomState): String {
    val starter = state.currentCall?.starter ?: return "Somebody"
    val tile = state.tiles.firstOrNull { it.participant == starter }
    return state.profiles[starter]?.name ?: tile?.cardName?.takeIf { it.isNotBlank() } ?: tile?.name ?: shortNpub(starter)
}

/** "Rowan started a call · 3 on it", or just "Rowan started a call" while they are alone on it. */
internal fun callBannerText(starter: String, people: Int): String =
    if (people > 1) "$starter started a call · $people on it" else "$starter started a call"

/** How long the call has run: "4:07", or "1:02:09" past the hour. */
internal fun callElapsed(since: Long, now: Long): String {
    val seconds = (now - since).coerceAtLeast(0)
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val rest = seconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, rest) else "%d:%02d".format(minutes, rest)
}

/** A call is on and this device is not on it: who started it, and Join call.
 *  The button goes under the words when large text leaves no room beside them. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CallBanner(text: String, joinEnabled: Boolean, onJoin: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        FlowRow(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text, Modifier.align(Alignment.CenterVertically).padding(end = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Button(onClick = onJoin, enabled = joinEnabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Join call") }
        }
    }
}

/** On the call but reading the chat: "On the call · 4:07", then Mute, Return
 *  and Leave, as the docked bar has them; the buttons wrap under the words
 *  when large text leaves no room beside them. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OnCallBar(text: String, micOn: Boolean, leaving: Boolean, onMic: () -> Unit, onReturn: () -> Unit, onLeave: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        FlowRow(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text, Modifier.align(Alignment.CenterVertically).padding(end = 8.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onMic, enabled = !leaving, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (micOn) "Mute" else "Unmute") }
                FilledTonalButton(onClick = onReturn, enabled = !leaving, modifier = Modifier.heightIn(min = 48.dp)) { Text("Return") }
                Button(onClick = onLeave, enabled = !leaving, modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)) {
                    Text("Leave")
                }
            }
        }
    }
}

/** Nobody else is here and nobody has said anything: the one thing to do is get somebody in. */
@Composable
internal fun EmptyRoom(onShare: () -> Unit, onShowQr: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.widthIn(max = 360.dp).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Nobody else is here yet.", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Button(onClick = onShare, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Share invite link") }
        OutlinedButton(onClick = onShowQr, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Show QR code") }
        Text("Anyone with the link can join.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center)
    }
}
