package dev.forgesworn.kithmoot.mls

/** This phone's part in a VMLS room (P3-03b-3 decision 21). */
enum class VmlsRole { KEEPER, GUEST }

/** The engine's commit kinds, as a room sees them. */
enum class CommitKind { ADD, REMOVE, UPDATE, REPAIR }

/**
 * One engine event as the room takes it (decision 22). The engine's own
 * types stay in [roomSignal]; an event the app does not know arrives as
 * [Unknown] and stops sending.
 */
sealed class RoomSignal {
    class Message(val senderLeaf: String, val senderIdentity: String, val epoch: Long, val body: ByteArray) : RoomSignal()
    data class Joined(val epoch: Long) : RoomSignal()
    data class EpochChanged(val epoch: Long) : RoomSignal()
    data class MemberAdded(val member: VmlsRoomMember) : RoomSignal()
    data class MemberRemoved(val leaf: String) : RoomSignal()
    data class MemberUpdated(val leaf: String) : RoomSignal()
    data class MemberConfirmed(val leaf: String) : RoomSignal()
    data object SelfRemoved : RoomSignal()
    data class CommitAccepted(val kind: CommitKind, val epoch: Long) : RoomSignal()
    data class CommitLost(val kind: CommitKind) : RoomSignal()
    data object CommitRedeposited : RoomSignal()
    /** [code] is the engine's reason (`CapabilityExpired`, `BindingExpired`). */
    data class ProposeRemoval(val leaf: String, val code: String) : RoomSignal()
    data class PendingMemberExpired(val leaf: String) : RoomSignal()
    data object UpdateDue : RoomSignal()
    /** [reason] is the engine's code: `Gap`, `Fork`, `RestoreFenced`, ... */
    data class NeedsRecovery(val reason: String) : RoomSignal()
    data object JoinExpired : RoomSignal()
    data object PossibleOwnKeyCompromise : RoomSignal()
    data object OrderingUnconfirmed : RoomSignal()
    data object InstallationNeeded : RoomSignal()
    /** An event the app does not know, by its name. */
    data class Unknown(val event: String) : RoomSignal()
}

/** A member as the room lists it; [pending] shows as "invited" until confirmed. Ids are lower-case hex. */
data class VmlsRoomMember(val leaf: String, val identity: String, val device: String, val pending: Boolean) {
    init { require(ROOM_HEX_ID.matches(leaf) && ROOM_HEX_ID.matches(identity) && ROOM_HEX_ID.matches(device)) }
}

/**
 * Why a room stopped sending. A stop holds until the engine or a repair
 * clears it: [Removed] and [JoinLapsed] never clear, [Recovery] clears when
 * the engine is active again, and [KeyCompromise] and [Unknown] only by a
 * repair, since the engine's phase does not show them.
 */
sealed class RoomStop(internal val rank: Int, internal val code: String) {
    /** `NeedsRecovery`: "Repair", with its [reason]. A `Gap` is still driven (P2-R-05). */
    data class Recovery(val reason: String) : RoomStop(0, "recovery:$reason")
    /** An event the app does not know. */
    data class Unknown(val event: String) : RoomStop(1, "unknown:$event")
    /** `PossibleOwnKeyCompromise`: a warning naming the device, and "Repair". */
    data object KeyCompromise : RoomStop(2, "compromise")
    /** `SelfRemoved`: "You were removed"; the room is read-only. */
    data object Removed : RoomStop(3, "removed")
    /** `JoinExpired`: "The invitation lapsed; ask again". */
    data object JoinLapsed : RoomStop(3, "lapsed")

    internal companion object {
        private val CODE = Regex("[A-Za-z0-9]{1,64}")

        fun parse(code: String): RoomStop = when {
            code == "compromise" -> KeyCompromise
            code == "removed" -> Removed
            code == "lapsed" -> JoinLapsed
            code.startsWith("recovery:") -> Recovery(code.removePrefix("recovery:").also { require(CODE.matches(it)) })
            code.startsWith("unknown:") -> Unknown(code.removePrefix("unknown:").also { require(CODE.matches(it)) })
            else -> throw IllegalArgumentException("Unknown room stop.")
        }

        /** An engine code or event name, kept to what [parse] reads back. */
        fun clean(name: String): String = name.filter { it.isLetterOrDigit() && it.code < 128 }.take(64).ifEmpty { "Unnamed" }
    }
}

/** What a room shows, in order of precedence (decision 22). */
sealed class RoomStatus {
    data class Stopped(val stop: RoomStop) : RoomStatus()
    /** `OrderingUnconfirmed` or `InstallationNeeded`: "checking with the box". */
    data object Checking : RoomStatus()
    /** `CommitLost` or `CommitRedeposited`: "retrying". */
    data object Retrying : RoomStatus()
    /** A commit of this phone's is on its way. */
    data object Sending : RoomStatus()
    /** A guest waiting for its Welcome. */
    data object Joining : RoomStatus()
    data object Ready : RoomStatus()
}

/** What the platform does next for a room. */
sealed class RoomAction {
    /** `UpdateDue`: an Update through the vault. */
    data object StartUpdate : RoomAction()
}

/**
 * One VMLS room on this phone (decision 21). The first group of fields is
 * stored; [members], [epoch] and the commit flags are rebuilt from the
 * engine at each open ([seed]) and from its events, so nothing the engine
 * already holds is kept twice. Messages are never stored: [apply] hands
 * them back and the screen shows them while the room is open (3b-3 is a
 * debug build; P3-05 decides history).
 *
 * The removal grace (decisions 14 and 19) is stored, because a pending
 * member's removal is proposed once only: [grace] holds the first proposal
 * per leaf, and [removing] the leaves a Remove commit is carrying. Only a
 * keeper keeps either.
 */
data class VmlsRoom(
    /** The persona's x-only key, hex. */
    val persona: String,
    /** The session id the snapshot store uses, hex. */
    val session: String,
    val name: String,
    /** The box's Link node id, hex: its route is [VmlsBoxRoute]. */
    val box: String,
    val role: VmlsRole,
    val joined: Boolean,
    val stop: RoomStop? = null,
    /**
     * The keeper's live invite link, by its id (hex), or null when none is
     * offered. Retiring the link clears it, which ends its prompts
     * (decision 18).
     */
    val invite: String? = null,
    /** The devices (hex) asked about over [invite]: each is asked once per link (decision 18). */
    val asked: Set<String> = emptySet(),
    /** When the room's last prompts were shown, oldest first: at most five in any hour (decision 18). */
    val prompted: List<Long> = emptyList(),
    val grace: Map<String, Long> = emptyMap(),
    val removing: Set<String> = emptySet(),
    // ---- rebuilt, never stored ----
    val members: Map<String, VmlsRoomMember> = emptyMap(),
    val epoch: Long? = null,
    val sending: Boolean = false,
    val retrying: Boolean = false,
    val checking: Boolean = false,
) {
    init {
        require(ROOM_HEX64.matches(persona) && ROOM_HEX64.matches(session) && ROOM_HEX64.matches(box))
        require(name.isNotBlank() && name.length <= MAX_NAME && name.none { it.isISOControl() })
        require(role == VmlsRole.KEEPER || (grace.isEmpty() && removing.isEmpty() && invite == null && asked.isEmpty() && prompted.isEmpty())) {
            "Only a keeper invites and removes members."
        }
        require(invite == null || ROOM_HEX64.matches(invite))
        require(invite != null || asked.isEmpty())
        require(asked.size <= MAX_ASKED && asked.all(ROOM_HEX_ID::matches))
        require(prompted.size <= VmlsConsentGate.MAX_PER_HOUR && prompted.zipWithNext().all { (a, b) -> a <= b } && prompted.all { it >= 0 })
        require(grace.size <= MAX_GRACE && grace.keys.all(ROOM_HEX_ID::matches) && grace.values.all { it >= 0 })
        require(removing.size <= MAX_GRACE && removing.all(ROOM_HEX_ID::matches))
    }

    val status: RoomStatus get() = when {
        stop != null -> RoomStatus.Stopped(stop)
        checking -> RoomStatus.Checking
        retrying -> RoomStatus.Retrying
        sending -> RoomStatus.Sending
        !joined -> RoomStatus.Joining
        else -> RoomStatus.Ready
    }

    /** Messages and commits go out only from a joined room that has not stopped. */
    val canSend: Boolean get() = joined && stop == null

    /** Read-only for good: the room can only be forgotten. */
    val ended: Boolean get() = stop == RoomStop.Removed || stop == RoomStop.JoinLapsed

    class Applied(val room: VmlsRoom, val messages: List<RoomSignal.Message>, val actions: List<RoomAction>)

    /**
     * The engine's state at open, which is authoritative over what was
     * stored: its [phase], its [epoch] and its member list. A stop the
     * phase does not show ([RoomStop.KeyCompromise], [RoomStop.Unknown])
     * is kept for a repair to clear.
     */
    fun seed(phase: Phase, epoch: Long?, members: List<VmlsRoomMember>): VmlsRoom {
        val stop = when (phase) {
            Phase.Removed -> RoomStop.Removed
            Phase.Expired -> RoomStop.JoinLapsed
            is Phase.NeedsRecovery -> RoomStop.Recovery(RoomStop.clean(phase.reason))
            Phase.PendingJoin, Phase.Active -> null
        }
        // An active engine has left its recovery; a stop it cannot show is kept.
        val kept = this.stop?.takeIf { it !is RoomStop.Recovery }
        val roster = members.associateBy { it.leaf }
        // A leaf the group no longer holds has nothing left to remove. A Remove handed out before the
        // app stopped may never have reached the engine: its leaves are due again, their grace kept,
        // and one the engine still carries ends in MemberRemoved or CommitLost either way.
        return copy(
            joined = phase != Phase.PendingJoin,
            // Of equal rank, the stop kept from before wins: a repair clears it.
            stop = higher(stop, kept),
            members = roster,
            epoch = epoch,
            grace = grace.filterKeys(roster::containsKey),
            removing = emptySet(),
            sending = false, retrying = false, checking = false,
        )
    }

    /** One step's events, in order, at [now] (seconds). */
    fun apply(signals: List<RoomSignal>, now: Long): Applied {
        var room = this
        // Grace ended by an update in this step: a proposal in the same step for that leaf means the engine
        // applied the update and still finds the leaf lapsed, so its first proposal's time holds.
        val updated = HashMap<String, Long>()
        val messages = mutableListOf<RoomSignal.Message>()
        val actions = mutableListOf<RoomAction>()
        for (signal in signals) {
            room = when (signal) {
                is RoomSignal.Message -> { messages += signal; room }
                is RoomSignal.Joined -> room.copy(joined = true, epoch = signal.epoch)
                is RoomSignal.EpochChanged -> room.copy(epoch = signal.epoch)
                is RoomSignal.MemberAdded -> room.copy(members = room.members + (signal.member.leaf to signal.member))
                // A pending member's first Update confirms it (decision 12), and only the adder sees MemberConfirmed.
                is RoomSignal.MemberConfirmed -> { room.grace[signal.leaf]?.let { updated[signal.leaf] = it }; room.confirmed(signal.leaf) }
                is RoomSignal.MemberUpdated -> { room.grace[signal.leaf]?.let { updated[signal.leaf] = it }; room.confirmed(signal.leaf) }
                is RoomSignal.MemberRemoved -> room.copy(
                    members = room.members - signal.leaf, grace = room.grace - signal.leaf, removing = room.removing - signal.leaf,
                )
                // The commit merged; a Remove's leaves leave by MemberRemoved.
                is RoomSignal.CommitAccepted -> room.copy(sending = false, retrying = false, epoch = signal.epoch)
                // The driver proposes it again; a lost Remove is offered again by [dueRemovals], its grace unchanged.
                is RoomSignal.CommitLost -> room.copy(retrying = true, removing = if (signal.kind == CommitKind.REMOVE) emptySet() else room.removing)
                RoomSignal.CommitRedeposited -> room.copy(retrying = true)
                is RoomSignal.ProposeRemoval -> room.proposed(signal.leaf, updated[signal.leaf] ?: now)
                is RoomSignal.PendingMemberExpired -> room.proposed(signal.leaf, updated[signal.leaf] ?: now)
                RoomSignal.UpdateDue -> { if (room.canSend && RoomAction.StartUpdate !in actions) actions += RoomAction.StartUpdate; room }
                RoomSignal.OrderingUnconfirmed, RoomSignal.InstallationNeeded -> room.copy(checking = true)
                is RoomSignal.NeedsRecovery -> room.stopped(RoomStop.Recovery(RoomStop.clean(signal.reason)))
                RoomSignal.PossibleOwnKeyCompromise -> room.stopped(RoomStop.KeyCompromise)
                RoomSignal.SelfRemoved -> room.stopped(RoomStop.Removed)
                RoomSignal.JoinExpired -> room.stopped(RoomStop.JoinLapsed)
                is RoomSignal.Unknown -> room.stopped(RoomStop.Unknown(RoomStop.clean(signal.event)))
            }
        }
        return Applied(room, messages, actions)
    }

    /** The keeper offers [link] as the room's live invite, or retires it with null: devices are asked afresh. */
    fun invited(link: String?): VmlsRoom = copy(invite = link, asked = emptySet())

    /** The driver's round ended with nothing left to check: "checking with the box" ends. */
    fun settled(): VmlsRoom = copy(checking = false)

    /** A commit of this phone's was handed to the engine. */
    fun committing(): VmlsRoom = copy(sending = true)

    /**
     * Leaves whose grace has run at [now], not already being removed, and
     * the room with them marked as [removing]: the keeper commits one
     * Remove for them. Nothing is due once the room has stopped.
     */
    fun dueRemovals(now: Long): Pair<VmlsRoom, List<String>> {
        if (role != VmlsRole.KEEPER || !canSend) return this to emptyList()
        val due = grace.filter { (leaf, first) -> leaf !in removing && now - first >= GRACE_SECONDS }.keys.sorted()
        return copy(removing = removing + due) to due
    }

    /** The engine refused the Remove for [leaves] (gone already, or never ours to remove): their grace ends. */
    fun removalAbandoned(leaves: Collection<String>): VmlsRoom = copy(grace = grace - leaves.toSet(), removing = removing - leaves.toSet())

    /**
     * A repair finished with the engine [phase] active: the stop the phase
     * could not show is cleared. An ended room stays ended.
     */
    fun repaired(phase: Phase): VmlsRoom =
        if (phase == Phase.Active && !ended) copy(stop = null, joined = true) else this

    private fun confirmed(leaf: String): VmlsRoom = copy(
        members = member(leaf) { it.copy(pending = false) },
        // Alive within its grace: no removal, and a later lapse starts a new grace.
        grace = grace - leaf,
    )

    private fun member(leaf: String, change: (VmlsRoomMember) -> VmlsRoomMember) =
        members[leaf]?.let { members + (leaf to change(it)) } ?: members

    private fun proposed(leaf: String, since: Long): VmlsRoom {
        // Only the keeper acts (decision 19); repeated proposals fold into the first.
        if (role != VmlsRole.KEEPER || leaf in grace || !ROOM_HEX_ID.matches(leaf) || grace.size >= MAX_GRACE) return this
        return copy(grace = grace + (leaf to since))
    }

    private fun stopped(next: RoomStop) = copy(stop = higher(stop, next))

    companion object {
        /** Decision 19: covers Bothy's 120 s clock allowance and a slow commit at the slot. */
        const val GRACE_SECONDS = 15 * 60L
        const val MAX_NAME = 80
        const val MAX_GRACE = 1024
        const val MAX_ASKED = 256

        /**
         * A later stop replaces one of no higher rank; an ended room stays
         * ended. A recovery ranks lowest, so the engine's leaving it never
         * clears a stop it cannot show.
         */
        private fun higher(current: RoomStop?, next: RoomStop?): RoomStop? = when {
            current == null -> next
            next == null -> current
            next.rank >= current.rank -> next
            else -> current
        }
    }
}

internal val ROOM_HEX64 = Regex("[0-9a-f]{64}")
/** An engine id: a leaf, an identity or a device, lower-case hex of 1 to 64 bytes. */
internal val ROOM_HEX_ID = Regex("(?:[0-9a-f]{2}){1,64}")
