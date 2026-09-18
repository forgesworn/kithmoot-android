package dev.forgesworn.kithmoot.ui.room

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Builds one expensive thing, however many callers ask for it at once.
 *
 * The WebRTC engine is the thing. Two callers reach `startMedia` at a
 * recovered epoch - the epoch-ready callback and the waiter that starts the
 * room when its epoch finally goes active - and the guard between them was
 * "is there an engine yet", which is only true some seconds later, after ICE
 * has been resolved and a peer connection factory built. Both passed, both
 * built, and the second assignment dropped the first engine on the floor
 * still holding a camera, a microphone and every peer connection it had made.
 *
 * So the claim is taken BEFORE the first suspension, and anything built by a
 * caller that no longer has anywhere to put it is disposed rather than
 * abandoned. Neither is something the calling code can be trusted to
 * remember, which is why it is a type.
 */
class SingleBuild<T : Any>(private val discard: (T) -> Unit) {

    private val building = AtomicBoolean(false)

    /** True while a build is in flight. */
    val inFlight: Boolean get() = building.get()

    /**
     * @param held what is already in place, if anything: no build is started
     *   over one, and the check is made again under [install].
     * @param make the expensive part. Null means it could not be built, which
     *   is the caller's business to report.
     * @param install put it in place, under whatever lock the caller keeps.
     *   False means the caller no longer wants it - the session moved on, or
     *   something else got there first - and it is disposed.
     * @return true when this call is the one that built and installed it.
     */
    suspend fun build(held: () -> T?, make: suspend () -> T?, install: (T) -> Boolean): Boolean {
        if (held() != null) return false
        if (!building.compareAndSet(false, true)) return false
        var made: T? = null
        var installed = false
        try {
            made = make() ?: return false
            if (install(made)) {
                installed = true
                made = null
            }
            return installed
        } finally {
            building.set(false)
            // Cancellation lands here too: a build the caller stopped waiting
            // for still produced a thing that holds hardware.
            made?.let { runCatching { discard(it) } }
        }
    }
}
