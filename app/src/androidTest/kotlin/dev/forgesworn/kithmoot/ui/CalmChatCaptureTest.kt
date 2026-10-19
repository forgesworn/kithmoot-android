package dev.forgesworn.kithmoot.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.session.ChatMessage
import dev.forgesworn.kithmoot.session.ChatReaction
import dev.forgesworn.kithmoot.ui.room.ChatPane
import dev.forgesworn.kithmoot.ui.theme.KithMootTheme
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Renders the chat pane over a fixed conversation in both themes and every
 * in-app text size, and saves each frame under the app's external files
 * directory (`files/calm/`) for review. The system font scale is whatever the
 * device is set to when this runs, and is in each file name.
 */
class CalmChatCaptureTest {
    @get:Rule val ui = createEmptyComposeRule()

    private val me = "03".repeat(32)
    private val priya = "02".repeat(32)
    private val rowan = "04".repeat(32)
    private val tally = "05".repeat(32)
    private val base = 1_800_000_000L

    private fun say(id: String, who: String, name: String?, text: String, at: Long) =
        ChatMessage(id, who, who.take(2).repeat(32), text, base + at, name = name)

    private val conversation = listOf(
        say("m1", priya, "Priya Natarajan", "Small thing: the invite link on Android opens the door twice. Screenshot in a minute.", 0),
        say("m2", priya, "Priya Natarajan", "It only happens on a cold start.", 20),
        say("m3", rowan, "Rowan", "Seen that before. It goes away if the app is already open.", 120),
        say("m4", tally, "Tally", "Summary so far: build 54 is good, two flaky specs on the board, one Android cold-start bug to write up.", 240),
        say("m5", rowan, "Rowan", "Perfect. Call at 3?", 300),
        ChatMessage("r1", priya, "02".repeat(32), "", base + 310, reaction = ChatReaction("m5", rowan, "👍", true, 1)),
        say("m6", me, "You", "Yes, 3 works. I will start it from here.", 360),
        say("m7", me, "You", "Bringing the build notes.", 370),
    )

    @Test fun capture_chat_pane() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val out = File(context.getExternalFilesDir(null), "calm").apply { mkdirs() }
        val system = "%.2f".format(context.resources.configuration.fontScale)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (dark in listOf(true, false)) for (textScale in listOf(1.0f, 1.25f, 1.5f)) {
                scenario.onActivity { activity -> activity.setContent {
                    KithMootTheme(darkTheme = dark, textScale = textScale) {
                        Surface(Modifier.fillMaxSize(), color = androidx.compose.material3.MaterialTheme.colorScheme.surface) {
                            ChatPane(conversation, me, { _, _ -> }, Modifier.fillMaxSize().statusBarsPadding(), showTitle = false)
                        }
                    }
                } }
                ui.waitForIdle()
                val bitmap = ui.onRoot().captureToImage().asAndroidBitmap()
                File(out, "chat-${if (dark) "dark" else "light"}-system$system-text$textScale.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        }
    }
}
