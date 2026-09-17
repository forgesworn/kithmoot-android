package dev.forgesworn.kithmoot.support

import dev.forgesworn.kithmoot.media.SignalEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * A signalling path that behaves like a relay on a bad day.
 *
 * The unit-level sibling of the web suite's `test/fault-transport.ts`, and the
 * reason [dev.forgesworn.kithmoot.media.SignalChannel] can be judged at all: a
 * channel that is only ever handed a perfect wire proves nothing, because the
 * thing it exists to survive never happens.
 *
 * Every fault is drawn from a seeded [Random], so a failing run is a failing
 * run again tomorrow. The four the spec names:
 *
 * - **drop**: [dropRate] of signals never arrive.
 * - **delay**: each signal waits a uniform time up to [maxDelayMs].
 * - **duplicate**: [duplicateRate] of signals arrive twice.
 * - **reorder**: not a setting of its own - it falls out of independent delays.
 *   Two signals sent a moment apart, held for 40ms and 5ms, arrive the wrong
 *   way round, which is exactly how a relay fanning out over several sockets
 *   reorders them.
 *
 * [connected] is the half-open socket: while it is false everything published
 * is recorded and nothing is delivered, and the sender finds out only when
 * something tells it the transport came back.
 */
class FaultTransport(
    private val scope: CoroutineScope,
    seed: Int = 1,
) {

    private val random = Random(seed)

    /** Fraction of signals that never arrive, from 0 to 1. */
    var dropRate: Double = 0.0

    /** A scripted drop, for a case with a name - "the first three answers" -
     *  rather than a rate. Consulted before [dropRate]. */
    var dropIf: (SignalEnvelope) -> Boolean = { false }

    /** Fraction of signals that arrive twice. */
    var duplicateRate: Double = 0.0

    /** Upper bound of the uniform per-signal latency. Zero means immediate. */
    var maxDelayMs: Long = 0

    /** False is a half-open socket: published, never delivered, nobody told. */
    var connected: Boolean = true

    /** Everything handed to this transport, whatever became of it. */
    val published = mutableListOf<SignalEnvelope>()

    /** Everything that actually reached the far end, in arrival order. */
    val arrived = mutableListOf<SignalEnvelope>()

    /** Where a delivered signal goes. Set once the far end exists. */
    var sink: (suspend (SignalEnvelope) -> Unit)? = null

    /** How many signals of a type were published, delivered or not. */
    fun publishedOfType(type: String): List<SignalEnvelope> = published.filter { it.type == type }

    suspend fun send(envelope: SignalEnvelope) {
        published += envelope
        if (!connected) return
        if (dropIf(envelope)) return
        if (random.nextDouble() < dropRate) return
        val copies = if (random.nextDouble() < duplicateRate) 2 else 1
        repeat(copies) {
            val wait = if (maxDelayMs > 0) random.nextLong(maxDelayMs + 1) else 0L
            scope.launch {
                if (wait > 0) delay(wait)
                arrived += envelope
                sink?.invoke(envelope)
            }
        }
    }
}
