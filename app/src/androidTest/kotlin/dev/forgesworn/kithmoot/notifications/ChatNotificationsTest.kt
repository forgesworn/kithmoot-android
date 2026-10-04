package dev.forgesworn.kithmoot.notifications

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.session.ChatMessage
import dev.forgesworn.kithmoot.session.SENDER_CLOCK_ALLOWANCE_SECONDS
import org.junit.*
import org.junit.Assert.*

class ChatNotificationsTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var notices: ChatNotifications
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val room = "a".repeat(64)
    @Before fun setup() {
        Assume.assumeTrue("Disposable emulator only", Build.HARDWARE in setOf("ranchu", "goldfish"))
        val grant = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        // Closing the pipe immediately can terminate pm before permission is granted.
        ParcelFileDescriptor.AutoCloseInputStream(grant).use { it.readBytes() }
        assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS))
        notices = ChatNotifications(context)
        notices.save(ChatNoticeSettings(enabled = true, previews = false))
        notices.begin(room, "Private room", "self", 100)
    }
    @After fun cleanup() { if (::notices.isInitialized) { notices.end(); notices.save(ChatNoticeSettings()) } }
    private fun message(id: String, at: Long = 100, author: String = "other") = ChatMessage(id, author, "device", "Secret message", at, name = "Alex")
    private fun notice(): Notification {
        repeat(30) { manager.activeNotifications.firstOrNull { it.id == MessageNotices.ID && it.tag == room }?.let { return it.notification }; Thread.sleep(100) }
        throw AssertionError("No chat notification posted")
    }
    private fun lines(notification: Notification) =
        NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)!!.messages
            .map { "${it.person?.name}: ${it.text}" }
    @Test fun privateAlertsCountUnreadAndClearWhenReading() {
        // History: stamped before the open, beyond the sender clock allowance.
        notices.accept(listOf(message("old", 100 - SENDER_CLOCK_ALLOWANCE_SECONDS - 1), message("self", author = "self")))
        assertTrue(manager.activeNotifications.isEmpty())
        notices.accept(listOf(message("one")))
        val first = notice()
        assertEquals(1, first.number)
        assertEquals(listOf("Alex: New message"), lines(first))
        assertEquals(Notification.VISIBILITY_PRIVATE, first.visibility)
        assertEquals("New message", first.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertTrue(manager.getNotificationChannel(ChatNotifications.CHANNEL).sound.toString().endsWith("/raw/zen_bell"))
        assertTrue(manager.getNotificationChannel(ChatNotifications.CHANNEL).canShowBadge())
        notices.accept(listOf(message("one"), message("two", 101)))
        repeat(30) { if (notice().number != 2) Thread.sleep(100) }
        assertEquals(2, notice().number)
        notices.foreground = true; notices.reading = true; notices.refresh()
        repeat(30) { if (manager.activeNotifications.isNotEmpty()) Thread.sleep(100) }
        assertTrue(manager.activeNotifications.isEmpty())
    }
    @Test fun callAlertsAreSilentAndPreviewsAreOnByDefault() {
        notices.onCall = true
        notices.save(ChatNoticeSettings())
        notices.accept(listOf(message("call")))
        val alert = notice()
        assertEquals(listOf("Alex: Secret message"), lines(alert))
        assertEquals("New message", alert.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertNull(alert.sound)
        assertEquals(Notification.GROUP_ALERT_SUMMARY, alert.groupAlertBehavior)
        notices.save(ChatNoticeSettings(enabled = false))
        repeat(30) { if (manager.activeNotifications.isNotEmpty()) Thread.sleep(100) }
        assertTrue(manager.activeNotifications.isEmpty())
    }
}
