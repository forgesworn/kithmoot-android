package dev.forgesworn.kithmoot.storage

import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.session.PrimaryIdentity
import kotlinx.serialization.json.*
import kotlin.test.*

/** A saved room keeps the shared name its members chose, ahead of the name a link carries. */
class SharedRoomNameStorageTest {
    private val now = 1_800_000_000L
    private val relays = listOf("wss://own.example")
    private val secret = Entropy.bytes(32)
    private val who = PrimaryIdentity.create(deriveRoom(secret).roomId, now + 3600, now)
    private val host = createRoomInvitation(true)
    private val link = encodeInvitationUrl("https://kithmoot.example/j/", host.invitation, relays)
    private fun room(name: String) = SavedRoom.create(secret, who, link, relays, name, now, host, host.invitation.canonicalInviter)
    private val rename = RoomNameRecord("Book club", "0123456789abcdef0123456789abcdef", 1_800_000_100_250, "p", 1_800_000_100)

    @Test fun `the shared name is kept with its order key and survives a save`() {
        val disk = MemoryStorage()
        val renamed = room("Old name").withSharedName(rename)
        assertEquals("Book club", renamed.name)
        RoomRepository(disk).save(renamed)
        val restored = RoomRepository(disk).get(renamed.id)!!
        assertEquals("Book club", restored.name)
        assertEquals(RoomNameRecord("Book club", rename.id, rename.at, sentAt = 1_800_000_100), restored.sharedName)
        assertFalse("by" in restored.json.getValue("sharedName").jsonObject, "who renamed is not kept")
    }

    @Test fun `opening from an older link keeps the members' name`() {
        val previous = room("Old name").withSharedName(rename)
        val reopened = room("Name on the link").retainingHistory(previous)
        assertEquals("Book club", reopened.name)
        assertEquals(rename.id, reopened.sharedName?.id)
        assertNull(room("Plain").sharedName)
    }

    @Test fun `a hostile kept name does not load`() {
        val bad = JsonObject(room("Room").json + ("sharedName" to buildJsonObject { put("name", "Bad\nname"); put("id", rename.id); put("at", 1) }))
        assertFailsWith<IllegalArgumentException> { SavedRoom.decode(bad) }
    }
}
