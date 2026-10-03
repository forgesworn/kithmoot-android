package dev.forgesworn.kithmoot.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.R

/**
 * The quiet notice for a call this person started on another of their own
 * devices - [IncomingCallChange.OwnCallElsewhere] - which deliberately never
 * rings here. No sound, no vibration, no full-screen intent: a low-importance
 * channel of its own, so it can be turned off without touching real rings.
 *
 * Tapping it opens the room; "Join from this phone" goes through the same
 * [IncomingCallActionReceiver.answerIntent] an answered ring does, which
 * opens the room straight into the call and records it in [HandledCalls].
 */
object OwnCallElsewhereNotice {
    const val CHANNEL_ID = "own_call_elsewhere_v1"
    private const val NOTIFICATION_ID = 4605

    fun channel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, "Calls on your other devices", NotificationManager.IMPORTANCE_LOW)
        channel.description = "A call you started on another device, in a room this device also has."
        channel.enableVibration(false)
        channel.setSound(null, null)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun post(context: Context, roomId: String, roomName: String, callId: String) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        channel(context)
        val open = Intent(context, MainActivity::class.java).setAction(ChatNotifications.OPEN)
            .setData(Uri.parse("kithmoot-own-call://room/$roomId"))
            .putExtra(ChatNotifications.ROOM, roomId)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val openPending = PendingIntent.getActivity(
            context, roomId.hashCode() xor 3, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val joinPending = PendingIntent.getActivity(
            context, roomId.hashCode() xor 4,
            IncomingCallActionReceiver.answerIntent(context, roomId, roomName, callId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_chat_notice)
            .setContentTitle("Call in progress on another device")
            .setContentText(roomName.ifBlank { "KithMoot" }.take(120))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(openPending)
            .addAction(0, "Join from this phone", joinPending)
            .build()
        // Never worth crashing over: the room still shows the call once open.
        runCatching { NotificationManagerCompat.from(context).notify(roomId, NOTIFICATION_ID, notification) }
    }

    fun cancel(context: Context, roomId: String) {
        NotificationManagerCompat.from(context).cancel(roomId, NOTIFICATION_ID)
    }
}
