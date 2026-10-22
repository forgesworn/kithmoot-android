package dev.forgesworn.kithmoot.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.onFirst
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.notifications.RoomNotifyMode
import dev.forgesworn.kithmoot.session.ChatMessage
import dev.forgesworn.kithmoot.session.MessageRef
import dev.forgesworn.kithmoot.session.refOf
import dev.forgesworn.kithmoot.ui.room.ChatPane
import dev.forgesworn.kithmoot.ui.room.ParticipantTile
import dev.forgesworn.kithmoot.ui.room.RoomNotifyActions
import dev.forgesworn.kithmoot.ui.room.RoomScreen
import dev.forgesworn.kithmoot.ui.theme.KithMootTheme
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Screenshots of reply quotes on Android for review (web #506): replies in
 * time order with a quote, the three states of a quote, Reply in a message's
 * dialog, the composer while replying, and a followed quote. Seeded data, no
 * network, sends kept in memory; saved under `files/replies/` with the theme
 * and font scale in each name.
 */
class ReplyCaptureTest {
    @get:Rule val ui = createEmptyComposeRule()

    private val me = "03".repeat(32)
    private val priya = "a2".repeat(32)
    private val rowan = "b4".repeat(32)
    private val now = System.currentTimeMillis() / 1000
    private val roomId = "3c".repeat(32)

    private fun say(id: String, who: String, name: String, body: String, ago: Long, reply: ChatMessage? = null) =
        ChatMessage(id, who, who, body, now - ago, name = name,
            reply = reply?.let(::refOf), thread = reply?.let { it.thread ?: it.reply ?: refOf(it) })

    private val agenda = say("m1", rowan, "Rowan", "Agenda for Thursday is in the doc. Read the budget section first.", 3600)
    private val projector = say("m2", priya, "Priya Natarajan", "I'll bring the projector.", 3300)
    private val wrong = say("m3", priya, "Priya Natarajan", "Wrong room, sorry", 3000)
    private val filler = (1..8).map { say("f$it", if (it % 2 == 0) rowan else priya, if (it % 2 == 0) "Rowan" else "Priya Natarajan",
        listOf("Room 4 is booked", "Coffee at 9?", "Yes please", "I'll print copies", "Two each", "Thanks", "Parking is round the back", "Noted")[it - 1], 2800L - it * 120) }
    private val mine = say("m4", me, "Ada", "Thanks Rowan, I'll read it tonight.", 1500, reply = agenda)
    private val theirs = say("m5", rowan, "Rowan", "Shout if anything's unclear.", 1200, reply = mine)
    private val toDeleted = say("m6", rowan, "Rowan", "No harm done", 900, reply = wrong)
    private val toMissing = ChatMessage("m7", rowan, rowan, "Still true for this week", now - 600, name = "Rowan",
        reply = MessageRef("old1", priya), thread = MessageRef("old1", priya))
    private val retraction = ChatMessage("x1", priya, priya, "Retracted a message", now - 2900, retracts = "m3")
    private val log = listOf(agenda, projector, wrong, retraction) + filler + listOf(mine, theirs, toDeleted, toMissing)

    private val notify = RoomNotifyActions({ RoomNotifyMode.DEFAULT }, { _, _ -> }, { _, _ -> }, {})
    private fun here(who: String, name: String) = ParticipantTile(who, false, 1, emptyList(), null, false, name = name)
    private val room = RoomState(roomId = roomId, selfParticipant = me, name = "Town hall", relaysUp = 3, relaysTotal = 3,
        joinUrl = "https://example.org/j/#townhall", tiles = listOf(here(me, "Ada"), here(priya, "Priya Natarajan"), here(rowan, "Rowan")))

    @Composable
    private fun Room() = RoomScreen(room, emptyMap(), null, {}, {}, {}, {}, {}, {}, {}, {}, modifier = Modifier.fillMaxSize(),
        pinned = false, onAddToProject = {}, notify = notify,
        chat = { ChatPane(log, me, { _, _ -> }, Modifier.fillMaxSize(), showTitle = false, onReply = { _, _, _ -> }) })

    @Test fun capture_replies() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val out = File(context.getExternalFilesDir(null), "replies").apply { mkdirs() }
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

            // The latest replies: one quoting Ada as You, one a deleted message, one not loaded.
            show { Room() }
            ui.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToIndex(14)
            shoot("chat-replies", theme)
            ui.onAllNodesWithText("Message deleted").fetchSemanticsNodes().let { check(it.isNotEmpty()) { "no deleted quote" } }
            ui.onAllNodesWithText("Original message not loaded").fetchSemanticsNodes().let { check(it.isNotEmpty()) { "no missing quote" } }

            // A little further up: Ada's reply quoting Rowan's agenda.
            ui.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToIndex(10)
            ui.onNodeWithContentDescription("Go to Rowan's message", substring = true).assertExists()
            shoot("chat-quote", theme)

            // Following the quote: the agenda, marked, and the way back to the latest.
            // The clock is held so the two-second mark is still on screen.
            ui.mainClock.autoAdvance = false
            ui.onNodeWithContentDescription("Go to Rowan's message", substring = true).performClick()
            ui.mainClock.advanceTimeBy(800)
            ui.onNodeWithContentDescription("Jump to the latest message").assertExists()
            shoot("jumped", theme)
            ui.mainClock.autoAdvance = true

            // Following a quote to a message part-way up, and one of Ada's own: where it lands, and the ring on her colour.
            show { Room() }
            ui.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToIndex(14)
            ui.mainClock.autoAdvance = false
            ui.onNodeWithContentDescription("Go to your message", substring = true).performClick()
            ui.mainClock.advanceTimeBy(800)
            shoot("jumped-mine", theme)
            ui.mainClock.autoAdvance = true

            // A message's dialog offers Reply.
            show { Room() }
            ui.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToIndex(14)
            ui.onNodeWithText("Still true for this week").performClick()
            ui.onNodeWithText("Reply").assertExists()
            shoot("dialog-reply", theme)

            // Replying: what it answers above the box, and Cancel.
            ui.onNodeWithText("Reply").performClick()
            ui.onNodeWithText("Replying to Rowan").assertExists()
            shoot("composer-replying", theme)
        }
    }
}
