package dev.forgesworn.kithmoot.notifications

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
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
    const val REPLY = "dev.forgesworn.kithmoot.REPLY_CHAT_NOTICE"
    const val REPLY_TEXT = "notification_reply"
    /** The single untagged notification earlier versions posted for every room. */
    private const val LEGACY_ID = 4602

    /** Posts a room's notification. [replyable] adds Reply: see [canReplyFromNotice]. */
    fun post(context: Context, roomId: String, content: NoticeContent, sound: Boolean, replyable: Boolean = false): Boolean {
        if (content.lines.isEmpty()) { cancel(context, roomId); return false }
        val style = NotificationCompat.MessagingStyle(self())
        content.title?.let { style.setConversationTitle(it).setGroupConversation(true) }
        for (line in content.lines) {
            style.addMessage(line.body, line.sentAt * 1000, Person.Builder().setName(line.sender).setKey(line.senderKey).build())
        }
        return notify(context, roomId, notice(context, roomId, style, content.unread, content.lines.last().sentAt * 1000, sound, replyable))
    }

    /**
     * Posts a room's notification again after Reply, silently, with [reply]
     * at the end as a message from "You", or unchanged when it is null.
     * Android keeps a spinner on the notification until it is posted again,
     * so this runs whatever happened to the reply. Built from what the
     * notification already shows, so it works the same for the open room's
     * notices and the background service's. Replying reads the room, so the
     * count goes.
     */
    fun replied(context: Context, roomId: String, reply: String?) {
        val shown = runCatching {
            context.getSystemService(NotificationManager::class.java).activeNotifications
                .firstOrNull { it.tag == roomId && it.id == ID }?.notification
        }.getOrNull()
        val style = shown?.let { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(it) }
            ?: NotificationCompat.MessagingStyle(self())
        val at = System.currentTimeMillis()
        if (reply != null) style.addMessage(reply, at, null as Person?)
        // Dismissed while the reply was on its way, and nothing to say: leave it gone.
        if (style.messages.isEmpty()) return
        val unread = if (reply == null) shown?.number ?: 0 else 0
        notify(context, roomId, notice(context, roomId, style, unread, style.messages.last().timestamp, sound = false, replyable = true))
    }

    private fun self() = Person.Builder().setName("You").build()

    private fun notice(context: Context, roomId: String, style: NotificationCompat.MessagingStyle, unread: Int,
        whenMs: Long, sound: Boolean, replyable: Boolean): Notification {
        ChatNotifications.channel(context)
        val intent = Intent(context, MainActivity::class.java).setAction(ChatNotifications.OPEN)
            .setData(Uri.parse("kithmoot-notice://room/$roomId"))
            .putExtra(ChatNotifications.ROOM, roomId)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val open = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val public = NotificationCompat.Builder(context, ChatNotifications.CHANNEL).setSmallIcon(R.drawable.ic_chat_notice)
            .setContentTitle("KithMoot").setContentText("New message").build()
        val builder = NotificationCompat.Builder(context, ChatNotifications.CHANNEL).setSmallIcon(R.drawable.ic_chat_notice)
            .setStyle(style).setContentIntent(open).setNumber(unread)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public)
            .setWhen(whenMs).setShowWhen(true)
            .setSilent(!sound).setOnlyAlertOnce(!sound).setAutoCancel(true)
        if (replyable) builder.addAction(replyAction(context, roomId))
        return builder.build()
    }

    /**
     * Reply, answered by [NoticeReplyReceiver]. Mutable, as RemoteInput needs,
     * which is safe because the intent names its receiver explicitly. Its
     * data carries the room, so two rooms' replies never share one
     * PendingIntent. Needs the phone unlocked: a locked phone shows "New
     * message" and nothing more, and must not be able to speak in a room.
     */
    private fun replyAction(context: Context, roomId: String): NotificationCompat.Action {
        val intent = Intent(context, NoticeReplyReceiver::class.java).setAction(REPLY)
            .setData(Uri.parse("kithmoot-notice://reply/$roomId"))
            .putExtra(ChatNotifications.ROOM, roomId)
            // Prompt delivery, with the spinner showing; the reply's own budget fits a foreground receiver's.
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        val reply = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        val input = RemoteInput.Builder(REPLY_TEXT).setLabel("Reply").build()
        return NotificationCompat.Action.Builder(R.drawable.ic_chat_notice, "Reply", reply)
            .addRemoteInput(input)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .setAllowGeneratedReplies(false)
            .setAuthenticationRequired(true)
            .build()
    }

    private fun notify(context: Context, roomId: String, notice: Notification): Boolean = try {
        NotificationManagerCompat.from(context).notify(roomId, ID, notice); true
    } catch (_: SecurityException) { false }

    fun cancel(context: Context, roomId: String) {
        val manager = NotificationManagerCompat.from(context)
        manager.cancel(roomId, ID)
        manager.cancel(LEGACY_ID)
    }
}

const val MAX_NOTICE_LINES = 6
const val MAX_NOTICE_BODY = 300
