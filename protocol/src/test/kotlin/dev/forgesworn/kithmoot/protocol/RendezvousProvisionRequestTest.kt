package dev.forgesworn.kithmoot.protocol

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RendezvousProvisionRequestTest {
    @Test fun `own stack handoff serialises only exact public fields and numeric wire`() {
        val request = RendezvousProvisionRequest("ab".repeat(32), "cd".repeat(32), 7, ByteArray(16) { it.toByte() }, 1_900_000_300)
        val json = Json.parseToJsonElement(request.json()).jsonObject
        assertEquals(setOf("v", "expectedPersona", "targetDevice", "index", "nonce", "expiresAt"), json.keys)
        assertFalse(json.getValue("index").jsonPrimitive.isString)
        assertFalse(json.getValue("expiresAt").jsonPrimitive.isString)
        assertEquals("AAECAwQFBgcICQoLDA0ODw", json.getValue("nonce").jsonPrimitive.content)
        assertEquals("""["${"cd".repeat(32)}",7,"AAECAwQFBgcICQoLDA0ODw",1900000300]""", request.params().toString())
        assertTrue(request.validAt(1_900_000_000))
        assertFalse(request.validAt(1_900_000_300))
        assertFalse(request.validAt(1_899_999_699))
    }
}
