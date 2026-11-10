package dev.forgesworn.kithmoot.node

import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.epoch.EpochPhase
import dev.forgesworn.kithmoot.epoch.EpochVault
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.RelaySocketFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Root-only ask door. Declining sends nothing; the guest continues waiting.
 * Public display names are untrusted, and no key leaves before both child ACKs. */
internal class NodeKeeperInbox(
    private val node: NodeRoomRecord,
    private val participant: String,
    private val store: NodeStore,
    private val admissions: NodeKeeperAdmissions,
    private val vault: EpochVault,
    private val sockets: RelaySocketFactory,
    private val scope: CoroutineScope,
    private val allowed: () -> Boolean,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    /** A closed-room keeper ([NodeClosedRoomKeepers]): it stands aside for any other keeper of
     * this room in the process. Every other inbox claims its room while it runs. */
    private val yields: Boolean = false,
) {
    private data class Pending(val request: NodeJoinRequest, val event: NostrEvent)
    private val mutex = Mutex()
    private val waiting = LinkedHashMap<String, Pending>()
    private val ignored = LinkedHashSet<String>()
    private val _requests = MutableStateFlow<List<NodeJoinRequest>>(emptyList())
    val requests: StateFlow<List<NodeJoinRequest>> = _requests.asStateFlow()
    private val _trouble = MutableStateFlow(false)
    val trouble: StateFlow<Boolean> = _trouble.asStateFlow()
    private var transport: NodeRoomTransport? = null
    private var job: Job? = null
    private var signingKey: ByteArray? = null
    @Volatile private var releaseClaim: (() -> Unit)? = null
    private fun publishWaiting() {
        waiting.entries.removeAll { now() >= it.value.request.expiresAt }
        _requests.value = waiting.values.map { it.request }
    }
    fun start() {
        check(job == null && allowed())
        val saved = requireNotNull(store.custody(participant, node.room))
        val key = requireNotNull(saved.rootKey)
        val door = try { NodeRoomTransport.create(node, LocalSigner(key), NodeDoor.ASK, sockets, scope, now, allowed) }
            catch (error: Throwable) { key.fill(0); throw error }
        signingKey = key
        transport = door
        val release = if (yields) null else NodeKeeperClaims.claim(node.room)
        releaseClaim = release
        job = scope.launch {
            try {
                val receiving = launch(start = CoroutineStart.UNDISPATCHED) {
                    door.subscribe(listOf(Filter(kinds = listOf(1059), tags = mapOf("#p" to listOf(node.authority)), limit = 128)))
                        .collect {
                            try { ingest(it) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { _trouble.value = true }
                        }
                }
                door.start()
                try {
                    while (isActive && allowed()) {
                        mutex.withLock { publishWaiting() }
                        try { admissions.renewDue(); _trouble.value = false }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { _trouble.value = true }
                        delay(60_000)
                    }
                } finally { receiving.cancel() }
            } finally { door.close(); key.fill(0); release?.invoke() }
        }
    }
    internal suspend fun ingest(event: NostrEvent) = mutex.withLock {
        if (!allowed()) return@withLock
        val saved = store.custody(participant, node.room) ?: return@withLock
        if (saved.node.event.id != node.event.id || saved.pendingClose != null) return@withLock
        val key = saved.rootKey ?: return@withLock
        val request = try { NodeAdmissions.readRequest(node, key, event.toJson(), now()) } finally { key.fill(0) }
            ?: return@withLock
        publishWaiting()
        if (request.id in ignored || request.id in waiting || waiting.size >= 128) return@withLock
        val state = admissions.state()
        // A removed person, or anybody a members-only room does not name, is never even listed.
        if (!admissions.permits(state, request.participant)) return@withLock
        val pending = Pending(request, event)
        waiting[request.id] = pending
        if (admissions.knows(state, request.participant)) respond(pending, false)
        publishWaiting()
    }
    suspend fun admit(requestId: String) = mutex.withLock {
        publishWaiting()
        waiting[requestId]?.let { respond(it, true) }
        publishWaiting()
    }
    suspend fun decline(requestId: String) = mutex.withLock {
        waiting.remove(requestId)
        ignored.add(requestId)
        while (ignored.size > 256) ignored.remove(ignored.first())
        publishWaiting()
    }
    private suspend fun respond(pending: Pending, manual: Boolean) {
        try {
            if (!allowed() || now() >= pending.request.expiresAt) return
            val row = admissions.admit(pending.request.credential, manuallyApproved = manual) ?: return
            NodeKeeperAdmissions.lockFor(participant, node.room).withLock {
                check(allowed())
                val saved = requireNotNull(store.custody(participant, node.room))
                require(saved.pendingClose == null && saved.node.event.id == node.event.id)
                val confirmed = NodeKeeperState.read(saved.keeper, node).devices.single { it.device == row.device }
                require(confirmed.acknowledged && confirmed.grant.event.id == row.grant.event.id
                    && NodeRooms.verifyGrant(confirmed.grant.event.toJson(), node, row.device, now()))
                val epoch = requireNotNull(vault.get(node.room))
                val key = requireNotNull(saved.rootKey); val secret = saved.roomSecret
                try {
                    require(epoch.authority == node.authority && epoch.phase == EpochPhase.ACTIVE && epoch.pending == null)
                    val answer = NodeAdmissions.answer(node, key, pending.event.toJson(), now(),
                        NodeAdmissionGrant(secret, epoch.currentEpoch, confirmed.grant.event.toJson()))
                    check(allowed())
                    val door = requireNotNull(transport)
                    check(door.publishConfirmed(answer, 6000))
                } finally { key.fill(0); secret.fill(0); epoch.currentSecret.fill(0); epoch.pending?.secret?.fill(0) }
                waiting.remove(pending.request.id)
                ignored.add(pending.request.id)
                while (ignored.size > 256) ignored.remove(ignored.first())
                _trouble.value = false
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { _trouble.value = true }
    }
    fun reconnect() { transport?.reconnect() }
    fun close() {
        job?.cancel(); job = null; transport?.close(); transport = null; signingKey?.fill(0); signingKey = null; _requests.value = emptyList()
        releaseClaim?.invoke(); releaseClaim = null
    }
}
