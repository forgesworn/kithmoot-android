package dev.forgesworn.kithmoot.mls

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A VMLS room's states and its removal grace (P3-03b-3 decisions 19, 20 and 22). */
class VmlsRoomTest {
    private val now = 1_900_000_000L
    private val leaf = "11".repeat(32)
    private val other = "22".repeat(32)
    private val member = VmlsRoomMember(leaf, "33".repeat(32), "44".repeat(32), pending = true)

    private fun keeper() = VmlsRoom("aa".repeat(32), "bb".repeat(32), "Kitchen", "cc".repeat(32), VmlsRole.KEEPER, joined = true)
        .seed(Phase.Active, 3, listOf(member))
    private fun guest() = VmlsRoom("aa".repeat(32), "bb".repeat(32), "Kitchen", "cc".repeat(32), VmlsRole.GUEST, joined = false)

    private fun VmlsRoom.on(at: Long, vararg signals: RoomSignal) = apply(signals.toList(), at).room

    @Test fun `a guest joins from pending, and the member list follows the engine`() {
        var room = guest().seed(Phase.PendingJoin, null, emptyList())
        assertEquals(RoomStatus.Joining, room.status)
        assertFalse(room.canSend)
        room = room.on(now, RoomSignal.Joined(4), RoomSignal.MemberAdded(member))
        assertEquals(RoomStatus.Ready, room.status)
        assertTrue(room.canSend)
        assertEquals(4, room.epoch)
        assertTrue(room.members.getValue(leaf).pending, "invited until confirmed")
        room = room.on(now, RoomSignal.MemberUpdated(leaf))
        assertFalse(room.members.getValue(leaf).pending, "a first Update confirms")
        room = room.on(now, RoomSignal.MemberRemoved(leaf), RoomSignal.EpochChanged(5))
        assertTrue(room.members.isEmpty())
        assertEquals(5, room.epoch)
    }

    @Test fun `messages are handed back, never kept`() {
        val applied = keeper().apply(listOf(RoomSignal.Message(leaf, "33".repeat(32), 3, "hi".encodeToByteArray())), now)
        assertEquals("hi", applied.messages.single().body.decodeToString())
        assertEquals(keeper(), applied.room)
    }

    @Test fun `commits show sending, then retrying, then clear when accepted`() {
        var room = keeper().committing()
        assertEquals(RoomStatus.Sending, room.status)
        room = room.on(now, RoomSignal.CommitLost(CommitKind.UPDATE))
        assertEquals(RoomStatus.Retrying, room.status)
        room = room.on(now, RoomSignal.CommitRedeposited)
        assertEquals(RoomStatus.Retrying, room.status)
        room = room.on(now, RoomSignal.CommitAccepted(CommitKind.UPDATE, 4))
        assertEquals(RoomStatus.Ready, room.status)
        assertEquals(4, room.epoch)
    }

    @Test fun `checking with the box lasts until the round settles`() {
        var room = keeper().on(now, RoomSignal.OrderingUnconfirmed)
        assertEquals(RoomStatus.Checking, room.status)
        assertTrue(room.canSend)
        room = room.settled().on(now, RoomSignal.InstallationNeeded)
        assertEquals(RoomStatus.Checking, room.status)
        assertEquals(RoomStatus.Ready, room.settled().status)
    }

    @Test fun `UpdateDue asks for one Update, and none once stopped`() {
        val applied = keeper().apply(listOf(RoomSignal.UpdateDue, RoomSignal.UpdateDue), now)
        assertEquals(listOf<RoomAction>(RoomAction.StartUpdate), applied.actions)
        val stopped = keeper().on(now, RoomSignal.NeedsRecovery("Fork"))
        assertTrue(stopped.apply(listOf(RoomSignal.UpdateDue), now).actions.isEmpty())
    }

    @Test fun `a recovery stops sending and clears when the engine is active again`() {
        val room = keeper().on(now, RoomSignal.NeedsRecovery("Gap"))
        assertEquals(RoomStatus.Stopped(RoomStop.Recovery("Gap")), room.status)
        assertFalse(room.canSend)
        assertEquals(RoomStop.Recovery("Fork"), room.on(now, RoomSignal.NeedsRecovery("Fork")).stop)
        assertNull(room.seed(Phase.Active, 4, listOf(member)).stop)
        assertEquals(RoomStop.Recovery("RestoreFenced"), room.seed(Phase.NeedsRecovery("RestoreFenced"), 4, listOf(member)).stop)
    }

    @Test fun `a compromise or an unknown event holds through a reopen until repaired`() {
        for (signal in listOf(RoomSignal.PossibleOwnKeyCompromise, RoomSignal.Unknown("NewEvent"))) {
            val room = keeper().on(now, signal)
            assertFalse(room.canSend)
            val reopened = room.seed(Phase.Active, 4, listOf(member))
            assertEquals(room.stop, reopened.stop)
            assertFalse(reopened.repaired(Phase.NeedsRecovery("Fork")).canSend)
            assertTrue(reopened.repaired(Phase.Active).canSend)
        }
        // A recovery does not hide the compromise warning.
        assertEquals(RoomStop.KeyCompromise, keeper().on(now, RoomSignal.PossibleOwnKeyCompromise, RoomSignal.NeedsRecovery("Gap")).stop)
    }

    @Test fun `removed and lapsed rooms end for good`() {
        val removed = keeper().on(now, RoomSignal.SelfRemoved, RoomSignal.NeedsRecovery("Gap"))
        assertEquals(RoomStop.Removed, removed.stop)
        assertTrue(removed.ended)
        assertEquals(RoomStop.Removed, removed.repaired(Phase.Active).stop)
        assertEquals(RoomStop.Removed, removed.seed(Phase.Active, 4, emptyList()).stop)
        assertEquals(RoomStop.JoinLapsed, guest().on(now, RoomSignal.JoinExpired).stop)
        assertEquals(RoomStop.JoinLapsed, guest().seed(Phase.Expired, null, emptyList()).stop)
        assertEquals(RoomStop.Removed, guest().seed(Phase.Removed, 4, emptyList()).stop)
    }

    @Test fun `a lapsed member is removed only after fifteen minutes, its repeated proposals folded into the first`() {
        var room = keeper().on(now, RoomSignal.PendingMemberExpired(leaf), RoomSignal.ProposeRemoval(leaf, "CapabilityExpired"))
        // The engine proposes again on every tick: the first proposal's time holds.
        room = room.on(now + 600, RoomSignal.ProposeRemoval(leaf, "CapabilityExpired"))
        assertEquals(now, room.grace.getValue(leaf))
        assertTrue(room.dueRemovals(now + VmlsRoom.GRACE_SECONDS - 1).second.isEmpty())
        val (removing, due) = room.dueRemovals(now + VmlsRoom.GRACE_SECONDS)
        assertEquals(listOf(leaf), due)
        // Handed out once, while its Remove is on its way.
        assertTrue(removing.dueRemovals(now + 2 * VmlsRoom.GRACE_SECONDS).second.isEmpty())
        val gone = removing.on(now, RoomSignal.CommitAccepted(CommitKind.REMOVE, 4), RoomSignal.MemberRemoved(leaf))
        assertTrue(gone.grace.isEmpty() && gone.removing.isEmpty() && leaf !in gone.members)
    }

    @Test fun `a member that confirms or updates within its grace is kept`() {
        for (alive in listOf(RoomSignal.MemberConfirmed(leaf), RoomSignal.MemberUpdated(leaf))) {
            val room = keeper().on(now, RoomSignal.ProposeRemoval(leaf, "CapabilityExpired")).on(now + 60, alive)
            assertTrue(room.dueRemovals(now + VmlsRoom.GRACE_SECONDS).second.isEmpty())
            assertFalse(room.members.getValue(leaf).pending)
            // A later lapse starts a new grace.
            val again = room.on(now + 120, RoomSignal.ProposeRemoval(leaf, "BindingExpired"))
            assertEquals(now + 120, again.grace.getValue(leaf))
        }
    }

    @Test fun `a lost Remove is offered again without restarting its grace`() {
        val (removing, _) = keeper().on(now, RoomSignal.ProposeRemoval(leaf, "BindingExpired")).dueRemovals(now + VmlsRoom.GRACE_SECONDS)
        val lost = removing.committing().on(now + VmlsRoom.GRACE_SECONDS + 5, RoomSignal.CommitLost(CommitKind.REMOVE))
        assertEquals(now, lost.grace.getValue(leaf))
        assertEquals(listOf(leaf), lost.dueRemovals(now + VmlsRoom.GRACE_SECONDS + 5).second)
        // An Update lost leaves a Remove on its way alone.
        assertTrue(removing.on(now, RoomSignal.CommitLost(CommitKind.UPDATE)).dueRemovals(now + 10 * VmlsRoom.GRACE_SECONDS).second.isEmpty())
    }

    @Test fun `a refused Remove ends its grace, and nothing is due once the room stops`() {
        val proposed = keeper().on(now, RoomSignal.ProposeRemoval(leaf, "BindingExpired"), RoomSignal.ProposeRemoval(other, "BindingExpired"))
        val (removing, due) = proposed.dueRemovals(now + VmlsRoom.GRACE_SECONDS)
        assertEquals(listOf(leaf, other), due)
        val abandoned = removing.removalAbandoned(listOf(other))
        assertEquals(setOf(leaf), abandoned.grace.keys)
        assertEquals(setOf(leaf), abandoned.removing)
        assertTrue(proposed.on(now, RoomSignal.NeedsRecovery("Fork")).dueRemovals(now + VmlsRoom.GRACE_SECONDS).second.isEmpty())
    }

    @Test fun `a reopen keeps the grace of leaves the group still holds`() {
        val proposed = keeper().on(now, RoomSignal.ProposeRemoval(leaf, "CapabilityExpired"), RoomSignal.ProposeRemoval(other, "CapabilityExpired"))
        val reopened = proposed.seed(Phase.Active, 4, listOf(member))
        assertEquals(mapOf(leaf to now), reopened.grace)
    }

    @Test fun `only the keeper acts on proposals`() {
        val room = guest().seed(Phase.Active, 3, listOf(member)).on(now, RoomSignal.ProposeRemoval(leaf, "BindingExpired"), RoomSignal.PendingMemberExpired(leaf))
        assertTrue(room.grace.isEmpty())
        assertTrue(room.dueRemovals(now + VmlsRoom.GRACE_SECONDS).second.isEmpty())
        assertFailsWith<IllegalArgumentException> { room.copy(grace = mapOf(leaf to now)) }
        assertFailsWith<IllegalArgumentException> { room.copy(invite = "dd".repeat(32)) }
    }

    @Test fun `names and ids are checked`() {
        assertFailsWith<IllegalArgumentException> { keeper().copy(name = " ") }
        assertFailsWith<IllegalArgumentException> { keeper().copy(name = "a\nb") }
        assertFailsWith<IllegalArgumentException> { keeper().copy(name = "a".repeat(VmlsRoom.MAX_NAME + 1)) }
        assertFailsWith<IllegalArgumentException> { keeper().copy(box = "CC".repeat(32)) }
        assertFailsWith<IllegalArgumentException> { VmlsRoomMember("1", "33", "44", false) }
        // A malformed engine code is kept to what the store reads back.
        val stop = keeper().on(now, RoomSignal.NeedsRecovery("Gap:\nx")).stop!!
        assertEquals(stop, RoomStop.parse(stop.code))
    }
}
