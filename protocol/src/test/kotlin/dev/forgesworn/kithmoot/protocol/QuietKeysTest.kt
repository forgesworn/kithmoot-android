package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Schnorr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietKeysTest {

    private val roomKey = ByteArray(32) { 7 }
    private val ikm = DeadDrop.roomIkm(roomKey)
    private val a = Schnorr.publicKeyHex(ByteArray(32) { 1 })
    private val b = Schnorr.publicKeyHex(ByteArray(32) { 2 })
    private val now = 498216L * 3600 + 100

    @Test
    fun everyMembersKeysFromTheLookbackToOneEpochAheadAreMatchedByTag() {
        val keys = QuietKeys(lookbackEpochs = 2)
        keys.set(ikm, listOf(a, b))
        keys.refresh(now)
        assertEquals(2 * 4 * DeadDrop.MAX_PER_EPOCH_ROOM, keys.size())
        val e = DeadDrop.epochIndexAt(now)
        for (i in (e - 2)..(e + 1)) for (m in listOf(a, b)) for (k in 0 until DeadDrop.MAX_PER_EPOCH_ROOM) {
            val key = DeadDrop.deriveDropKey(ikm, i, m, k)
            val hit = keys.lookup(key.publicKey)
            assertNotNull(hit)
            assertEquals(m, hit!!.member)
            assertEquals(k, hit.key.counter)
        }
        assertNull(keys.lookup(DeadDrop.deriveDropKey(ikm, e - 3, a, 0).publicKey))
        assertNull(keys.lookup(DeadDrop.deriveDropKey(ikm, e + 2, a, 0).publicKey))
        // The epoch moves: the oldest falls out, one more comes in.
        keys.refresh(now + 3600)
        assertNull(keys.lookup(DeadDrop.deriveDropKey(ikm, e - 2, a, 0).publicKey))
        assertNotNull(keys.lookup(DeadDrop.deriveDropKey(ikm, e + 2, a, 0).publicKey))
    }

    @Test
    fun sendKeysAreDrawnFromTheRangeWithoutRepeatUntilItIsSpent() {
        val keys = QuietKeys(lookbackEpochs = 1)
        keys.set(ikm, listOf(a))
        val drawn = HashSet<Int>()
        repeat(8) { drawn.add(keys.sendKey(a, now, 0 until 8).counter) }
        assertEquals((0 until 8).toSet(), drawn)
        var exhausted = false
        try { keys.sendKey(a, now, 0 until 8) } catch (_: QuietKeys.EpochExhausted) { exhausted = true }
        assertTrue(exhausted)
        // The other half is untouched, and the next epoch is fresh.
        assertTrue(keys.sendKey(a, now, 8 until 16).counter in 8 until 16)
        assertTrue(keys.sendKey(a, now + 3600, 0 until 8).counter in 0 until 8)
    }

    @Test
    fun aCounterSeenOnTheWireIsSpentAndTheStateSurvivesARestartUnderTheSameKey() {
        val keys = QuietKeys(lookbackEpochs = 1)
        keys.set(ikm, listOf(a))
        val e = DeadDrop.epochIndexAt(now)
        keys.markUsed(a, e, 3, now)
        keys.markUsed(a, e + 1, 4, now)
        val used = keys.exportUsed().getValue(a)
        assertEquals(setOf(3), used.counters)
        val again = QuietKeys(lookbackEpochs = 1)
        again.set(ikm, listOf(a))
        again.importUsed(keys.exportUsed(), now)
        repeat(15) { again.sendKey(a, now) }
        var exhausted = false
        try { again.sendKey(a, now) } catch (_: QuietKeys.EpochExhausted) { exhausted = true }
        assertTrue(exhausted)
        // A new room key forgets the counters, since the keys are new.
        again.set(DeadDrop.roomIkm(ByteArray(32) { 9 }), listOf(a))
        assertTrue(again.exportUsed().isEmpty())
        repeat(16) { again.sendKey(a, now) }
    }

    @Test
    fun aRosterChangeKeepsThisMembersCountersAndDropsTheLeaver() {
        val keys = QuietKeys(lookbackEpochs = 1)
        keys.set(ikm, listOf(a, b))
        keys.refresh(now)
        keys.sendKey(a, now)
        keys.set(ikm, listOf(a))
        assertEquals(1, keys.exportUsed().getValue(a).counters.size)
        assertNull(keys.lookup(DeadDrop.deriveDropKey(ikm, DeadDrop.epochIndexAt(now), b, 0).publicKey))
        assertNotNull(keys.lookup(DeadDrop.deriveDropKey(ikm, DeadDrop.epochIndexAt(now), a, 0).publicKey))
    }
}
