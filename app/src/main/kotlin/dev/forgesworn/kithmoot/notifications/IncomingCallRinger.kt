package dev.forgesworn.kithmoot.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import dev.forgesworn.kithmoot.R
import dev.forgesworn.kithmoot.ui.incoming.IncomingCallActivity
import dev.forgesworn.kithmoot.ui.room.callerLabel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Posts, and stops, the incoming-call notification: `CallStyle` on the
 * channel this declares, a full-screen intent to [IncomingCallActivity]
 * where Android 14's `canUseFullScreenIntent()` allows it, and Answer /
 * Decline actions routed through [IncomingCallActionReceiver].
 *
 * The one thing this never does on its own is decide *whether* to ring -
 * that is [IncomingCallTracker] plus [CallRingSettings], both upstream of
 * every call here.
 */
object IncomingCallRinger {
    const val CHANNEL_ID = "incoming_call_v1"
    private const val NOTIFICATION_ID = 4603
    private const val RING_TIMEOUT_MS = 45_000L

    const val EXTRA_ROOM_ID = "kithmoot.call_room_id"
    const val EXTRA_ROOM_NAME = "kithmoot.call_room_name"
    const val EXTRA_CALL_ID = "kithmoot.call_id"
    const val EXTRA_CALLER = "kithmoot.call_caller"

    data class ActiveCall(val roomId: String, val roomName: String, val callId: String, val caller: String)

    private val mutableActive = MutableStateFlow<ActiveCall?>(null)
    /** The call currently ringing or shown on [IncomingCallActivity], if any.
     *  The activity finishes itself once this no longer names its call. */
    val active: StateFlow<ActiveCall?> = mutableActive.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())
    private val timeouts = mutableMapOf<String, Runnable>()

    fun channel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, "Incoming calls", NotificationManager.IMPORTANCE_HIGH)
        channel.description = "A call starting in a room you have open on this device."
        channel.enableVibration(true)
        channel.setSound(
            Settings.System.DEFAULT_RINGTONE_URI,
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** Posts the incoming-call notification. `quiet` skips the ringtone,
     *  vibration and full-screen intent but still shows Answer / Decline. */
    fun ring(context: Context, roomId: String, roomName: String, callId: String, caller: String, quiet: Boolean) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        channel(context)
        mutableActive.value = ActiveCall(roomId, roomName, callId, caller)

        val fullScreen = Intent(context, IncomingCallActivity::class.java)
            .putExtra(EXTRA_ROOM_ID, roomId).putExtra(EXTRA_ROOM_NAME, roomName)
            .putExtra(EXTRA_CALL_ID, callId).putExtra(EXTRA_CALLER, caller)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val fullScreenPending = PendingIntent.getActivity(
            context, roomId.hashCode(), fullScreen,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val answerPending = PendingIntent.getBroadcast(
            context, roomId.hashCode() xor 1,
            actionIntent(context, IncomingCallActionReceiver.ACTION_ANSWER, roomId, roomName, callId, caller),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val declinePending = PendingIntent.getBroadcast(
            context, roomId.hashCode() xor 2,
            actionIntent(context, IncomingCallActionReceiver.ACTION_DECLINE, roomId, roomName, callId, caller),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val callerName = callerLabel(caller)
        val style = NotificationCompat.CallStyle.forIncomingCall(
            Person.Builder().setName(callerName).build(), declinePending, answerPending,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_chat_notice)
            .setContentTitle(callerName)
            .setContentText("Calling in ${roomName.ifBlank { "KithMoot" }.take(120)}")
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(fullScreenPending)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSilent(quiet)

        // Android 14 requires the check before a full-screen intent will
        // actually launch over the lock screen; asking for it any other way
        // is a settings-page nag, which the person did not ask for. When it
        // is not permitted the notification still posts, heads-up, with its
        // Answer and Decline actions - just without taking the screen.
        val allowFullScreen = if (Build.VERSION.SDK_INT >= 34) {
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        } else true
        if (allowFullScreen && !quiet) builder.setFullScreenIntent(fullScreenPending, true)

        try {
            NotificationManagerCompat.from(context).notify(roomId, NOTIFICATION_ID, builder.build())
        } catch (_: SecurityException) {
            mutableActive.value = null
            return
        }
        scheduleTimeout(context, roomId, callId)
    }

    /** Stops ringing for whatever call this room is currently showing, if any. */
    fun stop(context: Context, roomId: String) {
        cancelTimeout(roomId)
        NotificationManagerCompat.from(context).cancel(roomId, NOTIFICATION_ID)
        if (mutableActive.value?.roomId == roomId) mutableActive.value = null
    }

    private fun scheduleTimeout(context: Context, roomId: String, callId: String) {
        cancelTimeout(roomId)
        val runnable = Runnable {
            if (mutableActive.value?.roomId == roomId && mutableActive.value?.callId == callId) stop(context, roomId)
        }
        timeouts[roomId] = runnable
        handler.postDelayed(runnable, RING_TIMEOUT_MS)
    }

    private fun cancelTimeout(roomId: String) {
        timeouts.remove(roomId)?.let { handler.removeCallbacks(it) }
    }

    private fun actionIntent(context: Context, action: String, roomId: String, roomName: String, callId: String, caller: String) =
        Intent(context, IncomingCallActionReceiver::class.java).setAction(action)
            .putExtra(EXTRA_ROOM_ID, roomId).putExtra(EXTRA_ROOM_NAME, roomName)
            .putExtra(EXTRA_CALL_ID, callId).putExtra(EXTRA_CALLER, caller)
}
