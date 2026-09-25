package dev.forgesworn.kithmoot.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.telecom.CallTelecom

/**
 * Answer and Decline off the incoming-call notification (and, by the same
 * intents, off [dev.forgesworn.kithmoot.ui.incoming.IncomingCallActivity]'s
 * own buttons).
 *
 * Decline only cancels this device's notification for this call; it never
 * tells the room anything; [IncomingCallTracker] already keeps the call id
 * out of `seen` from stopping it ringing again while the same call runs.
 */
class IncomingCallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val roomId = intent.getStringExtra(IncomingCallRinger.EXTRA_ROOM_ID) ?: return
        // Before the stop: an answered Telecom call must outlive the ring.
        if (intent.action == ACTION_ANSWER) CallTelecom.answeredInApp(roomId) else CallTelecom.declinedInApp(roomId)
        IncomingCallRinger.stop(context, roomId)
        if (intent.action != ACTION_ANSWER) return
        val roomName = intent.getStringExtra(IncomingCallRinger.EXTRA_ROOM_NAME).orEmpty()
        val callId = intent.getStringExtra(IncomingCallRinger.EXTRA_CALL_ID).orEmpty()
        val answer = Intent(context, MainActivity::class.java).setAction(ACTION_ANSWER)
            .putExtra(IncomingCallRinger.EXTRA_ROOM_ID, roomId)
            .putExtra(IncomingCallRinger.EXTRA_ROOM_NAME, roomName)
            .putExtra(IncomingCallRinger.EXTRA_CALL_ID, callId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        context.startActivity(answer)
    }

    companion object {
        const val ACTION_ANSWER = "dev.forgesworn.kithmoot.CALL_ANSWER"
        const val ACTION_DECLINE = "dev.forgesworn.kithmoot.CALL_DECLINE"
    }
}
