package dev.forgesworn.kithmoot.notifications

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.forgesworn.kithmoot.service.BackgroundDeliverySettings
import dev.forgesworn.kithmoot.service.BackgroundRingSettings
import dev.forgesworn.kithmoot.service.effectivelyOn

/**
 * What is wrong with notifications and calls, said for the root list, or null
 * when nothing is. Several can be true at once; the one that matters most
 * comes first, since blocked notifications make the others moot.
 */
fun notificationsAttention(allowed: Boolean, ringingOff: Boolean, needsSigner: Boolean, batteryRestricted: Boolean): String? = when {
    !allowed -> "Needs attention: notifications are blocked"
    ringingOff -> "Needs attention: calls won't ring while KithMoot is closed"
    needsSigner -> "Needs attention: you can't answer calls yet"
    batteryRestricted -> "Needs attention: Android may stop calls ringing"
    else -> null
}

/** [notificationsAttention] for this phone right now, re-read whenever the app comes back to the front. */
@Composable
fun rememberNotificationsAttention(notices: ChatNotifications, ringingOff: Boolean, needsSigner: Boolean): String? {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var allowed by remember { mutableStateOf(notices.allowed()) }
    var batteryFree by remember { mutableStateOf(isIgnoringBatteryOptimisations(context)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { allowed = notices.allowed(); batteryFree = isIgnoringBatteryOptimisations(context) }
        }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    val ring = remember { BackgroundRingSettings(context) }
    val delivery = remember { BackgroundDeliverySettings(context) }
    val wantsBackground = effectivelyOn(ring.enabled(), allowed) || effectivelyOn(delivery.enabled(), allowed)
    return notificationsAttention(allowed, ringingOff, needsSigner, batteryRestricted = wantsBackground && !batteryFree)
}
