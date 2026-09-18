package dev.forgesworn.kithmoot.media

import dev.forgesworn.kithmoot.session.Roles

/**
 * Fixed media slots: the sender half of D1, section 3.1 of the call
 * reliability spec. The Android sibling of the web client's
 * `src/peer-slots.ts`.
 *
 * A profile-1 connection carries as many m-lines as it has ever carried
 * tracks. `addTrack` can only reuse a transceiver whose sender has never sent
 * anything, so a camera turned off and on again costs a fresh m-line, a fresh
 * offer and a fresh answer - and one lost answer in that exchange leaves the
 * pair blind for the rest of the call. That is H1, and it is not a bug in the
 * negotiation: it is what track-shaped negotiation costs.
 *
 * So a profile-2 connection has four `sendrecv` transceivers for its whole
 * life, in a fixed order, whether or not anything is attached to them, and a
 * camera toggle becomes `RtpSender.setTrack` - no SDP, no signal, nothing to
 * lose.
 *
 * Amendment A1 is the rule that has to be obeyed from outside: **only the side
 * that opens the generation creates them**. JSEP lets an incoming m-line adopt
 * an unassociated transceiver only when that transceiver came from `addTrack`,
 * so four slots opened with `addTransceiver` on the answering side cannot
 * absorb the offerer's four m-lines - the far end makes four more and the pair
 * ends up with eight. [SlotSet.open] is therefore the offerer's call and
 * [SlotSet.bind] the answerer's, and there is deliberately no method that does
 * both.
 *
 * **Where this differs from the web, and why.** The web's `SlotSet` holds the
 * four `RTCRtpTransceiver` objects. This one holds only their mids and reaches
 * for a transceiver through [PeerConnectionHandle] each time it needs one,
 * because libwebrtc's `PeerConnection.getTransceivers()` disposes every Java
 * wrapper a previous read returned - including the ones `addTransceiver`
 * handed back. A held wrapper is a use-after-free waiting for the next remote
 * track event, so nothing here holds one. The mid is a better name for a slot
 * anyway: it is the same name the far end uses.
 */

/** The two kinds of m-line a slot can be. A slot never changes kind. */
enum class SlotKind { AUDIO, VIDEO }

/** What a slot is doing, per section 3.1's table. There is no `unbound`: a
 *  [SlotSet] only exists once its transceivers do. */
enum class SlotState { IDLE, SENDING, BROKEN }

/**
 * A track on its way into a slot.
 *
 * [media] is the native `MediaStreamTrack`, typed as [Any] on purpose: a
 * native track cannot be constructed on a plain JVM, and the slot machine has
 * to be judgeable without a handset. The one cast back lives in
 * [WebRtcPeerConnection], which is the mechanical layer.
 */
data class SlotTrack(val role: String, val media: Any)

/**
 * The slot order, and it is a wire fact rather than a preference: it is the
 * order the m-lines appear in, and both ends read the same offer's `slots`
 * map, so the order only has to be stable, not meaningful. Audio first so a
 * pair whose video is refused still bundles its microphone on m-line zero.
 */
val SLOT_ORDER: List<String> = listOf(Roles.MIC, Roles.CAMERA, Roles.SCREEN, Roles.SCREEN_AUDIO)

/** Which kind of m-line each slot is. Fixed for the life of the connection,
 *  which is why setting a track can never fail for a reason the caller could
 *  have avoided. */
val SLOT_KINDS: Map<String, SlotKind> = mapOf(
    Roles.MIC to SlotKind.AUDIO,
    Roles.CAMERA to SlotKind.VIDEO,
    Roles.SCREEN to SlotKind.VIDEO,
    Roles.SCREEN_AUDIO to SlotKind.AUDIO,
)

/**
 * The media-section ids of a session description, in m-line order.
 *
 * Read off the SDP rather than off `getTransceivers()` for the disposal reason
 * above, and because the answer this produces - the `slots` map - has to be the
 * one the far end will read, which is the description and nothing else.
 * A rejected or mid-less m-line still takes its place in the order, as a gap,
 * so an index into this list is an index into the m-lines.
 */
internal fun sdpMids(sdp: String): List<String?> {
    val mids = mutableListOf<String?>()
    for (raw in sdp.lineSequence()) {
        val line = raw.trim()
        when {
            line.startsWith("m=") -> mids += null
            line.startsWith("a=mid:") -> {
                val mid = line.substringAfter("a=mid:").takeIf { it.isNotBlank() }
                if (mids.isNotEmpty()) mids[mids.size - 1] = mid
            }
        }
    }
    return mids
}

/**
 * The four slots of one connection.
 *
 * Nothing here emits a signal, and that is the whole point: every state
 * transition in section 3.1 is a `setTrack`, which changes no m-line, no mid
 * and no direction, and so gives the far end nothing to renegotiate.
 */
class SlotSet private constructor(private val connection: PeerConnectionHandle) {

    private var midByRole: Map<String, String> = emptyMap()
    private val attached = mutableMapOf<String, SlotTrack?>()
    private val broken = mutableSetOf<String>()

    companion object {
        /**
         * Open four idle slots on a connection that is about to offer.
         *
         * Called before the offer, so the offer describes all four. The mids
         * are not real until the local description has been applied, which is
         * why [assign] is a separate step and [map] refuses to answer before
         * it.
         */
        fun open(connection: PeerConnectionHandle): SlotSet {
            val set = SlotSet(connection)
            for (role in SLOT_ORDER) connection.addSlotTransceiver(SLOT_KINDS.getValue(role))
            return set
        }

        /**
         * Adopt the transceivers `setRemoteDescription` has just created, by
         * the offer's slot map.
         *
         * Called between `setRemoteDescription(offer)` and the answer, which is
         * the only window in which the direction can still be widened to
         * `sendrecv`: a remote offer creates its transceivers `recvonly`, and
         * an answer can only ever narrow what it describes, so a slot left
         * alone here is a slot this side can never send on for the life of the
         * connection.
         *
         * Returns null for a map that does not name all four slots. Half a slot
         * map is worse than none: this side would answer happily and then never
         * send on the slot it could not find.
         */
        fun bind(connection: PeerConnectionHandle, slots: Map<String, String>): SlotSet? {
            val byRole = mutableMapOf<String, String>()
            for ((mid, role) in slots) {
                if (role !in SLOT_KINDS) continue
                // One mid per role: a second is a map that disagrees with
                // itself, and the first writer is as good an answer as any.
                if (role !in byRole) byRole[role] = mid
            }
            if (byRole.keys != SLOT_ORDER.toSet()) return null
            val set = SlotSet(connection)
            set.midByRole = byRole
            for (role in SLOT_ORDER) {
                connection.setSlotDirection(byRole.getValue(role))
                set.attached[role] = null
            }
            return set
        }
    }

    /**
     * Learn the mids of the slots this side opened, from the offer it has just
     * applied locally.
     *
     * Refuses anything but exactly four m-lines, all with mids. More than four
     * means some other code path added a transceiver to a slotted connection,
     * which is the duplication risk section 9 names; fewer means the connection
     * did not create what it was asked for. Either way an incomplete map would
     * tell the far end to bind three slots and invent the fourth.
     */
    fun assign(localSdp: String): Boolean {
        val mids = sdpMids(localSdp)
        if (mids.size != SLOT_ORDER.size || mids.any { it == null }) return false
        midByRole = SLOT_ORDER.indices.associate { SLOT_ORDER[it] to mids[it]!! }
        for (role in SLOT_ORDER) attached[role] = null
        return true
    }

    /** How many slots this set knows about. Four is the only healthy answer. */
    val size: Int get() = midByRole.size

    /**
     * The `slots` map for a generation-opening offer: mid to role.
     *
     * Null until every slot has a mid, for the reason [assign] gives.
     */
    fun map(): Map<String, String>? {
        if (midByRole.size != SLOT_ORDER.size) return null
        return SLOT_ORDER.associateBy({ midByRole.getValue(it) }, { it })
    }

    /** The slot a mid belongs to - the whole of receive-side role resolution
     *  (section 4). Never a track id: a receiver's track id does not match the
     *  sender's in a slot, on any client measured. */
    fun roleOf(mid: String?): String? {
        if (mid == null) return null
        return midByRole.entries.firstOrNull { it.value == mid }?.key
    }

    /** The mid a slot sits on. */
    fun midOf(role: String): String? = midByRole[role]

    /** What each slot currently holds. Diagnostics and tests. */
    fun state(role: String): SlotState = when {
        role in broken -> SlotState.BROKEN
        attached[role] != null -> SlotState.SENDING
        else -> SlotState.IDLE
    }

    /** The track a slot is sending, if any. */
    fun track(role: String): SlotTrack? = attached[role]

    /**
     * Put this set of tracks in their slots, and empty every slot they do not
     * fill.
     *
     * This is every media change there is on a profile-2 connection: a camera
     * turned on, a share stopped, the microphone pipeline swapping its track,
     * and audience narrowing - which arrives here as an empty list, holds null
     * in every slot, and so sends that participant nothing at all, exactly as
     * removing the senders used to.
     *
     * Returns the roles whose `setTrack` was refused. There should never be
     * any: it is the only path in section 3.1 where a slot change still costs a
     * negotiation, and the caller's answer to it is a rebuild.
     */
    fun apply(tracks: List<SlotTrack>): List<String> {
        val wanted = mutableMapOf<String, SlotTrack>()
        for (track in tracks) {
            if (track.role !in SLOT_KINDS) continue
            // One advert per role per device is a decode rule on the wire
            // (section 2.1); here it is simply the first writer, because a
            // second track for a slot has nowhere to go.
            if (track.role !in wanted) wanted[track.role] = track
        }

        val refused = mutableListOf<String>()
        for (role in SLOT_ORDER) {
            val mid = midByRole[role] ?: continue
            val next = wanted[role]
            if (attached[role] === next) continue
            if (connection.setSlotTrack(mid, next?.media)) {
                attached[role] = next
                broken -= role
            } else {
                // Never swallowed silently: a slot that will not take a track
                // is the one case a rebuild is the answer to, and the caller
                // decides that.
                broken += role
                refused += role
            }
        }
        return refused
    }
}
