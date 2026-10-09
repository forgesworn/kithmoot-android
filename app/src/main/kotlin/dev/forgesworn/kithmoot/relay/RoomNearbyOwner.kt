package dev.forgesworn.kithmoot.relay

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex

/** A process-wide lease: the call and chat view models cannot own two radios.
 * A failed teardown poisons this process's lease rather than risking overlap. */
internal class RoomNearbyOwnership {
    private val gate = Mutex()
    @Volatile private var failure: Throwable? = null

    suspend fun open(config: RoomBleConfig, factory: () -> NativeRoomMeshLink): RoomNearbyOwner {
        gate.lock()
        var link: NativeRoomMeshLink? = null
        try {
            check(failure == null) { "Bluetooth teardown failed; restart KithMoot before reopening a nearby room" }
            link = factory()
            link.start(config)
            return RoomNearbyOwner(link) { error ->
                if (error != null) failure = error
                gate.unlock()
            }
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                try { link?.close(); link?.awaitClosed() }
                catch (close: Throwable) { failure = close }
                finally { gate.unlock() }
            }
            throw error
        }
    }
}

internal class RoomNearbyOwner(val link: NativeRoomMeshLink, private val release: (Throwable?) -> Unit) : AutoCloseable {
    private val closing = CompletableDeferred<Unit>()
    private var closed = false
    override fun close() = synchronized(this) {
        if (!closed) {
            closed = true
            link.close()
            // Survives view model cancellation and never opens another radio.
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                var failure: Throwable? = null
                try { link.awaitClosed() } catch (error: Throwable) { failure = error }
                release(failure)
                if (failure == null) closing.complete(Unit) else closing.completeExceptionally(failure)
            }
        }
    }
    suspend fun awaitClosed() = closing.await()
}

internal val roomNearbyOwnership = RoomNearbyOwnership()
