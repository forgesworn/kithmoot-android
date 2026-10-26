package dev.forgesworn.kithmoot.media.attachment

import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.session.ChatAttachment
import dev.forgesworn.kithmoot.session.openAttachment
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream

class IsolatedAttachmentDecoderTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun manifest_keeps_the_decoder_non_exported_and_isolated() {
        val services = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SERVICES).services.orEmpty()
        val service = services.single { it.name == IsolatedAttachmentDecoderService::class.java.name }
        assertFalse(service.exported)
        assertTrue(service.flags and ServiceInfo.FLAG_ISOLATED_PROCESS != 0)
        assertEquals("dev.forgesworn.kithmoot.attachment.decode", service.processName)
    }

    @Test fun static_formats_decode_in_the_service_to_owned_rgba() = runBlocking {
        val source = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until 16) for (x in 0 until 16) setPixel(x, y, if (x < 8) Color.RED else Color.BLUE)
        }
        for ((format, mime) in listOf(Bitmap.CompressFormat.PNG to "image/png",
            Bitmap.CompressFormat.JPEG to "image/jpeg", Bitmap.CompressFormat.WEBP to "image/webp")) {
            val bytes = ByteArrayOutputStream().also { source.compress(format, 100, it) }.toByteArray()
            val decoded = decodeAttachmentIsolated(context, bytes, mime)
            try {
                assertEquals(16, decoded.width); assertEquals(16, decoded.height)
                assertEquals(1, decoded.frames.size); assertEquals(0, decoded.delaysMs[0])
                val left = decoded.frames[0].getPixel(4, 8); val right = decoded.frames[0].getPixel(12, 8)
                assertTrue("$mime left pixel was ${Integer.toHexString(left)}", Color.red(left) > 150)
                assertTrue("$mime right pixel was ${Integer.toHexString(right)}", Color.blue(right) > 150)
            } finally {
                decoded.recycle()
            }
        }
        source.recycle()
    }

    @Test fun encrypted_gif_decodes_exact_frame_order_and_delays() = runBlocking {
        val file = openEncryptedGif()
        try {
            val decoded = decodeAttachmentIsolated(context, file.bytes, "image/gif")
            try { assertGifFrames(decoded, listOf(200, 200, 200, 200)) } finally { decoded.recycle() }
        } finally {
            file.bytes.fill(0)
        }
    }

    @Test fun mixed_gif_uses_raw_Movie_seek_times_and_normalised_UI_delays() = runBlocking {
        val file = openEncryptedGif()
        try {
            val bytes = file.bytes.copyOf()
            val offsets = gifDelayOffsets(bytes)
            assertEquals(4, offsets.size)
            offsets.zip(listOf(20, 1, 10, 20)).forEach { (offset, delay) ->
                bytes[offset] = (delay and 0xff).toByte(); bytes[offset + 1] = (delay ushr 8).toByte()
            }
            val decoded = decodeAttachmentIsolated(context, bytes, "image/gif")
            try { assertGifFrames(decoded, listOf(200, 100, 100, 200)) } finally { decoded.recycle() }
        } finally {
            file.bytes.fill(0)
        }
    }

    @Test fun raw_zero_delay_on_a_distinct_later_GIF_frame_is_rejected() = runBlocking {
        val file = openEncryptedGif()
        try {
            val bytes = file.bytes.copyOf()
            val offsets = gifDelayOffsets(bytes)
            assertEquals(4, offsets.size)
            bytes[offsets[2]] = 0; bytes[offsets[2] + 1] = 0
            try {
                decodeAttachmentIsolated(context, bytes, "image/gif")
                fail("A raw-zero third GIF frame was accepted")
            } catch (_: AttachmentDecodeException) {
            }
        } finally {
            file.bytes.fill(0)
        }
    }

    private fun openEncryptedGif(): dev.forgesworn.kithmoot.session.OpenedAttachment {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val meta = Json.parseToJsonElement(instrumentation.context.assets.open("animated-gif.json").bufferedReader().readText()).jsonObject
        val attachment = ChatAttachment("https://unused.invalid/party", meta.getValue("sha256").jsonPrimitive.content,
            meta.getValue("key").jsonPrimitive.content, "party.gif", "image/gif")
        val envelope = instrumentation.context.assets.open("animated-gif.enc").use { it.readBytes() }
        return try { openAttachment(envelope, attachment) } finally { envelope.fill(0) }
    }

    private fun gifDelayOffsets(bytes: ByteArray): List<Int> {
        val offsets = mutableListOf<Int>()
        for (at in 0..bytes.size - 8) {
            if (bytes[at] == 0x21.toByte() && bytes[at + 1] == 0xf9.toByte() && bytes[at + 2] == 4.toByte()) offsets += at + 4
        }
        return offsets
    }

    private fun assertGifFrames(decoded: DecodedAttachment, delays: List<Int>) {
        assertEquals(16, decoded.width); assertEquals(16, decoded.height)
        assertEquals(4, decoded.frames.size); assertEquals(delays, decoded.delaysMs.toList())
        decoded.frames.forEachIndexed { index, frame ->
            val pixel = frame.getPixel(frame.width / 2, frame.height / 2)
            if (index < 2) assertTrue("Frame $index must be red", Color.red(pixel) > 180 && Color.blue(pixel) < 80)
            else assertTrue("Frame $index must be blue", Color.blue(pixel) > 80 && Color.red(pixel) < 80)
        }
    }

    @Test fun partial_static_input_has_no_main_process_fallback() = runBlocking {
        val source = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val png = ByteArrayOutputStream().also { source.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        source.recycle()
        try {
            decodeAttachmentIsolated(context, png.copyOf(png.size / 2), "image/png")
            fail("Partial PNG was accepted")
        } catch (_: AttachmentDecodeException) {
        }
        try {
            decodeAttachmentIsolated(context, png, "image/webp")
            fail("A PNG routed as WebP was accepted")
        } catch (_: AttachmentDecodeException) {
        }
    }
}
