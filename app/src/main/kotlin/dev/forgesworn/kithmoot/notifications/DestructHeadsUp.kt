package dev.forgesworn.kithmoot.notifications

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.R
import dev.forgesworn.kithmoot.session.headsUpText

/**
 * One heads-up per self-destructing room, at the start of its countdown's red
 * stage ("Dark Prague self-destructs in 1 hour."), through the person's own
 * message notification setting and channel. None at the final minute: the
 * room on screen has its banner. Tagged with the room's id, like the room's
 * messages, so the room's wipe closes it. The lock screen sees only that a
 * room self-destructs soon, never which.
 */
object DestructHeadsUp {
    const val ID = 4612

    /** Posts the heads-up; false when notifications are off, here or in Android. */
    fun post(context: Context, roomId: String, roomLabel: String, remaining: Long): Boolean {
        if (!ChatNotifications.load(context).enabled) return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        ChatNotifications.channel(context)
        val intent = Intent(context, MainActivity::class.java).setAction(ChatNotifications.OPEN)
            .setData(Uri.parse("kithmoot-notice://destruct/$roomId"))
            .putExtra(ChatNotifications.ROOM, roomId)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val open = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val public = NotificationCompat.Builder(context, ChatNotifications.CHANNEL).setSmallIcon(R.drawable.ic_chat_notice)
            .setContentTitle("KithMoot").setContentText("A room self-destructs soon").build()
        val notice = NotificationCompat.Builder(context, ChatNotifications.CHANNEL).setSmallIcon(R.drawable.ic_chat_notice)
            .setContentTitle(roomLabel)
            .setContentText(headsUpText(roomLabel, remaining))
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public)
            .setOnlyAlertOnce(true).setAutoCancel(true)
            .build()
        return try { NotificationManagerCompat.from(context).notify(roomId, ID, notice); true }
        catch (_: SecurityException) { false }
    }
}
