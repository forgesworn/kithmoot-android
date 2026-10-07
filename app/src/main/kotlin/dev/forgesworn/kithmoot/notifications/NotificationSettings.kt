package dev.forgesworn.kithmoot.notifications

import android.Manifest
import android.os.Build
import android.os.PowerManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.forgesworn.kithmoot.service.BackgroundCallListenerService
import dev.forgesworn.kithmoot.service.BackgroundDeliverySettings
import dev.forgesworn.kithmoot.service.BackgroundRingSettings
import dev.forgesworn.kithmoot.service.ReachabilityBanner
import dev.forgesworn.kithmoot.service.ReachabilityPrompt
import dev.forgesworn.kithmoot.service.backgroundStatusLine
import dev.forgesworn.kithmoot.service.effectivelyOn
import dev.forgesworn.kithmoot.service.requestIgnoreBatteryOptimizations
import dev.forgesworn.kithmoot.telecom.CallTelecom
import dev.forgesworn.kithmoot.telecom.TelecomSettings
import dev.forgesworn.kithmoot.ui.ReachabilityBannerView
import dev.forgesworn.kithmoot.ui.settings.SettingsActionRow
import dev.forgesworn.kithmoot.ui.settings.SettingsNavRow
import dev.forgesworn.kithmoot.ui.settings.SettingsNote
import dev.forgesworn.kithmoot.ui.settings.SettingsRadioGroup
import dev.forgesworn.kithmoot.ui.settings.SettingsSection
import dev.forgesworn.kithmoot.ui.settings.SettingsSwitchRow
import kotlinx.coroutines.launch

private const val NEEDS_NOTIFICATIONS = "Needs notifications to be allowed"

/**
 * Notifications and calls. A switch shows what is really happening, not what
 * was asked for: with Android blocking notifications, the rows that depend on
 * them read off and say why, and the saved choices come back by themselves once
 * notifications are allowed again. At most one notice stands above the rows.
 */
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
    /** How many rooms this phone has saved, for the background status line. The default assumes some. */
    savedRoomCount: Int = 1,
) {
    val value by notices.settings.collectAsState()
    val context = LocalContext.current
    val appContext = context.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val backgroundRing = remember { BackgroundRingSettings(context) }
    val backgroundDelivery = remember { BackgroundDeliverySettings(context) }
    val telecom = remember { TelecomSettings(context) }
    var allowed by remember { mutableStateOf(notices.allowed()) }
    var asked by remember { mutableStateOf(notices.askedForNotifications) }
    var enableOnGrant by remember { mutableStateOf(false) }
    var ringSaved by remember { mutableStateOf(backgroundRing.enabled()) }
    var deliverySaved by remember { mutableStateOf(backgroundDelivery.enabled()) }
    var telecomEnabled by remember { mutableStateOf(telecom.enabled()) }
    var fullScreen by remember { mutableStateOf(canRingFullScreen(context)) }
    var fullScreenTried by remember { mutableStateOf(backgroundRing.fullScreenSettingsTried) }
    var batteryFree by remember { mutableStateOf(isIgnoringBatteryOptimisations(context)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                allowed = notices.allowed()
                fullScreen = canRingFullScreen(context)
                fullScreenTried = backgroundRing.fullScreenSettingsTried
                batteryFree = isIgnoringBatteryOptimisations(context)
            }
        }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed = granted
        asked = true; notices.askedForNotifications = true
        // Only a press on the message switch itself is a wish to turn messages on; the saved choices are otherwise left alone.
        if (granted && enableOnGrant) notices.save(notices.settings.value.copy(enabled = true))
        enableOnGrant = false
        // The background service needs notifications, so it was not running until now.
        if (granted) scope.launch { dev.forgesworn.kithmoot.service.startBackgroundServiceIfWanted(appContext) }
    }
    // The first press asks Android; after a refusal, or below Android 13 where there is nothing to ask, Android's own page is the only way.
    val asksInApp = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !asked
    fun allowNotifications() {
        if (asksInApp) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else notices.systemSettings()
    }
    // The running service keeps itself current; one of the two switches still
    // on means it restarts and reconciles rather than stopping.
    fun apply() {
        if (ringSaved || deliverySaved) BackgroundCallListenerService.start(context)
        else BackgroundCallListenerService.stop(context)
    }

    Column(Modifier.fillMaxWidth()) {
        if (showHeading) Text("Notifications and calls", style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        if (!allowed) {
            ReachabilityBannerView(
                ReachabilityBanner(
                    message = "Notifications are off for KithMoot",
                    detail = "Android is blocking them, so messages and calls can't reach you while KithMoot is closed.",
                    action = if (asksInApp) "Allow notifications" else "Open Android settings",
                ),
                confirming = false, onConfirm = { allowNotifications() }, clearStatusBar = false,
            )
        } else prompt?.let { ReachabilityBannerView(it.banner, it.busy, it.onAction, clearStatusBar = false) }

        SettingsSection("Messages") {
            val messagesOn = effectivelyOn(value.enabled, allowed)
            SettingsSwitchRow("Message notifications", if (allowed) "New messages in your rooms" else NEEDS_NOTIFICATIONS,
                checked = messagesOn) { on ->
                when {
                    on && !allowed -> { enableOnGrant = true; allowNotifications() }
                    else -> notices.save(value.copy(enabled = on))
                }
            }
            val dependent = value.enabled && allowed
            SettingsSwitchRow("Zen bell", "A soft bell when a message arrives. Silent during calls.",
                checked = value.bell, enabled = dependent) { notices.save(value.copy(bell = it)) }
            SettingsActionRow("Hear the Zen bell", Icons.Outlined.PlayArrow, enabled = dependent) { notices.preview() }
            SettingsSwitchRow("Show message text",
                "Off: notifications name only the room and sender. The lock screen says only that a message came, unless Android is set to show more.",
                checked = value.previews, enabled = dependent) { notices.save(value.copy(previews = it)) }
            SettingsNavRow("Android notification settings", "Channels, lock screen and Do Not Disturb", external = true) { notices.systemSettings() }
            SettingsNote("Android's sound, Do Not Disturb and notification settings come first. Dots or numbers on the app icon depend on your launcher.")
            SettingsSwitchRow("Receive messages when KithMoot is closed",
                if (allowed) "Keeps a connection open to each saved room, which uses some battery. There is no push server to do it for you. " +
                    "Tor-only and quiet rooms receive only when opened." else NEEDS_NOTIFICATIONS,
                checked = effectivelyOn(deliverySaved, allowed), enabled = allowed) { enabled ->
                deliverySaved = enabled
                backgroundDelivery.setEnabled(enabled)
                apply()
            }
            val delivery by produceState(backgroundDelivery.state()) {
                while (true) { this.value = backgroundDelivery.state(); kotlinx.coroutines.delay(2_000) }
            }
            backgroundStatusLine(deliverySaved, allowed, savedRoomCount, delivery)?.let { SettingsNote(it, live = true) }
        }

        SettingsSection("Calls") {
            if (room != null) {
                var mode by remember(room.roomId) { mutableStateOf(room.mode()) }
                SettingsNote("Incoming calls in this room")
                SettingsRadioGroup(CallRingMode.entries, mode, { it.label }) { mode = it; room.setMode(it) }
                SettingsNote("Only takes effect while message notifications above are on.")
            }
            SettingsSwitchRow("Ring when KithMoot is closed",
                if (allowed) "Rooms set to Ring me can still ring you. Keeps a quiet notification in the tray and uses some battery." else NEEDS_NOTIFICATIONS,
                checked = effectivelyOn(ringSaved, allowed), enabled = allowed) { enabled ->
                ringSaved = enabled
                backgroundRing.setEnabled(enabled)
                apply()
            }
            if (!fullScreen) {
                SettingsNavRow("Allow full-screen calls",
                    if (fullScreenTried) FULL_SCREEN_STILL_OFF_HELP
                    else "Calls ring as a notification only. Allow them to take over the screen, over the lock screen, like a phone call.",
                    external = true) { openFullScreenCallSettings(context) }
                if (fullScreenTried) SettingsNavRow("Open App info", external = true) { openAppInfo(context) }
            }
            val ringing = effectivelyOn(ringSaved, allowed) || effectivelyOn(deliverySaved, allowed)
            if (ringing && !batteryFree) {
                SettingsNavRow("Let KithMoot run in the background",
                    "Android is limiting KithMoot's battery use, so it may stop calls ringing and messages arriving a few minutes after the screen goes off.",
                    external = true) { requestIgnoreBatteryOptimizations(context) }
            }
            SettingsSwitchRow("Phone call controls",
                "Headsets and car kits can answer and hang up, and a phone call puts a KithMoot call on hold. KithMoot calls stay out of the phone's call history. " +
                    "Turn off if calls misbehave on this phone; it applies from the next call.",
                checked = telecomEnabled) { enabled ->
                telecomEnabled = enabled
                telecom.setEnabled(enabled)
                if (!enabled) CallTelecom.unregister(context)
            }
        }
    }
}

private fun isIgnoringBatteryOptimisations(context: android.content.Context): Boolean =
    context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) ?: true

/** The room a [NotificationSettings] menu was opened from, and how to read
 *  and change its incoming-call setting. Kept as an interface rather than
 *  a `RoomViewModel` reference so this file does not depend on it. */
interface CallRingRoom {
    val roomId: String
    fun mode(): CallRingMode
    fun setMode(mode: CallRingMode)
}
