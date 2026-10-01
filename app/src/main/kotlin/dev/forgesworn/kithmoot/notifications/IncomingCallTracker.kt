package dev.forgesworn.kithmoot.notifications

/** One call in progress, as seen from a device deciding whether to ring. */
data class IncomingCall(val id: String, val caller: String)

sealed interface IncomingCallChange {
    data class Ring(val call: IncomingCall) : IncomingCallChange
    data object Stop : IncomingCallChange
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
 */
class IncomingCallTracker {
    private val seen = mutableSetOf<String>()
    private var ringing: String? = null

    @Synchronized
    fun update(call: IncomingCall?, self: String?, joined: Boolean): IncomingCallChange? {
        if (call == null || joined || call.caller == self) {
            if (call != null) seen.add(call.id)
            if (ringing == null) return null
            ringing = null
            return IncomingCallChange.Stop
        }
        if (ringing == call.id || seen.contains(call.id)) return null
        seen.add(call.id)
        ringing = call.id
        return IncomingCallChange.Ring(call)
    }

    /** A freshly opened room may ring for whatever call is already running in it. */
    @Synchronized
    fun reset(): IncomingCallChange? {
        seen.clear()
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
}
