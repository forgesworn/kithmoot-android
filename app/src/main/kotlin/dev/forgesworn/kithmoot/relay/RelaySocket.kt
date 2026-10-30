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
    /** Optional diagnostic context; contains only ephemeral numeric identities. */
    val attemptTrace: RelayAttemptTrace? get() = null
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
    /**
     * The jitter's ceiling for the first retry, doubling per failed attempt up
     * to [maxDelayMs]. Every retry waits [minDelayMs] plus a uniform draw up to
     * this: a socket that drops after a healthy spell comes back 1 to 2 s
     * later, never at once, so a phone waking from sleep does not redial every
     * pool's sockets to one relay inside half a second.
     */
    val baseDelayMs: Long = 1_000,
    val maxDelayMs: Long = 30_000,
    /** The least any retry waits. */
    val minDelayMs: Long = 1_000,
    /**
     * A relay that never completes a handshake (a dropped TCP connection, a
     * closed TLS session) gives no reason to read, and retrying it at the 30 s
     * cap for ever is 120 dials an hour from one address: how a temporary ban
     * becomes a lasting one. After this many attempts in a row that never
     * opened, the ceiling becomes [unreachableMaxDelayMs].
     */
    val unreachableAfterAttempts: Int = 6,
    val unreachableMaxDelayMs: Long = 5L * 60 * 1000,
    /**
     * How long a connection has to survive before it counts as healthy. A relay
     * that accepts a socket and immediately drops it would otherwise reset the
     * backoff on every attempt and be reconnected to in a hot loop.
     */
    val stableAfterMs: Long = 10_000,
    /**
     * How long a socket may take to open before it is abandoned and retried.
     * Generous, because a Tor circuit is slow to build; a relay that has not
     * answered in this long is not going to.
     */
    val openTimeoutMs: Long = 30_000,
    /** How long an unsent publish waits for a relay to come up before it is dropped. */
    val outboxTtlMs: Long = 30_000,
    /** Cap on queued publishes per relay, so a permanently dead relay cannot grow without bound. */
    val outboxLimit: Int = 64,
    /** How long a best-effort stored read waits for the other relays once one has sent EOSE. */
    val storedGraceMs: Long = 3_000,
) {
    fun delayFor(attempt: Int): Long {
        val exponent = attempt.coerceIn(0, 16)
        val ceiling = (baseDelayMs shl exponent).coerceAtMost(maxDelayMs)
        return ceiling.coerceAtLeast(1)
    }

    /**
     * The wait before retry [attempt]: [minDelayMs] plus a uniform draw from
     * zero to the ceiling (full jitter). [unopened] counts the attempts in a
     * row whose socket never opened.
     */
    fun retryDelay(attempt: Int, unopened: Int, draw: (bound: Long) -> Long): Long {
        val ceiling = if (unopened >= unreachableAfterAttempts) maxOf(delayFor(attempt), unreachableMaxDelayMs) else delayFor(attempt)
        return minDelayMs + draw(ceiling + 1)
    }
}

/**
 * Waits for a Tor-only room, whose relays are onion services reached through
 * Orbot. The first stream to an onion fetches its descriptor and builds a
 * rendezvous circuit, and a freshly published onion can take most of two
 * minutes to answer. The lab saw first-room creation refused three times in a
 * row, on real Orbot and on host Tor alike, under the 15 s publish and 30 s open
 * limits (vennel P6-03). Each "Try again" started a new pool and so a new wait.
 */
object TorCarrierTimings {
    /** Tor's own limit on a stream waiting for its circuit (`SocksTimeout`,
     *  default two minutes). Tor fails a CONNECT it cannot build by then, so a
     *  shorter wait only cuts off one Tor would have finished. */
    const val CIRCUIT_MS = 120_000L

    /** A first durable publish or stored read: the circuit, then the relay's answer. */
    const val FIRST_ANSWER_MS = CIRCUIT_MS + 30_000L

    val policy = RelayPolicy(openTimeoutMs = CIRCUIT_MS, outboxTtlMs = CIRCUIT_MS)
}
