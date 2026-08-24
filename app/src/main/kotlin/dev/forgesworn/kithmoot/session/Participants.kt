package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.RosterEntry

/** One published track, attributed to the person rather than the device. */
data class ParticipantTrack(
    val device: String,
    val trackId: String,
    val role: String,
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
        entry.tracks.map { ParticipantTrack(entry.device, it.trackId, it.role) }
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

    val deviceCount: Int get() = devices.size
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
