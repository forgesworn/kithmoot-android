package dev.forgesworn.kithmoot.notifications

/** One call in progress, as seen from a device deciding whether to ring. */
data class IncomingCall(val id: String, val caller: String)

/**
 * How long a new call rings, counted from when it started. A call nobody
 * answers in a minute stops asking, as a phone does, and a room opened while
 * a call has been going a while does not say somebody is calling: the room's
 * own "Call on" banner already offers Join. The web client's
 * `RING_BANNER_SECONDS` (app/src/incoming-call.ts).
 */
const val RING_SECONDS = 60L

/**
 * Seconds left to ring a call that started at [since], or 0 when it is too
 * old to ring. Both in seconds. A caller whose clock is ahead of this one
 * counts as having just started. The web client's `ringBannerSeconds`.
 */
fun ringSecondsFor(since: Long, now: Long): Long {
    val age = maxOf(0L, now - since)
    return if (age < RING_SECONDS) RING_SECONDS - age else 0L
}

sealed interface IncomingCallChange {
    /** Ring once for a call this device has not joined, including this person's other devices. */
    data class Ring(val call: IncomingCall) : IncomingCallChange
    /** Stop the ring. */
    data object Stop : IncomingCallChange
}

/** Each device rings once per call until it joins or the call ends. The
 * caller's participant identity does not suppress their other devices. */
class IncomingCallTracker {
    private val seen = mutableSetOf<String>()
    private var ringing: String? = null

    @Synchronized
    fun update(call: IncomingCall?, joined: Boolean): IncomingCallChange? {
        if (call == null || joined) {
            if (call != null) seen.add(call.id)
            return stop()
        }
        if (ringing == call.id) return null
        if (!seen.add(call.id)) return stop()
        ringing = call.id
        return IncomingCallChange.Ring(call)
    }

    @Synchronized
    fun reset(): IncomingCallChange? {
        seen.clear()
        return stop()
    }

    private fun stop(): IncomingCallChange? {
        if (ringing == null) return null
        ringing = null
        return IncomingCallChange.Stop
    }
}

/**
 * Calls this device has answered, joined or declined, for the life of the
 * process. A room's ringing moves between trackers - the background
 * listener's while KithMoot is closed, the open room's while it is on
 * screen - and each new tracker starts with nothing seen. Without this,
 * leaving a call by closing KithMoot handed the room back to the background
 * listener, which rang again for the call just left.
 */
object HandledCalls {
    private const val LIMIT = 64
    private val calls = LinkedHashSet<String>()

    @Synchronized
    fun add(roomId: String, callId: String) {
        if (roomId.isEmpty() || callId.isEmpty()) return
        calls.remove("$roomId|$callId")
        calls.add("$roomId|$callId")
        while (calls.size > LIMIT) calls.remove(calls.first())
    }

    @Synchronized
    fun contains(roomId: String, callId: String): Boolean = calls.contains("$roomId|$callId")

    private val lastOnCall = mutableMapOf<String, Long>()

    /** This device is on a call in [roomId] at [atMillis]. */
    @Synchronized
    fun onCall(roomId: String, atMillis: Long) {
        if (roomId.isEmpty()) return
        lastOnCall[roomId] = atMillis
    }

    /**
     * Whether this device was on a call in [roomId] less than
     * [QUIET_AFTER_CALL_MILLIS] before [nowMillis]: a new call there then
     * does not ring. Kept here, beside the calls handled, for the same
     * reason: the room's ringing moves from the open room's tracker to the
     * background listener's the moment KithMoot closes, and the listener
     * starts with nothing seen.
     */
    @Synchronized
    fun justOnCall(roomId: String, nowMillis: Long): Boolean =
        lastOnCall[roomId]?.let { nowMillis - it < QUIET_AFTER_CALL_MILLIS } == true
}

/**
 * How long after this device was last on a call in a room a new call there
 * does not ring. The web client's `QUIET_AFTER_CALL_MS`.
 *
 * A call nobody started is the one ring worse than none. When a call broke
 * up, a device still running an older build - or one whose relays were a
 * beat behind and so could not see the call it was on - declared a fresh
 * call on its own, and every phone in the room rang with its owner's name
 * while nobody was calling. A minute covers that tail. A call somebody
 * really does start straight after still shows in the room; it just does
 * not ring the people who have only just put the last one down.
 */
const val QUIET_AFTER_CALL_MILLIS = 60_000L
