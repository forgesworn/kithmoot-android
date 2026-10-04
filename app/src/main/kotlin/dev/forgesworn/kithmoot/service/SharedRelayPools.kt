package dev.forgesworn.kithmoot.service

import dev.forgesworn.kithmoot.relay.LinkRelayAddress
import dev.forgesworn.kithmoot.relay.RelayPool

/**
 * The closed-app listener's pools, one per distinct set of public relays.
 *
 * Every socket pings on its own timer, and every ping wakes the radio, which
 * then stays up for its tail of several seconds. A pool per room put ten saved
 * rooms on the three default relays at thirty sockets: a wake-up every three
 * seconds or so, often enough that the radio barely slept. Rooms that list the
 * same relays now hold one pool between them, each room its own subscriptions
 * on it, so those ten rooms keep three sockets.
 *
 * Keyed by the whole relay set, not by relay, so a pool publishes and
 * subscribes only where every room on it already does: a relay that does not
 * list a room still learns nothing about it.
 *
 * Only rooms with no Link relay share ([canShareBackgroundPool]). A Link socket
 * is routed and authenticated as one room's participant, so those rooms keep a
 * pool of their own.
 */
internal class SharedRelayPools(private val build: (relays: List<String>) -> RelayPool) {
    private class Entry(val pool: RelayPool, var holders: Int)

    private val entries = mutableMapOf<List<String>, Entry>()

    /** The started pool for [relays], in any order; each call needs one [release]. */
    @Synchronized fun acquire(relays: List<String>): RelayPool {
        val key = relays.distinct().sorted()
        val entry = entries.getOrPut(key) { Entry(build(key).also { it.start() }, 0) }
        entry.holders += 1
        return entry.pool
    }

    /** Stops [pool] once the last room holding it lets go. */
    @Synchronized fun release(pool: RelayPool) {
        val (key, entry) = entries.entries.firstOrNull { it.value.pool === pool }?.toPair() ?: return
        entry.holders -= 1
        if (entry.holders > 0) return
        entries.remove(key)
        pool.stop()
    }

    @Synchronized fun stopAll() {
        entries.values.forEach { it.pool.stop() }
        entries.clear()
    }

    /** How many pools are open. */
    @Synchronized fun size(): Int = entries.size
}

/** Whether a room's relays can go on a [SharedRelayPools] pool: none is, or is routed as, a Link relay. */
internal fun canShareBackgroundPool(relays: List<String>, linkRoute: (url: String) -> String?): Boolean =
    relays.none { LinkRelayAddress.looksLikeLink(it) || linkRoute(it) != null }
