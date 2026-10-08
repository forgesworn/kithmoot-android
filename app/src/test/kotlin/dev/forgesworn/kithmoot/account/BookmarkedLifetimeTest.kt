package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.session.PrimaryIdentity
import dev.forgesworn.kithmoot.storage.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class BookmarkedLifetimeTest {
    private val now = 1_800_000_000L
    private val ends = now + 3 * 3600
    private val secret = ByteArray(32) { 7 }
    private val relays = listOf("wss://relay.example")
    private val host = createRoomInvitation(true)
    private val link = encodeInvitationUrl("https://kithmoot.example/j/", host.invitation, relays)
    private val signer = object : ParticipantSigner by LocalSigner(ByteArray(32) { 9 }) { override val method = "nip55" }

    private suspend fun saved(): SavedRoom {
        val id = deriveRoom(secret).roomId
        val who = PrimaryIdentity.createWith(signer, id, now + 86_400, now)
        return SavedRoom.create(secret, who, link, relays, "Darky Praguy", now, null, host.invitation.canonicalInviter)
    }
    private fun bookmark() = AccountRoom(deriveRoom(secret).roomId, link, "Darky Praguy", now, secret.toHex(), ends, true, now - 86_400)

    @Test fun `a room already on the phone learns its Mac expiry and is due after reopening`() = runTest {
        val disk = MemoryStorage()
        val rooms = RoomRepository(disk)
        val saved = saved()
        rooms.save(saved)
        rooms.update(saved.id) { it.learnBookmarkLifetime(bookmark(), signer.pubkey) }
        val reopened = RoomRepository(disk).get(saved.id)!!
        assertEquals(ends, reopened.ends)
        assertTrue(reopened.destruct)
        assertEquals(now - 86_400, reopened.startsAt)
        assertFalse(reopened.ended(ends - 1))
        assertTrue(reopened.ended(ends))
        assertTrue(reopened.summary(now).destruct)
    }

    @Test fun `late stale bookmarks cannot remove destruction or extend the deadline`() = runTest {
        val learned = saved().learnBookmarkLifetime(bookmark(), signer.pubkey)
        val stale = learned.learnBookmarkLifetime(bookmark().copy(endsAt = ends + 86_400, destruct = false, startsAt = null), signer.pubkey)
        assertSame(learned, stale)
        assertSame(learned, learned.learnBookmarkLifetime(bookmark().copy(endsAt = null, destruct = false), signer.pubkey))
    }

    @Test fun `a different account room secret or invitation cannot change the phone copy`() = runTest {
        val saved = saved()
        assertSame(saved, saved.learnBookmarkLifetime(bookmark(), "b".repeat(64)))
        assertSame(saved, saved.learnBookmarkLifetime(bookmark().copy(roomId = "a".repeat(64)), signer.pubkey))
        assertSame(saved, saved.learnBookmarkLifetime(bookmark().copy(admission = "c".repeat(64)), signer.pubkey))
        val other = createRoomInvitation(true)
        assertSame(saved, saved.learnBookmarkLifetime(bookmark().copy(link = encodeInvitationUrl("https://kithmoot.example/j/", other.invitation, relays)), signer.pubkey))
    }
}
