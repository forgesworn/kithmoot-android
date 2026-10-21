package dev.forgesworn.kithmoot.ui.room

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.NotificationsPaused
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.notifications.MuteLength
import dev.forgesworn.kithmoot.notifications.RoomMute
import dev.forgesworn.kithmoot.notifications.RoomNotifyMode
import dev.forgesworn.kithmoot.notifications.mutedUntilText
import dev.forgesworn.kithmoot.ui.epochSeconds
import java.time.ZoneId
import java.util.Locale

/** What the Notifications sheet reads and changes for a room (RoomViewModel). */
class RoomNotifyActions(
    val mode: (String) -> RoomNotifyMode,
    val setMode: (String, RoomNotifyMode) -> Unit,
    val mute: (String, MuteLength) -> Unit,
    val unmute: (String) -> Unit,
)

/** "Muted until 11:00", in the phone's zone and 12 or 24-hour clock. */
@Composable
internal fun mutedText(mute: RoomMute, now: Long = epochSeconds()): String {
    val context = LocalContext.current
    val is24Hour = remember(context) { android.text.format.DateFormat.is24HourFormat(context) }
    return mutedUntilText(mute, now, ZoneId.systemDefault(), Locale.getDefault(), is24Hour)
}

/** The bell-off glyph and the muted line, under a room's name. */
@Composable
internal fun MutedLine(mute: RoomMute, colour: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(Icons.Outlined.NotificationsOff, null, Modifier.size(14.dp), tint = colour)
        Text(mutedText(mute, rememberNow()), style = MaterialTheme.typography.labelSmall, color = colour,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * ⋮ › Notifications, and a row's Notifications in Chats (web #496): which
 * messages tell this person, then a timed mute, or Unmute while one runs.
 * A room set to Nothing has no lengths to offer: it is already silent until
 * changed. Each choice closes the sheet, as a menu item would.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RoomNotificationsSheet(roomId: String, mute: RoomMute?, actions: RoomNotifyActions, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        RoomNotificationsContent(remember(roomId) { actions.mode(roomId) }, mute,
            onMode = { actions.setMode(roomId, it); onDismiss() },
            onMute = { actions.mute(roomId, it); onDismiss() },
            onUnmute = { actions.unmute(roomId); onDismiss() })
    }
}

/** The sheet's content, apart from the sheet so a capture can draw it. */
@Composable
internal fun RoomNotificationsContent(mode: RoomNotifyMode, mute: RoomMute?, onMode: (RoomNotifyMode) -> Unit,
    onMute: (MuteLength) -> Unit, onUnmute: () -> Unit, modifier: Modifier = Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        Text("Notifications", style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp).semantics { heading() })
        if (mute != null) Row(Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) { MutedLine(mute, muted) }
        Column(Modifier.fillMaxWidth().padding(top = 8.dp).selectableGroup()) {
            for (option in RoomNotifyMode.entries) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .selectable(selected = option == mode, role = Role.RadioButton) { onMode(option) }
                    .padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    RadioButton(selected = option == mode, onClick = null)
                    Text(option.label, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        if (mode == RoomNotifyMode.NOTHING) return@Column
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        if (mute is RoomMute.Until) SheetAction("Unmute", Icons.Outlined.Notifications, onUnmute)
        else for (length in MuteLength.entries) SheetAction(length.label, Icons.Outlined.NotificationsPaused) { onMute(length) }
    }
}

@Composable
private fun SheetAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
