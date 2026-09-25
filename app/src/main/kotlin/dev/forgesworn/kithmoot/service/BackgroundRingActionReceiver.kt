package dev.forgesworn.kithmoot.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** "Turn off" on the listening-for-calls notification: the same switch as
 *  turning it off in Notification settings, reachable without opening the app. */
class BackgroundRingActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TURN_OFF) return
        BackgroundRingSettings(context).setEnabled(false)
        BackgroundCallListenerService.stop(context)
    }

    companion object {
        const val ACTION_TURN_OFF = "dev.forgesworn.kithmoot.BACKGROUND_RING_TURN_OFF"
    }
}
