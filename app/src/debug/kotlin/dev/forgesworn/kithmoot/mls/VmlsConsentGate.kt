package dev.forgesworn.kithmoot.mls

/**
 * Which join requests reach the keeper as a consent prompt (P3-03b-3
 * decision 18). Per room: one prompt open at a time, a device asked about
 * at most once per link, and at most [MAX_PER_HOUR] prompts in any hour.
 * Requests are deduplicated by request id. A request turned away is
 * dropped unseen; its guest times out and may ask again later.
 *
 * A retired link is not the gate's: the caller offers only a request over
 * the room's live link ([VmlsRoom.invite]), which is stored, so retiring
 * survives a restart. The counts here are held in memory: a restart of the
 * keeper's app starts them afresh, which only the keeper can cause. One
 * gate per process: its lock is the instance's. Times are seconds.
 */
class VmlsConsentGate {
    sealed class Verdict {
        /** Show the prompt; answer it with [answered]. */
        data object Ask : Verdict()
        /** Dropped unseen, for [reason]. */
        data class Drop(val reason: Reason) : Verdict()
    }

    enum class Reason { DUPLICATE, ASKED, BUSY, RATE }

    private class Room(
        var open: Pair<String, Long>? = null,
        val prompts: ArrayDeque<Long> = ArrayDeque(),
        val asked: LinkedHashSet<String> = LinkedHashSet(),
    )

    private val rooms = LinkedHashMap<String, Room>()
    private val seen = LinkedHashSet<String>()

    /**
     * A join request [requestId] from [device] over [link] for [room] at
     * [now]. Every id is remembered, so a repeat is a duplicate whatever
     * the first one's verdict.
     */
    @Synchronized fun offer(room: String, link: String, requestId: String, device: String, now: Long): Verdict {
        if (!seen.add(requestId)) return Verdict.Drop(Reason.DUPLICATE)
        bound(seen, MAX_SEEN)
        val state = rooms.getOrPut(room) { Room() }
        state.open?.let { (_, opened) -> if (now - opened < PROMPT_SECONDS) return Verdict.Drop(Reason.BUSY) }
        state.open = null
        if ("$link/$device" in state.asked) return Verdict.Drop(Reason.ASKED)
        while (state.prompts.isNotEmpty() && now - state.prompts.first() >= HOUR) state.prompts.removeFirst()
        if (state.prompts.size >= MAX_PER_HOUR) return Verdict.Drop(Reason.RATE)
        state.prompts.addLast(now)
        state.asked += "$link/$device"
        bound(state.asked, MAX_ASKED)
        state.open = requestId to now
        bound(rooms.keys, MAX_ROOMS, except = room)
        return Verdict.Ask
    }

    /** The keeper answered [requestId], either way: the room's prompt closes. */
    @Synchronized fun answered(room: String, requestId: String) {
        val state = rooms[room] ?: return
        if (state.open?.first == requestId) state.open = null
    }

    private fun bound(set: MutableSet<String>, max: Int, except: String? = null) {
        val iterator = set.iterator()
        while (set.size > max && iterator.hasNext()) if (iterator.next() != except) iterator.remove()
    }

    companion object {
        const val MAX_PER_HOUR = 5
        /** An open prompt lapses with the guest's wait for the answer (decision 17). */
        const val PROMPT_SECONDS = 10 * 60L
        private const val HOUR = 3600L
        /** As today's answered requests (`serveInvitation`'s 256). */
        private const val MAX_SEEN = 256
        private const val MAX_ASKED = 256
        private const val MAX_ROOMS = 64
    }
}
