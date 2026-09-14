package dev.forgesworn.kithmoot.ui.room

import dev.forgesworn.kithmoot.session.Participant
import dev.forgesworn.kithmoot.session.Roles

/** One renderable track, still carrying the device it came off. */
data class TileTrack(val device: String, val trackId: String, val role: String)

/**
 * One person's tile group.
 *
 * There is one of these per **participant**, never per device. Someone sitting
 * at a laptop with their phone propped up beside it is one tile group with two
 * video panes in it, one name, and one microphone - not two strangers who
 * happen to share a face. Everything below is arranged so that the device count
 * is available where it is useful (labelling your own devices) and nowhere it
 * is not (the room's list of who is here).
 */
data class ParticipantTile(
    val participant: String,
    val isSelf: Boolean,
    val deviceCount: Int,
    /** Camera and screen tracks from every one of this person's devices. */
    val videos: List<TileTrack>,
    /** The device whose microphone the room is actually hearing, if any. */
    val micDevice: String?,
    /** True when the live microphone is the device you are holding. */
    val micIsThisDevice: Boolean,
    /** Set when this phone holds a contact card for the person: the name on
     *  it, or an empty string for a card without one. See storage/ContactBook.kt. */
    val cardName: String? = null,
    /** True when one of this person's devices is carrying their share's own
     *  sound as a separate track (`Roles.SCREEN_AUDIO`). Audio-only, so it
     *  never appears in [videos]. */
    val hasScreenAudio: Boolean = false,
    /** How loud this person is on this device only: 0.0 to 2.0, 1.0 being
     *  untouched. See media/CallVolume.kt. Meaningless for [isSelf]. */
    val callVolume: Float = 1.0f,
) {
    val holdsCard: Boolean get() = cardName != null
    val hasVideo: Boolean get() = videos.isNotEmpty()
    val hasMic: Boolean get() = micDevice != null
    val isSharingScreen: Boolean get() = videos.any { it.role == Roles.SCREEN }
    /** The web client's own name for a level dragged to zero: still in the
     *  room, just not heard here. */
    val isSilencedForYou: Boolean get() = !isSelf && callVolume <= 0f
}

/**
 * Folds the roster into tile groups.
 *
 * Ordering puts you first and everyone else in the roster's own stable order, so
 * the room does not reshuffle under your finger on every heartbeat. Only
 * [Participant.liveTracks] is used, which is what drops a second device's stale
 * microphone track after it has lost the claim.
 */
fun buildTiles(
    participants: List<Participant>,
    selfParticipant: String,
    selfDevice: String,
    /** Participants this phone holds a card for, to the name on the card. */
    cards: Map<String, String> = emptyMap(),
    /** This device's remembered call volume per participant. Missing means
     *  untouched (1.0). See media/CallVolume.kt. */
    volumes: Map<String, Float> = emptyMap(),
): List<ParticipantTile> = participants
    .map { person ->
        val isSelf = person.participant == selfParticipant
        ParticipantTile(
            participant = person.participant,
            isSelf = isSelf,
            deviceCount = person.deviceCount,
            videos = person.liveTracks
                .filter { it.role == Roles.CAMERA || it.role == Roles.SCREEN }
                .map { TileTrack(it.device, it.trackId, it.role) }
                // Screens before faces: if somebody is showing something, that is
                // what the room came to look at.
                .sortedWith(compareBy({ if (it.role == Roles.SCREEN) 0 else 1 }, { it.device }, { it.trackId })),
            micDevice = person.micDevice,
            micIsThisDevice = isSelf && person.micDevice == selfDevice,
            cardName = cards[person.participant],
            hasScreenAudio = person.liveTracks.any { it.role == Roles.SCREEN_AUDIO },
            callVolume = volumes[person.participant] ?: 1.0f,
        )
    }
    .sortedWith(compareByDescending<ParticipantTile> { it.isSelf }.thenBy { it.participant })

/**
 * A pubkey, shortened for a label.
 *
 * Deliberately both ends rather than a prefix. Two keys that share a prefix are
 * cheap to grind; showing the tail as well means a name that looks right almost
 * certainly is.
 */
fun shortId(pubkey: String): String =
    if (pubkey.length <= 12) pubkey else "${pubkey.take(6)}…${pubkey.takeLast(4)}"
