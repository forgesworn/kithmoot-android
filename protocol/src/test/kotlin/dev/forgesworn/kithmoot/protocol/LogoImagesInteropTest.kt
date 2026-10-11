package dev.forgesworn.kithmoot.protocol

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LogoImagesInteropTest {
    private fun vectors(): JsonArray = Json.parseToJsonElement(
        requireNotNull(javaClass.getResourceAsStream("/logo-images-v1.json")).bufferedReader().use { it.readText() },
    ).jsonObject.getValue("vectors").jsonArray

    @Test fun `published logo reader vectors agree and retain every accepted field`() {
        val cases = vectors()
        assertEquals(28, cases.size)
        for (value in cases) {
            val entry = value.jsonObject; val label = entry.getValue("id").jsonPrimitive.content
            val payload = entry.getValue("payload"); val accepted = entry.getValue("accepted").jsonPrimitive.boolean
            val image = LogoImages.read(payload)
            assertEquals(label, accepted, image != null)
            if (accepted) {
                assertEquals(label, payload, requireNotNull(image).toJson())
                assertEquals(label, payload, LogoImages.create(requireNotNull(LogoImages.bytes(payload)), image.mime).toJson())
            } else assertNull(label, LogoImages.bytes(payload))
        }
    }

    @Test fun `JSON number spelling agrees with the JavaScript reader while quoted numbers are refused`() {
        val payload = vectors().first().jsonObject.getValue("payload").jsonObject
        val decimal = JsonObject(payload + mapOf("v" to Json.parseToJsonElement("1.0"), "width" to Json.parseToJsonElement("128e0")))
        assertEquals(payload, requireNotNull(LogoImages.read(decimal)).toJson())
        assertNull(LogoImages.read(JsonObject(payload + ("width" to JsonPrimitive("128")))))
    }

    @Test fun `decoding returns a fresh buffer without changing the validated image`() {
        val payload = vectors().first().jsonObject.getValue("payload")
        val first = requireNotNull(LogoImages.bytes(payload)); val original = first.clone()
        first.fill(0)
        assertArrayEquals(original, LogoImages.bytes(payload))
        assertEquals(payload, requireNotNull(LogoImages.read(payload)).toJson())
    }
}
