package dev.forgesworn.kithmoot.relay

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import dev.forgesworn.kithmoot.BuildConfig
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Process-ephemeral opt-in; no receiver, permission, preference or network route.
 * Only the debug app exports bounded numeric diagnostics to its own private file.
 * The runner must collect it with run-as and retain its request/clock brackets. */
object OrbotTrace {
    const val ENABLE = "dev.forgesworn.kithmoot.TOR_TRACE_ENABLE"
    const val SNAPSHOT = "dev.forgesworn.kithmoot.TOR_TRACE_SNAPSHOT"
    const val REQUEST = "dev.forgesworn.kithmoot.TOR_TRACE_REQUEST"
    @Volatile private var buffer: RelayTraceBuffer? = null
    private val exporting = AtomicBoolean()

    fun enable(intent: Intent) {
        if (BuildConfig.DEBUG && intent.getBooleanExtra(ENABLE, false) && buffer == null) {
            buffer = RelayTraceBuffer(elapsedNanos = SystemClock::elapsedRealtimeNanos)
        }
    }

    fun session(): RelaySessionTrace? = if (BuildConfig.DEBUG) buffer?.session() else null

    fun snapshotRequested(intent: Intent): Boolean =
        BuildConfig.DEBUG && intent.getBooleanExtra(SNAPSHOT, false)

    fun beginSnapshot(intent: Intent) {
        if (BuildConfig.DEBUG) buffer?.record(0, 0, 0, RelayTraceEvent.SNAPSHOT_REQUEST, intent.getLongExtra(REQUEST, 0))
    }

    fun export(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return
        val trace = buffer ?: return
        val request = intent.getLongExtra(REQUEST, 0)
        if (request <= 0 || !exporting.compareAndSet(false, true)) { trace.exportFailed(); return }
        trace.record(0, 0, 0, RelayTraceEvent.EXPORT_REQUEST, request)
        // The activity/callback threads never perform file or logcat I/O.
        try {
            thread(name = "orbot-trace-export", isDaemon = true) {
                try {
                    val snapshot = trace.snapshot(request)
                    val pending = File(context.filesDir, "tor-trace.json.part")
                    pending.writeText("{\"pid\":${android.os.Process.myPid()},\"clock\":\"elapsedRealtimeNanos\",\"trace\":${snapshot.json().trim()}}\n")
                    check(pending.renameTo(File(context.filesDir, "tor-trace.json")))
                } catch (_: Throwable) { trace.exportFailed() }
                finally { exporting.set(false) }
            }
        } catch (_: Throwable) { trace.exportFailed(); exporting.set(false) }
    }
}
