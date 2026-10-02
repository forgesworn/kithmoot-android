package dev.forgesworn.kithmoot.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
 * Also when KithMoot's notifications are unblocked in Android's settings
 * (`APP_BLOCK_STATE_CHANGED`, sent by the system to this app alone): the
 * service needs them, so until then it was not running. Android lets that
 * start through only while KithMoot is exempt from battery optimisation;
 * otherwise the app coming to the front starts it.
 *
 * `goAsync()` extends the receiver's lifetime past `onReceive` returning, so
 * the encrypted-storage reads this needs happen off the boot sequence's main
 * thread. Modelled on Cambium's `BootReceiver`.
 */
class BackgroundRingBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!startsBackgroundService(intent.action, intent.getBooleanExtra(NotificationManager.EXTRA_BLOCKED_STATE, true))) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                startBackgroundServiceIfWanted(context)
            } finally {
                pendingResult.finish()
            }
        }
    }
}

/** Whether this broadcast is one to start the service on: a reboot, an update, or notifications unblocked. */
internal fun startsBackgroundService(action: String?, blocked: Boolean): Boolean = when (action) {
    Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> true
    NotificationManager.ACTION_APP_BLOCK_STATE_CHANGED -> !blocked
    else -> false
}
