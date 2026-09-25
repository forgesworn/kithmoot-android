package dev.forgesworn.kithmoot.notifications

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
fun NotificationSettings(
    notices: ChatNotifications,
    /** The room this menu was opened from, if any: only then is there a
     *  call to ring for. See RoomViewModel.callRingMode / setCallRingMode. */
    room: CallRingRoom? = null,
) {
    val value by notices.settings.collectAsState()
    var allowed by remember { mutableStateOf(notices.allowed()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) allowed = notices.allowed() }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed = granted; notices.save(notices.settings.value.copy(enabled = granted))
    }
    Text("Notifications & sound", style = MaterialTheme.typography.headlineSmall)
    Text("Alerts for new messages in your joined room while KithMoot stays connected. Other rooms and delivery after Android closes or suspends the app are not covered yet.")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Message notifications", Modifier.weight(1f))
        Switch(value.enabled, { enabled ->
            if (enabled && !notices.allowed()) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else notices.save(value.copy(enabled = enabled))
        }, Modifier.semantics { contentDescription = "Message notifications" })
    }
    if (!allowed) Text("Android notifications are currently blocked. Enable them here or in Android settings.")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Zen bell", Modifier.weight(1f))
        Switch(value.bell, { notices.save(value.copy(bell = it)) }, Modifier.semantics { contentDescription = "Zen bell" })
    }
    OutlinedButton({ notices.preview() }) { Text("Preview Zen bell") }
    Text("Quiet during calls. Android's sound, Do Not Disturb and notification settings take priority. Icon dots or numbers depend on your launcher.", style = MaterialTheme.typography.bodySmall)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Show message previews", Modifier.weight(1f))
        Switch(value.previews, { notices.save(value.copy(previews = it)) }, Modifier.semantics { contentDescription = "Show message previews" })
    }
    Text("Off by default. Alerts name the room and sender; message text stays inside KithMoot. Lock-screen alerts hide these details unless Android permits them.", style = MaterialTheme.typography.bodySmall)
    TextButton({ notices.systemSettings() }) { Text("Android notification settings") }
    if (room != null) {
        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        Text("Incoming calls in this room", style = MaterialTheme.typography.titleSmall)
        var mode by remember(room.roomId) { mutableStateOf(room.mode()) }
        Column(Modifier.selectableGroup()) {
            CallRingMode.entries.forEach { option ->
                Row(
                    Modifier.fillMaxWidth().selectable(selected = mode == option, onClick = {
                        mode = option
                        room.setMode(option)
                    }).padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = mode == option, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Text(option.label)
                }
            }
        }
        Text("Only takes effect while message notifications above are on.", style = MaterialTheme.typography.bodySmall)
    }
    Spacer(Modifier.height(12.dp))
}

/** The room a [NotificationSettings] menu was opened from, and how to read
 *  and change its incoming-call setting. Kept as an interface rather than
 *  a `RoomViewModel` reference so this file does not depend on it. */
interface CallRingRoom {
    val roomId: String
    fun mode(): CallRingMode
    fun setMode(mode: CallRingMode)
}
