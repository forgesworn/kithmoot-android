package dev.forgesworn.kithmoot.service

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.notifications.CallRingSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Starts [BackgroundCallListenerService] when the switches, the saved rooms
 * and Android's notification permission all want it running, and says
 * whether it did. The one rule for every way in - app start, the app coming
 * back to the front, a reboot or update, notifications allowed in KithMoot or
 * in Android's settings - so permission granted after the app started is
 * picked up at the next of these rather than only at the next launch.
 *
 * Off the main thread: this decrypts the saved rooms. The OS can refuse a
 * foreground start from the background; the next of these tries again.
 */
suspend fun startBackgroundServiceIfWanted(context: Context): Boolean = withContext(Dispatchers.IO) {
    val application = context.applicationContext as KithMootApplication
    val toggle = BackgroundRingSettings(context).enabled()
    val delivery = BackgroundDeliverySettings(context).enabled()
    val ringSettings = CallRingSettings(context)
    val savedIds = savedRoomIdsOrNone(application.savedRooms)
    val notificationsPermitted = NotificationManagerCompat.from(context).areNotificationsEnabled()
    shouldRunBackgroundService(toggle, delivery, savedIds, ringSettings::modeFor, notificationsPermitted) &&
        runCatching { BackgroundCallListenerService.start(context) }.isSuccess
}
