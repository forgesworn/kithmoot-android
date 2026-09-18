package dev.forgesworn.kithmoot.ui

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
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
 * holder to finish and then takes the gate, which is what a room tap does.
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
     *
     * Whether the gate was taken is recorded outside the timeout, and never
     * inferred from how [withTimeout] finished. A timeout firing in the
     * instant between the flip and the return would otherwise report a
     * refusal while holding the gate for the rest of the process's life -
     * every later room tap waiting thirty seconds on a holder that does not
     * exist. Cancellation from the caller's own scope gives the gate back,
     * because nobody is left to release it.
     */
    suspend fun awaitAcquire(timeoutMs: Long): Boolean {
        var taken = false
        try {
            withTimeout(timeoutMs) {
                while (!tryAcquire()) _held.first { !it }
                taken = true
            }
        } catch (_: TimeoutCancellationException) {
            // Fell through: `taken` is the truth, not the exception.
        } catch (cancelled: CancellationException) {
            if (taken) release()
            throw cancelled
        }
        return taken
    }

    /** Gives the gate back. Safe to call when it is not held. */
    fun release() {
        _held.value = false
    }
}

/**
 * The one request waiting on something: the latest, never the first.
 *
 * A person who taps a room, changes their mind and taps a different one is
 * asking for the second room. Keeping the first and refusing the second -
 * which is what a plain "one waiter allowed" flag does - opens the room they
 * decided against, which is worse than the silence it replaced.
 */
class LatestRequest<T : Any> {

    private val slot = AtomicReference<T?>(null)

    /** What is waiting, if anything. */
    val waiting: T? get() = slot.get()

    /**
     * Puts [value] in the slot, replacing whatever was there.
     *
     * True when the slot was empty, which is the caller's signal that it owns
     * the waiting and should start it; false means somebody is already
     * waiting and will pick this up instead of what they came for.
     */
    fun offer(value: T): Boolean = slot.getAndSet(value) == null

    /** Takes what is waiting and empties the slot. */
    fun take(): T? = slot.getAndSet(null)
}
