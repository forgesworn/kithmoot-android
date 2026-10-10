package dev.forgesworn.kithmoot.storage

import dev.forgesworn.kithmoot.BuildConfig
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Opt-in debug observation of a disposable receiver. Never receives seal keys,
 * aliases, store bytes or exception messages, and never reports on a writer. */
internal object ReceiverKeyCreationProbe {
    private val owner = AtomicReference<Session?>()
    private const val MAX_EVENTS = 128
    private const val MAX_CALLERS = 16

    enum class Phase(val wire: String) {
        BASELINE("baseline"), DIRECT_ENTRY("direct-entry"), FOREGROUND_CONSTRUCTION("foreground-construction"),
        FOREGROUND_STARTUP("foreground-startup"), FOREGROUND_REOPEN("foreground-reopen"),
        OWNER_BARRIERS("owner-barriers"), LEASE_CLOSE("lease-close"), CASE_CLEANUP("case-cleanup"),
    }
    enum class Outcome { REQUESTED, CREATED, FAILED }
    data class Context(val window: String, val target: String, val fault: String, val phase: Phase) {
        init {
            require(window in setOf("committed-source-before-offer", "charged-original-before-offer", "offered-before-index",
                "index-committed-before-source-acknowledgement", "reference-installed-before-subscription-switch"))
            require(target in setOf("NONE", "SOURCE", "RECEIVER", "COURIER", "INDEX"))
            require(fault in setOf("NONE", "MISSING", "CORRUPT", "OWNER_DEVICE", "ROUTE_PINS", "INVITATION"))
        }
    }
    data class Event(val sequence: Long, val request: Long, val thread: Long, val context: Context,
        val outcome: Outcome, val callers: List<String>, val callersTruncated: Boolean)
    data class Snapshot(val events: List<Event>, val overflow: Long, val inFlight: Int,
        val activeRecorders: Int, val missingRecords: Long, val observationErrors: Long) {
        val complete get() = overflow == 0L && inFlight == 0 && activeRecorders == 0 && missingRecords == 0L &&
            observationErrors == 0L && events.none { it.callersTruncated }
    }
    class Token internal constructor(internal val session: Session, internal val request: Long,
        internal val context: Context, internal val callers: List<String>, internal val truncated: Boolean) {
        internal val finished = AtomicBoolean()
    }

    fun open(context: Context): Session {
        check(BuildConfig.DEBUG) { "Receiver observation is debug-only" }
        return Session(context).also { check(owner.compareAndSet(null, it)) { "Receiver observation already has an owner" } }
    }

    /** The key boundary has already selected the fixed receiver category. */
    fun requested(): Token? {
        val session = owner.get() ?: return null
        return try { session.request() } catch (_: Throwable) { session.observationFailed(); null }
    }
    fun completed(token: Token?, outcome: Outcome) {
        if (token == null) return
        require(outcome != Outcome.REQUESTED)
        try { token.session.finish(token, outcome) } catch (_: Throwable) { token.session.observationFailed() }
    }

    class Session internal constructor(context: Context) : AutoCloseable {
        private val closed = AtomicBoolean()
        private val sequence = AtomicLong()
        private val reservations = AtomicLong()
        private val overflow = AtomicLong()
        private val inFlight = AtomicInteger()
        private val activeRecorders = AtomicInteger()
        private val observationErrors = AtomicLong()
        private val events = ConcurrentLinkedQueue<Event>()
        @Volatile private var context = context

        fun phase(value: Context) { check(!closed.get()); context = value }
        internal fun observationFailed() { observationErrors.incrementAndGet() }

        internal fun request(): Token? {
            if (closed.get()) return null
            val captured = context
            val callers = Throwable().stackTrace.filter {
                it.className.startsWith("dev.forgesworn.kithmoot.") &&
                    it.className != ReceiverKeyCreationProbe::class.java.name &&
                    !it.className.startsWith(ReceiverKeyCreationProbe::class.java.name + "$" )
            }
            val public = callers.take(MAX_CALLERS).map { frame ->
                // Static class/method/line only; never Throwable.toString().
                "${frame.className}#${frame.methodName}:${frame.lineNumber}"
            }
            val token = Token(this, sequence.incrementAndGet(), captured, public, callers.size > MAX_CALLERS)
            inFlight.incrementAndGet()
            record(token, Outcome.REQUESTED, token.request)
            return token
        }

        internal fun finish(token: Token, outcome: Outcome) {
            if (!token.finished.compareAndSet(false, true)) return
            try { record(token, outcome, sequence.incrementAndGet()) }
            finally { inFlight.decrementAndGet() }
        }

        private fun record(token: Token, outcome: Outcome, order: Long) {
            activeRecorders.incrementAndGet()
            try {
                if (closed.get()) return
                if (reservations.getAndIncrement() >= MAX_EVENTS) { overflow.incrementAndGet(); return }
                events.add(Event(order, token.request, Thread.currentThread().id, token.context, outcome,
                    token.callers, token.truncated))
            } finally { activeRecorders.decrementAndGet() }
        }

        override fun close() { if (closed.compareAndSet(false, true)) owner.compareAndSet(this, null) }

        fun closeAndSnapshot(): Snapshot {
            close()
            val writers = activeRecorders.get()
            val captured = events.toList().sortedBy { it.sequence }
            return Snapshot(captured, overflow.get(), inFlight.get(), writers,
                (minOf(reservations.get(), MAX_EVENTS.toLong()) - captured.size).coerceAtLeast(0), observationErrors.get())
        }
    }
}
