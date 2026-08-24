package dev.forgesworn.kithmoot.relay

/**
 * A bounded set of event ids, oldest evicted first.
 *
 * Publishing to every relay means receiving the same event from every relay, so
 * something has to remember what it has already delivered. It has to be bounded:
 * a room left open all day would otherwise accumulate an id for every heartbeat
 * from every device until the process ran out of memory.
 */
class SeenEvents(private val capacity: Int = 4096) {

    private val seen = object : LinkedHashMap<String, Boolean>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean =
            size > capacity
    }

    /** True the first time an id is offered, false every time after. */
    @Synchronized
    fun admit(id: String): Boolean = seen.put(id, true) == null

    @Synchronized
    fun size(): Int = seen.size

    @Synchronized
    fun clear() = seen.clear()
}
