package dev.forgesworn.kithmoot.vectors

import dev.forgesworn.kithmoot.protocol.ROOM_NAME_REKEY_GRACE_SECONDS
import dev.forgesworn.kithmoot.protocol.RoomNameBook
import dev.forgesworn.kithmoot.protocol.RoomNameRecord
import dev.forgesworn.kithmoot.protocol.compareRoomNames
import dev.forgesworn.kithmoot.protocol.decodeRoomNameOp
import dev.forgesworn.kithmoot.protocol.encodeRoomNameOp
import dev.forgesworn.kithmoot.protocol.roomNameFromMessage
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The `roomName` vectors this module can run without the chat codec: every
 * op and record read out of the frozen message text, the three order
 * vectors and the left-epoch cut. The events themselves are decoded and
 * rebuilt by the app's `MessageLayerVectorsTest`, where the chat codec lives.
 * See `docs/room-name.md` in the reference implementation.
 */
class RoomNameVectorsTest {
    private val group = Vectors.group("roomName")

    private fun recordJson(record: RoomNameRecord?): Any = record?.let {
        buildJsonObject {
            put("name", it.name); put("id", it.id); put("at", it.at)
            it.by?.let { by -> put("by", by) }
            put("sentAt", it.sentAt)
        }
    } ?: JsonNull

    private fun record(json: JsonObject) = RoomNameRecord(json.text("name"), json.text("id"), json.number("at"),
        json.textOrNull("by"), json["sentAt"]?.let { json.number("sentAt") } ?: Math.floorDiv(json.number("at"), 1000L))

    @Test fun `every frozen message reads as the op and record the reference reads`() {
        val events = group.filter { "event" in it.child("input") }
        assertEquals(11, events.size)
        for (vector in events) {
            val name = vector.text("name")
            val output = vector.child("output")
            if (output.isNull("message")) {
                assertNull("$name has no message, so no rename", output["record"]?.takeIf { it != JsonNull })
                continue
            }
            val message = output.child("message")
            val op = decodeRoomNameOp(message.text("text"))
            if (output.isNull("op")) assertNull("$name op must be refused", op)
            else {
                val decoded = op ?: error("$name op must decode")
                val expected = output.child("op")
                assertEquals("$name op re-encodes as frozen", expected.toString(), kotlinx.serialization.json.Json.parseToJsonElement(encodeRoomNameOp(decoded)).jsonObject.toString())
            }
            val record = roomNameFromMessage(message.text("text"), message.text("participant"), message.number("sentAt"))
            assertEquals("$name record", output["record"].toString(), recordJson(record).toString())
            if (vector.text("kind") == "negative") assertNull(name, record)
        }
    }

    @Test fun `renames order as frozen`() {
        val orderVectors = group.filter { "records" in it.child("input") }
        assertEquals(3, orderVectors.size)
        for (vector in orderVectors) {
            val records = vector.child("input").list("records").map { record(it.jsonObject) }
            val sorted = records.sortedWith(::compareRoomNames)
            assertEquals(vector.text("name"), vector.child("output").strings("order"), sorted.map { it.name })
            assertEquals(vector.text("name"), vector.child("output").text("winner"), sorted.last().name)
        }
    }

    @Test fun `a rename stamped after the rekey that left its epoch is discounted`() {
        val vector = group.single { it.text("name") == "left-epoch-cut" }
        val input = vector.child("input")
        assertEquals(ROOM_NAME_REKEY_GRACE_SECONDS, input.number("graceSeconds"))
        val book = RoomNameBook()
        for (entry in input.list("entries")) {
            val e = entry.jsonObject
            book.add(record(e.child("record")), e.number("epoch").toInt())
        }
        val rekeyedAt = input.child("rekeyedAt").mapKeys { it.key.toInt() }.mapValues { (_, v) -> v.toString().toLong() }
        assertEquals(vector.child("output").textOrNull("inEpoch0"), book.current(0)?.name)
        assertEquals(vector.child("output").textOrNull("inEpoch1"), book.current(1) { rekeyedAt[it] }?.name)
    }
}
