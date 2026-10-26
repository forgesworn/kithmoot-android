package dev.forgesworn.kithmoot.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentSafetyVectorsTest {
    private val root: JsonObject = javaClass.getResourceAsStream("/attachment-safety-v1.json")!!.use {
        Json.parseToJsonElement(String(it.readBytes(), Charsets.UTF_8)).jsonObject
    }

    @Test fun everyPublishedNameCase() {
        val cases = root.getValue("names").jsonArray
        assertEquals(8, cases.size)
        for (element in cases) {
            val row = element.jsonObject
            val actual = AttachmentSafety.cleanFileName(row.getValue("raw").jsonPrimitive.content)
            assertEquals(row.getValue("name").jsonPrimitive.content, actual.name)
            assertEquals(row.getValue("ext").jsonPrimitive.content, actual.ext)
            assertEquals(row.getValue("hadDirectionControls").jsonPrimitive.boolean, actual.hadDirectionControls)
        }
    }

    @Test fun everyPublishedSniffCase() {
        val cases = root.getValue("sniff").jsonArray
        assertEquals(10, cases.size)
        for (element in cases) {
            val row = element.jsonObject
            assertEquals(SniffKind.fromWire(row.getValue("kind").jsonPrimitive.content),
                AttachmentSafety.sniffKind(hex(row.getValue("head").jsonPrimitive.content)))
        }
    }

    @Test fun everyPublishedDecisionCase() {
        val cases = root.getValue("decisions").jsonArray
        assertEquals(14, cases.size)
        for (element in cases) {
            val row = element.jsonObject
            val actual = AttachmentSafety.assessAttachment(AttachmentInput(
                bytes = hex(row.getValue("head").jsonPrimitive.content),
                envelopeName = row.getValue("envelopeName").jsonPrimitive.content,
                declaredType = row.getValue("declaredType").jsonPrimitive.content,
                messageName = row["messageName"]?.jsonPrimitive?.content
                    ?: row.getValue("envelopeName").jsonPrimitive.content,
            ))
            assertEquals(AttachmentTier.fromWire(row.getValue("tier").jsonPrimitive.content), actual.tier)
            assertEquals(SafetyReason.fromWire(row.getValue("reason").jsonPrimitive.content), actual.reason)
        }
    }

    private fun hex(value: String): ByteArray {
        require(value.length % 2 == 0)
        return ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
