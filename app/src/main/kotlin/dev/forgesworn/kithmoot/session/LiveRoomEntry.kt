package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.RoomTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

/**
 * Both entry stages on a caller-owned route. The factory runs only after the
 * signed proof establishes the room; its session must pin this root, device,
 * room and hint and require a fresh epoch. The caller owns route teardown.
 */
suspend fun joinLivePersistentRoom(
    invitation: RoomInvitation,
    descriptor: String,
    ownerDevice: String,
    transport: RoomTransport,
    now: () -> Long = { System.currentTimeMillis() / 1000 },
    monotonicMs: () -> Long = { System.nanoTime() / 1_000_000 },
    retired: () -> Boolean = { false },
    createSession: suspend (LivePersistentAnswer) -> RoomSession,
): RoomSession {
    val context = requireNotNull(decodeLivePersistentDescriptor(descriptor, invitation)) { "The discovery descriptor does not match this invitation" }
    var room: RoomSession? = null
    try {
        return coroutineScope {
            check(!retired()) { "This invitation was retired" }
            val clockLock = Any()
            var wall = now()
            fun checkLive() = synchronized(clockLock) {
                val at = now()
                check(at >= wall && !retired()) { "This invitation was retired or the clock moved backwards" }
                wall = at
            }
            var checked = 0
            val retirement = launch(start = CoroutineStart.UNDISPATCHED) {
                transport.subscribe(listOf(Filter(kinds = listOf(KIND_INVITATION_RETIREMENT),
                    authors = listOf(invitation.inviter), tags = mapOf("#d" to listOf(deriveInvitationId(invitation)))))).collect { event ->
                    check(++checked <= 64) { "Too many retirement checks during admission" }
                    if (!boundedAdmissionEvent(event, 512) || event.toJson().toString().length > 4096) return@collect
                    check(!decodeInvitationRetirement(event, invitation)) { "This invitation was retired" }
                }
            }
            val monitor = launch { while (isActive) { delay(1000); checkLive() } }
            try {
                val proof = requestLivePersistentAdmission(context, ownerDevice, transport, now, monotonicMs, retired)
                checkLive()
                check(proof.epochHint in 0..Int.MAX_VALUE.toLong()) { "Unsupported room epoch" }
                val joined = createSession(proof).also { room = it }
                currentCoroutineContext().ensureActive()
                joined.joinFromLiveAdmission(context, ownerDevice, proof.epochHint.toInt(), proof.expiresAt, transport)
                checkLive()
                currentCoroutineContext().ensureActive()
                joined
            } finally {
                retirement.cancel(); monitor.cancel()
            }
        }
    } catch (error: Throwable) {
        room?.leave()
        throw error
    }
}
