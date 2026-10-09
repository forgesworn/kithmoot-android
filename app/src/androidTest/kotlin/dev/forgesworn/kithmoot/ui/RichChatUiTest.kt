package dev.forgesworn.kithmoot.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.account.npubOf
import dev.forgesworn.kithmoot.session.ChatAttachment
import dev.forgesworn.kithmoot.session.ChatMessage
import dev.forgesworn.kithmoot.ui.room.ChatPane
import dev.forgesworn.kithmoot.ui.room.PublicProfile
import dev.forgesworn.kithmoot.ui.theme.KithMootTheme
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class RichChatUiTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val peer = "02".repeat(32)
    private val message = ChatMessage("chat", peer, "01".repeat(32), "Hello from Rowan", 1_800_000_000, name = "Rowan")

    @Test fun names_open_copyable_profiles_private_chat_and_full_reactions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var privatePeer: String? = null
        var chosen: String? = null
        var unlocked by mutableStateOf(false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { KithMootTheme {
                ChatPane(listOf(message), "03".repeat(32), { _, _ -> }, Modifier.fillMaxSize(),
                    onReact = { _, emoji -> chosen = emoji }, profilesEnabled = true,
                    profiles = mapOf(peer to PublicProfile("Rowan", null, 1, "id", "rowan@example.com")),
                    privateConversationPeers = listOf(peer), onMessagePrivately = { privatePeer = it },
                    memberPackAvailable = { unlocked }, unlockMemberPacks = { unlocked = true; true })
            } } }
            ui.onNodeWithText("GIFs and stickers").assertDoesNotExist()
            ui.onNodeWithContentDescription("Images, GIFs and stickers").performClick()
            ui.onNodeWithText("GIFs and stickers").assertIsDisplayed()
            ui.onNodeWithContentDescription("Images, GIFs and stickers").performClick()
            ui.onNodeWithText("Rowan", useUnmergedTree = true).performClick()
            ui.onNodeWithText("Participant details").assertIsDisplayed()
            ui.onNodeWithText("Copy npub").performClick()
            ui.runOnIdle {
                val clipboard = instrumentation.targetContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                assertEquals(npubOf(peer), clipboard.primaryClip?.getItemAt(0)?.text.toString())
            }
            ui.onNodeWithText("Copy NIP-05").performClick()
            ui.runOnIdle {
                val clipboard = instrumentation.targetContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                assertEquals("rowan@example.com", clipboard.primaryClip?.getItemAt(0)?.text.toString())
            }
            ui.onNodeWithText("Message privately").performClick()
            ui.runOnIdle { assertEquals(peer, privatePeer) }
            ui.waitUntil(5_000) { !ui.onNodeWithText("Participant details").isDisplayed() }
            ui.onNodeWithText("Hello from Rowan", useUnmergedTree = true).performClick()
            ui.waitUntil(5_000) { ui.onNodeWithText("More emoji…").isDisplayed() }
            ui.onNodeWithText("More emoji…").performClick()
            ui.onNodeWithContentDescription("Search artwork").performClick()
            ui.onNodeWithTag("artwork-search").performTextInput("unicorn")
            ui.onNodeWithContentDescription("🦄 unicorn").performClick()
            ui.runOnIdle { assertEquals("🦄", chosen) }
            ui.onNodeWithContentDescription("Emoji").performClick()
            ui.onNodeWithContentDescription("Search artwork").performClick()
            ui.onNodeWithTag("artwork-search").performTextInput("600")
            ui.onNodeWithContentDescription(":600: 600 billion").assertDoesNotExist()
            ui.onNodeWithContentDescription("Artwork options").performClick()
            ui.onNodeWithText("Unlock Nostr packs").performClick()
            ui.waitUntil(5_000) { ui.onNodeWithContentDescription(":600: 600 billion").isDisplayed() }
            ui.onNodeWithContentDescription(":600: 600 billion").performClick()
            ui.onNodeWithText(":600:", substring = true).assertExists()
        }
    }

    @Test fun web_encrypted_gif_plays_multiple_frames_in_the_native_viewer() {
        val url = InstrumentationRegistry.getArguments().getString("animatedGifFixtureUrl")
        assumeTrue("Supply the encrypted synthetic animated GIF fixture", url?.startsWith("https://") == true)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val meta = Json.parseToJsonElement(instrumentation.context.assets.open("animated-gif.json").bufferedReader().readText()).jsonObject
        val attachment = ChatAttachment(url!!, meta.getValue("sha256").jsonPrimitive.content, meta.getValue("key").jsonPrimitive.content, "party.gif")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { KithMootTheme {
                ChatPane(listOf(message.copy(attachments = listOf(attachment))), "03".repeat(32), { _, _ -> }, Modifier.fillMaxSize())
            } } }
            ui.onNodeWithText("Open attachment: party.gif").performClick()
            ui.waitUntil(35_000) { ui.onNodeWithContentDescription("party.gif").isDisplayed() }
            val bounds = ui.onNodeWithContentDescription("party.gif").fetchSemanticsNode().boundsInWindow
            var red = false; var blue = false
            ui.waitUntil(6_000) {
                val frame = instrumentation.uiAutomation.takeScreenshot()
                val pixel = frame.getPixel(bounds.center.x.toInt(), bounds.center.y.toInt()); frame.recycle()
                red = red || android.graphics.Color.red(pixel) > 180 && android.graphics.Color.blue(pixel) < 80
                blue = blue || android.graphics.Color.blue(pixel) > 180 && android.graphics.Color.red(pixel) < 80
                red && blue
            }
            assertTrue("The decrypted GIF must show both frames", red && blue)
            ui.onNodeWithText("Close image").performClick()
            ui.onNodeWithContentDescription("party.gif").assertDoesNotExist()
        }
    }
}
