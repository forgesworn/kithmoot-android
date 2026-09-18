package dev.forgesworn.kithmoot.ui

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/**
 * The one-at-a-time gate around opening, changing and closing a room.
 *
 * Two rooms open at once would be two sets of sockets, two engines and two
 * claims on the same device key, so exactly one of these acts may be in
 * flight. That part was never in doubt. What was wrong was what happened to
 * the second tap: the gate was a bare `AtomicBoolean` and every caller did
 * `if (!compareAndSet(false, true)) return`, so the tap vanished with no
 * mark on the screen at all.
 *
 * Leaving a room is the case that made it a complaint. `leave()` hands the
 * person back to the start screen at once and then holds this gate for the
 * whole teardown - a farewell to publish over relays that may be slow or
 * gone, a WebRTC engine to dispose, a pile of sockets to close. For those
 * seconds the start screen looks idle and ready, and a room tapped on it was
 * silently ignored. Three taps forty seconds apart is what that feels like.
 *
 * So there are two ways in. [tryAcquire] is the old one, for acts that have
 * something else to say when they are refused. [awaitAcquire] waits for the
 * holder to finish and then takes the gate, which is what a room tap does:
 * the person is told the last room is still closing, and their tap is
 * carried out when it is, rather than being asked to tap again.
 */
class EntryGate {

    private val _held = MutableStateFlow(false)

    /** True while somebody holds the gate. Drives the "still finishing" state. */
    val held: StateFlow<Boolean> get() = _held.asStateFlow()

    /** Takes the gate if it is free. False means somebody else holds it. */
    fun tryAcquire(): Boolean = _held.compareAndSet(expect = false, update = true)

    /**
     * Waits for the gate and takes it, giving up after [timeoutMs].
     *
     * False means the holder is still not finished, which is a fault worth a
     * message rather than a wait without end: a teardown wedged on a dead
     * relay must not leave the room list permanently unusable.
     */
    suspend fun awaitAcquire(timeoutMs: Long): Boolean = try {
        withTimeout(timeoutMs) {
            while (!tryAcquire()) _held.first { !it }
            true
        }
    } catch (_: TimeoutCancellationException) {
        false
    }

    /** Gives the gate back. Safe to call when it is not held. */
    fun release() {
        _held.value = false
    }
}
