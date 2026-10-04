package dev.forgesworn.kithmoot.epoch

import dev.forgesworn.kithmoot.storage.MemoryStorage
import dev.forgesworn.kithmoot.storage.RoomStorage
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Who each room knows, for the known-members gate (kithmoot#207). */
class RoomMembersTest {
    private val room = "ab".repeat(32)
    private val other = "cd".repeat(32)
    private val ann = "11".repeat(32)
    private val bob = "22".repeat(32)

    @Test fun `the authority's list and whoever was let in are known, per room, across a restart`() {
        val storage = MemoryStorage()
        val members = RoomMembers(storage)
        assertFalse(members.knows(room, ann))
        members.setMembers(room, listOf(ann.uppercase()))
        members.letIn(room, bob)
        assertTrue(members.knows(room, ann))
        assertTrue(members.knows(room, bob.uppercase()))
        assertFalse(members.knows(other, ann), "another room's list is its own")

        val reopened = RoomMembers(storage)
        assertTrue(reopened.knows(room, ann))
        assertTrue(reopened.knows(room, bob))

        // A newer list replaces the old one; whoever was let in here stays let in.
        reopened.setMembers(room, listOf(bob))
        assertFalse(reopened.knows(room, ann))
        assertTrue(reopened.knows(room, bob))
    }

    @Test fun `an unreadable store knows nobody, which is the safe way to be wrong`() {
        val storage = MemoryStorage()
        storage.write("not json".toByteArray())
        assertFalse(RoomMembers(storage).knows(room, ann))
        val failing = object : RoomStorage {
            override fun read(): ByteArray? = throw IllegalStateException("locked")
            override fun write(value: ByteArray) = throw IllegalStateException("locked")
            override fun reset() = Unit
        }
        val members = RoomMembers(failing)
        assertFalse(members.knows(room, ann))
        // Kept for this process even when it cannot be written.
        members.letIn(room, ann)
        assertTrue(members.knows(room, ann))
    }

    @Test fun `forgetting rooms forgets who they know`() {
        val storage = MemoryStorage()
        val members = RoomMembers(storage)
        members.setMembers(room, listOf(ann))
        members.setMembers(other, listOf(bob))
        members.retainOnly { setOf(other) }
        assertFalse(RoomMembers(storage).knows(room, ann))
        assertTrue(RoomMembers(storage).knows(other, bob))
        members.reset()
        assertFalse(members.knows(other, bob))
        assertFalse(RoomMembers(storage).knows(other, bob))
    }
}
