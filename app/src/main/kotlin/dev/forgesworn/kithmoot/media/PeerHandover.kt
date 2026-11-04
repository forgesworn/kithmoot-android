package dev.forgesworn.kithmoot.media

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** At most one retiring transport per peer. A subsequent repair changes the
 * replacement identity, never the original retirement timer. */
internal class PeerHandover<L : Any>(private val scope: CoroutineScope, private val close: (L) -> Unit) {
    private class Held<L>(val old: L, var replacement: L) { var timeout: Job? = null; var completion: Job? = null }
    private val lock = Any()
    private val held = mutableMapOf<String, Held<L>>()

    fun replace(device: String, old: L, replacement: L, connected: Boolean, graceMillis: Long) {
        var discard = false
        synchronized(lock) {
            val existing = held[device]
            if (existing != null) {
                existing.replacement = replacement
                existing.completion?.cancel(); existing.completion = null
                discard = old !== existing.old
            } else if (connected && graceMillis > 0) {
                val value = Held(old, replacement)
                held[device] = value
                value.timeout = scope.launch { delay(graceMillis.coerceAtMost(20_000)); remove(device, value) }
            } else discard = true
        }
        if (discard) close(old)
    }

    fun connected(device: String, replacement: L) = synchronized(lock) {
        val value = held[device] ?: return@synchronized
        if (value.replacement !== replacement || value.completion != null) return@synchronized
        value.completion = scope.launch { delay(1_000); remove(device, value, replacement) }
    }

    fun owns(device: String, peer: L): Boolean = synchronized(lock) { held[device]?.old === peer }
    fun snapshot(): List<Pair<String, L>> = synchronized(lock) { held.map { it.key to it.value.old } }
    fun remove(device: String) { val value = synchronized(lock) { held.remove(device) } ?: return; dispose(value) }
    fun clear() { val values = synchronized(lock) { held.values.toList().also { held.clear() } }; values.forEach(::dispose) }
    private fun remove(device: String, value: Held<L>, replacement: L? = null) {
        val removed = synchronized(lock) {
            if (held[device] !== value || replacement != null && value.replacement !== replacement) false
            else { held.remove(device); true }
        }
        if (removed) dispose(value)
    }
    private fun dispose(value: Held<L>) { value.timeout?.cancel(); value.completion?.cancel(); close(value.old) }
}
