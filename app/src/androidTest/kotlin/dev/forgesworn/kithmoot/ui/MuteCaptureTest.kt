package dev.forgesworn.kithmoot.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.account.npubOf
import dev.forgesworn.kithmoot.notifications.ChatNotifications
import dev.forgesworn.kithmoot.notifications.NotificationSettings
import dev.forgesworn.kithmoot.notifications.RoomMute
import dev.forgesworn.kithmoot.notifications.RoomNotifyMode
import dev.forgesworn.kithmoot.session.ChatMessage
import dev.forgesworn.kithmoot.storage.SavedRoomSummary
import dev.forgesworn.kithmoot.ui.room.ChatPane
import dev.forgesworn.kithmoot.ui.room.ParticipantTile
import dev.forgesworn.kithmoot.ui.room.RoomNotifyActions
import dev.forgesworn.kithmoot.ui.room.RoomScreen
import dev.forgesworn.kithmoot.ui.start.Destination
import dev.forgesworn.kithmoot.ui.start.ShellNavigationBar
import dev.forgesworn.kithmoot.ui.start.StartScreen
import dev.forgesworn.kithmoot.ui.start.shellBadges
import dev.forgesworn.kithmoot.ui.theme.KithMootTheme
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Screenshots of calm shell stage 7 on Android for review (web #496): muted
 * rows in Chats, a row's and the room's Notifications, the sheet in its
 * three states, a muted room's header, and the default in Settings. Seeded
 * data, no network, the sheet's choices kept in memory; saved under
 * `files/mute/` with the theme and font scale in each name.
 */
class MuteCaptureTest {
    @get:Rule val ui = createEmptyComposeRule()

    private val me = "03".repeat(32)
    private val priya = "a2".repeat(32)
    private val rowan = "b4".repeat(32)
    private val now = System.currentTimeMillis() / 1000

    private val release = "1a".repeat(32)
    private val priyaChat = "2b".repeat(32)
    private val townHall = "3c".repeat(32)
    private val moot = "4d".repeat(32)

    private val account = AccountView(me, npubOf(me), "npub1ada…", "local", name = "Ada")
    private val rooms = listOf(
        SavedRoomSummary(release, "Release workshop", false, now - 60, account = me, canShareInvite = true),
        SavedRoomSummary(priyaChat, "Private with Priya Natarajan", false, now - 30, account = me, privatePeer = priya, canShareInvite = true),
        SavedRoomSummary(townHall, "Town hall", false, now - 600, account = me, canShareInvite = true),
        SavedRoomSummary(moot, "The moot", false, now - 9000, account = me, canShareInvite = true),
    )
    private val townHallMute = RoomMute.Until(now + 2 * 3600)
    private val chats = StartState(loadingRooms = false, account = account, publicProfiles = true, savedRooms = rooms,
        latestMessageTimes = mapOf(release to now - 60, priyaChat to now - 30, townHall to now - 600, moot to now - 9000),
        unreadCounts = mapOf(release to 3, priyaChat to 1, townHall to 12, moot to 2),
        mutedRooms = mapOf(townHall to townHallMute, moot to RoomMute.Always))

    private val modes = mutableMapOf(townHall to RoomNotifyMode.MENTIONS, moot to RoomNotifyMode.NOTHING)
    private val notify = RoomNotifyActions({ modes[it] ?: RoomNotifyMode.DEFAULT }, { id, mode -> modes[id] = mode }, { _, _ -> }, {})

    private fun here(who: String, name: String) = ParticipantTile(who, false, 1, emptyList(), null, false, name = name)
    private val room = RoomState(roomId = townHall, selfParticipant = me, name = "Town hall", relaysUp = 3, relaysTotal = 3,
        joinUrl = "https://example.org/j/#townhall", tiles = listOf(here(me, "Ada"), here(priya, "Priya Natarajan"), here(rowan, "Rowan")),
        mute = townHallMute)
    private val conversation = listOf(
        ChatMessage("m1", rowan, rowan, "Agenda for Thursday is in the doc.", now - 900, name = "Rowan"),
        ChatMessage("m2", priya, priya, "I'll bring the projector.", now - 600, name = "Priya Natarajan"),
    )

    @Composable
    private fun Phone(state: StartState, content: @Composable () -> Unit) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().consumeWindowInsets(WindowInsets.navigationBars)) { content() }
            ShellNavigationBar(Destination.entries, Destination.CHATS, {}, shellBadges(state))
        }
    }

    @Composable
    private fun Chats(state: StartState) = StartScreen(
        state = state, onRoomNameChanged = {}, onJoinUrlChanged = {}, onRelaysChanged = {}, onAnonymousModeChanged = {},
        onPersistentGroupChanged = {}, onStartRoom = {}, onJoin = {}, onReopen = {}, onForget = {}, onProject = { _, _ -> },
        onRetryStorage = {}, onResetStorage = {}, onMessageContact = {}, roomNotify = notify)

    @Test fun capture_mutes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val out = File(context.getExternalFilesDir(null), "mute").apply { mkdirs() }
        val font = "%.1f".format(context.resources.configuration.fontScale)
        fun shoot(name: String, theme: String) {
            ui.waitForIdle()
            instrumentation.uiAutomation.waitForIdle(500, 5_000)
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            File(out, "$name-$theme-font$font.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var theme = "light"
            fun show(content: @Composable () -> Unit) = scenario.onActivity { activity -> activity.setContent {
                val dark = isSystemInDarkTheme()
                theme = if (dark) "dark" else "light"
                KithMootTheme(darkTheme = dark) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
                }
            } }

            // Chats: Town hall muted for two hours and The moot set to Nothing, both with
            // their counts in grey and out of the Chats count, which is 2.
            show { Phone(chats) { Chats(chats) } }
            ui.onNodeWithText("Town hall").assertExists()
            shoot("chats-muted", theme)

            // A row's long press offers Notifications.
            ui.onNodeWithText("Town hall").performTouchInput { longClick() }
            ui.onNodeWithText("Notifications").assertExists()
            shoot("row-menu", theme)

            // A timed mute running: Unmute in place of the lengths.
            ui.onNodeWithText("Notifications").performClick()
            ui.onNodeWithText("Unmute").assertExists()
            ui.onNodeWithText("Only mentions and replies").assertExists()
            shoot("sheet-muted", theme)

            // Nothing: already silent until changed, so no lengths.
            show { Phone(chats) { Chats(chats) } }
            ui.onNodeWithText("The moot").performTouchInput { longClick() }
            ui.onNodeWithText("Notifications").performClick()
            ui.onNodeWithText("Muted").assertExists()
            check(ui.onAllNodesWithText("Mute for 1 hour").fetchSemanticsNodes().isEmpty())
            shoot("sheet-nothing", theme)

            // Use my default, not muted: the four lengths.
            show { Phone(chats) { Chats(chats) } }
            ui.onNodeWithText("Release workshop").performTouchInput { longClick() }
            ui.onNodeWithText("Notifications").performClick()
            ui.onNodeWithText("Mute for 1 week").assertExists()
            shoot("sheet-default", theme)

            // A muted room's header, and its ⋮ menu with the bell-off glyph.
            show {
                RoomScreen(room, emptyMap(), null, {}, {}, {}, {}, {}, {}, {}, {}, modifier = Modifier.fillMaxSize(),
                    pinned = false, onAddToProject = {}, notify = notify,
                    chat = { ChatPane(conversation, me, { _, _ -> }, Modifier.fillMaxSize(), showTitle = false) })
            }
            ui.onAllNodesWithText("Muted until", substring = true).fetchSemanticsNodes().let { check(it.isNotEmpty()) }
            shoot("room-muted", theme)
            ui.onNodeWithContentDescription("More for this room").performClick()
            ui.onNodeWithText("Notifications").assertExists()
            shoot("room-menu", theme)

            // Settings › Notifications: the default for rooms left on Use my default.
            ChatNotifications(context).save(ChatNotifications.load(context).copy(enabled = true, level = RoomNotifyMode.MENTIONS))
            show { Column(Modifier.statusBarsPadding().verticalScroll(rememberScrollState())) { NotificationSettings(ChatNotifications(context)) } }
            ui.onNodeWithText("In your rooms, tell me about").assertExists()
            shoot("settings-default", theme)
            ChatNotifications(context).save(ChatNotifications.load(context).copy(level = RoomNotifyMode.ALL))
        }
    }
}
