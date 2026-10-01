package dev.forgesworn.kithmoot.notifications

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.R
import dev.forgesworn.kithmoot.session.ChatMessage

/** One message as a notification shows it. Held in memory only, never stored. */
data class NoticeLine(val id: String, val senderKey: String, val sender: String, val body: String, val sentAt: Long)

/** What one room's notification says, decided without Android. */
data class NoticeContent(
    /** The conversation's name; null in a two-person conversation, where the sender's name is the title. */
    val title: String?,
    val lines: List<NoticeLine>,
    val unread: Int,
)

/** How a sender is named on a notification: their name, else the start of their key. */
fun noticeSender(message: ChatMessage): String =
    message.name?.takeIf { it.isNotBlank() }?.take(80) ?: message.participant.take(12)

fun noticeLine(message: ChatMessage): NoticeLine =
    NoticeLine(message.id, message.participant, noticeSender(message), message.body.take(MAX_NOTICE_BODY), message.sentAt)

/**
 * A room's notification: the latest few unread messages, oldest first. With
 * previews off each line only says that a message came, so the text never
 * leaves KithMoot. A two-person conversation is titled by whoever wrote, the
 * way a phone's messages are; any other room by its name.
 */
fun noticeContent(roomName: String, private: Boolean, unread: List<NoticeLine>, previews: Boolean): NoticeContent {
    val lines = unread.sortedBy { it.sentAt }.takeLast(MAX_NOTICE_LINES)
        .map { if (previews) it else it.copy(body = "New message") }
    return NoticeContent(if (private) null else roomName.ifBlank { "KithMoot" }.take(120), lines, unread.size)
}

/**
 * Posts and clears message notifications, one per room, for both the open
 * room ([ChatNotifications]) and the background service. Each room's
 * notification is tagged with its id, so rooms never replace each other, and
 * its tap intent carries the id in its data, so two rooms' taps never share
 * one PendingIntent. The lock screen sees only "New message" unless Android
 * is set to show private content.
 */
object MessageNotices {
    const val ID = 4610
    /** The single untagged notification earlier versions posted for every room. */
    private const val LEGACY_ID = 4602

    fun post(context: Context, roomId: String, content: NoticeContent, sound: Boolean): Boolean {
        if (content.lines.isEmpty()) { cancel(context, roomId); return false }
        ChatNotifications.channel(context)
        val intent = Intent(context, MainActivity::class.java).setAction(ChatNotifications.OPEN)
            .setData(Uri.parse("kithmoot-notice://room/$roomId"))
            .putExtra(ChatNotifications.ROOM, roomId)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val open = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("You").build())
        content.title?.let { style.setConversationTitle(it).setGroupConversation(true) }
        for (line in content.lines) {
            style.addMessage(line.body, line.sentAt * 1000, Person.Builder().setName(line.sender).setKey(line.senderKey).build())
        }
        val public = NotificationCompat.Builder(context, ChatNotifications.CHANNEL).setSmallIcon(R.drawable.ic_chat_notice)
            .setContentTitle("KithMoot").setContentText("New message").build()
        val notice = NotificationCompat.Builder(context, ChatNotifications.CHANNEL).setSmallIcon(R.drawable.ic_chat_notice)
            .setStyle(style).setContentIntent(open).setNumber(content.unread)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public)
            .setWhen(content.lines.last().sentAt * 1000).setShowWhen(true)
            .setSilent(!sound).setOnlyAlertOnce(!sound).setAutoCancel(true).build()
        return try {
            NotificationManagerCompat.from(context).notify(roomId, ID, notice); true
        } catch (_: SecurityException) { false }
    }

    fun cancel(context: Context, roomId: String) {
        val manager = NotificationManagerCompat.from(context)
        manager.cancel(roomId, ID)
        manager.cancel(LEGACY_ID)
    }
}

const val MAX_NOTICE_LINES = 6
const val MAX_NOTICE_BODY = 300
