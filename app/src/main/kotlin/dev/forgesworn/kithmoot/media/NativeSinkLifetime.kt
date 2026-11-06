package dev.forgesworn.kithmoot.media

/** Owns a track's Java sink map as well as its native reference. Unlike peer
 * teardown, sink disposal must finish before another sink operation enters. */
internal class NativeSinkLifetime {
    private var closed = false

    @Synchronized
    fun <T> use(fallback: T, action: () -> T): T = if (closed) fallback else action()

    @Synchronized
    fun close(dispose: () -> Unit) {
        if (closed) return
        closed = true
        dispose()
    }
}
