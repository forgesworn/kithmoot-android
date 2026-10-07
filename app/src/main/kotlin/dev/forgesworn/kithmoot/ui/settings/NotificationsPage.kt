package dev.forgesworn.kithmoot.ui.settings

import androidx.compose.runtime.Composable

/**
 * Notifications and calls: the notifications body (its notice first) and, since
 * it is how a call looks rather than how it sounds, the camera mirror choice
 * last. Device-wide, like every row here.
 */
@Composable
internal fun NotificationsPage(mirrorSelf: Boolean, onMirrorSelf: (Boolean) -> Unit, notifications: @Composable () -> Unit) {
    notifications()
    SettingsSection("Camera") {
        SettingsSwitchRow("Mirror my camera", "Shows your own camera like a mirror. Others always see it the right way round.",
            checked = mirrorSelf, onCheckedChange = onMirrorSelf)
    }
}
