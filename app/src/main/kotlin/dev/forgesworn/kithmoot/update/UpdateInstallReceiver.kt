package dev.forgesworn.kithmoot.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.forgesworn.kithmoot.KithMootApplication

/** Where the package installer reports on an update session: see [AppUpdates.onInstallStatus]. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        (context.applicationContext as KithMootApplication).updates.onInstallStatus(intent)
    }
}
