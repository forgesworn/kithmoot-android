package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.toHex
import kotlinx.serialization.json.*
import kotlin.test.*

class ChatAttachmentTest {
    private fun fixture(name: String): Pair<ByteArray, ChatAttachment> {
        val meta = Json.parseToJsonElement(javaClass.getResource("/attachments/$name.json")!!.readText()).jsonObject
        return javaClass.getResourceAsStream("/attachments/$name.enc")!!.readBytes() to ChatAttachment(
            "https://files.example/image", meta.getValue("sha256").jsonPrimitive.content,
            meta.getValue("key").jsonPrimitive.content)
    }
    @Test fun `opens real web envelopes including more than one authenticated record`() {
        for (name in listOf("web-image", "web-multirecord")) {
            val (bytes, attachment) = fixture(name)
            val opened = openAttachment(bytes, attachment)
            assertEquals("picture.png", opened.name)
            assertEquals("image/png", opened.type)
            if (name == "web-image") assertEquals("89504e470d0a1a0a", opened.bytes.take(8).toByteArray().toHex())
            else { assertEquals(1_100_000, opened.bytes.size); assertTrue(opened.bytes.all { it == 73.toByte() }) }
        }
    }
    @Test fun `refuses wrong hash wrong key tampered final record and truncation`() {
        val (bytes, attachment) = fixture("web-multirecord")
        assertFails { openAttachment(bytes, attachment.copy(sha256 = "0".repeat(64))) }
        assertFails { openAttachment(bytes, attachment.copy(key = "0".repeat(64))) }
        val changed = bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertFails { openAttachment(changed, attachment.copy(sha256 = Digests.sha256(changed).toHex())) }
        val truncated = bytes.copyOf(bytes.size - 16)
        assertFails { openAttachment(truncated, attachment.copy(sha256 = Digests.sha256(truncated).toHex())) }
    }
    @Test fun `only accepts bounded HTTPS references with complete cryptographic material`() {
        val valid = """{"url":"https://example.test/file","sha256":"${"ab".repeat(32)}","key":"${"cd".repeat(32)}"}"""
        assertNotNull(parseAttachment(Json.parseToJsonElement(valid)))
        assertNull(parseAttachment(Json.parseToJsonElement(valid.replace("https:", "http:"))))
        assertNull(parseAttachment(Json.parseToJsonElement(valid.replace("cd".repeat(32), "invalid"))))
    }
}
