package dev.forgesworn.kithmoot.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.R
import dev.forgesworn.kithmoot.session.ChatMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ChatNoticeSettings(val enabled: Boolean = false, val bell: Boolean = true, val previews: Boolean = false)

/** Alerts only for the joined room's verified live messages; no hidden relay connections. */
class ChatNotifications(private val context: Context) {
    private val prefs = context.getSharedPreferences("kithmoot.notifications", Context.MODE_PRIVATE)
    private val mutableSettings = MutableStateFlow(ChatNoticeSettings(prefs.getBoolean("enabled", false), prefs.getBoolean("bell", true), prefs.getBoolean("previews", false)))
    val settings = mutableSettings.asStateFlow()
    private var tracker: ChatNoticeState? = null
    private var roomId = ""
    private var roomName = ""
    private var lastSoundAt = Long.MIN_VALUE
    private var player: MediaPlayer? = null
    @Volatile var foreground = false
    @Volatile var reading = false
    @Volatile var onCall = false
    private var lastNotice = ""
    private var messages: List<ChatMessage> = emptyList()
    fun allowed() = NotificationManagerCompat.from(context).areNotificationsEnabled()
    @Synchronized fun save(value: ChatNoticeSettings) {
        prefs.edit().putBoolean("enabled", value.enabled).putBoolean("bell", value.bell).putBoolean("previews", value.previews).apply()
        mutableSettings.value = value
        if (!value.enabled) cancel() else { channel(); refresh() }
    }
    @Synchronized fun begin(id: String, name: String, self: String, since: Long) {
        end(); roomId = id; roomName = name; tracker = ChatNoticeState(since, self)
    }
    @Synchronized fun accept(value: List<ChatMessage>) { messages = value; refresh() }
    @Synchronized fun refresh() {
        val update = tracker?.update(messages, foreground && reading) ?: return
        val settings = settings.value
        if (!settings.enabled || !allowed() || update.unread.isEmpty()) { cancel(); return }
        channel()
        val newest = update.unread.last()
        val now = android.os.SystemClock.elapsedRealtime()
        val sound = update.arrived.isNotEmpty() && settings.bell && !onCall && (lastSoundAt == Long.MIN_VALUE || now - lastSoundAt >= 5_000)
        if (sound) lastSoundAt = now
        val sender = newest.name?.takeIf { it.isNotBlank() }?.take(80) ?: newest.participant.take(12)
        val body = if (settings.previews) "$sender: ${newest.body.take(300)}" else "$sender sent a message"
        val noticeKey = "$roomId:${update.unread.size}:$body"
        if (noticeKey == lastNotice && update.arrived.isEmpty()) return
        lastNotice = noticeKey
        val intent = Intent(context, MainActivity::class.java).setAction(OPEN).putExtra(ROOM, roomId)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(context, ID, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val public = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_chat_notice)
            .setContentTitle("KithMoot").setContentText("New messages").build()
        val notice = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_chat_notice)
            .setContentTitle(roomName.ifBlank { "KithMoot" }.take(120)).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body)).setContentIntent(pending)
            .setNumber(update.unread.size).setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public)
            .setSilent(!sound).setAutoCancel(true).setOnlyAlertOnce(!sound).build()
        try { NotificationManagerCompat.from(context).notify(ID, notice) } catch (_: SecurityException) { lastNotice = "" }
    }
    fun channel() {
        val channel = NotificationChannel(CHANNEL, "Chat messages · Zen bell", NotificationManager.IMPORTANCE_DEFAULT)
        channel.description = "Messages received in your joined room. Sound and icon badges follow Android settings."
        channel.setSound(soundUri(), AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        channel.enableVibration(false); channel.setShowBadge(true)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
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
    private fun soundUri() = Uri.parse("android.resource://${context.packageName}/raw/zen_bell")
    fun cancel() { lastNotice = ""; NotificationManagerCompat.from(context).cancel(ID) }
    @Synchronized fun end() { cancel(); tracker = null; messages = emptyList(); roomId = ""; reading = false; lastSoundAt = Long.MIN_VALUE; player?.release(); player = null }
    companion object { const val CHANNEL = "chat_zen_v1"; const val ID = 4602; const val OPEN = "dev.forgesworn.kithmoot.OPEN_CHAT_NOTICE"; const val ROOM = "notification_room" }
}
