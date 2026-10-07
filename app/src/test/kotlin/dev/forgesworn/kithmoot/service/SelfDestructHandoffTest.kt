package dev.forgesworn.kithmoot.service

import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.protocol.createRoomInvitation
import dev.forgesworn.kithmoot.protocol.deriveRoom
import dev.forgesworn.kithmoot.protocol.encodeInvitationUrl
import dev.forgesworn.kithmoot.session.PrimaryIdentity
import dev.forgesworn.kithmoot.storage.MemoryStorage
import dev.forgesworn.kithmoot.storage.RoomRepository
import dev.forgesworn.kithmoot.storage.SavedRoom
import dev.forgesworn.kithmoot.ui.wipesOnBookmarkTombstone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the app and the background service hand each other for a self-destructing room. */
class SelfDestructHandoffTest {
    private val now = 1_800_000_000L
    private val relays = listOf("wss://relay.example")

    private fun room(ends: Long? = now + 7 * 86_400, destruct: Boolean = true, at: Long = now): SavedRoom {
        val secret = Entropy.bytes(32)
        val derived = deriveRoom(secret)
        val who = PrimaryIdentity.create(derived.roomId, at + 3600, at)
        val host = createRoomInvitation(true)
        return SavedRoom.create(secret, who, encodeInvitationUrl("https://kithmoot.example/j/", host.invitation, relays), relays,
            "Dark Prague", at, host, host.invitation.canonicalInviter, ends = ends, destruct = destruct)
    }

    @Test fun `a bookmark tombstone wipes only a self-destructing room, and leaves any other as it was`() {
        assertTrue(wipesOnBookmarkTombstone(room()))
        assertTrue(wipesOnBookmarkTombstone(room(ends = null)))
        assertFalse(wipesOnBookmarkTombstone(room(destruct = false)))
        assertFalse(wipesOnBookmarkTombstone(room(ends = null, destruct = false)))
        assertFalse(wipesOnBookmarkTombstone(null))
    }

    @Test fun `the heads-up is for a self-destructing room in red, and nothing else`() {
        val ends = now + 7 * 86_400
        val repo = RoomRepository(MemoryStorage())
        val red = room(ends = ends)
        val kept = room(ends = ends, destruct = false)
        val noEnd = room(ends = null)
        listOf(red, kept, noEnd).forEach(repo::save)
        val inRed = ends - 1_800
        assertEquals(listOf(red.id), destructHeadsUpsDue(repo.list(), inRed).map { it.id })
        assertEquals(emptyList(), destructHeadsUpsDue(repo.list(), ends - 2 * 3_600).map { it.id })
        assertEquals(emptyList(), destructHeadsUpsDue(repo.list(), ends - 30).map { it.id })
    }

    @Test fun `the heads-up is claimed once, whoever asks first, and the claim outlives a restart`() {
        val disk = MemoryStorage()
        val repo = RoomRepository(disk)
        val doomed = room()
        repo.save(doomed)
        // The app and the background service asking at the same moment: one wins.
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val wins = java.util.concurrent.atomic.AtomicInteger()
        repeat(8) { pool.execute { start.await(); if (claimDestructHeadsUp(repo, doomed.id)) wins.incrementAndGet() } }
        start.countDown(); pool.shutdown(); pool.awaitTermination(10, TimeUnit.SECONDS)
        assertEquals(1, wins.get())
        assertFalse(claimDestructHeadsUp(RoomRepository(disk), doomed.id))
        assertFalse(claimDestructHeadsUp(repo, "f".repeat(64)))
    }
}
