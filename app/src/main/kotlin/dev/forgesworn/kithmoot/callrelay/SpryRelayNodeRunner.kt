package dev.forgesworn.kithmoot.callrelay

import dev.forgesworn.spry.SpryRelayNative

/**
 * Runs the node in this process through [SpryRelayNative] (spry-turn
 * docs/embedding.md, "The JNI shim"), on threads of its own:
 *
 * - one reader thread per run loops on `nextLine` until it returns an empty
 *   array (the `stopped` line was read), then frees the handle itself, so
 *   `free` never runs on the main thread and never before the reader ends;
 * - [RelayNode.stop] calls `stop`, which returns at once; the node removes its
 *   router mappings and ends, and the reader sees `stopped`;
 * - if the node has not ended [STOP_GRACE_MS] after a stop, the reader gives
 *   up and frees the handle anyway (`free` stops, waits seven seconds and
 *   detaches a node that still runs).
 *
 * Lines are parsed strictly ([RelayNodeLine]) and only checked events are
 * handed on; nothing the node prints is logged. [post] moves every event onto
 * the controller's thread.
 */
internal class SpryRelayNodeRunner(private val post: (Runnable) -> Unit) : RelayNodeStarter {
    override fun start(args: List<String>, environment: List<String>, events: RelayNodeEvents): RelayNode? {
        val handle = SpryRelayNative.start(args.toTypedArray(), environment.toTypedArray())
        if (handle == 0L) return null
        val run = Run(handle)
        val reader = Thread({ read(run, events) }, "call-relay-reader")
        reader.isDaemon = true
        try { reader.start() } catch (error: Throwable) {
            SpryRelayNative.stop(handle)
            Thread({ SpryRelayNative.free(handle) }, "call-relay-free").apply { isDaemon = true }.start()
            return null
        }
        return RelayNode { run.stop() }
    }

    private class Run(val handle: Long) {
        private val lock = Any()
        /** Set before `free`; nothing may touch the handle after. */
        private var ending = false
        @Volatile var stopAskedAt = 0L

        fun stop() {
            synchronized(lock) {
                if (ending) return
                if (stopAskedAt == 0L) stopAskedAt = System.nanoTime()
                SpryRelayNative.stop(handle)
            }
        }

        fun end() = synchronized(lock) { ending = true }
    }

    private fun read(run: Run, events: RelayNodeEvents) {
        var code = -1
        var crashed = false
        var detached = false
        try {
            while (true) {
                val line = SpryRelayNative.nextLine(run.handle, READ_TIMEOUT_MS)
                if (line == null) {
                    val asked = run.stopAskedAt
                    if (asked != 0L && (System.nanoTime() - asked) / 1_000_000 > STOP_GRACE_MS) break
                    continue
                }
                if (line.isEmpty()) break
                val event = RelayNodeLine.parse(line) ?: continue
                if (event is RelayNodeEvent.Stopped) code = event.code
                post(Runnable { events.line(event) })
            }
            if (code < 0) code = SpryRelayNative.exitCode(run.handle)
        } catch (_: Throwable) {
            crashed = true
        } finally {
            run.end()
            try { detached = SpryRelayNative.free(run.handle) != 0 } catch (_: Throwable) { detached = true }
            val finalCode = code
            val died = crashed
            val gone = detached
            post(Runnable { events.ended(finalCode, died, gone) })
        }
    }

    private companion object {
        const val READ_TIMEOUT_MS = 1000
        /** The node removes its mappings within five seconds of a stop. */
        const val STOP_GRACE_MS = 9_000L
    }
}
