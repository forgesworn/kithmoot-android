package dev.forgesworn.kithmoot.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.notifications.CallRingSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Restarts [BackgroundCallListenerService] after a reboot or an update, but
 * only if the person had actually turned "Ring when KithMoot is closed" on
 * and at least one saved room is still set to Ring me - a fresh install, or
 * a phone with the toggle off, gets no background service at all.
 * `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` are protected broadcasts (only
 * the system can send them), so this can safely be `exported="true"`.
 *
 * `goAsync()` extends the receiver's lifetime past `onReceive` returning, so
 * the encrypted-storage reads this needs happen off the boot sequence's main
 * thread. Modelled on Cambium's `BootReceiver`.
 */
class BackgroundRingBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val application = context.applicationContext as KithMootApplication
                val toggle = BackgroundRingSettings(context).enabled()
                val ringSettings = CallRingSettings(context)
                val savedIds = savedRoomIdsOrNone(application.savedRooms)
                val notificationsPermitted = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
                if (shouldRunBackgroundListener(toggle, savedIds, ringSettings::modeFor, notificationsPermitted)) {
                    BackgroundCallListenerService.start(context)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
