package dev.forgesworn.kithmoot.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The background notification's "Stop ringing", and the follow-up's "Turn back
 * on". See [handleRingAction]: this only hands the press to it.
 */
class BackgroundRingActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        handleRingAction(intent.action, AndroidRingHost(context))
    }

    companion object {
        /** Kept as it was, so a notification already in the tray from an older build still works. */
        const val ACTION_TURN_OFF = "dev.forgesworn.kithmoot.BACKGROUND_RING_TURN_OFF"
        const val ACTION_TURN_ON = "dev.forgesworn.kithmoot.BACKGROUND_RING_TURN_ON"
    }
}
