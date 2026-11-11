package dev.forgesworn.kithmoot.ui

import dev.forgesworn.kithmoot.protocol.carryRoomNameOp
import dev.forgesworn.kithmoot.protocol.encodeRoomNameOp
import dev.forgesworn.kithmoot.protocol.roomNameFromMessage
import kotlin.test.*

/** The name a device keeping a node room gives everybody, since its link carries none. */
class NodeRoomFirstNameTest {
    private val room = "d0559f3c" + "a".repeat(56)
    private val id = "0123456789abcdef0123456789abcdef"

    @Test fun `only a device keeping the room, with a name of its own, has one to give`() {
        assertNull(nodeRoomFirstName("Book club", room, keeps = false, startsAt = 1_000, id = id))
        assertNull(nodeRoomFirstName("Room d0559f3c", room, keeps = true, startsAt = 1_000, id = id), "the stored placeholder is no name")
        assertNull(nodeRoomFirstName(" ​ ", room, keeps = true, startsAt = 1_000, id = id))
        assertNull(nodeRoomFirstName("Book club", room, keeps = true, startsAt = 0, id = id))
    }

    @Test fun `it is stamped at the room's start, names nobody, and reads back as the room's name`() {
        val first = assertNotNull(nodeRoomFirstName("  Book‮ club ", room, keeps = true, startsAt = 1_000, id = id))
        assertEquals("Book club", first.name); assertEquals(1_000_000, first.at); assertNull(first.by)
        // What RoomWork posts, as a member three minutes in reads it.
        val read = assertNotNull(roomNameFromMessage(encodeRoomNameOp(carryRoomNameOp(first)), "c".repeat(64), 1_180))
        assertEquals("Book club", read.name); assertEquals(id, read.id); assertNull(read.by, "a carried copy shows nobody a rename")
    }
}
