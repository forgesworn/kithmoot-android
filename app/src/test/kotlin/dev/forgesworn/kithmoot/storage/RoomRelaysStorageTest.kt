package dev.forgesworn.kithmoot.storage

import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.session.PrimaryIdentity
import kotlinx.serialization.json.*
import kotlin.test.*

/** A saved room keeps its own relays apart from this device's, and a link never displaces a signed list. */
class RoomRelaysStorageTest {
    private val now = 1_800_000_000L
    private val own = listOf("wss://own.example")
    private val fixed = listOf("wss://room-a.example/", "wss://room-b.example/")
    private val base = "https://kithmoot.example/j/"

    private fun room(roomRelays: List<String> = emptyList(), signed: Boolean = false): SavedRoom {
        val secret = Entropy.bytes(32)
        val derived = deriveRoom(secret)
        val who = PrimaryIdentity.create(derived.roomId, now + 3600, now)
        val host = createRoomInvitation(true)
        return SavedRoom.create(secret, who, encodeInvitationUrl(base, host.invitation, own), own,
            "Room", now, host, host.invitation.canonicalInviter, roomRelays = roomRelays, roomRelaysSigned = signed)
    }

    @Test fun `the room's relays survive a save and a reopen, apart from this device's own`() {
        val disk = MemoryStorage()
        val saved = room(fixed, signed = true)
        RoomRepository(disk).save(saved)
        val restored = RoomRepository(disk).get(saved.id)!!
        assertEquals(fixed, restored.roomRelays)
        assertTrue(restored.roomRelaysSigned)
        assertEquals(own, restored.relays)
        assertEquals(fixed, restored.opened(now + 60).roomRelays)
    }

    @Test fun `a record from before room relays has none`() {
        val saved = room()
        assertFalse("fixedRelays" in saved.json)
        assertEquals(emptyList(), saved.roomRelays)
        assertFalse(saved.roomRelaysSigned)
        assertEquals(emptyList(), saved.sharedRelays)
    }

    @Test fun `a signed list replaces anything, an unsigned one only fills an empty slot`() {
        val linked = room().withRoomRelays(listOf("wss://link.example/"), signed = false)
        assertEquals(listOf("wss://link.example/"), linked.roomRelays)
        assertFalse(linked.roomRelaysSigned)
        assertEquals(listOf("wss://link.example/"), linked.withRoomRelays(listOf("wss://other.example/"), signed = false).roomRelays)
        val signed = linked.withRoomRelays(fixed, signed = true)
        assertEquals(fixed, signed.roomRelays)
        assertTrue(signed.roomRelaysSigned)
        assertEquals(fixed, signed.withRoomRelays(listOf("wss://link.example/"), signed = false).roomRelays)
        assertSame(signed, signed.withRoomRelays(emptyList(), signed = true))
    }

    @Test fun `the shared relays are the room's own, then its authority's record`() {
        val record = RoomRelaysRecord(listOf("wss://op.example/", "wss://room-a.example/"), 3, "ab".repeat(64))
        assertEquals(fixed + "wss://op.example/", room(fixed, signed = true).withRoomRelaysRecord(record).sharedRelays)
    }

    @Test fun `a later opening from a link keeps the signed relays and the authority's record`() {
        val record = RoomRelaysRecord(listOf("wss://op.example/"), 3, "ab".repeat(64))
        val previous = room(fixed, signed = true).withRoomRelaysRecord(record)
        val identity = previous.identity(now)
        val again = SavedRoom.create(previous.secret, identity, previous.joinUrl, own, "Room", now + 10, null, previous.authority,
            roomRelays = listOf("wss://stale.example/"))
        val kept = again.retainingHistory(previous)
        assertEquals(fixed, kept.roomRelays)
        assertTrue(kept.roomRelaysSigned)
        assertEquals(record, kept.roomRelayRecord)
        // A signed list learnt now beats an unsigned one held before.
        val unsigned = room(listOf("wss://link.example/"))
        val fresh = SavedRoom.create(unsigned.secret, unsigned.identity(now), unsigned.joinUrl, own, "Room", now + 10, null, unsigned.authority,
            roomRelays = fixed, roomRelaysSigned = true)
        assertEquals(fixed, fresh.retainingHistory(unsigned).roomRelays)
    }

    @Test fun `a malformed list is refused on load`() {
        val good = room(fixed, signed = true).json
        val bad = listOf(
            JsonArray(emptyList()),
            JsonArray((0..8).map { JsonPrimitive("wss://r$it.example/") }),
            JsonArray(listOf(JsonPrimitive("wss://room-a.example"))),
            JsonArray(listOf(JsonPrimitive("wss://room-a.example/"), JsonPrimitive("wss://room-a.example/"))),
            JsonPrimitive("wss://room-a.example/"),
        )
        for (value in bad) assertFails { SavedRoom.decode(JsonObject(good + ("fixedRelays" to value))) }
        assertFails { SavedRoom.decode(JsonObject(good - "fixedRelays")) }
        assertFails { SavedRoom.decode(JsonObject(good + ("fixedRelaysSigned" to JsonPrimitive("yes")))) }
    }
}
