package dev.forgesworn.kithmoot.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class BoxStatusTest {
    private val vectors: JsonObject by lazy {
        val stream = requireNotNull(javaClass.getResourceAsStream("/box-discovery.json"))
        Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
    }
    private fun pin(p: JsonObject) = BoxPin(
        p.getValue("p").jsonPrimitive.content, p.getValue("claim").jsonPrimitive.content,
        p.getValue("nodeId").jsonPrimitive.content, p.getValue("highestSerial").jsonPrimitive.long,
        p["card"]?.jsonPrimitive?.contentOrNull, p["statusCreatedAt"]?.jsonPrimitive?.long,
        p["statusId"]?.jsonPrimitive?.contentOrNull,
    )
    @Test fun signedStatusVectorsAgreeWithTheBrowser() {
        val cases = vectors.getValue("cases").jsonArray
        assertEquals(34, cases.size)
        for (raw in cases) {
            val c = raw.jsonObject; val name = c.getValue("name").jsonPrimitive.content
            val expected = c.getValue("expect").jsonObject
            val r = BoxStatuses.read(NostrEvent.fromJson(c.getValue("status")), NostrEvent.fromJson(c.getValue("claim")), pin(c.getValue("pin").jsonObject), c.getValue("now").jsonPrimitive.long)
            assertEquals("$name: $r", expected.getValue("ok").jsonPrimitive.boolean, r is BoxStatusResult.Ok)
            if (r is BoxStatusResult.Ok) {
                assertEquals(name, expected.getValue("drops").jsonPrimitive.boolean, r.status.drops)
                assertEquals(name, expected["dropsUrl"]?.jsonPrimitive?.contentOrNull, r.status.dropsUrl)
                assertEquals(name, expected.getValue("validUntil").jsonPrimitive.long, r.status.validUntil)
                assertFalse(name, r.status.dropsUrl in r.status.link.relays)
            }
        }
    }
    @Test fun signedClaimVectorsAgreeWithTheBrowser() {
        val cases = vectors.getValue("claims").jsonArray
        assertEquals(8, cases.size)
        for (raw in cases) {
            val c = raw.jsonObject
            val r = BoxStatuses.readClaim(NostrEvent.fromJson(c.getValue("event")), c.getValue("now").jsonPrimitive.long)
            assertEquals(c.getValue("name").jsonPrimitive.content + ": $r", c.getValue("expect").jsonObject.getValue("ok").jsonPrimitive.boolean, r is BoxClaimResult.Ok)
        }
    }
    @Test fun everySingleByteLinkMutationFailsEvenWhenTheBoxSignsIt() {
        val c = vectors.getValue("cases").jsonArray.first().jsonObject
        val status = NostrEvent.fromJson(c.getValue("status")); val claim = NostrEvent.fromJson(c.getValue("claim"))
        val pin = pin(c.getValue("pin").jsonObject); val now = c.getValue("now").jsonPrimitive.long
        val bytes = Base64.getDecoder().decode(status.tagValue("card"))
        for (i in bytes.indices) {
            val changed = bytes.clone(); changed[i] = (changed[i].toInt() xor 1).toByte()
            val tags = status.tags.map { if (it[0] == "card") listOf("card", Base64.getEncoder().encodeToString(changed)) else it }
            val signed = Events.sign(ByteArray(32) { 1 }, status.kind, status.createdAt, tags, status.content)
            assertTrue("Link byte $i", BoxStatuses.read(signed, claim, pin, now) is BoxStatusResult.Refused)
        }
    }
    @Test fun oversizedMalformedAndOverflowInputsReturnTheirOwnRefusal() {
        val c = vectors.getValue("cases").jsonArray.first().jsonObject
        val status = NostrEvent.fromJson(c.getValue("status")); val claim = NostrEvent.fromJson(c.getValue("claim"))
        val pin = pin(c.getValue("pin").jsonObject); val now = c.getValue("now").jsonPrimitive.long
        for (bad in listOf(status.copy(tags = listOf(emptyList())), status.copy(tags = List(129) { listOf("x", "y") }), status.copy(content = "x".repeat(32769)), status.copy(createdAt = Long.MAX_VALUE), status.copy(sig = "z"))) {
            assertTrue(BoxStatuses.read(bad, claim, pin, now) is BoxStatusResult.Refused)
        }
        assertTrue(BoxStatuses.read(status, claim, pin, Long.MAX_VALUE) is BoxStatusResult.Refused)
        assertTrue(BoxStatuses.read(status, claim, pin.copy(highestSerial = Long.MAX_VALUE), now) is BoxStatusResult.Refused)
    }
}
