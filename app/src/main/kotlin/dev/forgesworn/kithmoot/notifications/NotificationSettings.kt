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
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.forgesworn.kithmoot.service.BackgroundCallListenerService
import dev.forgesworn.kithmoot.service.BackgroundDeliverySettings
import dev.forgesworn.kithmoot.service.BackgroundRingSettings
import dev.forgesworn.kithmoot.service.ReachabilityPrompt

@Composable
fun NotificationSettings(
    notices: ChatNotifications,
    /** The room this menu was opened from, if any: only then is there a
     *  call to ring for. See RoomViewModel.callRingMode / setCallRingMode. */
    room: CallRingRoom? = null,
    /** False inside Settings, whose section label already names it. */
    showHeading: Boolean = true,
    /** What is stopping calls from ringing, with its one button: a second, permanent way to put it right. */
    prompt: ReachabilityPrompt? = null,
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
    if (showHeading) Text("Notifications & sound", style = MaterialTheme.typography.headlineSmall)
    prompt?.let {
        Text(it.banner.message, style = MaterialTheme.typography.titleSmall)
        Text(it.banner.detail)
        Button(it.onAction, Modifier.heightIn(min = 48.dp), enabled = !it.busy) { Text(if (it.busy) "Waiting for your signer…" else it.banner.action) }
        HorizontalDivider()
    }
    Text("New messages in your rooms, including while KithMoot is closed when receiving in the background is on below.")
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
    Text("On by default. Off, alerts name the room and sender and message text stays inside KithMoot. The lock screen says only that a message came unless Android is set to show more.", style = MaterialTheme.typography.bodySmall)
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
    HorizontalDivider()
    Spacer(Modifier.height(12.dp))
    val context = LocalContext.current
    val backgroundRing = remember { BackgroundRingSettings(context) }
    val backgroundDelivery = remember { BackgroundDeliverySettings(context) }
    var backgroundEnabled by remember { mutableStateOf(backgroundRing.enabled()) }
    var deliveryEnabled by remember { mutableStateOf(backgroundDelivery.enabled()) }
    // The running service keeps itself current; one of the two switches still
    // on means it restarts and reconciles rather than stopping.
    fun apply() {
        if (backgroundEnabled || deliveryEnabled) BackgroundCallListenerService.start(context)
        else BackgroundCallListenerService.stop(context)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Ring when KithMoot is closed", Modifier.weight(1f))
        Switch(backgroundEnabled, { enabled ->
            backgroundEnabled = enabled
            backgroundRing.setEnabled(enabled)
            apply()
        }, Modifier.semantics { contentDescription = "Ring when KithMoot is closed" })
    }
    Text("On by default. Keeps a quiet notification in the tray and uses some battery so a Ring me room can still ring you while KithMoot is closed.", style = MaterialTheme.typography.bodySmall)
    var fullScreen by remember { mutableStateOf(canRingFullScreen(context)) }
    var fullScreenTried by remember { mutableStateOf(backgroundRing.fullScreenSettingsTried) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                fullScreen = canRingFullScreen(context)
                fullScreenTried = backgroundRing.fullScreenSettingsTried
            }
        }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    if (!fullScreen) {
        Spacer(Modifier.height(8.dp))
        Text("Calls ring as a notification only. Let them take the screen, over the lock screen, like a phone call.")
        OutlinedButton({ openFullScreenCallSettings(context) }, Modifier.semantics { contentDescription = "Allow full-screen calls" }) {
            Text("Allow full-screen calls")
        }
        // Once Android's page has been opened and the permission is still
        // missing on the way back, say why it may be greyed out there.
        if (fullScreenTried) {
            Text(FULL_SCREEN_STILL_OFF_HELP, style = MaterialTheme.typography.bodySmall)
            TextButton({ openAppInfo(context) }) { Text("Open App info") }
        }
    }
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Receive messages when KithMoot is closed", Modifier.weight(1f))
        Switch(deliveryEnabled, { enabled ->
            deliveryEnabled = enabled
            backgroundDelivery.setEnabled(enabled)
            apply()
        }, Modifier.semantics { contentDescription = "Receive messages when KithMoot is closed" })
    }
    Text("On by default, so messages arrive as they are sent. Keeps a connection open for each saved room, which uses some battery: there is no push server to do it instead. Anonymous and quiet rooms receive only when opened.", style = MaterialTheme.typography.bodySmall)
    if (deliveryEnabled) {
        val state by produceState(backgroundDelivery.state()) {
            while (true) { this.value = backgroundDelivery.state(); kotlinx.coroutines.delay(2_000) }
        }
        Text("Background messages: ${state.label}", Modifier.semantics { contentDescription = "Background messages: ${state.label}" },
            style = MaterialTheme.typography.bodySmall)
    }
}

/** The room a [NotificationSettings] menu was opened from, and how to read
 *  and change its incoming-call setting. Kept as an interface rather than
 *  a `RoomViewModel` reference so this file does not depend on it. */
interface CallRingRoom {
    val roomId: String
    fun mode(): CallRingMode
    fun setMode(mode: CallRingMode)
}
