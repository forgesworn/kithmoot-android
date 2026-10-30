package dev.forgesworn.kithmoot.ui

/**
 * When a kept message is offered again without anybody pressing Retry, and
 * when that stops.
 *
 * It used to be offered every 60 s for ever, and from the start of the
 * sequence again whenever a relay came or went. Each offer goes to every
 * write relay, so a message one relay keeps refusing became a steady stream
 * of identical events at all of them. Now the waits grow, and after
 * [MAX_OFFERS] offers that a relay was there to take, the message stays
 * "Not sent" until Retry or a new message starts the count again. An attempt
 * made with no relay reachable offers nothing, so it does not count.
 */
internal class PendingChatRetry {
    private var step = 0
    private var offers = 0

    /** Retry pressed, or a new message kept: a fresh count. */
    fun reset() { step = 0; offers = 0 }

    /** An automatic attempt finished; [offered] when a relay was reachable to take it. */
    fun attempted(offered: Boolean) { if (offered) offers++ }

    /** Automatic retries have stopped; the message waits for Retry. */
    val stopped: Boolean get() = offers >= MAX_OFFERS

    /** The wait before the next automatic attempt, or null once they have stopped. */
    fun nextDelayMs(): Long? {
        if (stopped) return null
        val seconds = DELAYS_SECONDS[minOf(step, DELAYS_SECONDS.lastIndex)]
        step++
        return seconds * 1_000
    }

    companion object {
        /** Seconds before each automatic retry, the last repeating while nothing reachable takes it. */
        val DELAYS_SECONDS: List<Long> = listOf(5, 15, 30, 60, 120, 240, 480, 900)
        const val MAX_OFFERS = 8

        /**
         * Whether a change in the connected relays is worth an attempt at once:
         * only when the room had none and now has one. A relay dropping out of a
         * set that still has others, or rejoining it, changes nothing for a message
         * the others were already offered.
         */
        fun reconnectTriggers(previous: Set<String>, current: Set<String>): Boolean = previous.isEmpty() && current.isNotEmpty()
    }
}
