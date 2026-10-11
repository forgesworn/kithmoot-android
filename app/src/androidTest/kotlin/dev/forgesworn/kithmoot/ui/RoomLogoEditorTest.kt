package dev.forgesworn.kithmoot.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import android.app.Activity
import android.content.Intent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.FileProvider
import androidx.compose.ui.test.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.protocol.LogoImages
import dev.forgesworn.kithmoot.ui.room.*
import dev.forgesworn.kithmoot.ui.theme.KithMootTheme
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Exercises actual decoder, orientation, crop pixels and private wire output. */
class RoomLogoEditorTest {
    @get:Rule val compose = createComposeRule()
    private fun split(): Bitmap = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.RED)
        val blue = IntArray(200 * 200) { Color.BLUE }
        setPixels(blue, 0, 200, 200, 0, 200, 200)
    }
    private fun fixture() = InstrumentationRegistry.getInstrumentation().context.assets.open("logo-images-v1.json")
        .bufferedReader().use { Json.parseToJsonElement(it.readText()) }.jsonObject.getValue("vectors").jsonArray
        .mapNotNull { LogoImages.read(it.jsonObject["payload"]) }.first()

    @Test fun nativeCropProducesVisibleBoundedMetadataFreeWirePixels() = runBlocking {
        val source = split()
        try {
            val left = encodeLogoCrop(source, 1f, 0f, .5f)
            val right = encodeLogoCrop(source, 1f, 1f, .5f)
            for ((image, colour) in listOf(left to Color.RED, right to Color.BLUE)) {
                assertTrue(Base64.getDecoder().decode(image.data).size <= LogoImages.MAX_BYTES)
                assertEquals(256, image.width); assertEquals(image.width, image.height)
                assertNotNull(LogoImages.read(image.toJson()))
                val decoded = requireNotNull(decodePrivateLogo(image))
                try {
                    val pixel = decoded.getPixel(128, 128)
                    assertTrue(kotlin.math.abs(Color.red(pixel) - Color.red(colour)) < 10)
                    assertTrue(kotlin.math.abs(Color.blue(pixel) - Color.blue(colour)) < 10)
                } finally { decoded.recycle() }
            }
            assertNotEquals(left.sha256, right.sha256)
        } finally { source.recycle() }
    }

    @Test fun jpegExifOrientationIsAppliedBeforeCropAndRemovedFromOutput() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "opened-images").apply { mkdirs() }
        val file = File.createTempFile("logo-orientation-", ".jpg", directory)
        val original = split()
        try {
            file.outputStream().use { assertTrue(original.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                setAttribute(ExifInterface.TAG_USER_COMMENT, "private metadata")
                saveAttributes()
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.images", file)
            val decoded = decodeLogoSource(context.contentResolver, uri)
            try {
                assertEquals(200, decoded.width); assertEquals(400, decoded.height)
                val image = encodeLogoCrop(decoded, 1f, .5f, 0f)
                assertNotNull(LogoImages.read(image.toJson()))
                val pixels = requireNotNull(decodePrivateLogo(image))
                try { assertTrue(Color.red(pixels.getPixel(128, 128)) > 240) }
                finally { pixels.recycle() }
            } finally { decoded.recycle() }
            try { decodeLogoSource(context.contentResolver, Uri.parse("https://example.invalid/logo.png")); fail("External image URL accepted") }
            catch (_: IllegalArgumentException) { }
        } finally { original.recycle(); file.delete() }
    }

    @Test fun invisibleOrAnimatedSourcesDoNotBecomeRoomLogos() = runBlocking {
        val source = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        try {
            try { encodeLogoCrop(source, 1f, .5f, .5f); fail("Transparent crop accepted") }
            catch (_: IllegalArgumentException) { }
            try { decodeLogoBytes("GIF89a".toByteArray()); fail("Animation accepted") }
            catch (_: IllegalStateException) { }
        } finally { source.recycle() }
    }

    @Test fun sourceHeaderLimitsAndWorkingBitmapBoundApplyBeforeCropping() = runBlocking {
        for (width in listOf(8193, 3000)) {
            val original = Bitmap.createBitmap(width, 10, Bitmap.Config.ARGB_8888)
            try {
                original.eraseColor(Color.RED)
                val encoded = ByteArrayOutputStream()
                assertTrue(original.compress(Bitmap.CompressFormat.PNG, 100, encoded))
                if (width == 8193) {
                    try { decodeLogoBytes(encoded.toByteArray()); fail("Oversized header accepted") }
                    catch (_: IllegalArgumentException) { }
                } else {
                    val working = decodeLogoBytes(encoded.toByteArray())
                    try { assertEquals(2048, working.width); assertTrue(working.height in 1..10) }
                    finally { working.recycle() }
                }
            } finally { original.recycle() }
        }
    }

    @Test fun removalIsExplicit() {
        var removed = false; var closed = false
        val edit = RoomLogoEditScope("Room with logo", fixture(), { true }) { image, allowed ->
            assertTrue(allowed()); assertNull(image); removed = true
        }
        compose.setContent { KithMootTheme { RoomLogoEditor(edit) { closed = true } } }
        compose.onNodeWithText("Remove logo for everyone").performScrollTo().performClick()
        compose.waitUntil(5_000) { removed && closed }
    }

    @Test fun selectedContentUriCanBePreviewedCroppedAndSharedThroughEditor() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("logo-picker-", ".png", File(context.cacheDir, "opened-images").apply { mkdirs() })
        val source = split()
        var closed = false
        try {
            file.outputStream().use { assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.images", file)
            // Substitute only the system picker's result; content reading,
            // decoding, preview, sliders and sharing run the product path.
            val registry = object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                    dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(uri))
                }
            }
            val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
            val edit = RoomLogoEditScope("Workshop", null, { true }) { image, allowed ->
                assertTrue(allowed())
                val pixels = requireNotNull(decodePrivateLogo(requireNotNull(image)))
                try { assertTrue(Color.blue(pixels.getPixel(128, 128)) > 240) }
                finally { pixels.recycle() }
            }
            compose.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                KithMootTheme { RoomLogoEditor(edit) { closed = true } }
            } }
            compose.onNodeWithText("Choose image").performScrollTo().performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Room logo crop preview").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Horizontal position").performScrollTo()
                .performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
            compose.onNodeWithText("Share logo with room").performScrollTo().assertIsEnabled().performClick()
            compose.waitUntil(5_000) { closed }
        } finally { source.recycle(); file.delete() }
    }

    @Test fun cancelLeavesTheRoomLogoUnchanged() {
        var closed = false
        val edit = RoomLogoEditScope("Room", fixture(), { true }) { _, _ -> fail("Cancel shared a logo") }
        compose.setContent { KithMootTheme { RoomLogoEditor(edit) { closed = true } } }
        compose.onNodeWithText("Choose image").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Share logo with room").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(closed) }
    }

    @Test fun largeTextEditorKeepsActionsReachableInBothThemes() {
        val dark = mutableStateOf(false)
        val edit = RoomLogoEditScope("Shared workshop with a long room name", fixture(), { true }) { _, _ -> }
        compose.setContent { KithMootTheme(darkTheme = dark.value, textScale = 1.5f) { RoomLogoEditor(edit) {} } }
        for (theme in listOf("light", "dark")) {
            compose.runOnIdle { dark.value = theme == "dark" }
            compose.onNodeWithText("Choose image").performScrollTo().assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithText("Remove logo for everyone").performScrollTo().assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithText("Cancel").performScrollTo().assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithText("Room logo").performScrollTo()
            val picture = compose.onNodeWithText("Room logo").onParent().captureToImage().asAndroidBitmap()
            try {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                File(context.getExternalFilesDir("ui-proof"), "room-logo-editor-large-$theme.png").outputStream().use {
                    assertTrue(picture.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { picture.recycle() }
        }
    }

    @Test fun expiredEditorDisablesShareAndCanStillBeCancelled() {
        var valid = true; var closed = false
        val edit = RoomLogoEditScope("Room", fixture(), { valid }) { _, _ -> fail("Expired editor shared") }
        compose.setContent { KithMootTheme { RoomLogoEditor(edit) { closed = true } } }
        compose.runOnIdle { valid = false }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("This room or its key changed. Close this preview and open the editor again.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Remove logo for everyone").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(closed) }
    }
}
