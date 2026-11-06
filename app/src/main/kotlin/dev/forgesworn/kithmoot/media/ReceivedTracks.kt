package dev.forgesworn.kithmoot.media

/** Capture borrowed callback objects before returning to their owner. Releases
 * run outside the store monitor; an already captured UI snapshot may still be
 * detaching its sinks. The retained object's own lifetime guards that detach. */
internal class ReceivedTracks<T : Any>(
    private val retain: (String, T) -> T,
    private val release: (T) -> Unit,
    private val sameSource: (T, T) -> Boolean = { held, incoming -> held === incoming },
) {
    private val monitor = Any()
    private val tracks = linkedMapOf<String, T>()
    private var closed = false

    val values: List<T> get() = synchronized(monitor) { tracks.values.toList() }

    operator fun set(id: String, source: T) {
        val previous = synchronized(monitor) {
            if (closed) return
            val previous = tracks[id]
            if (previous != null && sameSource(previous, source)) return
            tracks[id] = retain(id, source)
            previous
        }
        previous?.let(release)
    }

    fun remove(id: String) {
        synchronized(monitor) { tracks.remove(id) }?.let(release)
    }

    fun close() {
        val released = synchronized(monitor) {
            if (closed) return
            closed = true
            tracks.values.toList().also { tracks.clear() }
        }
        released.forEach(release)
    }
}
