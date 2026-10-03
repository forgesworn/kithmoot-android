package dev.forgesworn.kithmoot.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomNameTest {
    private val id = "0123456789abcdef0123456789abcdef"
    private fun op(fields: String) = decodeRoomNameOp("{\"op\":\"name\",$fields}")

    @Test fun `a rename encodes in the reference's key order and reads back`() {
        val op = RoomNameOp("Book club", id, 1_800_000_100_250)
        assertEquals("{\"op\":\"name\",\"name\":\"Book club\",\"id\":\"$id\",\"at\":1800000100250}", encodeRoomNameOp(op))
        assertEquals(op, decodeRoomNameOp(encodeRoomNameOp(op)))
        val carried = op.copy(carried = true)
        assertEquals("{\"op\":\"name\",\"name\":\"Book club\",\"id\":\"$id\",\"at\":1800000100250,\"carried\":true}", encodeRoomNameOp(carried))
        assertEquals(carried, decodeRoomNameOp(encodeRoomNameOp(carried)))
    }

    @Test fun `the codec refuses what the reference refuses`() {
        assertNull("other ops", decodeRoomNameOp("{\"op\":\"relays\"}"))
        assertNull("not JSON", decodeRoomNameOp("Book club"))
        assertNull("33 code points", op("\"name\":\"${"x".repeat(33)}\",\"id\":\"$id\",\"at\":1"))
        assertNotNull("32 astral code points are 32, not 64", op("\"name\":\"${"😀".repeat(32)}\",\"id\":\"$id\",\"at\":1"))
        assertNull("nothing left after sanitising", op("\"name\":\" \\u200b \",\"id\":\"$id\",\"at\":1"))
        assertNull("not a string", op("\"name\":7,\"id\":\"$id\",\"at\":1"))
        assertNull("upper-case id", op("\"name\":\"R\",\"id\":\"${id.uppercase()}\",\"at\":1"))
        assertNull("short id", op("\"name\":\"R\",\"id\":\"abc\",\"at\":1"))
        assertNull("zero at", op("\"name\":\"R\",\"id\":\"$id\",\"at\":0"))
        assertNull("negative at", op("\"name\":\"R\",\"id\":\"$id\",\"at\":-5"))
        assertNull("fractional at", op("\"name\":\"R\",\"id\":\"$id\",\"at\":1.5"))
        assertNull("string at", op("\"name\":\"R\",\"id\":\"$id\",\"at\":\"1\""))
        assertNull("unsafe at", op("\"name\":\"R\",\"id\":\"$id\",\"at\":9007199254740992"))
        assertNotNull("largest safe at", op("\"name\":\"R\",\"id\":\"$id\",\"at\":9007199254740991"))
        assertNull("carried false", op("\"name\":\"R\",\"id\":\"$id\",\"at\":1,\"carried\":false"))
        assertNull("carried as a string", op("\"name\":\"R\",\"id\":\"$id\",\"at\":1,\"carried\":\"true\""))
        assertNull("carried as null", op("\"name\":\"R\",\"id\":\"$id\",\"at\":1,\"carried\":null"))
        assertEquals("sanitised, not refused", "Book club night", op("\"name\":\"Book\\u202e club\\n\\u200bnight\",\"id\":\"$id\",\"at\":1")?.name)
    }

    @Test fun `a rename may not be stamped after the message carrying it`() {
        val body = encodeRoomNameOp(RoomNameOp("Pinned", id, 1_800_000_160_000))
        assertNull(roomNameFromMessage(body, "p", 1_800_000_100))
        assertEquals("p", roomNameFromMessage(body, "p", 1_800_000_160)?.by)
        assertNull("a carried copy names nobody", roomNameFromMessage(encodeRoomNameOp(RoomNameOp("Pinned", id, 1_000, carried = true)), "p", 1)?.by)
    }

    @Test fun `a fresh rename sanitises and cuts what was typed`() {
        val op = renameRoomOp("  ${"y".repeat(40)}  ", 5)
        assertEquals("y".repeat(32), op.name)
        assertTrue(op.id.matches(Regex("[0-9a-f]{32}")))
        assertThrows(IllegalArgumentException::class.java) { renameRoomOp(" ​ ", 5) }
        assertThrows(IllegalArgumentException::class.java) { renameRoomOp("Room", 0) }
    }

    @Test fun `newest wins, then id, then name`() {
        fun r(name: String, id: String, at: Long) = RoomNameRecord(name, id, at, sentAt = at / 1000)
        val book = RoomNameBook()
        book.add(r("Earlier", "f".repeat(32), 2_000), 0)
        book.add(r("Later", "0".repeat(32), 2_001), 0)
        assertEquals("Later", book.current(0)?.name)
        book.add(r("Same time, greater id", "1".repeat(32), 2_001), 0)
        assertEquals("Same time, greater id", book.current(0)?.name)
        assertEquals(1, compareRoomNames(r("Beta", id, 1), r("Alpha", id, 1)))
    }

    @Test fun `renames from a later epoch do not count yet, and a kept one seeds the book`() {
        val book = RoomNameBook()
        book.seed("Kept", id, 1_000)
        assertEquals("Kept", book.current(0)?.name)
        book.add(RoomNameRecord("Ahead", "f".repeat(32), 9_000, "p", 9), 2)
        assertEquals("Kept", book.current(1)?.name)
        assertEquals("Ahead", book.current(2)?.name)
    }

    @Test fun `a carry is due without a fresh copy in this epoch's log`() {
        val book = RoomNameBook()
        val record = RoomNameRecord("Book club", id, 1_000_000, "p", 1_000)
        book.add(record, 0)
        assertNull("a fresh copy in this epoch", book.carryDue(0, now = 2_000))
        assertEquals("never posted in epoch 1", record, book.carryDue(1, now = 2_000))
        assertEquals("only an old copy", record, book.carryDue(0, now = 1_001 + ROOM_NAME_REPOST_SECONDS))
        book.add(record.copy(by = null, sentAt = 2_000), 1)
        assertNull("carried into epoch 1", book.carryDue(1, now = 2_000))
        val kept = RoomNameBook().apply { seed("Kept", id, 1_000_000) }
        assertEquals("a kept name with no copy anywhere", "Kept", kept.carryDue(0, now = 2_000)?.name)
        assertNull("nothing to carry", RoomNameBook().carryDue(0, now = 2_000))
    }
}
