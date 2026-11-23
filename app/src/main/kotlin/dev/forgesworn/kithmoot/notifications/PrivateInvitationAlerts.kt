package dev.forgesworn.kithmoot.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.R
import dev.forgesworn.kithmoot.account.ContactInvitation

internal class PrivateInvitationAlerts(private val context: Context) {
    private val preferences = context.getSharedPreferences("kithmoot.privateInvitationAlerts", Context.MODE_PRIVATE)

    fun enabledSince(account: String): Long = preferences.getLong(account, 0)

    fun setEnabled(account: String, enabled: Boolean, now: Long = System.currentTimeMillis() / 1000) {
        require(INVITATION_ID.matches(account))
        val change = preferences.edit()
        if (enabled) change.putLong(account, now) else change.remove(account)
        change.apply()
        if (!enabled) cancelAll()
    }

    fun post(account: String, invite: ContactInvitation) {
        val allowedSince = enabledSince(account)
        if (!isNewPrivateInvitation(account, invite, allowedSince)) return
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled() || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Private invitations", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = Intent(context, MainActivity::class.java).setAction(OPEN)
            .setData(Uri.parse("kithmoot-notice://invitation/${invite.id}"))
            .putExtra(REQUEST, invite.id)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val open = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val alert = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_chat_notice)
            .setContentTitle("KithMoot").setContentText("New private conversation invitation")
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setShowWhen(false)
            .setOnlyAlertOnce(true).setAutoCancel(true).setContentIntent(open).build()
        // Permission can change between the check and the post.
        runCatching { manager.notify(invite.id, NOTIFICATION_ID, alert) }
    }

    fun cancelAll() {
        val manager = context.getSystemService(NotificationManager::class.java)
        runCatching { manager.activeNotifications.filter { it.id == NOTIFICATION_ID }.forEach { manager.cancel(it.tag, it.id) } }
    }

    companion object {
        private const val CHANNEL = "private-invitations"
        private const val NOTIFICATION_ID = 4613
        private const val REQUEST = "private_invitation"
        const val OPEN = "dev.forgesworn.kithmoot.REVIEW_PRIVATE_INVITATION"
        private val INVITATION_ID = Regex("[0-9a-f]{64}")

        fun requestFrom(intent: Intent): String? = intent.getStringExtra(REQUEST)?.takeIf(INVITATION_ID::matches)
    }
}

internal fun isNewPrivateInvitation(account: String, invite: ContactInvitation, enabledSince: Long): Boolean =
    enabledSince > 0 && invite.recipient == account && invite.sender != account && invite.sentAt >= enabledSince
