package dev.forgesworn.kithmoot.ui

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.view.KeyEvent
import android.view.WindowInsets
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.runtime.*
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.ui.room.ChatPane
import dev.forgesworn.kithmoot.ui.theme.KithMootTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Real installed phone widgets with synthetic drafts: no relay, upload or send. */
class ArtworkPickerUiTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun capture(label: String) {
        ui.waitForIdle()
        Thread.sleep(400) // Window/IME and orientation compositor transitions finish outside Compose.
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "artwork-review").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(folder, "tray-$label.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun mount(scenario: ActivityScenario<MainActivity>, offline: Boolean = false) {
        scenario.onActivity { activity -> activity.setContent { KithMootTheme {
            var artwork by remember { mutableStateOf(emptyList<dev.forgesworn.kithmoot.session.ChatArtwork>()) }
            ChatPane(emptyList(), "03".repeat(32), { _, _ -> error("Review must not send") }, Modifier.fillMaxSize().systemBarsPadding(),
                onAddImage = { _, _, _ -> error("Review must not upload") }, artwork = artwork,
                onAddArtwork = { artwork = artwork + it }, onRemoveArtwork = { index -> artwork = artwork.filterIndexed { position, _ -> position != index } },
                internetAllowed = !offline, torOnly = offline)
        } } }
    }

    private fun waitForKeyboard(scenario: ActivityScenario<MainActivity>) {
        ui.waitUntil(10_000) {
            var shown = false
            scenario.onActivity { shown = it.window.decorView.rootWindowInsets.isVisible(WindowInsets.Type.ime()) }
            shown
        }
    }

    @Test fun familiar_tray_keeps_draft_repeats_tones_and_recents_without_keyboard() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            mount(scenario)
            ui.onNodeWithText("Say something").performTextInput("Keep this draft ")
            ui.onNodeWithContentDescription("Emoji").performClick()
            ui.waitUntil(10_000) {
                var hidden = false
                scenario.onActivity { hidden = !it.window.decorView.rootWindowInsets.isVisible(WindowInsets.Type.ime()) }
                hidden
            }
            ui.onNodeWithText("Keep this draft ").assertIsDisplayed()
            capture("emoji-portrait")
            ui.onNodeWithContentDescription("👍 Thumbs up").performClick().performClick()
            ui.onNodeWithText("Keep this draft 👍👍").assertIsDisplayed()
            ui.onNodeWithContentDescription("Hand colour: Classic yellow").performClick()
            ui.onNodeWithText("Dark skin").performClick()
            ui.onNodeWithContentDescription("👍🏿 Thumbs up").performClick()
            ui.onNodeWithText("Keep this draft 👍👍👍🏿").assertIsDisplayed()
            ui.onNodeWithText("Recent").performClick()
            ui.onNodeWithContentDescription("👍🏿 Thumbs up").assertIsDisplayed()
            capture("emoji-recents")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            ui.onNodeWithContentDescription("Close artwork picker").assertDoesNotExist()
            ui.onNodeWithText("Keep this draft 👍👍👍🏿").assertIsDisplayed()
        }
    }

    @Test fun search_keyboard_rotation_close_and_original_unicode_are_preserved() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            mount(scenario)
            ui.onNodeWithText("Say something").performTextInput("Unsent ")
            ui.onNodeWithContentDescription("Emoji").performClick()
            ui.waitUntil(5_000) { ui.onNodeWithContentDescription("Search artwork").isDisplayed() }
            ui.onNodeWithContentDescription("Search artwork").performClick()
            ui.onNodeWithTag("artwork-search").performClick()
            waitForKeyboard(scenario)
            ui.onNodeWithTag("artwork-search").performTextInput("unicorn")
            ui.onNodeWithContentDescription("🦄 unicorn").assertExists()
            capture("emoji-search-keyboard")
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            ui.waitUntil(10_000) { instrumentation.targetContext.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
            waitForKeyboard(scenario)
            ui.onNodeWithTag("artwork-search").assertIsFocused()
            ui.onNodeWithContentDescription("🦄 unicorn").assertIsDisplayed()
            capture("emoji-landscape-keyboard")
            ui.onNodeWithContentDescription("🦄 unicorn").performClick()
            ui.onNodeWithContentDescription("Close artwork picker").performClick()
            ui.onNodeWithText("Unsent 🦄").assertExists()
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            ui.waitUntil(10_000) { instrumentation.targetContext.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
            ui.onNodeWithContentDescription("Emoji").performClick()
            ui.waitUntil(5_000) { ui.onNodeWithContentDescription("Search artwork").isDisplayed() }
            ui.onNodeWithContentDescription("Search artwork").performClick()
            ui.onNodeWithTag("artwork-search").performClick()
            waitForKeyboard(scenario)
            ui.onNodeWithTag("artwork-search").performTextInput("unicorn")
            ui.onNodeWithContentDescription("🦄 unicorn").performClick()
            ui.onNodeWithText("Unsent 🦄🦄").assertExists()
            ui.onNodeWithContentDescription("Close artwork picker").assertIsDisplayed()
        }
    }

    @Test fun reaction_search_in_landscape_returns_unicode_without_changing_the_draft() {
        var chosen: String? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { KithMootTheme {
                ChatPane(listOf(dev.forgesworn.kithmoot.session.ChatMessage("chat", "02".repeat(32), "01".repeat(32), "React to this", 1_800_000_000)),
                    "03".repeat(32), { _, _ -> error("Review must not send") }, Modifier.fillMaxSize().systemBarsPadding(),
                    onReact = { _, emoji -> chosen = emoji })
            } } }
            ui.onNodeWithText("Say something").performTextInput("Keep my reaction draft")
            ui.onNodeWithText("React to this", useUnmergedTree = true).performClick()
            ui.onNodeWithText("More emoji…").performClick()
            ui.onNodeWithContentDescription("Search artwork").performClick()
            ui.onNodeWithTag("artwork-search").performClick()
            waitForKeyboard(scenario)
            ui.onNodeWithTag("artwork-search").performTextInput("unicorn")
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            ui.waitUntil(10_000) { instrumentation.targetContext.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
            waitForKeyboard(scenario)
            ui.onNodeWithTag("artwork-search").assertIsFocused()
            ui.onNodeWithContentDescription("🦄 unicorn").assertIsDisplayed()
            capture("reaction-landscape-keyboard")
            ui.onNodeWithContentDescription("🦄 unicorn").performClick()
            ui.runOnIdle { assertEquals("🦄", chosen) }
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            ui.waitUntil(10_000) { instrumentation.targetContext.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
            ui.onNodeWithText("Keep my reaction draft").assertExists()
        }
    }

    @Test fun local_media_preview_stages_an_offline_reference_without_storage_or_send() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            mount(scenario, offline = true)
            ui.onNodeWithText("Say something").performTextInput("Keep this draft ")
            ui.onNodeWithContentDescription("Emoji").performClick()
            ui.onNodeWithText("Stickers").performClick()
            capture("stickers-portrait")
            ui.onNodeWithText("GIFs").performClick()
            ui.onNodeWithContentDescription("Preview Coffee.gif").performClick()
            capture("gif-preview")
            ui.onNodeWithText("Add to message").performClick()
            ui.onNodeWithContentDescription("Remove Coffee from message").assertIsDisplayed()
            ui.onNodeWithText("Share an encrypted image").assertDoesNotExist()
            ui.onNodeWithContentDescription("Close artwork picker").assertDoesNotExist()
            capture("media-local-reference")
            ui.onNodeWithContentDescription("Send").assertIsEnabled()
            ui.onNodeWithContentDescription("Remove Coffee from message").performClick()
            ui.onNodeWithText("Keep this draft ").assertIsDisplayed()
        }
    }

    @Test fun received_known_artwork_renders_offline_and_unknown_hash_stays_readable() {
        val coffee = dev.forgesworn.kithmoot.session.catalogueArtwork(dev.forgesworn.kithmoot.session.searchMediaCatalogue("coffee", false).single())
        val message = dev.forgesworn.kithmoot.session.ChatMessage("local-art", "02".repeat(32), "01".repeat(32), "Caption", 1_800_000_000,
            artwork = listOf(coffee, coffee.copy(sha256 = "0".repeat(64), label = "Unknown revision")))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { KithMootTheme {
                ChatPane(listOf(message), "03".repeat(32), { _, _ -> error("Review must not send") }, Modifier.fillMaxSize().systemBarsPadding(), internetAllowed = false)
            } } }
            ui.onNodeWithContentDescription("gif: Coffee").assertIsDisplayed()
            ui.onNodeWithText("GIF: Unknown revision").assertIsDisplayed()
            ui.waitForIdle()
            Thread.sleep(500)
            val bounds = ui.onNodeWithContentDescription("gif: Coffee").fetchSemanticsNode().boundsInWindow
            fun renderedFrame(): Int {
                val bitmap = instrumentation.uiAutomation.takeScreenshot()
                var fingerprint = 1
                for (y in bounds.top.toInt() until bounds.bottom.toInt() step 12)
                    for (x in bounds.left.toInt() until bounds.right.toInt() step 12)
                        fingerprint = 31 * fingerprint + bitmap.getPixel(x, y)
                bitmap.recycle()
                return fingerprint
            }
            val firstFrame = renderedFrame()
            if (android.animation.ValueAnimator.areAnimatorsEnabled()) ui.waitUntil(5_000) { renderedFrame() != firstFrame }
            else { Thread.sleep(500); assertEquals(firstFrame, renderedFrame()) }
            capture("received-local-artwork")
        }
    }
}
