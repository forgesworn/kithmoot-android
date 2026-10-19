package dev.forgesworn.kithmoot.relay

import java.io.IOException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReferenceArray
import javax.net.ssl.SSLException

/** Diagnostic values only. No caller-supplied strings, URLs, frames or exceptions. */
enum class RelayTraceEvent {
    TRACE_START, SESSION_CREATE, POOL_CREATE, POOL_START, POOL_STOP,
    ATTEMPT_START, ADAPTER_REQUEST, ADAPTER_OPEN, ADAPTER_FAILURE, ADAPTER_CLOSED,
    ADAPTER_CLOSE, ADAPTER_CANCEL, OPEN_DECISION, WATCHDOG_DECISION, CALLBACK_CLOSED,
    LINK_OPEN_ENTER, LINK_OPEN_COMPLETE, LINK_OPEN_FAILED, CONNECTED_PUBLISHED,
    RETRY_SCHEDULED, RETRY_STARTED, ATTEMPT_LOOP_END, SOCKET_OPEN_FAILED,
    SESSION_CURRENT, CONSUMER_OBSERVED, CONSUMER_REJECTED, CONSUMER_APPLIED,
    VISIBILITY, PARK, SESSION_CLOSE, SCOPE_END, STATE_SNAPSHOT, CONSUMER_SNAPSHOT, LINK_SNAPSHOT,
    SNAPSHOT_REQUEST, EXPORT_REQUEST
}

enum class RelayTraceFailure { NONE, IO, TLS, TIMEOUT, PROTOCOL, CANCELLED, OTHER }

fun relayTraceFailure(error: Throwable): RelayTraceFailure = when (error) {
    is SSLException -> RelayTraceFailure.TLS
    is SocketTimeoutException -> RelayTraceFailure.TIMEOUT
    is ProtocolException -> RelayTraceFailure.PROTOCOL
    is CancellationException -> RelayTraceFailure.CANCELLED
    is IOException -> RelayTraceFailure.IO
    else -> RelayTraceFailure.OTHER
}

data class RelayTraceEntry(
    val sequence: Long, val elapsedNanos: Long, val session: Long,
    val link: Long, val attempt: Long, val event: RelayTraceEvent,
    val value: Long, val detail: Long,
)

/** Fixed memory, no I/O, locks or caller code except the supplied monotonic clock.
 * Sequence reservation precedes the slot write. A concurrent/incomplete write,
 * wrap, clock error or missing sequence makes an absence inference invalid.
 * Event sequence/time is observational ordering, not a cross-thread CAS proof. */
class RelayTraceBuffer(
    val capacity: Int = 2048,
    private val elapsedNanos: () -> Long = System::nanoTime,
) {
    init { require(capacity in 2..8192) }
    private val slots = AtomicReferenceArray<RelayTraceEntry>(capacity)
    private val sequence = AtomicLong()
    private val sessions = AtomicLong()
    private val errors = AtomicLong()

    init { record(0, 0, 0, RelayTraceEvent.TRACE_START) }

    fun session(): RelaySessionTrace = RelaySessionTrace(this, sessions.incrementAndGet()).also {
        it.record(RelayTraceEvent.SESSION_CREATE)
    }

    internal fun record(session: Long, link: Long, attempt: Long, event: RelayTraceEvent,
                        value: Long = 0, detail: Long = 0) {
        try {
            val next = sequence.incrementAndGet()
            val entry = RelayTraceEntry(next, elapsedNanos(), session, link, attempt, event, value, detail)
            slots.set(((next - 1) % capacity).toInt(), entry)
        } catch (_: Throwable) { errors.incrementAndGet() }
    }

    fun exportFailed() { errors.incrementAndGet() }

    fun snapshot(request: Long): RelayTraceSnapshot {
        val firstError = errors.get()
        val end = sequence.get()
        val start = (end - capacity + 1).coerceAtLeast(1)
        val entries = (0 until capacity).mapNotNull { slots.get(it) }
            .filter { it.sequence in start..end }.sortedBy { it.sequence }
        val missing = end - start + 1 - entries.size
        val lastError = errors.get()
        return RelayTraceSnapshot(request, capacity, end, (end - capacity).coerceAtLeast(0),
            missing, lastError, firstError != lastError || sequence.get() != end, entries)
    }
}

class RelaySessionTrace internal constructor(private val buffer: RelayTraceBuffer, val id: Long) {
    private val links = AtomicLong()
    private val revisions = AtomicLong()
    fun nextLink(): Long = links.incrementAndGet()
    fun nextRevision(): Long = revisions.incrementAndGet()
    fun revision(): Long = revisions.get()
    fun record(event: RelayTraceEvent, value: Long = 0, detail: Long = 0) =
        buffer.record(id, 0, 0, event, value, detail)
    fun attempt(link: Long, attempt: Long): RelayAttemptTrace = RelayAttemptTrace(buffer, id, link, attempt)
}

class RelayAttemptTrace internal constructor(
    private val buffer: RelayTraceBuffer, val session: Long, val link: Long, val attempt: Long,
) {
    fun record(event: RelayTraceEvent, value: Long = 0, detail: Long = 0) =
        buffer.record(session, link, attempt, event, value, detail)
}

data class RelayTraceSnapshot(
    val request: Long, val capacity: Int, val reserved: Long, val overwritten: Long,
    val missing: Long, val errors: Long, val concurrentChange: Boolean,
    val entries: List<RelayTraceEntry>,
) {
    val complete: Boolean get() = overwritten == 0L && missing == 0L && errors == 0L && !concurrentChange

    /** Entire export is bounded by capacity. All text is defined here, never from the relay. */
    fun json(): String = buildString {
        append("{\"schema\":1,\"request\":$request,\"capacity\":$capacity,\"reserved\":$reserved,")
        append("\"overwritten\":$overwritten,\"missing\":$missing,\"errors\":$errors,")
        append("\"concurrentChange\":$concurrentChange,\"complete\":$complete,")
        append("\"columns\":[\"sequence\",\"elapsedNanos\",\"session\",\"link\",\"attempt\",\"event\",\"value\",\"detail\"],")
        append("\"events\":[")
        entries.forEachIndexed { index, e ->
            if (index != 0) append(',')
            append("[${e.sequence},${e.elapsedNanos},${e.session},${e.link},${e.attempt},\"${e.event.name}\",${e.value},${e.detail}]")
        }
        append("]}\n")
    }
}
