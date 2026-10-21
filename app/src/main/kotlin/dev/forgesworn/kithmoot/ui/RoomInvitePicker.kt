package dev.forgesworn.kithmoot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.protocol.LogoImage
import dev.forgesworn.kithmoot.storage.SavedRoomSummary
import dev.forgesworn.kithmoot.ui.start.RoomAvatar
import dev.forgesworn.kithmoot.ui.start.roomLabel

/** One room the picker offers. [here] is the room on screen, which the person is already in. */
data class InviteRoomChoice(val id: String, val name: String, val logo: LogoImage?, val here: Boolean)

/**
 * The room-invite picker while it is open: who it invites, whether it was
 * opened from People (the invite then rides in the private chat with them)
 * or from somebody in the room on screen, and how the last send went.
 */
data class RoomInvitePicker(val peer: String, val name: String?, val fromPeople: Boolean,
    val sending: Boolean = false, val error: String? = null)

/**
 * Whether [room] can be offered to [account]'s contact: a group room still
 * going, whose saved link is an invitation this phone may share, on the
 * Internet. Never a private chat, a quiet or anonymous room, a nearby-only
 * room, a paired device's copy, or a room joined as another account (that
 * would tie the two accounts together for the person invited).
 */
internal fun invitable(room: SavedRoomSummary, account: String): Boolean =
    !room.ended && room.canShareInvite && !room.secondary && room.privatePeer == null && room.route.internet &&
        !room.anonymous && !room.quiet && (room.account == null || room.account == account)

/** The picker's rooms, most recently opened first, as on the web. [current] is the
 *  room on screen when the invite comes from somebody in it. */
internal fun invitableRooms(rooms: List<SavedRoomSummary>, account: String, current: String?): List<InviteRoomChoice> =
    rooms.filter { invitable(it, account) }.sortedByDescending { it.openedAt }
        .map { InviteRoomChoice(it.id, roomLabel(it.name, it.id), it.logo, here = it.id == current) }

/** The rows matching [query] by name, ignoring case. */
internal fun filterInviteRooms(rooms: List<InviteRoomChoice>, query: String): List<InviteRoomChoice> {
    val q = query.trim()
    return if (q.isEmpty()) rooms else rooms.filter { it.name.contains(q, ignoreCase = true) }
}

/** The picker as a sheet (design-calm-shell 6.4): pick one room, Send invite. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RoomInviteSheet(picker: RoomInvitePicker, rooms: List<InviteRoomChoice>, onSend: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = { if (!picker.sending) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        RoomInviteContent(picker, rooms, onSend, onDismiss)
    }
}

/** The picker's content, apart from the sheet so a capture can draw it. */
@Composable
internal fun RoomInviteContent(picker: RoomInvitePicker, rooms: List<InviteRoomChoice>, onSend: (String) -> Unit,
    onDismiss: () -> Unit, modifier: Modifier = Modifier, initialChoice: String? = null) {
    var query by rememberSaveable(picker.peer) { mutableStateOf("") }
    var chosen by rememberSaveable(picker.peer) { mutableStateOf(initialChoice) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 24.dp).padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(InvitationWords.pickerTitle(picker.name), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        if (rooms.isEmpty()) {
            Text(InvitationWords.empty(picker.name), style = MaterialTheme.typography.bodyLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                FilledTonalButton(onDismiss, Modifier.heightIn(min = 48.dp)) { Text(InvitationWords.CLOSE) }
            }
            return@Column
        }
        if (rooms.size >= InvitationWords.SEARCH_FROM) OutlinedTextField(query, { query = it.take(80) }, Modifier.fillMaxWidth(),
            placeholder = { Text(InvitationWords.FIND_ROOM) }, leadingIcon = { Icon(Icons.Filled.Search, null) }, singleLine = true,
            label = { Text(InvitationWords.FIND_ROOM) })
        val shown = filterInviteRooms(rooms, query)
        if (shown.isEmpty()) Text(InvitationWords.NO_MATCH, style = MaterialTheme.typography.bodyMedium, color = muted)
        Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()).selectableGroup()) {
            for (room in shown) {
                val note = if (room.here) InvitationWords.alreadyHere(picker.name) else null
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .selectable(selected = chosen == room.id, enabled = !room.here && !picker.sending, role = Role.RadioButton) { chosen = room.id },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RadioButton(selected = chosen == room.id, onClick = null, enabled = !room.here)
                    RoomAvatar(room.id, room.name, ended = false, logo = room.logo)
                    Column(Modifier.weight(1f)) {
                        Text(room.name, style = MaterialTheme.typography.bodyLarge, color = if (room.here) muted else MaterialTheme.colorScheme.onSurface)
                        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = muted) }
                    }
                }
            }
        }
        Text(InvitationWords.sealedNote(picker.name), style = MaterialTheme.typography.bodySmall, color = muted)
        picker.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        val ready = chosen != null && rooms.any { it.id == chosen && !it.here }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            FilledTonalButton(onDismiss, Modifier.heightIn(min = 48.dp), enabled = !picker.sending) { Text(InvitationWords.CANCEL) }
            Button({ chosen?.let(onSend) }, Modifier.heightIn(min = 48.dp), enabled = ready && !picker.sending) {
                Text(if (picker.sending) InvitationWords.SENDING else InvitationWords.SEND)
            }
        }
    }
}
