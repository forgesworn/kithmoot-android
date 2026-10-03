package dev.forgesworn.kithmoot.notifications

/** One call in progress, as seen from a device deciding whether to ring. */
data class IncomingCall(val id: String, val caller: String)

sealed interface IncomingCallChange {
    /** Ring for someone else's call, replacing any [OwnCallElsewhere] notice. */
    data class Ring(val call: IncomingCall) : IncomingCallChange
    /** Stop the ring. */
    data object Stop : IncomingCallChange
    /** This person's own call, started on another of their devices: a quiet
     *  notice with no ring, replacing any [Ring]. */
    data class OwnCallElsewhere(val call: IncomingCall) : IncomingCallChange
    /** Take the [OwnCallElsewhere] notice down. */
    data object OwnCallElsewhereStop : IncomingCallChange
}

/**
 * Turns a room's repeated presence snapshots into one incoming-call edge.
 *
 * A direct port of `IncomingCallTracker` in the web client
 * (app/src/incoming-call.ts): a recipient device rings once per call id,
 * stops when that device joins or the call ends, and never rings for a call
 * started by another device of the same person. `self` and `call.caller`
 * are participant identities (stable across a person's own devices), not
 * device identities, so this rule alone is what keeps a person's own other
 * devices silent.
 *
 * Silent, but not unannounced: a person's own call elsewhere is shown once,
 * as [IncomingCallChange.OwnCallElsewhere], until it ends or this device
 * joins. At most one of the ring and that notice is up at a time; each
 * replaces the other, so a stop only ever names the one that is showing.
 */
class IncomingCallTracker {
    private val seen = mutableSetOf<String>()
    private var ringing: String? = null
    private var elsewhere: String? = null

    @Synchronized
    fun update(call: IncomingCall?, self: String?, joined: Boolean): IncomingCallChange? {
        if (call == null || joined) {
            if (call != null) seen.add(call.id)
            return stop()
        }
        if (ringing == call.id || elsewhere == call.id) return null
        // Back to a call already seen: whatever is up belongs to another call.
        if (seen.contains(call.id)) return stop()
        seen.add(call.id)
        return if (call.caller == self) {
            ringing = null
            elsewhere = call.id
            IncomingCallChange.OwnCallElsewhere(call)
        } else {
            elsewhere = null
            ringing = call.id
            IncomingCallChange.Ring(call)
        }
    }

    /** A freshly opened room may ring for whatever call is already running in it. */
    @Synchronized
    fun reset(): IncomingCallChange? {
        seen.clear()
        return stop()
    }

    private fun stop(): IncomingCallChange? = when {
        ringing != null -> { ringing = null; IncomingCallChange.Stop }
        elsewhere != null -> { elsewhere = null; IncomingCallChange.OwnCallElsewhereStop }
        else -> null
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
}
