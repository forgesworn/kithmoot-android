package dev.forgesworn.kithmoot.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import dev.forgesworn.kithmoot.R
import dev.forgesworn.kithmoot.session.ChatMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** On by default, previews included, as on any messaging app: the lock screen
 *  still shows only "New message" unless Android is set to show more. */
data class ChatNoticeSettings(val enabled: Boolean = true, val bell: Boolean = true, val previews: Boolean = true)

/** Alerts for the open room's verified live messages. A closed room's come
 *  from the background service through the same [MessageNotices]. */
class ChatNotifications(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutableSettings = MutableStateFlow(load(context))
    val settings = mutableSettings.asStateFlow()
    private var tracker: ChatNoticeState? = null
    private var roomId = ""
    private var roomName = ""
    private var private = false
    private var lastSoundAt = Long.MIN_VALUE
    private var player: MediaPlayer? = null
    @Volatile var foreground = false
    @Volatile var reading = false
    @Volatile var onCall = false
    /** Whether this room's notification offers Reply, asked at each post: a credential's life runs down. */
    @Volatile var replyable: () -> Boolean = { false }
    /**
     * Told what this room has read ([read] true) and what it has alerted, so
     * the background inbox knows it too and a restart after the process dies
     * neither alerts it again nor counts it as unread. Must not block.
     */
    @Volatile var inbox: (read: Boolean, alerted: List<ChatMessage>) -> Unit = { _, _ -> }
    /** Replied from the notification: it shows the reply until something new arrives or the room is read. */
    private var keepReply = false
    private var lastNotice = ""
    private var messages: List<ChatMessage> = emptyList()
    fun allowed() = NotificationManagerCompat.from(context).areNotificationsEnabled()
    @Synchronized fun save(value: ChatNoticeSettings) {
        prefs.edit().putBoolean("enabled", value.enabled).putBoolean("bell", value.bell).putBoolean("previews", value.previews).apply()
        mutableSettings.value = value
        if (!value.enabled) cancel() else { channel(); refresh() }
    }
    @Synchronized fun begin(id: String, name: String, self: String, since: Long, private: Boolean = false) {
        end(); roomId = id; roomName = name; this.private = private; tracker = ChatNoticeState(since, self)
    }
    @Synchronized fun accept(value: List<ChatMessage>) { messages = value; refresh() }
    /** A reply sent from the notification: what it showed is read, and [MessageNotices.replied] shows the reply. */
    @Synchronized fun replied() { tracker?.read() ?: return; keepReply = true; lastNotice = ""; inbox(true, emptyList()) }
    @Synchronized fun refresh() {
        val update = tracker?.update(messages, foreground && reading) ?: return
        if (update.read || update.arrived.isNotEmpty()) inbox(update.read, update.arrived)
        if (keepReply && update.unread.isEmpty() && !(foreground && reading)) return
        keepReply = false
        val settings = settings.value
        if (!settings.enabled || !allowed() || update.unread.isEmpty()) { cancel(); return }
        val now = android.os.SystemClock.elapsedRealtime()
        // On screen already: the count updates, but nothing drops down over the room.
        val sound = update.arrived.isNotEmpty() && settings.bell && !onCall && !foreground && (lastSoundAt == Long.MIN_VALUE || now - lastSoundAt >= 5_000)
        val content = noticeContent(roomName, private, update.unread.map(::noticeLine), settings.previews)
        val noticeKey = "$roomId:${content.unread}:${content.lines.lastOrNull()?.let { it.id + it.body }}"
        if (noticeKey == lastNotice && update.arrived.isEmpty()) return
        if (!MessageNotices.post(context, roomId, content, sound, replyable())) { lastNotice = ""; return }
        if (sound) lastSoundAt = now
        lastNotice = noticeKey
    }
    fun channel() = channel(context)
    fun systemSettings() {
        channel()
        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    fun preview() {
        player?.release()
        player = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            setDataSource(context, soundUri()); setOnCompletionListener { it.release(); if (player === it) player = null }
            runCatching { prepare(); start() }.onFailure { release(); player = null }
        }
    }
    private fun soundUri() = soundUri(context)
    fun cancel() { lastNotice = ""; if (roomId.isNotEmpty()) MessageNotices.cancel(context, roomId) }
    @Synchronized fun end() { cancel(); tracker = null; keepReply = false; replyable = { false }; inbox = { _, _ -> }; messages = emptyList(); roomId = ""; reading = false; lastSoundAt = Long.MIN_VALUE; player?.release(); player = null }
    companion object {
        // A new id because a channel's importance cannot be raised once
        // created, and messages now arrive as heads-up notices.
        const val CHANNEL = "chat_messages_v2"; private const val OLD_CHANNEL = "chat_zen_v1"; const val OPEN = "dev.forgesworn.kithmoot.OPEN_CHAT_NOTICE"; const val ROOM = "notification_room"
        private const val PREFS = "kithmoot.notifications"

        /** The saved settings, for the background service as well as the open room. */
        fun load(context: Context): ChatNoticeSettings {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return ChatNoticeSettings(prefs.getBoolean("enabled", true), prefs.getBoolean("bell", true), prefs.getBoolean("previews", true))
        }

        fun channel(context: Context) {
            val channel = NotificationChannel(CHANNEL, "Chat messages · Zen bell", NotificationManager.IMPORTANCE_HIGH)
            channel.description = "New messages in your rooms. Sound and icon badges follow Android settings."
            channel.setSound(soundUri(context), AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            channel.enableVibration(false); channel.setShowBadge(true)
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.deleteNotificationChannel(OLD_CHANNEL)
            manager.createNotificationChannel(channel)
        }

        private fun soundUri(context: Context) = Uri.parse("android.resource://${context.packageName}/raw/zen_bell")
    }
}
