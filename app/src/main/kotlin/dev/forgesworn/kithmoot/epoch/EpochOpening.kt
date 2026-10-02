package dev.forgesworn.kithmoot.epoch

/**
 * What a room session does about the room's epoch as it opens, before it says
 * anything in the room.
 *
 * A link's secret opens epoch 0 and nothing after it. A room that has been
 * rekeyed is read only with its current epoch's secret, which the room's
 * authority hands to a member on proof of who it is (kind 20468/20469). A
 * device that opens at an older epoch than everybody else's is in a room of
 * its own: its calls do not ring, its chat does not cross, and nothing on
 * screen says so.
 */
sealed interface EpochOpening {
    /**
     * The responder that admitted this device said the room is at [epoch],
     * past the one this device holds: ask the authority for it before
     * announcing, exactly as the web client's `RoomSession` does with
     * `expectedEpoch`. Nothing is published under the old key meanwhile, and
     * if the authority does not answer the room says it needs recovery rather
     * than looking current.
     */
    data class Recover(val epoch: Int) : EpochOpening

    /**
     * Nobody said, or what was said is no further on than this device: open
     * as usual, and ask the authority once, quietly, whether the room has
     * moved on. A missed rekey is otherwise only learnt from a rekey event a
     * relay still replays, and a room saved before this device was told can
     * sit at a stale epoch indefinitely. No answer changes nothing.
     */
    data object Probe : EpochOpening

    /** Nobody to ask: no pinned authority, this device is the authority, or asking is not allowed here. */
    data object None : EpochOpening
}

/**
 * Decide [EpochOpening] for a room opening at epoch [current].
 *
 * [hint] is the highest epoch this device has been told the room is at: the
 * admission grant's `epoch` (see `RoomAdmission.epoch`), or one remembered from
 * an earlier opening that has not yet been recovered. [follows] is whether the
 * session believes a pinned authority's rekeys and can commit them (it has an
 * authority and an epoch gate). [isAuthority] is whether this device answers
 * epoch requests itself. [mayProbe] is false where an unprompted request would
 * say more than the room wants said (a quiet room's traffic shape).
 */
fun epochOpening(hint: Int?, current: Int, follows: Boolean, isAuthority: Boolean, mayProbe: Boolean): EpochOpening = when {
    !follows || isAuthority -> EpochOpening.None
    hint != null && hint > current -> EpochOpening.Recover(hint)
    mayProbe -> EpochOpening.Probe
    else -> EpochOpening.None
}
