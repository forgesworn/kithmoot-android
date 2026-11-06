package dev.forgesworn.kithmoot.media

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Serialises entry into synchronous JNI calls with the decision to dispose. */
internal class NativePeerLifetime(private val monitor: Any) {
    private var closed = false

    fun <T> use(action: () -> T): T = synchronized(monitor) {
        check(!closed) { "peer connection is closed" }
        action()
    }

    fun close(dispose: () -> Unit) {
        val first = synchronized(monitor) {
            if (closed) false else { closed = true; true }
        }
        // Native close waits for its signalling callbacks. They must be free
        // to return rather than finding the JNI-entry monitor held here.
        if (first) dispose()
    }
}

/** A native callback cannot wait for a monitor held by a blocking JNI caller. */
internal fun dispatchPeerCallback(scope: CoroutineScope, action: () -> Unit) =
    scope.launch(Dispatchers.Default) { action() }
