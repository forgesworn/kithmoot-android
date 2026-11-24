package dev.forgesworn.kithmoot.node

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serialises renewal and route repairs without moving the oldest allocation's
 * deadline when only one pair is repaired. Credentials stay in the caller. */
internal class NodeCallRecovery(
    private val permitted: () -> Boolean,
    private val remainingMillis: () -> Long,
    private val refresh: suspend () -> Unit,
    private val replace: suspend (Set<String>?, Long, stillCurrent: () -> Boolean) -> Unit,
    private val terminate: () -> Unit,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val operation = Mutex()
    private val lock = Any()
    private data class Work(val peers: Set<String>?, val route: Long)
    private var route = 0L
    private var all = false
    private val peers = linkedSetOf<String>()
    private var rotationAt = clock() + remainingMillis() - 60_000
    private var retryAt = Long.MIN_VALUE
    @Volatile private var stopped = false

    fun request(peer: String? = null) = synchronized(lock) {
        if (!stopped) {
            if (peer == null) {
                route++
                all = true
                peers.clear()
                retryAt = Long.MIN_VALUE
            }
            else if (!all) peers.add(peer)
        }
    }

    suspend fun tick() = operation.withLock {
        if (stopped) return@withLock
        if (!permitted() || remainingMillis() <= 0) { stop(); return@withLock }
        val work = synchronized(lock) {
            if (clock() < retryAt) return@withLock
            if (clock() >= rotationAt) all = true
            if (!all && peers.isEmpty()) return@withLock
            val targets = Work(if (all) null else peers.toSet(), route)
            all = false; peers.clear()
            targets
        }
        fun stillCurrent() = synchronized(lock) { route == work.route }
        val grace = remainingMillis().coerceIn(0, 20_000)
        try {
            refresh()
            if (stopped) return@withLock
            check(permitted() && remainingMillis() > 0)
            if (!stillCurrent()) return@withLock
            replace(work.peers, grace, ::stillCurrent)
            synchronized(lock) {
                if (route != work.route) return@withLock
                // A pair-only refresh must not postpone every other pair's renewal.
                if (work.peers == null) rotationAt = clock() + remainingMillis() - 60_000
                retryAt = clock() + 5_000
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (failure is NodeRequestException && failure.retryable && permitted() && remainingMillis() > 0) {
                synchronized(lock) {
                    if (route != work.route) return@withLock
                    if (work.peers == null) { all = true; peers.clear() }
                    else if (!all) peers.addAll(work.peers)
                    retryAt = clock() + 2_000
                }
            } else stop()
        }
    }

    private fun stop() { if (!stopped) { stopped = true; terminate() } }
}
