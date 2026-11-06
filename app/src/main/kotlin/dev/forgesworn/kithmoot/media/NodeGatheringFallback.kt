package dev.forgesworn.kithmoot.media

/** Per-connection gathering watchdog. All methods run on the media dispatcher.
 * A route change asks the existing owner for fresh credentials and replacement;
 * it never extends a login or retries an authenticated refusal. */
internal class NodeGatheringFallback(
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val active: () -> Boolean,
    private val advanceRoute: () -> Boolean,
    private val recover: () -> Unit,
    private val hasLocalDescription: () -> Boolean = { true },
    private val routeWorksElsewhere: () -> Boolean = { false },
) {
    private var cancel: (() -> Unit)? = null
    private var relay = false
    private var refused = false
    private var requested = false
    private var established = false
    private var closed = false

    fun gatheringStarted() {
        if (closed) return
        relay = false
        stopTimer()
        arm()
    }
    fun gatheringComplete() = arm()
    fun relayCandidate() { relay = true; stopTimer() }
    fun error(code: Int) {
        if (code in setOf(401, 403, 486, 508)) {
            refused = true
            stopTimer()
        }
        // 701 can describe only one address family. Wait for the other one.
    }
    fun connectionState(isConnected: Boolean) {
        // The owner replaces an established link with fresh credentials. Do
        // not spend its route in parallel while that replacement is waiting.
        if (isConnected) { established = true; stopTimer() }
    }
    fun transportFailed() = fallback()
    private fun arm() {
        if (closed || relay || refused || requested || established || cancel != null) return
        cancel = schedule(4_000) { cancel = null; fallback() }
    }
    private fun fallback() {
        if (closed || refused || requested || established || !active() || !hasLocalDescription()) return
        requested = true
        stopTimer()
        // Another live pair proves this route reaches the node. Repair only
        // this attempted pair; its empty gathering cannot poison the shared route.
        if (routeWorksElsewhere() || advanceRoute()) recover()
    }
    private fun stopTimer() { cancel?.invoke(); cancel = null }
    fun close() { closed = true; stopTimer() }
}
