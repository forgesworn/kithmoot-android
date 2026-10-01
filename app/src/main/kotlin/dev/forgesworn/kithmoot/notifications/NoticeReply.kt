package dev.forgesworn.kithmoot.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import dev.forgesworn.kithmoot.service.sendReplyInBackground
import dev.forgesworn.kithmoot.session.MAX_CHAT_TEXT_LENGTH
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Reply on a message notification, typed and sent without opening KithMoot.
 *
 * Offered only where a message can be signed and sent with nobody to ask: a
 * reply is signed by this device's own key under a credential that already
 * exists, sealed to the room's current epoch, and published to the room's own
 * relays. Where that cannot be done the notification has no Reply, and
 * tapping it opens the chat as before.
 */

/** How a room joined as an account stands for a reply nobody can approve. */
enum class ReplyAccount {
    /** Not an account room: the key, or the pairing, is held here. */
    NONE,
    /** The signed-in account is the one this room was joined as, through a signer app or a key held here. */
    SIGNED_IN,
    /** A bunker: excluded, as the background service excludes it (see [dev.forgesworn.kithmoot.service.DeliveryExclusion.BUNKER]). */
    BUNKER,
    /** Signed out, or signed in as someone else: the room would not open either. */
    SIGNED_OUT,
}

/** What the decision needs to know about one saved room, read without Android. */
data class ReplyRoom(
    val anonymous: Boolean,
    val quiet: Boolean,
    val ended: Boolean,
    val hasEpoch: Boolean,
    /** The room admits only with a Kindred proof, which this build cannot obtain. */
    val needsProof: Boolean,
    val account: ReplyAccount,
    /** When the credential a reply would carry stops working; null when there is none to carry. */
    val credentialExpiresAt: Long?,
)

/** A credential this close to its end is not offered: the reply could be refused on its way out. */
const val REPLY_CREDENTIAL_MARGIN_SECONDS: Long = 5 * 60

/**
 * Whether a room's notification offers Reply. Anonymous rooms use Tor only,
 * quiet rooms send on their own schedule through their box, an ended room or
 * one without a current epoch has nowhere to send, and a bunker or a missing
 * account would need someone to approve a signature.
 */
fun canReplyFromNotice(room: ReplyRoom, now: Long): Boolean = when {
    room.anonymous || room.quiet || room.ended || !room.hasEpoch || room.needsProof -> false
    room.account == ReplyAccount.BUNKER || room.account == ReplyAccount.SIGNED_OUT -> false
    else -> room.credentialExpiresAt?.let { it - now >= REPLY_CREDENTIAL_MARGIN_SECONDS } == true
}

/** The text to send: trimmed, capped as the composer caps it, or null when there is nothing to send. */
fun noticeReplyText(raw: CharSequence?): String? =
    raw?.toString()?.trim()?.take(MAX_CHAT_TEXT_LENGTH)?.trimEnd()?.takeIf { it.isNotEmpty() }

enum class ReplyOutcome {
    /** A relay confirmed it. */
    SENT,
    /** Signed and kept, encrypted, on this phone; sent when the room or the background service next reaches a relay. */
    KEPT,
    /** Nothing was kept: the typed text is shown on the notification so it is not lost. */
    FAILED,
}

/**
 * The line a notification shows for this device's reply. With previews off
 * a sent or kept reply is not repeated on the notification; one that failed
 * always is, because nothing else holds it.
 */
fun replyNoticeBody(text: String, outcome: ReplyOutcome, previews: Boolean): String = when (outcome) {
    ReplyOutcome.SENT -> if (previews) text.take(MAX_NOTICE_BODY) else "Reply sent"
    ReplyOutcome.KEPT -> (if (previews) text.take(MAX_NOTICE_BODY) + "\n" else "") + "Not sent yet. Kept on this phone: open KithMoot to retry."
    ReplyOutcome.FAILED -> "$text\nNot sent. Open KithMoot to retry."
}

/**
 * Open rooms' send paths, by room id, so a reply to a room open in this
 * process goes through its session rather than a second connection. Set
 * beside [ActiveRoomRegistry]; a room marked open whose sender is already
 * gone falls back to the background path.
 */
object OpenRoomReplies {
    private val senders = ConcurrentHashMap<String, suspend (String) -> ReplyOutcome>()

    fun register(roomId: String, sender: suspend (String) -> ReplyOutcome) { if (roomId.isNotEmpty()) senders[roomId] = sender }

    /** Removes only this sender, so a room closed and reopened at once keeps the new one. */
    fun unregister(roomId: String, sender: suspend (String) -> ReplyOutcome) { senders.remove(roomId, sender) }

    fun sender(roomId: String): (suspend (String) -> ReplyOutcome)? = senders[roomId]
}

/**
 * Reply on a message notification. Not exported: only this app's own
 * PendingIntent reaches it. Android shows a spinner on the notification until
 * it is posted again, so it is re-posted whatever happens, with the reply as
 * a message from "You" or with what went wrong.
 */
class NoticeReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != MessageNotices.REPLY) return
        val roomId = intent.getStringExtra(ChatNotifications.ROOM)?.takeIf { it.isNotEmpty() } ?: return
        val text = noticeReplyText(RemoteInput.getResultsFromIntent(intent)?.getCharSequence(MessageNotices.REPLY_TEXT))
        val app = context.applicationContext
        val result = goAsync()
        scope.launch {
            try {
                if (text == null) { MessageNotices.replied(app, roomId, null); return@launch }
                val open = if (ActiveRoomRegistry.isOpen(roomId)) OpenRoomReplies.sender(roomId) else null
                val outcome = try { open?.invoke(text) ?: sendReplyInBackground(app, roomId, text) }
                    catch (_: Exception) { ReplyOutcome.FAILED }
                MessageNotices.replied(app, roomId, replyNoticeBody(text, outcome, ChatNotifications.load(app).previews))
            } finally { result.finish() }
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
