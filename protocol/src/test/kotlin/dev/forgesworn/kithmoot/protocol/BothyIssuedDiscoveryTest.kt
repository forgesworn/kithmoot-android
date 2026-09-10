package dev.forgesworn.kithmoot.protocol

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Generated with Bothy's production builders, issuer and real Link verifier.
 * These cases do not claim live public-relay or physical-device acceptance. */
class BothyIssuedDiscoveryTest {
    @Test fun nativeClientReadsTheExactBothyProducedCases() {
        val stream = requireNotNull(javaClass.getResourceAsStream("/bothy-issued-discovery.json"))
        val fixture = Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
        val cases = fixture.getValue("cases").jsonArray
        assertEquals(8, cases.size)
        for (raw in cases) {
            val c = raw.jsonObject; val name = c.getValue("name").jsonPrimitive.content
            val expected = c.getValue("expect").jsonObject
            val bothy = c.getValue("bothy").jsonObject
            val ok = expected.getValue("ok").jsonPrimitive.boolean
            assertEquals(name, bothy.getValue("accepted").jsonPrimitive.boolean && !bothy.getValue("stale").jsonPrimitive.boolean, ok)
            val p = c.getValue("pin").jsonObject
            val pin = BoxPin(p.getValue("p").jsonPrimitive.content, p.getValue("claim").jsonPrimitive.content,
                p.getValue("nodeId").jsonPrimitive.content, p.getValue("highestSerial").jsonPrimitive.long,
                p.getValue("card").jsonPrimitive.content)
            val result = BoxStatuses.read(NostrEvent.fromJson(c.getValue("status")), NostrEvent.fromJson(c.getValue("claim")), pin, c.getValue("now").jsonPrimitive.long)
            assertEquals("$name: $result", ok, result is BoxStatusResult.Ok)
            if (result is BoxStatusResult.Ok) {
                assertEquals(name, expected.getValue("drops").jsonPrimitive.boolean, result.status.drops)
                assertEquals(name, expected["dropsUrl"]?.jsonPrimitive?.contentOrNull, result.status.dropsUrl)
                assertEquals(name, expected.getValue("validUntil").jsonPrimitive.long, result.status.validUntil)
            }
        }
    }
}
