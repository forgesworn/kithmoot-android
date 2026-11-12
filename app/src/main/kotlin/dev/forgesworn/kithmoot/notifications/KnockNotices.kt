package dev.forgesworn.kithmoot.notifications

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.R

/**
 * One notice per node room while somebody is waiting to join it and KithMoot
 * is out of sight ("Sam wants to join Dark Prague"), through the person's own
 * message notification setting and channel. Anyone with the link can knock,
 * so a room's notice replaces itself rather than stacking up, and a room
 * whose notifications come to Nothing keeps quiet. The name is the guest's
 * own. The lock screen sees only that somebody wants to join a room. Tapping
 * opens the room, where its keeper lists the request.
 */
object KnockNotices {
    const val ID = 4613

    /** Posts the notice; false when notifications are off, here, for the room or in Android. */
    fun post(context: Context, roomId: String, roomLabel: String, who: String, waiting: Int): Boolean {
        if (!ChatNotifications.load(context).enabled) return false
        if (roomNotifyLevel(context, roomId) == NotifyLevel.NOTHING) return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        ChatNotifications.channel(context)
        val intent = Intent(context, MainActivity::class.java).setAction(ChatNotifications.OPEN)
            .setData(Uri.parse("kithmoot-notice://knock/$roomId"))
            .putExtra(ChatNotifications.ROOM, roomId)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val open = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val public = NotificationCompat.Builder(context, ChatNotifications.CHANNEL).setSmallIcon(R.drawable.ic_chat_notice)
            .setContentTitle("KithMoot").setContentText("Somebody wants to join a room").build()
        val title = if (waiting > 1) "$waiting people want to join" else "$who wants to join"
        val notice = NotificationCompat.Builder(context, ChatNotifications.CHANNEL).setSmallIcon(R.drawable.ic_chat_notice)
            .setContentTitle(title)
            .setContentText("Asking to join $roomLabel. Open it to let them in.")
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public)
            .setAutoCancel(true)
            .build()
        return try { NotificationManagerCompat.from(context).notify(roomId, ID, notice); true }
        catch (_: SecurityException) { false }
    }

    fun cancel(context: Context, roomId: String) {
        try { NotificationManagerCompat.from(context).cancel(roomId, ID) } catch (_: SecurityException) { }
    }
}
