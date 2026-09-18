package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.CallMembership
import dev.forgesworn.kithmoot.protocol.RosterEntry

/** One published track, attributed to the person rather than the device. */
data class ParticipantTrack(
    val device: String,
    val trackId: String,
    val role: String,
    /** The device that published this track says it muted it at the source. */
    val muted: Boolean = false,
)

/**
 * One person in the room, with everything they are publishing from every device
 * they are on.
 *
 * This grouping is the entire product. A person with a laptop and a phone in the
 * room is **one** entry here, not two, and the interface renders one tile group
 * for them. Anything that leaks the device count into what the room sees - two
 * names in the participant list, two microphones, their phone appearing as a
 * stranger - is the bug this type exists to prevent.
 */
data class Participant(
    val participant: String,
    /** Sorted by device pubkey so the interface does not reshuffle on every heartbeat. */
    val devices: List<RosterEntry>,
    val micDevice: String?,
    val monitorDevice: String?,
) {
    /** Every track from every one of this person's devices. */
    val tracks: List<ParticipantTrack> = devices.flatMap { entry ->
        entry.tracks.map { ParticipantTrack(entry.device, it.trackId, it.role, muted = it.muted == true) }
    }

    /**
     * The tracks that should actually be rendered.
     *
     * Cameras and screens are additive - a person may reasonably show their face
     * from a laptop and their slides from a tablet. Microphones are not: only
     * the arbitration winner's audio is played, so a second device that is still
     * publishing a stale mic track after losing the claim is silent rather than
     * doubled.
     */
    val liveTracks: List<ParticipantTrack> = tracks.filter {
        it.role != Roles.MIC || it.device == micDevice
    }

    /** True when the microphone the room is hearing has muted itself at the source. */
    val micMuted: Boolean = liveTracks.any { it.role == Roles.MIC && it.muted }

    /**
     * True when any of this person's devices says it is an automated
     * participant. Self-declared - see `RosterEntry.agent` - and what the
     * "agents can hear me" switch acts on.
     */
    val agent: Boolean = devices.any { it.agent }

    /**
     * The call this person is on, if any, and which of their devices are on it.
     *
     * One answer per person even when their laptop and their phone disagree,
     * which happens for a heartbeat or two whenever somebody moves a call from
     * one of their devices to another. The freshest entry that names a call
     * decides which call it is; every device naming that same call is listed,
     * and `since` is the earliest of theirs, so "on the call since" is when
     * the person joined rather than when their latest device did.
     *
     * The web client reaches the same answer by stamping each participant as
     * it folds the roster (`participants()` in `src/session.ts`); done here on
     * the grouped devices instead, which does not depend on the order the
     * roster happened to arrive in.
     */
    val call: CallMembership?
    val callDevices: List<String>

    init {
        val onCall = devices.filter { it.call != null }
        val freshest = onCall.maxByOrNull { it.updatedAt }
        val id = freshest?.call?.id
        val together = onCall.filter { it.call?.id == id }
        call = id?.let { CallMembership(it, together.minOf { entry -> entry.call!!.since }) }
        callDevices = together.map { it.device }
    }

    val deviceCount: Int get() = devices.size
}

/**
 * One call in progress, read off presence: everybody with a device on it.
 *
 * Usually zero or one. Two means two people pressed Start at once, and a
 * client offers the bigger or the older one and lets the other wither - see
 * [callsOf]'s ordering, which is the web client's `calls()` rule.
 */
data class CallView(
    val id: String,
    /** The earliest join across everybody on it. */
    val since: Long,
    val participants: List<String>,
    val devices: List<String>,
)

/**
 * The calls in progress in a room, best first.
 *
 * The head of this list is what every client joins, so every client has to
 * compute the same head from the same roster or a room with two accidental
 * calls never collapses back to one. The comparator, exactly:
 *
 *  1. number of participants, DESCENDING - the bigger call wins;
 *  2. then `since`, ASCENDING - of two equal calls, the older wins;
 *  3. then `id`, ASCENDING, as a plain lower-case hex string comparison.
 *
 * Step 3 is the one the web client does not have yet. `Session.calls()` in
 * `src/session.ts` falls back to the insertion order of its map, which is the
 * order presence happened to arrive in on that device - so two clients with
 * the same roster can disagree about which of two exactly-tied calls to join,
 * and split the room permanently. The ids are unique by construction, so
 * ordering on them is total and the tie cannot survive. The web side is to
 * adopt the same third step.
 */
fun callsOf(people: Collection<Participant>): List<CallView> {
    val byId = LinkedHashMap<String, CallView>()
    for (person in people) {
        val call = person.call ?: continue
        val held = byId[call.id]
        byId[call.id] = if (held == null) {
            CallView(call.id, call.since, listOf(person.participant), person.callDevices)
        } else {
            held.copy(
                since = minOf(held.since, call.since),
                participants = held.participants + person.participant,
                devices = held.devices + person.callDevices,
            )
        }
    }
    return byId.values.sortedWith(
        compareByDescending<CallView> { it.participants.size }.thenBy { it.since }.thenBy { it.id },
    )
}

/**
 * Folds a flat roster of devices into people.
 *
 * Ordering is stable and derived only from pubkeys, so every client in the room
 * lists the participants in the same order without a coordinator.
 */
fun groupByParticipant(entries: Collection<RosterEntry>): List<Participant> = entries
    .groupBy { it.participant }
    .map { (participant, devices) ->
        val sorted = devices.sortedBy { it.device }
        Participant(
            participant = participant,
            devices = sorted,
            micDevice = RoleArbiter.holder(sorted, Roles.MIC),
            monitorDevice = RoleArbiter.holder(sorted, Roles.MONITOR),
        )
    }
    .sortedBy { it.participant }

/**
 * Who this device's media may be sent to.
 *
 * The switch is on the SENDER, and that is the whole of why it means
 * anything: a device the rule refuses is handed no tracks at all, so nothing
 * of this camera or microphone leaves for them. A flag asking an agent not
 * to listen would be a request; not sending is a fact.
 *
 * `agentsMayHear` off refuses every device belonging to a participant that
 * says it is an agent - see `RosterEntry.agent`. It is per person and per
 * device, because it is that person's media: a room where three people allow
 * agents and one does not has three transcribed voices and one silence.
 */
fun mediaAudience(agentDevices: Set<String>, agentsMayHear: Boolean): (String) -> Boolean =
    { device -> agentsMayHear || device !in agentDevices }
