package dev.forgesworn.kithmoot.ui

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.session.ChatAttachment
import dev.forgesworn.kithmoot.session.ChatMessage
import dev.forgesworn.kithmoot.ui.room.ChatPane
import dev.forgesworn.kithmoot.ui.theme.KithMootTheme
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class AttachmentViewerTest {
    @get:Rule val ui = createEmptyComposeRule()
    @Test fun opens_a_web_encrypted_image_fits_zooms_and_closes_on_retraction() {
        val url = InstrumentationRegistry.getArguments().getString("attachmentFixtureUrl")
        assumeTrue("Supply an HTTPS host serving the exact synthetic web-image.enc fixture", url?.startsWith("https://") == true)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val meta = Json.parseToJsonElement(instrumentation.context.assets.open("web-image.json").bufferedReader().readText()).jsonObject
        val attachment = ChatAttachment(url!!, meta.getValue("sha256").jsonPrimitive.content, meta.getValue("key").jsonPrimitive.content, "picture.png")
        val message = ChatMessage("image", "01".repeat(32), "02".repeat(32), "A synthetic image", 1_800_000_000, attachments = listOf(attachment))
        var messages by mutableStateOf(listOf(message))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { KithMootTheme { ChatPane(messages, "03".repeat(32), {}, Modifier.fillMaxSize()) } } }
            ui.onNodeWithText("Open attachment: picture.png").performClick()
            ui.waitUntil(35_000) { ui.onNodeWithContentDescription("picture.png").isDisplayed() }
            ui.onNodeWithText("Actual size").performClick()
            ui.onNodeWithText("Fit").performClick()
            ui.onNodeWithContentDescription("picture.png").performTouchInput {
                pinch(center - androidx.compose.ui.geometry.Offset(30f, 0f), center + androidx.compose.ui.geometry.Offset(30f, 0f),
                    center - androidx.compose.ui.geometry.Offset(100f, 0f), center + androidx.compose.ui.geometry.Offset(100f, 0f))
            }
            ui.onNodeWithText("Fit").performClick()
            val bounds = ui.onNodeWithContentDescription("picture.png").fetchSemanticsNode().boundsInWindow
            ui.waitUntil(5_000) {
                val frame = instrumentation.uiAutomation.takeScreenshot()
                val margin = frame.getPixel(bounds.center.x.toInt(), (bounds.top + 5).toInt())
                frame.recycle()
                android.graphics.Color.red(margin) > 240 && android.graphics.Color.blue(margin) > 240
            }
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            val left = bitmap.getPixel((bounds.left + bounds.width / 3).toInt(), bounds.center.y.toInt())
            val right = bitmap.getPixel((bounds.left + bounds.width * 2 / 3).toInt(), bounds.center.y.toInt())
            assertTrue(android.graphics.Color.red(left) > 180)
            assertTrue(android.graphics.Color.blue(right) > 180)
            File(instrumentation.targetContext.getExternalFilesDir(null), "attachment-viewer.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            ui.runOnIdle { messages = messages + message.copy(id = "retraction", body = "Retracted", sentAt = message.sentAt + 1, attachments = emptyList(), retracts = message.id) }
            ui.waitUntil(5_000) { !ui.onNodeWithText("Close image").isDisplayed() }
            ui.onNodeWithText("Message retracted").assertIsDisplayed()
        }
    }
}
