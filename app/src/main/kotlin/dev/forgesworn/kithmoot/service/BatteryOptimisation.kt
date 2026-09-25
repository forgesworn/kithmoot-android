package dev.forgesworn.kithmoot.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Asked for once, when the person turns "Ring when KithMoot is closed" on:
 * without it Android can suspend the background listener's network within
 * minutes of the screen going off. Declining changes nothing else - the
 * service still runs, just less reliably - and this never asks again on its
 * own.
 */
fun requestIgnoreBatteryOptimizations(context: Context) {
    val powerManager = context.getSystemService(PowerManager::class.java) ?: return
    if (powerManager.isIgnoringBatteryOptimizations(context.packageName)) return
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        // No activity can handle it (some OEM skins, or a restricted
        // profile): the toggle still works, just without the exemption.
    }
}
