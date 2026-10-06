package dev.forgesworn.kithmoot.protocol

/**
 * How long a member goes on reading an epoch the room has left: 30 days, as long as a chat
 * log keeps a message. fold-kit's `HISTORY_WINDOW_SECONDS`.
 */
const val HISTORY_WINDOW_SECONDS: Long = 30L * 86_400

/**
 * The most left epochs a member goes on reading, and an authority's grant hands over, however
 * many fall inside the window. A weekly schedule puts about five in it; removals are what push
 * it higher. Not [MAX_MEMBER_EPOCH_CHAIN], which bounds how far behind a member desk will bring
 * somebody. fold-kit's `MAX_HISTORY_EPOCHS`.
 */
const val MAX_HISTORY_EPOCHS = 16

/** An epoch the room has moved past, and when: [leftAt] is the `created_at` of the rekey out of it. */
class LeftEpoch(val epoch: Int, secret: ByteArray, val leftAt: Long) {
    val secret = secret.copyOf()
    init {
        require(epoch in 0..MAX_EPOCH) { "epoch must be a small non-negative integer" }
        require(secret.size == 32) { "epoch secret must be 32 bytes" }
    }
    fun roomEpoch() = RoomEpoch(epoch, secret)
    override fun toString() = "LeftEpoch(epoch=$epoch, secret=<32 bytes>, leftAt=$leftAt)"
}

/**
 * The left epochs still read at [now]: those left within [HISTORY_WINDOW_SECONDS] (one left
 * exactly that long ago is kept), newest first, one per epoch number (the first given), at most
 * [MAX_HISTORY_EPOCHS]. Every client applies the same rule, so what an authority hands over is
 * what a member goes on reading. Pure, and never throws: an entry with no usable number is
 * dropped. fold-kit's `epochsInWindow`, checked against the same vectors.
 */
fun <T> epochsInWindow(left: List<T>, now: Long, epochOf: (T) -> Int, leftAtOf: (T) -> Long): List<T> {
    val since = now - HISTORY_WINDOW_SECONDS
    val seen = HashSet<Int>()
    // A stable sort, so of a doubled epoch the first given is the one kept.
    return left.filter { epochOf(it) >= 0 && leftAtOf(it) >= since }
        .sortedByDescending(epochOf)
        .filter { seen.add(epochOf(it)) }
        .take(MAX_HISTORY_EPOCHS)
}
