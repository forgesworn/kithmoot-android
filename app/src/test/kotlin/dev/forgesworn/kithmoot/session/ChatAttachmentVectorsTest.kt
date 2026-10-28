package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.NostrEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The published `chatAttachment` group, all seven vectors. The chat codec
 * lives in this module, so the vectors are read from the protocol module's
 * verbatim copy. A decoded share is re-encoded and compared as JSON text,
 * so an unmodelled field or a changed key order cannot pass unnoticed.
 */
class ChatAttachmentVectorsTest {

    private val root: JsonObject by lazy {
        val file = listOf("../protocol/src/test/resources/kithmoot-vectors.json", "protocol/src/test/resources/kithmoot-vectors.json")
            .map(::File).first { it.exists() }
        Json.parseToJsonElement(file.readText()).jsonObject
    }
    private fun vector(name: String): JsonObject = root.getValue("groups").jsonObject.getValue("chatAttachment").jsonArray
        .map { it.jsonObject }.single { it.getValue("name").jsonPrimitive.content == name }
    private fun JsonObject.child(key: String) = getValue(key).jsonObject
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    private fun decode(vector: JsonObject, decode: JsonObject): ChatMessage? = decodeChatEvent(
        NostrEvent.fromJson(vector.child("input").child("event")),
        decode.text("roomId"), decode.text("roomKeyHex").hexToBytes(), decode.getValue("now").jsonPrimitive.long)

    private fun assertDecodesAsReference(name: String): ChatMessage {
        val v = vector(name)
        val expected = v.child("expected").child("result")
        val decoded = assertNotNull(decode(v, v.child("expected").child("decode")), "$name must decode")
        assertEquals(expected.text("id"), decoded.id)
        assertEquals(expected.text("participant"), decoded.participant)
        assertEquals(expected.text("device"), decoded.device)
        assertEquals(expected.text("text"), decoded.body)
        assertEquals(expected.getValue("sentAt").jsonPrimitive.long, decoded.sentAt)
        assertEquals(expected.getValue("attachments").jsonArray.toString(), JsonArray(decoded.attachments.map { it.toJson() }).toString(),
            "$name attachments re-encode exactly as the reference reads them")
        return decoded
    }

    @Test fun `chat-with-attachment - everything that matters is inside the ciphertext`() {
        val decoded = assertDecodesAsReference("chat-with-attachment")
        assertEquals(1, decoded.attachments.size)
        val wire = vector("chat-with-attachment").child("input").child("event").toString()
        val share = decoded.attachments.single()
        for (secret in listOf(share.key, share.url, share.sha256)) assertFalse(wire.contains(secret))
    }

    @Test fun `attachments-a-reader-must-defuse - the message survives, the bad shares do not`() {
        val share = assertDecodesAsReference("attachments-a-reader-must-defuse").attachments.single()
        assertEquals("application/pdf", share.type)
        assertFalse(share.name!!.contains('‮'))
        assertNull(share.size)
    }

    @Test fun `more-attachments-than-a-message-may-carry - the whole message is refused`() {
        val v = vector("more-attachments-than-a-message-may-carry")
        assertNull(decode(v, v.child("input").child("decode")))
        assertEquals("null", v.child("output").getValue("result").toString())
    }

    @Test fun `envelope-key-derivation`() {
        val v = vector("envelope-key-derivation")
        val input = v.child("input")
        assertEquals(v.child("output").text("keyHex"), envelopeKey(input.text("recoveryKeyHex").hexToBytes(), input.text("saltHex").hexToBytes()).toHex())
    }

    @Test fun `padded-plaintext-length`() {
        val v = vector("padded-plaintext-length")
        val lengths = v.child("input").getValue("lengths").jsonArray.map { it.jsonPrimitive.long }
        assertEquals(v.child("output").getValue("padded").jsonArray.map { it.jsonPrimitive.long }, lengths.map(::paddedPlaintextLength))
    }

    @Test fun `file-event - the reference announcement shape`() {
        val v = vector("file-event")
        val input = v.child("input")
        val template = v.child("output").child("template")
        assertEquals(template.getValue("kind").jsonPrimitive.long, FILE_EVENT_KIND.toLong())
        assertEquals(template.text("content"), ENVELOPE_FILE_NAME)
        assertEquals(template.getValue("tags").jsonArray.map { tag -> tag.jsonArray.map { it.jsonPrimitive.content } },
            fileEventTags(input.text("url"), input.text("sha256"), input.getValue("size").jsonPrimitive.long))
    }

    /** The reference template is signed a second early and lives ninety
     * seconds; given those times, this client's signer writes the same event.
     * (This client's own uploads choose a five-minute lifetime; see
     * RichMediaTest.) */
    @Test fun `upload-authorisation - the reference template`() {
        val v = vector("upload-authorisation")
        val input = v.child("input")
        val template = v.child("output").child("template")
        val createdAt = template.getValue("created_at").jsonPrimitive.long
        assertEquals(input.getValue("now").jsonPrimitive.long - 1, createdAt)
        val expires = template.getValue("tags").jsonArray.map { it.jsonArray }.single { it[0].jsonPrimitive.content == "expiration" }[1].jsonPrimitive.long
        val signed = mediaAuthorisation("upload", input.text("sha256"), input.text("server"), ByteArray(32) { 7 }, createdAt, expires)
        assertEquals(template.getValue("kind").jsonPrimitive.long, signed.kind.toLong())
        assertEquals(createdAt, signed.createdAt)
        assertEquals(template.getValue("tags").jsonArray.map { tag -> tag.jsonArray.map { it.jsonPrimitive.content } }, signed.tags)
        assertEquals(template.text("content"), signed.content)
    }
}
