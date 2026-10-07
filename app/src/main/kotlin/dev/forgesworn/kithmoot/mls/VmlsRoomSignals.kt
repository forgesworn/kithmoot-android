package dev.forgesworn.kithmoot.mls

import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.vmls.ffi.VmlsCommitKind
import dev.forgesworn.vmls.ffi.VmlsEvent
import dev.forgesworn.vmls.ffi.VmlsMember
import dev.forgesworn.vmls.ffi.VmlsMemberInfo

/**
 * One engine event as the room takes it (P3-03b-3 decision 22). The
 * driver hands events over untyped ([Effects.events]); anything that is not
 * an event this build knows becomes [RoomSignal.Unknown], which stops
 * sending.
 */
fun roomSignal(event: Any): RoomSignal = when (event) {
    is VmlsEvent.Message -> RoomSignal.Message(event.sender.leafId.toHex(), event.sender.identity.toHex(), event.epoch.signed(), event.body)
    is VmlsEvent.Joined -> RoomSignal.Joined(event.epoch.signed())
    is VmlsEvent.EpochChanged -> RoomSignal.EpochChanged(event.epoch.signed())
    is VmlsEvent.MemberAdded -> RoomSignal.MemberAdded(roomMember(event.member, pending = true))
    is VmlsEvent.MemberRemoved -> RoomSignal.MemberRemoved(event.leafId.toHex())
    is VmlsEvent.MemberUpdated -> RoomSignal.MemberUpdated(event.leafId.toHex())
    is VmlsEvent.MemberConfirmed -> RoomSignal.MemberConfirmed(event.leafId.toHex())
    VmlsEvent.SelfRemoved -> RoomSignal.SelfRemoved
    is VmlsEvent.CommitAccepted -> RoomSignal.CommitAccepted(commitKind(event.kind), event.epoch.signed())
    is VmlsEvent.CommitLost -> RoomSignal.CommitLost(commitKind(event.kind))
    is VmlsEvent.CommitRedeposited -> RoomSignal.CommitRedeposited
    is VmlsEvent.ProposeRemoval -> RoomSignal.ProposeRemoval(event.leafId.toHex(), event.code)
    is VmlsEvent.PendingMemberExpired -> RoomSignal.PendingMemberExpired(event.leafId.toHex())
    VmlsEvent.UpdateDue -> RoomSignal.UpdateDue
    is VmlsEvent.NeedsRecovery -> RoomSignal.NeedsRecovery(event.reason)
    VmlsEvent.JoinExpired -> RoomSignal.JoinExpired
    VmlsEvent.PossibleOwnKeyCompromise -> RoomSignal.PossibleOwnKeyCompromise
    is VmlsEvent.OrderingUnconfirmed -> RoomSignal.OrderingUnconfirmed
    is VmlsEvent.InstallationNeeded -> RoomSignal.InstallationNeeded
    else -> RoomSignal.Unknown(event::class.simpleName ?: "Unnamed")
}

/** The engine's member list at open, for [VmlsRoom.seed]; this phone's own leaf is not listed. */
fun roomMembers(members: List<VmlsMemberInfo>): List<VmlsRoomMember> =
    members.filterNot { it.own }.map { roomMember(it.member, it.pending) }

private fun roomMember(member: VmlsMember, pending: Boolean) =
    VmlsRoomMember(member.leafId.toHex(), member.identity.toHex(), member.device.toHex(), pending)

private fun commitKind(kind: VmlsCommitKind) = when (kind) {
    VmlsCommitKind.ADD -> CommitKind.ADD
    VmlsCommitKind.REMOVE -> CommitKind.REMOVE
    VmlsCommitKind.UPDATE -> CommitKind.UPDATE
    VmlsCommitKind.REPAIR -> CommitKind.REPAIR
}

private fun ULong.signed(): Long { check(this <= Long.MAX_VALUE.toULong()); return toLong() }
