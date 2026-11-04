package dev.forgesworn.kithmoot.node

import dev.forgesworn.kithmoot.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.put
import java.net.URI

/** Persists grant ordering before use or local removal. A transport failure,
 * signed reset or completion receipt cannot authorise destruction. */
internal class NodeDeviceAccess(
    val node: NodeRoomRecord,
    private val participant: String,
    private val device: String,
    private val store: NodeStore,
    private val query: suspend (String) -> NodeAccess,
    private val allowed: () -> Boolean,
    private val onUnavailable: () -> Unit,
    private val onRemoved: suspend () -> Unit,
    private val onClosed: suspend () -> Unit,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<NodeAccess?>(null)
    val state = _state.asStateFlow()
    @Volatile private var closed = false
    private var poller: Job? = null
    fun permitted(): Boolean = !closed && allowed() && (node.endsAt?.let { now() < it } ?: true) &&
        (_state.value as? NodeAccess.Active)?.let { it.grant.expiration > now() && it.expiresAt > now() } == true
    private fun block() { _state.value = null; onUnavailable() }
    suspend fun refresh(): NodeAccess = mutex.withLock {
        check(!closed && allowed())
        try {
            val saved = requireNotNull(store.custody(participant, node.room))
            require(saved.node.event.id == node.event.id && saved.device == device)
            val held = requireNotNull(saved.grant)
            val original = NostrEvent.fromJson(held)
            val grantId = original.tags.single { it.firstOrNull() == "grant" }.also { require(it.size == 2) }[1]
            val result = if (node.endsAt?.let { now() >= it } == true) NodeAccess.Ended else query(grantId)
            check(!closed && allowed())
            when (result) {
                is NodeAccess.Active, is NodeAccess.Removed, is NodeAccess.Reset -> {
                    val event = when (result) {
                        is NodeAccess.Active -> result.grant.event
                        is NodeAccess.Removed -> result.grant.event
                        is NodeAccess.Reset -> result.reset.event
                        else -> error("Unreachable")
                    }
                    val parsed = (parseGroupEvent(event.toJson(), now(), listOf(node.authority), listOf(URI(node.origin).host)) as? GroupEventVerdict.Ok)?.parsed
                    require(parsed != null && parsed.roomId == node.room)
                    if (parsed is ParsedGrant) require(parsed.device == device && parsed.grantId == grantId)
                    val persisted = store.update(participant, node.room) { current ->
                        require(current.node.event.id == node.event.id && current.device == device)
                        require(current.pendingClose == null || result !is NodeAccess.Active)
                        val receiver = GrantReceiver.restore(node.authority, node.room, device, current.receiver)
                        receiver.offer(requireNotNull(current.grant))
                        receiver.offer(event.toJson())
                        current.changed {
                            put("receiver", receiver.snapshot())
                            if (result is NodeAccess.Active && receiver.grantsFor(URI(node.origin).host, now()).any { it.event.id == event.id }) put("grant", event.toJson())
                        }
                    }
                    val receiver = GrantReceiver.restore(node.authority, node.room, device, persisted.receiver)
                    if (result is NodeAccess.Active) {
                        require(NodeRooms.verifyGrant(event.toJson(), node, device, now()) && result.expiresAt > now()
                            && result.expiresAt <= now() + NODE_HORIZON_SECONDS + 30 && Regex("^[A-Za-z0-9_-]{43}$").matches(result.token)
                            && receiver.grantsFor(URI(node.origin).host, now()).any { it.event.id == event.id })
                    } else if (result is NodeAccess.Removed) {
                        require(parsed is ParsedGrant && parsed.status == "revoked" && receiver.lockedOut(URI(node.origin).host, now()))
                        block(); _state.value = result; onRemoved(); return@withLock result
                    } else require(parsed is ParsedReset)
                }
                is NodeAccess.Closed -> {
                    requireNotNull(NodeRooms.readClosure(result.closure.toJson(), node, now()))
                    block(); _state.value = result; onClosed(); return@withLock result
                }
                NodeAccess.Ended -> {
                    require(node.endsAt?.let { now() >= it } == true)
                    block(); _state.value = result; onClosed(); return@withLock result
                }
            }
            _state.value = result
            if (result !is NodeAccess.Active) onUnavailable()
            result
        } catch (cancelled: CancellationException) { block(); throw cancelled }
        catch (error: Exception) {
            // A missed poll is not a revocation. Keep only the already verified
            // admission until its original deadline; never trust a failed reply.
            if (error !is NodeRequestException || !error.retryable || !permitted()) block()
            throw error
        }
    }
    fun start(scope: CoroutineScope) {
        check(poller == null && !closed)
        poller = scope.launch { while (isActive && !closed) {
            try { refresh() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* unavailable, retained for an explicit valid proof */ }
            delay(5000)
        } }
    }
    fun close() { closed = true; poller?.cancel(); block() }
}
