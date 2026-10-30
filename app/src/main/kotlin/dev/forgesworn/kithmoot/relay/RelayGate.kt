package dev.forgesworn.kithmoot.relay

import dev.forgesworn.kithmoot.protocol.canonicalRelayUrl

/** What a relay's machine-readable refusal asks of this client. */
enum class RelayRefusalKind {
    /** Slow down: hold writes and reads to that relay for a while, then resume. */
    RATE_LIMITED,

    /** Do not send that subscription or event again until something changes on this side. */
    STOP,
}

/**
 * Reading a relay's refusals, in one place.
 *
 * NIP-01 gives OK and CLOSED a `prefix: words` reason. Some relays put
 * `ERROR: ` in front of it, so the prefix is looked for after that.
 */
object RelayRefusal {
    private const val ERROR_PREFIX = "ERROR:"

    /** The reason without an optional leading `ERROR: `, which some relays add before the NIP-01 prefix. */
    fun strip(reason: String): String {
        val trimmed = reason.trimStart()
        return if (trimmed.startsWith(ERROR_PREFIX, ignoreCase = true)) trimmed.substring(ERROR_PREFIX.length).trimStart() else trimmed
    }

    /**
     * What an OK false or CLOSED reason asks for, or null when it asks for
     * nothing in particular. `auth-required:` is a stop only when this client
     * holds no grant to answer it with: with one, the NIP-42 exchange owns it.
     */
    fun classify(reason: String, canAuthenticate: Boolean): RelayRefusalKind? {
        val bare = strip(reason)
        return when {
            bare.startsWith("rate-limited:") -> RelayRefusalKind.RATE_LIMITED
            bare.startsWith("blocked:") || bare.startsWith("restricted:") || bare.startsWith("pow:") -> RelayRefusalKind.STOP
            bare.startsWith("auth-required:") -> if (canAuthenticate) null else RelayRefusalKind.STOP
            else -> null
        }
    }

    /** Word boundaries matter: "moderate" and "operate" turn up in relay notices. */
    private val RATE_NOTICE = Regex("""(?i)(\brate\b|\bratelimit|\brate-limit|\btoo many\b|\bslow down\b)""")

    /** A NOTICE has no prefix convention; one that speaks of rate or too many is taken as a rate limit. */
    fun noticeAsksToSlowDown(text: String): Boolean = RATE_NOTICE.containsMatchIn(text)

    private val HTTP_REFUSAL = Regex("""^failed: http (\d{3})\b""")

    /** How a socket reports a refused websocket upgrade in its close reason, so every wrapper passes it on. */
    fun httpCloseReason(status: Int): String = "failed: http $status"

    /** The HTTP status of a refused upgrade, from a close reason written by [httpCloseReason]. */
    fun httpStatus(closeReason: String): Int? = HTTP_REFUSAL.find(closeReason)?.groupValues?.get(1)?.toIntOrNull()

    /** 429 and 503 say slow down; 403 is how a relay that has banned an address says it at the upgrade. */
    fun httpBacksOff(status: Int): Boolean = status == 429 || status == 503 || status == 403
}

/**
 * Every relay refusal this process has been given, by relay.
 *
 * A phone holds several pools to the same public relay (the open room, the
 * account, the contact inbox, the background listener, profile lookups), and
 * a relay that rate-limits one of them is rate-limiting the phone's address.
 * So the hold lives here, per canonical relay URL, and every pool asks it
 * before it dials, writes or subscribes. [process] is the one production
 * instance; tests pass their own.
 *
 * Times are the caller's clock, in milliseconds. Production pools all use the
 * wall clock, so their answers agree.
 */
class RelayGate {
    private val lock = Any()
    private val holds = mutableMapOf<String, Hold>()
    private val stoppedEvents = LinkedHashSet<String>()
    private val turns = mutableMapOf<String, Long>()

    private class Hold(var until: Long, var level: Int)

    /**
     * Holds [url] after a rate limit. The first hold is a minute; another
     * within a hold's length of the last one lifting doubles it, up to half an
     * hour; a hold's length of quiet starts again from a minute. A refusal that
     * arrives while already held (a burst of OK falses for one send) changes
     * nothing.
     */
    fun rateLimited(url: String, at: Long) = synchronized(lock) {
        val key = key(url)
        val hold = holds[key]
        if (hold != null && at < hold.until) return@synchronized
        val level = if (hold != null && at - hold.until <= windowFor(hold.level)) minOf(hold.level + 1, MAX_LEVEL) else 0
        holds[key] = Hold(at + windowFor(level), level)
    }

    /** A refused websocket upgrade: 429, 503 and 403 hold the relay like a rate limit. */
    fun upgradeRefused(url: String, status: Int, at: Long) {
        if (RelayRefusal.httpBacksOff(status)) rateLimited(url, at)
    }

    /** Milliseconds until [url] may be written to, subscribed to or dialled again; 0 when it is not held. */
    fun heldFor(url: String, at: Long): Long = synchronized(lock) {
        val hold = holds[key(url)] ?: return@synchronized 0L
        (hold.until - at).coerceAtLeast(0)
    }

    fun isHeld(url: String, at: Long): Boolean = heldFor(url, at) > 0

    /** [url] refused [eventId] for a reason that will not change by asking again. */
    fun stopEvent(url: String, eventId: String) = synchronized(lock) {
        stoppedEvents.add("${key(url)}\n$eventId")
        while (stoppedEvents.size > MAX_STOPPED_EVENTS) stoppedEvents.remove(stoppedEvents.first())
    }

    fun eventStopped(url: String, eventId: String): Boolean = synchronized(lock) { "${key(url)}\n$eventId" in stoppedEvents }

    /**
     * Books the next slot for a one-off request to [url], at least [spacingMs]
     * after the last one booked, and returns how long to wait for it. Spry
     * control opens a socket per request; this keeps it to one a second per relay.
     */
    fun reserveTurn(url: String, at: Long, spacingMs: Long = 1_000): Long = synchronized(lock) {
        val key = key(url)
        val slot = maxOf(at, (turns[key] ?: Long.MIN_VALUE / 2) + spacingMs)
        turns[key] = slot
        slot - at
    }

    private val keys = LinkedHashMap<String, String>()

    /** Under [lock]. One relay spelt with and without a trailing slash is one relay. */
    private fun key(url: String): String = keys.getOrPut(url) {
        runCatching { canonicalRelayUrl(url) }.getOrDefault(url.trim().trimEnd('/').lowercase())
    }.also { while (keys.size > 512) keys.remove(keys.keys.first()) }

    companion object {
        const val FIRST_HOLD_MS = 60_000L
        const val MAX_HOLD_MS = 30L * 60 * 1000
        private const val MAX_LEVEL = 5
        private const val MAX_STOPPED_EVENTS = 2_048

        /** The hold after [level] doublings: 60 s, 120 s, 240 s, 480 s, 960 s, then 30 min. */
        fun windowFor(level: Int): Long = (FIRST_HOLD_MS shl level.coerceIn(0, MAX_LEVEL)).coerceAtMost(MAX_HOLD_MS)

        /** The one gate every production pool shares. */
        val process = RelayGate()
    }
}
