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
 * tells the room anything. Both record the call in [HandledCalls], so it
 * does not ring again while it runs, whichever tracker is watching the room.
 */
class IncomingCallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val roomId = intent.getStringExtra(IncomingCallRinger.EXTRA_ROOM_ID) ?: return
        val callId = intent.getStringExtra(IncomingCallRinger.EXTRA_CALL_ID).orEmpty()
        HandledCalls.add(roomId, callId)
        // Before the stop: an answered Telecom call must outlive the ring.
        if (intent.action == ACTION_ANSWER) CallTelecom.answeredInApp(roomId) else CallTelecom.declinedInApp(roomId)
        IncomingCallRinger.stop(context, roomId)
        if (intent.action != ACTION_ANSWER) return
        context.startActivity(answerIntent(context, roomId, intent.getStringExtra(IncomingCallRinger.EXTRA_ROOM_NAME).orEmpty(), callId))
    }

    companion object {
        /**
         * Opens KithMoot straight into the call. Answer buttons start this
         * activity themselves: Android 12 and later block an activity started
         * from a broadcast a notification sent (a "trampoline"), so an Answer
         * routed through this receiver did nothing while the phone was in use.
         * MainActivity stops the ring and records the call as handled.
         */
        fun answerIntent(context: Context, roomId: String, roomName: String, callId: String): Intent =
            Intent(context, MainActivity::class.java).setAction(ACTION_ANSWER)
                .putExtra(IncomingCallRinger.EXTRA_ROOM_ID, roomId)
                .putExtra(IncomingCallRinger.EXTRA_ROOM_NAME, roomName)
                .putExtra(IncomingCallRinger.EXTRA_CALL_ID, callId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        const val ACTION_ANSWER = "dev.forgesworn.kithmoot.CALL_ANSWER"
        const val ACTION_DECLINE = "dev.forgesworn.kithmoot.CALL_DECLINE"
    }
}
