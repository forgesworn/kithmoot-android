package dev.forgesworn.kithmoot.protocol

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Real local daemon/runtime relay events, with their recorded evaluation clocks. */
class BothyDaemonDiscoveryTest {
    @Test fun actualDaemonAndTlsRuntimeEventsPreserveIdentityAcrossRestart() {
        val stream = requireNotNull(javaClass.getResourceAsStream("/bothy-daemon-discovery.json"))
        val evidence = Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
        val cases = evidence.getValue("cases").jsonArray.map { it.jsonObject }
        assertEquals(4, cases.size)
        val p = cases.first().getValue("pin").jsonObject
        var pin = BoxPin(p.getValue("p").jsonPrimitive.content, p.getValue("claim").jsonPrimitive.content,
            p.getValue("nodeId").jsonPrimitive.content, p.getValue("highestSerial").jsonPrimitive.long,
            p.getValue("card").jsonPrimitive.content)
        for (c in cases) {
            val name = c.getValue("name").jsonPrimitive.content
            val expected = c.getValue("expect").jsonObject
            val result = BoxStatuses.read(NostrEvent.fromJson(c.getValue("status")), NostrEvent.fromJson(c.getValue("claim")), pin, c.getValue("now").jsonPrimitive.long)
            assertEquals("$name: $result", expected.getValue("ok").jsonPrimitive.boolean, result is BoxStatusResult.Ok)
            if (result is BoxStatusResult.Refused) {
                assertEquals(name, "Link card: relay hint url", result.reason)
                continue
            }
            val status = (result as BoxStatusResult.Ok).status
            assertFalse(name, status.drops)
            assertNull(name, status.dropsUrl)
            assertEquals(name, expected.getValue("validUntil").jsonPrimitive.long, status.validUntil)
            pin = pin.copy(highestSerial = status.link.serial, card = status.card,
                statusCreatedAt = status.createdAt, statusId = status.id)
        }
        val before = cases[2]; val after = cases[3]
        assertTrue("The earlier valid TLS status cannot roll the watermark back",
            BoxStatuses.read(NostrEvent.fromJson(before.getValue("status")), NostrEvent.fromJson(before.getValue("claim")), pin, after.getValue("now").jsonPrimitive.long) is BoxStatusResult.Refused)
    }
}
