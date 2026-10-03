package dev.forgesworn.kithmoot.session

/**
 * What a room says about its epochs going wrong, in the web client's words
 * (`reportEpochTrouble` in `app/src/main.ts`), one line per gap or conflict.
 *
 * A gap from epoch 0 is not said: a device opening a room for the first time
 * from a link always jumps from 0 to wherever the room is, and missed nothing
 * it was ever in.
 */
fun epochTroubleLines(gaps: List<EpochGap>, conflicts: List<EpochConflict>): List<String> =
    gaps.filter { it.from > 0 }.map {
        "This device was away while the room lock changed, so messages sent in that time cannot be read here."
    } + conflicts.map {
        "The room lock was changed from two places at once (epoch ${it.epoch}). Some people here may not see each " +
            "other's messages until whoever holds the room changes it again from one device."
    }
