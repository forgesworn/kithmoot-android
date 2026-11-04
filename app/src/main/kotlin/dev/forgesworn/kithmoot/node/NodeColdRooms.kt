package dev.forgesworn.kithmoot.node

import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.notifications.ActiveRoomRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Closed screens still check signed device revocation and compact closure.
 * No relay, TURN allocation or account signer is opened for this sweep. */
internal object NodeColdRooms {
    suspend fun sweep(application: KithMootApplication) = coroutineScope {
        val network = application.accountNetwork.token()
        if (!application.accountNetwork.allowsNostr) return@coroutineScope
        val store = NodeStore.android(application)
        val permits = Semaphore(3)
        application.savedRooms.list().map { summary -> async {
            permits.withPermit {
                val saved = application.savedRooms.get(summary.id) ?: return@withPermit
                val node = saved.nodeRoom ?: return@withPermit
                fun allowed() = isActive && application.accountNetwork.current(network) && !ActiveRoomRegistry.isOpen(saved.id)
                if (!allowed()) return@withPermit
                val held = store.custody(saved.participant, saved.id) ?: return@withPermit
                require(held.node.event.id == node.event.id && held.device == saved.devicePubkey)
                val client = NodeClient(allowed = ::allowed)
                val erase: suspend () -> Unit = { withContext(NonCancellable) { application.selfDestructor.run(saved.id, force = true); Unit } }
                try {
                    if (held.pendingClose != null) {
                        NodeRoomCloser(node, saved.participant, store, client::close, erase, ::allowed).retry()
                    } else if (held.grant != null) {
                        val key = held.deviceKey
                        val access = NodeDeviceAccess(node, saved.participant, saved.devicePubkey, store,
                            { id -> client.access(node, id, LocalSigner(key)) }, ::allowed, {}, erase, erase)
                        try { access.refresh() } finally { access.close(); key.fill(0) }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Reset, transport failure and missing data retain local keys. */ }
            }
        } }.awaitAll()
    }
}
