package dev.forgesworn.kithmoot.node

import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.notifications.ActiveRoomRegistry
import dev.forgesworn.kithmoot.relay.OkHttpRelaySockets
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Node rooms that already have a keeper answering in this process: an open
 * room's or the background service's. A closed-room keeper stands aside for
 * them, so one room never has two keepers answering the same ask. */
internal object NodeKeeperClaims {
    private val held = ConcurrentHashMap<String, Int>()
    fun claim(room: String): () -> Unit {
        held.merge(room, 1, Int::plus)
        val released = AtomicBoolean(false)
        return { if (released.compareAndSet(false, true)) held.computeIfPresent(room) { _, count -> if (count <= 1) null else count - 1 } }
    }
    fun claimed(room: String): Boolean = (held[room] ?: 0) > 0
}

/**
 * Lets known members' devices into the node rooms this device keeps while
 * those rooms are not open here, for as long as the app process runs, so a
 * member's device that missed a move follows as soon as any of the owner's
 * devices is running. Known means the keeper's journal or a members-only
 * room's policy ([NodeKeeperAdmissions.knows]); anybody else still waits for
 * the open room's let-in card. A removed person never comes in, and a person
 * blocked on this phone is skipped ([NodeKeeperBlocks]).
 *
 * [refresh] starts a keeper for each due room and stops any no longer due or
 * no longer permitted. It is cheap, and the app's minute loops call it.
 */
internal class NodeClosedRoomKeepers(
    private val scope: CoroutineScope,
    /** Keeper custody for each node room this device could keep now. */
    private val candidates: () -> List<NodeCustody>,
    /** A fresh check of whether a closed-room keeper may run for a room, taken once per keeper. */
    private val gate: () -> (String) -> Boolean,
    /** Opens one room's keeper inside its own scope. */
    private val start: (NodeCustody, CoroutineScope, () -> Boolean) -> AutoCloseable,
) {
    private class Running(val descriptor: String, val scope: CoroutineScope, val handle: AutoCloseable, val allowed: () -> Boolean)
    private val running = HashMap<String, Running>()

    val rooms: Set<String> @Synchronized get() = running.keys.toSet()

    @Synchronized fun refresh() {
        val due = try { candidates() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { emptyList() }
            .associateBy { it.node.room }
        for ((room, run) in running.entries.toList()) {
            if (due[room]?.node?.event?.id != run.descriptor || !run.allowed()) stop(room)
        }
        for ((room, custody) in due) {
            if (room in running) continue
            val check = gate()
            if (!check(room)) continue
            val child = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
            val allowed = { child.isActive && check(room) }
            try { running[room] = Running(custody.node.event.id, child, start(custody, child, allowed), allowed) }
            catch (cancelled: CancellationException) { child.cancel(); throw cancelled }
            catch (_: Exception) { child.cancel() }
        }
    }

    @Synchronized fun close() { running.keys.toList().forEach(::stop) }

    private fun stop(room: String) {
        val run = running.remove(room) ?: return
        try { run.handle.close() } catch (_: Exception) { } finally { run.scope.cancel() }
    }

    companion object {
        fun android(application: KithMootApplication): NodeClosedRoomKeepers {
            val store = NodeStore.android(application)
            val sockets by lazy { OkHttpRelaySockets(OkHttpRelaySockets.backgroundClient()) }
            val now = { System.currentTimeMillis() / 1000 }
            return NodeClosedRoomKeepers(CoroutineScope(SupervisorJob() + Dispatchers.IO),
                candidates = {
                    if (!application.accountNetwork.allowsNostr) emptyList() else application.savedRooms.list().mapNotNull { summary ->
                        runCatching {
                            val saved = application.savedRooms.get(summary.id) ?: return@runCatching null
                            val node = saved.nodeRoom ?: return@runCatching null
                            store.custody(saved.participant, saved.id)?.takeIf { held ->
                                held.node.event.id == node.event.id && held.device == saved.devicePubkey && held.wire["rootKey"] != null &&
                                    held.phase == "admitted" && held.pendingClose == null && held.pendingRekey == null && (node.endsAt ?: Long.MAX_VALUE) > now()
                            }
                        }.getOrNull()
                    }
                },
                gate = {
                    val network = application.accountNetwork.token()
                    val check: (String) -> Boolean = { room ->
                        application.accountNetwork.current(network) && !ActiveRoomRegistry.isOpen(room) && !NodeKeeperClaims.claimed(room)
                    }
                    check
                },
                start = { custody, child, allowed ->
                    val node = custody.node
                    val client = NodeClient(now = now, allowed = allowed)
                    val keeper = NodeKeeperAdmissions(node, custody.participant, store, { event, root -> client.postGrant(node, event, root) }, allowed, now)
                    val inbox = NodeKeeperInbox(node, custody.participant, store, keeper, application.roomEpochs,
                        application.accountNetwork.ordinarySockets(sockets), child, allowed, now, yields = true,
                        blocked = NodeKeeperBlocks.predicate(application, custody.participant))
                    inbox.start()
                    AutoCloseable { inbox.close() }
                })
        }
    }
}
