package dev.forgesworn.kithmoot.protocol

/**
 * How long a signalling event stays acceptable, in seconds.
 *
 * Signalling is live state. An offer that is half a minute old describes a
 * connection attempt that has already been superseded, and applying it forces a
 * renegotiation nobody asked for - which is exactly what a hostile or buggy
 * relay re-delivering a captured wrap achieves. Reused from NIP-AC, as §3 of the
 * design says, and matching `SIGNAL_MAX_AGE_SECONDS` in the TypeScript
 * reference.
 *
 * The window is symmetric, so a sender cannot mint a wrap that stays acceptable
 * for ever by stamping it years ahead. It is also the tolerance two devices'
 * clocks are allowed to differ by: a device whose clock is a minute out will not
 * connect to anybody, which is a real deployment hazard and the reason this is a
 * constant rather than a hard-coded number.
 */
const val SIGNAL_MAX_AGE_SECONDS: Long = 20

/** The rate-limit window, in seconds. */
const val RATE_WINDOW_SECONDS: Long = 20

/**
 * How many signals one sending device may deliver per window.
 *
 * Generous by design: one negotiation is an offer, an answer and a few dozen
 * trickled candidates, and a track toggle starts another. This is a bound on
 * abuse, not a traffic shaper - a peer that trips it is flooding.
 */
const val MAX_SIGNALS_PER_WINDOW: Int = 120

/**
 * How many event ids and senders are remembered.
 *
 * Both tables have to be bounded: a room left open all day would otherwise
 * accumulate an entry per signal until the process ran out of memory.
 */
const val MAX_REMEMBERED_SIGNALS: Int = 4096

/**
 * Deduplication and rate limiting - two of the three rules §3 of the design says
 * signalling reuses from NIP-AC. The third, staleness, lives in [unwrapSignal],
 * where the timestamp is.
 *
 * Deliberately not a transport concern. Publishing to every relay means the same
 * wrap arrives from every relay, and a relay that means harm can send it again
 * later - so the room, not the socket, is what has to act on each signal exactly
 * once.
 */
class SignalGuard {

    /** Access-ordered only in insertion terms: the oldest id is the first out. */
    private val seen = object : LinkedHashMap<String, Boolean>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean =
            size > MAX_REMEMBERED_SIGNALS
    }

    private val senders = object : LinkedHashMap<String, Window>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Window>): Boolean =
            size > MAX_REMEMBERED_SIGNALS
    }

    private class Window(val start: Long, var count: Int)

    /** True the first time an event id is offered, false every time after. */
    @Synchronized
    fun admitEvent(id: String): Boolean = seen.put(id, true) == null

    /**
     * True while `sender` is within its budget for the current window.
     *
     * A fixed window rather than a sliding one: a burst straddling a boundary
     * can pass twice the budget, which is a rounding error against a flood and
     * costs one number per sender instead of a list of timestamps.
     */
    @Synchronized
    fun admitSender(sender: String, now: Long): Boolean {
        val window = senders[sender]
        if (window == null || now - window.start >= RATE_WINDOW_SECONDS) {
            senders[sender] = Window(now, 1)
            return true
        }
        if (window.count >= MAX_SIGNALS_PER_WINDOW) return false
        window.count++
        return true
    }

    /** Remembered event ids. Exposed so a test can prove the bound bites. */
    @Synchronized
    fun size(): Int = seen.size

    /** Tracked senders. Exposed for the same reason. */
    @Synchronized
    fun senderCount(): Int = senders.size

    @Synchronized
    fun clear() {
        seen.clear()
        senders.clear()
    }
}
