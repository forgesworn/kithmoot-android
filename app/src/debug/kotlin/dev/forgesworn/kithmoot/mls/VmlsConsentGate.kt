package dev.forgesworn.kithmoot.mls

/**
 * Which join requests reach the keeper as a consent prompt (P3-03b-3
 * decision 18). Per room: nothing but over its live link, one prompt open
 * at a time, a device asked about at most once per link, and at most
 * [MAX_PER_HOUR] prompts in any hour. Requests are deduplicated by request
 * id. A request turned away is dropped unseen; its guest times out and may
 * ask again later.
 *
 * The limits that must outlast the app are the room's ([VmlsRoom.invite],
 * [VmlsRoom.asked], [VmlsRoom.prompted]): [offer] hands back the room to
 * store before the prompt is shown. Only the open prompt and the request
 * ids seen are held here, and both end with the process, as the prompt on
 * screen does. One gate per process: its lock is the instance's. Times are
 * seconds.
 */
class VmlsConsentGate {
    sealed class Verdict {
        /** Show the prompt; answer it with [answered]. */
        data object Ask : Verdict()
        /** Dropped unseen, for [reason]. */
        data class Drop(val reason: Reason) : Verdict()
    }

    enum class Reason { DUPLICATE, RETIRED, ASKED, BUSY, RATE }

    /** The open prompt per room session: its request id and when it opened. */
    private val open = LinkedHashMap<String, Pair<String, Long>>()
    private val seen = LinkedHashSet<String>()

    /**
     * A join request [requestId] from [device] over [link] for the keeper's
     * [room] at [now]: the verdict, and the room as it must be stored before
     * an [Verdict.Ask] is shown. Every id is remembered, so a repeat is a
     * duplicate whatever the first one's verdict.
     */
    @Synchronized fun offer(room: VmlsRoom, link: String, requestId: String, device: String, now: Long): Pair<Verdict, VmlsRoom> {
        require(room.role == VmlsRole.KEEPER && ROOM_HEX64.matches(link) && ROOM_HEX_ID.matches(device) && REQUEST_ID.matches(requestId))
        fun drop(reason: Reason) = Verdict.Drop(reason) to room
        if (!seen.add(requestId)) return drop(Reason.DUPLICATE)
        bound(seen, MAX_SEEN)
        if (room.invite != link || !room.canSend) return drop(Reason.RETIRED)
        // A prompt left unanswered lapses with the guest's wait; the caller dismisses it.
        open[room.session]?.let { (_, opened) -> if (now - opened < PROMPT_SECONDS) return drop(Reason.BUSY) }
        open -= room.session
        if (device in room.asked) return drop(Reason.ASKED)
        // A prompt dated after [now] (the clock went back) still counts.
        val recent = room.prompted.filter { now - it < HOUR }
        if (recent.size >= MAX_PER_HOUR || room.asked.size >= VmlsRoom.MAX_ASKED) return drop(Reason.RATE)
        open[room.session] = requestId to now
        bound(open.keys, MAX_ROOMS, except = room.session)
        return Verdict.Ask to room.copy(asked = room.asked + device, prompted = (recent + now).sorted())
    }

    /** The keeper answered [requestId], either way, or its prompt was dismissed: the room's prompt closes. */
    @Synchronized fun answered(session: String, requestId: String) {
        if (open[session]?.first == requestId) open -= session
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
        private const val MAX_ROOMS = 64
        private val REQUEST_ID = Regex("[A-Za-z0-9_-]{1,128}")
    }
}
