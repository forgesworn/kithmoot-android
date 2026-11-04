package dev.forgesworn.kithmoot.node

/** Synchronous callers must observe completed teardown, including when a
 * different thread already started it. Factory disposal follows media stop. */
internal class NodeCallShutdown(private val stop: () -> Unit, private val release: () -> Unit) {
    private var stopped = false
    private var released = false

    @Synchronized fun close() {
        if (stopped) return
        stopped = true
        stop()
    }

    @Synchronized fun dispose() {
        close()
        if (released) return
        released = true
        release()
    }
}
