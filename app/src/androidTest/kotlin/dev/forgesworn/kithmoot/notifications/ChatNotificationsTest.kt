package dev.forgesworn.kithmoot.notifications

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.session.ChatMessage
import org.junit.*
import org.junit.Assert.*

class ChatNotificationsTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var notices: ChatNotifications
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    @Before fun setup() {
        Assume.assumeTrue("Disposable emulator only", Build.HARDWARE in setOf("ranchu", "goldfish"))
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS").close()
        notices = ChatNotifications(context)
        notices.save(ChatNoticeSettings(enabled = true))
        notices.begin("a".repeat(64), "Private room", "self", 100)
    }
    @After fun cleanup() { if (::notices.isInitialized) { notices.end(); notices.save(ChatNoticeSettings()) } }
    private fun message(id: String, at: Long = 100, author: String = "other") = ChatMessage(id, author, "device", "Secret message", at, name = "Alex")
    private fun notice(): Notification {
        repeat(30) { manager.activeNotifications.firstOrNull { it.id == ChatNotifications.ID }?.let { return it.notification }; Thread.sleep(100) }
        throw AssertionError("No chat notification posted")
    }
    @Test fun privateAlertsCountUnreadAndClearWhenReading() {
        notices.accept(listOf(message("old", 99), message("self", author = "self")))
        assertTrue(manager.activeNotifications.isEmpty())
        notices.accept(listOf(message("one")))
        val first = notice()
        assertEquals(1, first.number)
        assertEquals("Alex sent a message", first.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(Notification.VISIBILITY_PRIVATE, first.visibility)
        assertEquals("New messages", first.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertTrue(manager.getNotificationChannel(ChatNotifications.CHANNEL).sound.toString().endsWith("/raw/zen_bell"))
        assertTrue(manager.getNotificationChannel(ChatNotifications.CHANNEL).canShowBadge())
        notices.accept(listOf(message("one"), message("two", 101)))
        repeat(30) { if (notice().number != 2) Thread.sleep(100) }
        assertEquals(2, notice().number)
        notices.foreground = true; notices.reading = true; notices.refresh()
        repeat(30) { if (manager.activeNotifications.isNotEmpty()) Thread.sleep(100) }
        assertTrue(manager.activeNotifications.isEmpty())
    }
    @Test fun callAlertsAreSilentAndPreviewsRequireOptIn() {
        notices.onCall = true
        notices.save(ChatNoticeSettings(enabled = true, previews = true))
        notices.accept(listOf(message("call")))
        val alert = notice()
        assertEquals("Alex: Secret message", alert.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertNull(alert.sound)
        assertEquals(Notification.GROUP_ALERT_SUMMARY, alert.groupAlertBehavior)
        notices.save(ChatNoticeSettings(enabled = false))
        repeat(30) { if (manager.activeNotifications.isNotEmpty()) Thread.sleep(100) }
        assertTrue(manager.activeNotifications.isEmpty())
    }
}
