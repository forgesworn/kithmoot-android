package dev.forgesworn.kithmoot.mls

import dev.forgesworn.vmls.ffi.VmlsCommitKind
import dev.forgesworn.vmls.ffi.VmlsEvent
import dev.forgesworn.vmls.ffi.VmlsMember
import dev.forgesworn.vmls.ffi.VmlsMemberInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Every engine event has a room signal (P3-03b-3 decision 22); anything else stops sending. */
class VmlsRoomSignalsTest {
    private val leaf = ByteArray(32) { 1 }
    private val hex = "01".repeat(32)
    private val member = VmlsMember(leaf, ByteArray(32) { 2 }, ByteArray(32) { 3 })

    @Test fun `each engine event maps to its signal`() {
        val cases = listOf(
            VmlsEvent.Joined(4uL) to RoomSignal.Joined(4),
            VmlsEvent.EpochChanged(5uL) to RoomSignal.EpochChanged(5),
            VmlsEvent.MemberAdded(member) to RoomSignal.MemberAdded(VmlsRoomMember(hex, "02".repeat(32), "03".repeat(32), pending = true)),
            VmlsEvent.MemberRemoved(leaf) to RoomSignal.MemberRemoved(hex),
            VmlsEvent.MemberUpdated(leaf) to RoomSignal.MemberUpdated(hex),
            VmlsEvent.MemberConfirmed(leaf) to RoomSignal.MemberConfirmed(hex),
            VmlsEvent.SelfRemoved to RoomSignal.SelfRemoved,
            VmlsEvent.CommitAccepted(VmlsCommitKind.REMOVE, 6uL) to RoomSignal.CommitAccepted(CommitKind.REMOVE, 6),
            VmlsEvent.CommitLost(VmlsCommitKind.REPAIR) to RoomSignal.CommitLost(CommitKind.REPAIR),
            VmlsEvent.CommitRedeposited(2u) to RoomSignal.CommitRedeposited,
            VmlsEvent.ProposeRemoval(leaf, "BindingExpired", 7u) to RoomSignal.ProposeRemoval(hex, "BindingExpired"),
            VmlsEvent.PendingMemberExpired(leaf) to RoomSignal.PendingMemberExpired(hex),
            VmlsEvent.UpdateDue to RoomSignal.UpdateDue,
            VmlsEvent.NeedsRecovery("Fork", 2u) to RoomSignal.NeedsRecovery("Fork"),
            VmlsEvent.JoinExpired to RoomSignal.JoinExpired,
            VmlsEvent.PossibleOwnKeyCompromise to RoomSignal.PossibleOwnKeyCompromise,
            VmlsEvent.OrderingUnconfirmed(3uL, leaf, 1u) to RoomSignal.OrderingUnconfirmed,
            VmlsEvent.InstallationNeeded(leaf) to RoomSignal.InstallationNeeded,
        )
        for ((event, signal) in cases) assertEquals(signal, roomSignal(event))
        val message = roomSignal(VmlsEvent.Message(member, 4uL, "hi".encodeToByteArray())) as RoomSignal.Message
        assertEquals(hex, message.senderLeaf)
        assertEquals("hi", message.body.decodeToString())
    }

    @Test fun `a value the app does not know stops sending`() {
        val signal = roomSignal("not an event")
        assertTrue(signal is RoomSignal.Unknown)
        val room = VmlsRoom("aa".repeat(32), "bb".repeat(32), "Kitchen", "cc".repeat(32), VmlsRole.GUEST, joined = true)
        assertTrue(!room.apply(listOf(signal), 0).room.canSend)
    }

    @Test fun `the member list at open leaves out this phone`() {
        val own = VmlsMemberInfo(VmlsMember(ByteArray(32) { 9 }, ByteArray(32), ByteArray(32)), ByteArray(32), 0uL, own = true, pending = false)
        val pending = VmlsMemberInfo(member, ByteArray(32), 0uL, own = false, pending = true)
        assertEquals(listOf(VmlsRoomMember(hex, "02".repeat(32), "03".repeat(32), pending = true)), roomMembers(listOf(own, pending)))
    }
}
