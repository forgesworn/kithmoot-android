package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.RoomTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.util.concurrent.atomic.AtomicReference

internal data class FreshRootAnswer(val event: NostrEvent, val grant: EpochGrant.Current)

/** Initial entry only. Member catch-up and EOSE cannot release this gate. */
internal suspend fun requestFreshRootEpoch(
    room: Room,
    authority: String,
    identity: RoomIdentity,
    transport: RoomTransport,
    floor: () -> Int,
    now: () -> Long,
    timeoutMs: Long,
    proof: KindredProof? = null,
    ends: Long? = null,
): FreshRootAnswer {
    require(timeoutMs in 1..90_000)
    val clockLock = Any()
    var wall = now()
    fun checkLive() = synchronized(clockLock) {
        val at = now()
        check(at >= wall && (ends == null || at < ends)) { "Epoch admission expired or clock moved backwards" }
        wall = at
    }
    return withTimeout(timeoutMs) {
        coroutineScope {
            val answer = CompletableDeferred<FreshRootAnswer>()
            val currentRequest = AtomicReference<String?>()
            var checked = 0
            val listener = launch(start = CoroutineStart.UNDISPATCHED) {
                transport.subscribe(listOf(Filter(kinds = listOf(KIND_EPOCH_GRANT), authors = listOf(authority),
                    tags = mapOf("#d" to listOf(room.roomId), "#p" to listOf(identity.devicePubkey))))).collect { event ->
                    if (answer.isCompleted || checked >= 64) return@collect
                    checkLive()
                    val request = currentRequest.get() ?: return@collect
                    if (!boundedAdmissionEvent(event, 262_144) || event.toJson().toString().length > 263_168) return@collect
                    checked++
                    val grant = decodeEpochGrant(event, room.roomId, authority, identity.deviceSecretKey, request, now()) ?: return@collect
                    synchronized(answer) {
                        // Offer rotation may run on another dispatcher while decoding.
                        if (currentRequest.get() != request || answer.isCompleted) return@synchronized
                        when (grant) {
                            is EpochGrant.Refused -> if (grant.reason != "unknown") error("Epoch admission refused: ${grant.reason}")
                            is EpochGrant.Current -> {
                                val minimum = floor()
                                if (minimum >= 0 && grant.epoch >= minimum) {
                                    check(identity.participant !in grant.removed) { "Epoch admission refused: removed" }
                                    answer.complete(FreshRootAnswer(event, grant))
                                }
                            }
                        }
                    }
                }
            }
            val monitor = launch { while (isActive) { delay(1000); checkLive() } }
            val offers = launch {
                repeat(3) { attempt ->
                    if (attempt > 0) delay(maxOf(1, timeoutMs / 3))
                    if (answer.isCompleted) return@launch
                    checkLive()
                    // Existing root desks answer an ID once. Renew this request,
                    // while the invitation exchange reuses its cached answer.
                    val request = encodeEpochRequest(room.roomId, authority, room.roomKey, identity.deviceSecretKey,
                        identity.credential, now(), proof, roomEnds = ends)
                    val offer = synchronized(answer) {
                        if (answer.isCompleted) false else { currentRequest.set(request.id); true }
                    }
                    if (!offer) return@launch
                    try { transport.publishRecovery(request) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Bounded retry; no different route. */ }
                }
            }
            try { answer.await().also { checkLive() } }
            finally { listener.cancel(); monitor.cancel(); offers.cancel() }
        }
    }
}
