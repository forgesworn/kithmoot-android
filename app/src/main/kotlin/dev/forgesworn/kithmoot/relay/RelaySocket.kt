package dev.forgesworn.kithmoot.relay

/**
 * One relay websocket, narrowed to the three things the pool does with it.
 *
 * This exists so the pool's real behaviour - de-duplication across relays,
 * reconnect with backoff, re-subscribing after a drop - is testable against a
 * fake socket rather than against a live relay. A test that needs a network to
 * prove reconnect works is a test that will not be run.
 */
interface RelaySocket {
    fun send(text: String)
    fun close()
}

/** Callbacks from a socket. A failure and a clean close both arrive as [onClosed]. */
interface RelaySocketListener {
    fun onOpen()
    fun onMessage(text: String)

    /**
     * Called at most once per socket. The pool makes no distinction between a
     * relay that hung up politely and one that vanished: both mean reconnect.
     */
    fun onClosed(reason: String)
}

fun interface RelaySocketFactory {
    fun open(url: String, listener: RelaySocketListener): RelaySocket
}

/**
 * Reconnect timing: exponential backoff, capped, with full jitter.
 *
 * The jitter is not decoration. Every device in a room is connected to the same
 * handful of relays, so a relay restart drops all of them at once; without
 * jitter they would all come back in lockstep and hammer it in waves.
 */
data class RelayPolicy(
    val baseDelayMs: Long = 500,
    val maxDelayMs: Long = 30_000,
    /**
     * How long a connection has to survive before it counts as healthy. A relay
     * that accepts a socket and immediately drops it would otherwise reset the
     * backoff on every attempt and be reconnected to in a hot loop.
     */
    val stableAfterMs: Long = 10_000,
    /** How long an unsent publish waits for a relay to come up before it is dropped. */
    val outboxTtlMs: Long = 30_000,
    /** Cap on queued publishes per relay, so a permanently dead relay cannot grow without bound. */
    val outboxLimit: Int = 64,
) {
    fun delayFor(attempt: Int): Long {
        val exponent = attempt.coerceIn(0, 16)
        val ceiling = (baseDelayMs shl exponent).coerceAtMost(maxDelayMs)
        return ceiling.coerceAtLeast(1)
    }
}
