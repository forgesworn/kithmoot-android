package dev.forgesworn.kithmoot.notifications

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Whether an incoming call may take the screen, over the lock screen, the
 * way a phone call does. Android 14 grants full-screen intents by default
 * only to apps it classes as calling or alarm apps; a sideloaded KithMoot
 * usually is not one, so without the person's say-so a call arrives as a
 * heads-up notification only (seen on the Pixel: `USE_FULL_SCREEN_INTENT`
 * rejected for an incoming call).
 */
fun canRingFullScreen(context: Context): Boolean =
    Build.VERSION.SDK_INT < 34 || context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

/**
 * Said when full-screen calls are still off after Android's page was opened:
 * a sideloaded app's request can be greyed out until restricted settings are
 * allowed for it (GrapheneOS and stock Android 13 and later alike).
 */
const val FULL_SCREEN_STILL_OFF_HELP: String =
    "Still off? Android may be blocking it for apps installed from a download: open App info → ⋮ → Allow restricted settings, then come back and turn on full-screen calls."

/** This app's App info page, where the ⋮ menu offers Allow restricted settings. */
fun openAppInfo(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        // No App info page to open (a restricted profile): nothing more to offer.
    }
}

/** Android's page for this app's full-screen permission, or its app details page before Android 14. */
fun openFullScreenCallSettings(context: Context) {
    runCatching { dev.forgesworn.kithmoot.service.BackgroundRingSettings(context).fullScreenSettingsTried = true }
    val pkg = Uri.parse("package:${context.packageName}")
    val intent = if (Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg)
        else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        try {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            // No settings page to open (a restricted profile): calls still ring heads-up.
        }
    }
}
