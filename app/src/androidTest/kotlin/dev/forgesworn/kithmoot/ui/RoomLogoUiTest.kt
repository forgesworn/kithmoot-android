package dev.forgesworn.kithmoot.ui

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.protocol.LogoImage
import dev.forgesworn.kithmoot.protocol.LogoImages
import dev.forgesworn.kithmoot.ui.room.PrivateLogo
import dev.forgesworn.kithmoot.ui.room.decodePrivateLogo
import dev.forgesworn.kithmoot.ui.start.RoomRow
import dev.forgesworn.kithmoot.ui.theme.KithMootTheme
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

/** Real Android decoders and drawn pixels, rather than JVM bitmap stubs. */
class RoomLogoUiTest {
    @get:Rule val compose = createComposeRule()

    private fun fixture(id: String): LogoImage {
        val text = InstrumentationRegistry.getInstrumentation().context.assets.open("logo-images-v1.json")
            .bufferedReader().use { it.readText() }
        val vector = Json.parseToJsonElement(text).jsonObject.getValue("vectors").jsonArray
            .first { it.jsonObject.getValue("id").jsonPrimitive.content == id }.jsonObject
        return requireNotNull(LogoImages.read(vector.getValue("payload")))
    }

    private fun solid(colour: Int): LogoImage {
        // Engineering fixture with no Android compressor metadata, three PNG chunks.
        val bytes = ByteArrayOutputStream()
        bytes.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
        fun chunk(kind: String, data: ByteArray) {
            val type = kind.toByteArray(Charsets.US_ASCII)
            val output = DataOutputStream(bytes)
            output.writeInt(data.size); output.write(type); output.write(data)
            output.writeInt(CRC32().apply { update(type); update(data) }.value.toInt())
        }
        val header = ByteArrayOutputStream()
        DataOutputStream(header).apply { writeInt(128); writeInt(128); write(byteArrayOf(8, 6, 0, 0, 0)) }
        chunk("IHDR", header.toByteArray())
        val pixels = ByteArrayOutputStream()
        DeflaterOutputStream(pixels).use { output ->
            repeat(128) {
                output.write(0)
                repeat(128) { output.write(byteArrayOf((colour ushr 16).toByte(), (colour ushr 8).toByte(),
                    colour.toByte(), (colour ushr 24).toByte())) }
            }
        }
        chunk("IDAT", pixels.toByteArray()); chunk("IEND", byteArrayOf())
        return LogoImages.create(bytes.toByteArray(), "image/png")
    }

    private fun centre(): Int {
        val shot = compose.onNodeWithTag("logo-frame").captureToImage().asAndroidBitmap()
        return try { shot.getPixel(shot.width / 2, shot.height / 2) } finally { shot.recycle() }
    }

    private fun awaitColour(colour: Int) = compose.waitUntil(5_000) { centre() == colour }

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val shot = compose.onRoot().captureToImage().asAndroidBitmap()
        try {
            File(instrumentation.targetContext.getExternalFilesDir("ui-proof"), name).outputStream().use {
                assertTrue(shot.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { shot.recycle() }
    }

    @Test fun nativeDecoderChecksPixelsInBothFormatsAndRejectsInvisibleOrBrokenImages() {
        for (id in listOf("real-png", "real-webp", "canvas-png-with-metadata-removed")) {
            val image = fixture(id)
            val bitmap = requireNotNull(decodePrivateLogo(image))
            try { assertEquals(image.width, bitmap.width); assertEquals(image.height, bitmap.height) }
            finally { bitmap.recycle() }
        }
        assertNull(decodePrivateLogo(fixture("framed-webp-with-undecodable-pixels")))
        assertNull(decodePrivateLogo(solid(AndroidColor.TRANSPARENT)))
    }

    private fun replacement(dark: Boolean) {
        val green = solid(AndroidColor.GREEN)
        val blue = solid(AndroidColor.BLUE)
        val selected = mutableStateOf<LogoImage?>(green)
        compose.setContent {
            KithMootTheme(darkTheme = dark) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp)) {
                    Row {
                        Box(Modifier.size(40.dp).testTag("logo-frame")) {
                            PrivateLogo(selected.value, "Workshop", Modifier.fillMaxSize(), fill = Color.Red, ink = Color.White)
                        }
                        Text("Workshop", color = MaterialTheme.colorScheme.onBackground)
                    }
                }
            }
        }
        awaitColour(AndroidColor.GREEN)
        compose.onNodeWithText("Workshop").assertIsDisplayed()
        compose.onAllNodesWithText("W", useUnmergedTree = true).assertCountEquals(0)
        screenshot("room-logo-${if (dark) "dark" else "light"}.png")
        compose.runOnIdle { selected.value = blue }
        awaitColour(AndroidColor.BLUE)
        compose.runOnIdle { selected.value = fixture("framed-webp-with-undecodable-pixels") }
        compose.waitUntil(5_000) { centre() != AndroidColor.BLUE }
        // A transparent image gives exactly the same initials fallback as removal.
        compose.runOnIdle { selected.value = solid(AndroidColor.TRANSPARENT) }
        compose.waitForIdle()
        val transparent = centre()
        compose.runOnIdle { selected.value = null }
        compose.waitForIdle()
        assertEquals(transparent, centre())
        assertNotEquals(AndroidColor.BLUE, centre())
        compose.onNodeWithText("Workshop").assertIsDisplayed()
        screenshot("room-logo-fallback-${if (dark) "dark" else "light"}.png")
    }

    @Test fun logoReplacementAndRemovalKeepTheNameInLightTheme() = replacement(false)
    @Test fun logoReplacementAndRemovalKeepTheNameInDarkTheme() = replacement(true)

    @Test fun aRoomRowKeepsItsUnreadCountAndOpenActionWithoutSpeakingTheInitialTwice() {
        var opened = 0
        val green = solid(AndroidColor.GREEN)
        compose.setContent {
            KithMootTheme(darkTheme = true) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp)) {
                    RoomRow("a".repeat(64), "Workshop", "A new message", "09:30", "Today at 09:30", true,
                        { opened++ }, emptyList(), unread = 3, logo = green)
                }
            }
        }
        compose.onNodeWithText("Workshop").assertIsDisplayed().assertHasClickAction().performClick()
        compose.onNodeWithText("3", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithText("W", useUnmergedTree = true).assertCountEquals(0)
        compose.runOnIdle { assertEquals(1, opened) }
        screenshot("room-logo-row-dark.png")
    }

    @Test fun rapidChangesAndLeavingTheSurfaceDoNotDisplayAStaleDecode() {
        val selected = mutableStateOf<LogoImage?>(solid(AndroidColor.GREEN))
        val visible = mutableStateOf(true)
        val blue = solid(AndroidColor.BLUE)
        val green = selected.value
        compose.setContent {
            KithMootTheme {
                if (visible.value) Box(Modifier.size(40.dp).testTag("logo-frame")) {
                    PrivateLogo(selected.value, "Workshop", Modifier.fillMaxSize())
                }
            }
        }
        awaitColour(AndroidColor.GREEN)
        repeat(20) { i -> compose.runOnIdle { selected.value = if (i % 2 == 0) blue else green } }
        compose.runOnIdle { selected.value = blue }
        awaitColour(AndroidColor.BLUE)
        compose.runOnIdle { visible.value = false; selected.value = green }
        compose.onNodeWithTag("logo-frame").assertDoesNotExist()
        compose.runOnIdle { visible.value = true }
        awaitColour(AndroidColor.GREEN)
    }
}
