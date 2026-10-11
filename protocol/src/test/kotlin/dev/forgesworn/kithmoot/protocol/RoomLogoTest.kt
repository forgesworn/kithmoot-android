package dev.forgesworn.kithmoot.protocol

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RoomLogoTest {
    private val image = requireNotNull(LogoImages.read(Json.parseToJsonElement(
        requireNotNull(javaClass.getResourceAsStream("/logo-images-v1.json")).bufferedReader().use { it.readText() },
    ).jsonObject.getValue("vectors").jsonArray.first().jsonObject.getValue("payload")))
    private val id = "a".repeat(32)
    private val who = "b".repeat(64)
    private val at = 1_800_000_000_000L
    private fun op(logo: LogoImage? = image, time: Long = at, carried: Boolean = false) =
        roomLogoOp(logo, time, id).copy(carried = carried)
    private fun record(logo: LogoImage? = image, time: Long = at, sent: Long = time / 1000) =
        RoomLogoRecord(logo, id, time, sent, who)

    @Test fun `accepted operations retain every field when reencoded`() {
        for (change in listOf(op(), op(null), op(carried = true))) {
            val encoded = encodeRoomLogoOp(change)
            assertEquals(Json.parseToJsonElement(encoded), Json.parseToJsonElement(encodeRoomLogoOp(requireNotNull(decodeRoomLogoOp(encoded)))))
        }
        val decimal = encodeRoomLogoOp(op()).replace(at.toString(), "${at}.0")
        assertEquals(op(), decodeRoomLogoOp(decimal))
    }

    @Test fun `missing hash unsafe time extra fields and false carriage are refused`() {
        val valid = Json.parseToJsonElement(encodeRoomLogoOp(op())).jsonObject
        val invalid = listOf(
            JsonObject(valid - "sha256"), JsonObject(valid + ("url" to JsonPrimitive("https://example.invalid/logo"))),
            JsonObject(valid + ("carried" to JsonPrimitive(false))), JsonObject(valid + ("carried" to JsonNull)),
            JsonObject(valid + ("at" to JsonPrimitive(at.toString()))), JsonObject(valid + ("at" to JsonPrimitive(0))),
            JsonObject(valid + ("at" to JsonPrimitive(9_007_199_254_740_992L))),
            JsonObject(valid + ("id" to JsonPrimitive(id.uppercase()))),
            JsonObject(valid + ("sha256" to JsonPrimitive(image.sha256.uppercase()))),
        )
        for (value in invalid) assertNull(value.toString(), decodeRoomLogoOp(value.toString()))
    }

    @Test fun `image and removal require matching control operation and explicit payload`() {
        val body = encodeRoomLogoOp(op())
        assertEquals(image.toJson(), requireNotNull(roomLogoPayload(body, image.toJson(), "control")).image?.toJson())
        for (channel in listOf(null, "chat", "assignment")) assertNull(roomLogoPayload(body, image.toJson(), channel))
        assertNull(roomLogoPayload(body, JsonNull, "control"))
        assertNull(roomLogoPayload(body, null, "control"))
        val wrongHash = body.replace(image.sha256, "0".repeat(64))
        assertNull(roomLogoPayload(wrongHash, image.toJson(), "control"))
        val removal = encodeRoomLogoOp(op(null))
        assertNotNull(roomLogoPayload(removal, JsonNull, "control"))
        assertNull(requireNotNull(roomLogoPayload(removal, JsonNull, "control")).image)
        assertNull(roomLogoPayload(removal, null, "control"))
        assertNull(roomLogoPayload(removal, image.toJson(), "control"))
    }

    @Test fun `future order keys cannot pin the logo and carried copies do not claim authorship`() {
        val body = encodeRoomLogoOp(op())
        assertNull(roomLogoFromMessage(body, image.toJson(), "control", who, at / 1000 - 1))
        assertEquals(who, roomLogoFromMessage(body, image.toJson(), "control", who, at / 1000)?.by)
        val carried = encodeRoomLogoOp(op(carried = true))
        val copy = requireNotNull(roomLogoFromMessage(carried, image.toJson(), "control", who, at / 1000 + 10))
        assertNull(copy.by)
        assertEquals(at, copy.at)
        assertEquals(at / 1000 + 10, copy.sentAt)
    }

    @Test fun `a cached seed is not a current epoch relay copy and removals are carried`() {
        val book = RoomLogoBook(); val removed = record(null)
        book.seed(removed, 2)
        assertNull(book.current(1))
        assertEquals(removed, book.current(2))
        assertEquals(removed, book.carryDue(2, at / 1000))
        assertTrue(book.add(removed, 2))
        assertFalse(book.add(removed.copy(by = "c".repeat(64)), 2))
        assertNull(book.carryDue(2, at / 1000))
        assertEquals(removed, book.carryDue(3, at / 1000))
        assertEquals(removed, book.carryDue(2, at / 1000 + ROOM_NAME_REPOST_SECONDS + 1))
    }

    @Test fun `writes from removed epochs after grace lose to a permitted older logo`() {
        val book = RoomLogoBook(); val original = record()
        book.add(original, 0)
        val rekey = at / 1000 + 1
        val late = record(null, (rekey + ROOM_NAME_REKEY_GRACE_SECONDS + 1) * 1000)
        book.add(late, 0)
        assertEquals(late, book.current(0))
        assertEquals(original, book.current(1) { rekey })
        val permitted = record(null, (rekey + ROOM_NAME_REKEY_GRACE_SECONDS) * 1000)
        book.add(permitted, 0)
        assertEquals(permitted, book.current(1) { rekey })
        assertEquals(permitted, book.carryDue(1, permitted.sentAt) { rekey })
    }

    @Test fun `bad cache records never become a source`() {
        val book = RoomLogoBook()
        book.seed(record().copy(id = "bad"))
        book.seed(record().copy(at = 0))
        book.seed(record().copy(sentAt = at / 1000 - 1))
        book.seed(record(), -1)
        assertNull(book.current(0))
    }
}
