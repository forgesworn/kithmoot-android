package dev.forgesworn.kithmoot.media

import java.util.concurrent.ConcurrentHashMap

/**
 * The engine's table of one link per remote device, readable from any thread.
 *
 * The engine mutates it under its own lock, so two reconciles cannot open one
 * device twice. What this class exists for is the other direction: a libwebrtc
 * callback, which arrives on the signalling thread, may ask "is this link still
 * the device's current one" WITHOUT that lock.
 *
 * Closing a peer connection waits for the signalling thread. If the signalling
 * thread is waiting for the engine lock, and the engine is holding that lock
 * while it closes, neither can move, and five seconds later Android calls the
 * app not responding. That was every freeze on 0.6.9: three traces, each with
 * the main thread inside `PeerConnection.close` under the lock and the
 * signalling thread "waiting to lock, held by thread 1". So identity reads here
 * take no lock, and [reconcile] hands back what it removed so the engine can
 * close it after the lock has gone.
 */
internal class LinkTable<L : Any> {
    private val links = ConcurrentHashMap<String, L>()

    operator fun get(device: String): L? = links[device]

    /** Read from any thread, never under the engine lock. */
    fun isCurrent(device: String, link: L): Boolean = links[device] === link

    val devices: Set<String> get() = links.keys.toSet()

    fun values(): List<L> = links.values.toList()

    fun snapshot(): List<Pair<String, L>> = links.entries.map { it.key to it.value }

    fun put(device: String, link: L) {
        links[device] = link
    }

    fun remove(device: String): L? = links.remove(device)

    /** Empties the table and hands back what was in it, for closing outside the lock. */
    fun clear(): List<L> {
        val all = values()
        links.clear()
        return all
    }

    /**
     * Applies a roster: opens, under [lock], every device not yet linked, and
     * removes every link whose device has gone. The removed links are handed
     * back, not closed: the caller closes them once it has released [lock].
     */
    fun reconcile(lock: Any, devices: Set<String>, open: (String) -> L): List<Pair<String, L>> = synchronized(lock) {
        for (device in devices - links.keys) links[device] = open(device)
        (links.keys - devices).mapNotNull { device -> links.remove(device)?.let { device to it } }
    }
}
