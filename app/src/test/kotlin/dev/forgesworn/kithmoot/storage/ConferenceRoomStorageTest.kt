package dev.forgesworn.kithmoot.storage

import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.session.PrimaryIdentity
import dev.forgesworn.kithmoot.session.conferenceEndedMessage
import kotlinx.serialization.json.*
import kotlin.test.*

/** A conference room remembers its end across visits, and stops at it. */
class ConferenceRoomStorageTest {
    private val now = 1_800_000_000L
    private val ends = now + 3 * 24 * 60 * 60
    private val relays = listOf("wss://relay.example")
    private val base = "https://kithmoot.example/j/"

    private fun room(ends: Long? = this.ends, persistent: Boolean = true): SavedRoom {
        val secret = Entropy.bytes(32)
        val derived = deriveRoom(secret)
        val who = PrimaryIdentity.create(derived.roomId, now + 3600, now)
        val host = createRoomInvitation(persistent)
        return SavedRoom.create(secret, who, encodeInvitationUrl(base, host.invitation, relays), relays,
            "Conference", now, host, host.invitation.canonicalInviter, ends = ends)
    }

    @Test fun `the end survives a save and a reopen`() {
        val disk = MemoryStorage()
        val saved = room()
        RoomRepository(disk).save(saved)
        val restored = RoomRepository(disk).get(saved.id)!!
        assertEquals(ends, restored.ends)
        assertEquals(ends, restored.opened(now + 60).ends)
        assertEquals(ends, restored.summary(now).endsAt)
    }

    @Test fun `a record from before conference rooms has no end`() {
        val saved = room(ends = null)
        assertFalse("ends" in saved.json)
        assertNull(saved.ends)
        assertFalse(saved.ended(Long.MAX_VALUE))
        assertFalse(saved.summary(now + 365L * 24 * 60 * 60).ended)
    }

    @Test fun `before its end the room opens as usual`() {
        val saved = room()
        assertFalse(saved.summary(ends - 1).ended)
        assertTrue(saved.summary(ends - 1).canShareInvite)
        assertNotNull(saved.host(ends - 1))
        assertEquals(saved.id, saved.identity(ends - 1).credential.tagValue("d"))
        assertNotNull(saved.headlessSigning(ends - 1))
    }

    @Test fun `at its end the room is ended and refuses to open, sign or share`() {
        val saved = room()
        assertTrue(saved.ended(ends))
        assertTrue(saved.summary(ends).ended)
        assertFalse(saved.summary(ends).canShareInvite)
        assertNull(saved.host(ends))
        assertNull(saved.headlessSigning(ends))
        val refused = assertFailsWith<RoomRecoveryException> { saved.identity(ends) }
        assertEquals(conferenceEndedMessage(ends), refused.message)
    }

    @Test fun `a malformed end is refused on load`() {
        val good = room().json
        for (bad in listOf(JsonPrimitive("$ends"), JsonPrimitive(0), JsonPrimitive(-1), JsonPrimitive(true))) {
            assertFails { SavedRoom.decode(JsonObject(good + ("ends" to bad))) }
        }
        assertFails { room(persistent = false) }
    }

    @Test fun `a later opening that did not learn the end keeps it`() {
        val previous = room()
        val identity = previous.identity(now)
        val again = SavedRoom.create(previous.secret, identity, previous.joinUrl, relays, "Conference", now + 10, null, previous.authority)
        assertNull(again.ends)
        assertEquals(ends, again.retainingHistory(previous).ends)
    }
}
