package dev.forgesworn.kithmoot.ui.room

import dev.forgesworn.kithmoot.protocol.LogoImages
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64
import java.util.zip.CRC32
import kotlinx.serialization.json.*
import kotlin.test.*
import org.junit.Test

class LogoPixelsTest {
    private val png: ByteArray get() {
        val fixture = requireNotNull(javaClass.getResourceAsStream("/room-logo-chat-v1.json"))
            .bufferedReader().use { it.readText() }
        val image = Json.parseToJsonElement(fixture).jsonObject.getValue("vectors").jsonArray.first()
            .jsonObject.getValue("expected").jsonObject.getValue("roomLogo").jsonObject
        return Base64.getDecoder().decode(image.getValue("data").jsonPrimitive.content)
    }
    private fun chunk(type: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(); val tag = type.toByteArray(Charsets.US_ASCII)
        DataOutputStream(out).apply {
            writeInt(data.size); write(tag); write(data)
            writeInt(CRC32().apply { update(tag); update(data) }.value.toInt())
        }
        return out.toByteArray()
    }
    private fun insertPng(type: String, data: ByteArray) = png.let {
        it.copyOfRange(0, 33) + chunk(type, data) + it.copyOfRange(33, it.size)
    }

    @Test fun `bounded picker reads allow PNG JPEG and refuse other formats`() {
        assertContentEquals(png, LogoPixels.readSource(ByteArrayInputStream(png)))
        LogoPixels.checkStillSource(byteArrayOf(-1, -40, -1, -32))
        for (bytes in listOf("GIF89a".toByteArray(), "<svg/>".toByteArray(), byteArrayOf()))
            assertFails { LogoPixels.checkStillSource(bytes) }
        assertFails { LogoPixels.readSource(ByteArrayInputStream(ByteArray(LogoPixels.MAX_SOURCE_BYTES + 1))) }
    }
    @Test fun `animated or incomplete PNG is refused before native decoding`() {
        assertFails { LogoPixels.checkStillSource(insertPng("acTL", ByteArray(8))) }
        assertFails { LogoPixels.checkStillSource(png.copyOf(png.size - 1)) }
    }
    @Test fun `fresh PNG strips source metadata without weakening the recipient reader`() {
        val source = insertPng("tEXt", "Location\u0000private address".toByteArray())
        assertFails { LogoImages.create(source, "image/png") }
        val cleaned = LogoPixels.fromFreshEncoding(source, "image/png")
        assertContentEquals(png, Base64.getDecoder().decode(cleaned.data))
        assertNotNull(LogoImages.read(cleaned.toJson()))
        assertFails { LogoPixels.fromFreshEncoding(insertPng("acTL", ByteArray(8)), "image/png") }
        assertFails { LogoPixels.fromFreshEncoding(insertPng("ABCD", ByteArray(1)), "image/png") }
    }
    @Test fun `pixel limits use long arithmetic before allocation`() {
        LogoPixels.checkDimensions(4000, 4000)
        for ((w, h) in listOf(0 to 2, 8193 to 1, 8192 to 8192, Int.MAX_VALUE to Int.MAX_VALUE))
            assertFails { LogoPixels.checkDimensions(w, h) }
    }
    @Test fun `crop centres zooms and clamps without escaping source bounds`() {
        assertEquals(LogoPixels.Crop(100f, 0f, 200f), LogoPixels.crop(400, 200, 1f, .5f, .5f))
        assertEquals(LogoPixels.Crop(0f, 150f, 50f), LogoPixels.crop(400, 200, 4f, -1f, 2f))
        assertFails { LogoPixels.crop(400, 200, Float.NaN, .5f, .5f) }
    }
}
