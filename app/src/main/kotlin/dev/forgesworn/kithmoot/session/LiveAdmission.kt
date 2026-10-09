package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.RoomTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

private object LiveAdmissionOwners {
    private val active = mutableSetOf<String>()
    @Synchronized fun acquire(owner: String) {
        check(owner !in active && active.size < 8) { "A live admission is already running or the local limit is reached" }
        active += owner
    }
    @Synchronized fun release(owner: String) { active -= owner }
}

/** Caller-owned explicit route; cancellation removes every collector and erases the reply key. */
suspend fun requestLivePersistentAdmission(
    context: LivePersistentContext,
    ownerDevice: String,
    transport: RoomTransport,
    now: () -> Long = { System.currentTimeMillis() / 1000 },
    monotonicMs: () -> Long = { System.nanoTime() / 1_000_000 },
    retired: () -> Boolean = { false },
): LivePersistentAnswer {
    require(Regex("[0-9a-f]{64}").matches(ownerDevice))
    currentCoroutineContext().ensureActive()
    val owner = "$ownerDevice:${context.roomId}"
    LiveAdmissionOwners.acquire(owner)
    var key: ByteArray? = null
    try {
        check(!retired()) { "This invitation was retired" }
        val replyKey = Entropy.bytes(32).also { key = it }
        val started = monotonicMs()
        val clockLock = Any()
        var tick = started
        var wall = now()
        val request = encodeLivePersistentRequest(context, replyKey, wall)
        fun checkLive() = synchronized(clockLock) {
            val at = now(); val mono = monotonicMs()
            check(at >= wall && at < request.createdAt + 90 && mono >= tick && mono - started in 0 until 90_000 && !retired()) {
                "Live admission expired, was retired or the clock moved backwards"
            }
            wall = at; tick = mono
        }
        return withTimeout(90_000) {
            coroutineScope {
                val answer = CompletableDeferred<LivePersistentAnswer>()
                var checked = 0
                val invitationId = deriveInvitationId(context.invitation)
                val listener = launch(start = CoroutineStart.UNDISPATCHED) {
                    transport.subscribe(listOf(
                        Filter(kinds = listOf(KIND_INVITATION_GRANT), authors = listOf(context.invitation.inviter),
                            tags = mapOf("#d" to listOf(invitationId), "#p" to listOf(Schnorr.publicKeyHex(replyKey)))),
                        Filter(kinds = listOf(KIND_INVITATION_RETIREMENT), authors = listOf(context.invitation.inviter),
                            tags = mapOf("#d" to listOf(invitationId))),
                    )).collect { event ->
                        if (answer.isCompleted || checked >= 64) return@collect
                        checkLive()
                        if (!boundedAdmissionEvent(event, 16_384)) return@collect
                        checked++
                        if (event.kind == KIND_INVITATION_RETIREMENT) {
                            check(event.content.length > 512 || event.toJson().toString().length > 4096 ||
                                !decodeInvitationRetirement(event, context.invitation)) { "This invitation was retired" }
                        } else {
                            val strict = parseLivePersistentEvent(event.toJson().toString(), request = false)
                            strict?.let { decodeLivePersistentAnswer(it, context, request, replyKey, wall) }
                                ?.let(answer::complete)
                        }
                    }
                }
                val monitor = launch { while (isActive) { delay(1000); checkLive() } }
                val offers = launch {
                    repeat(3) { attempt ->
                        if (attempt > 0) delay(10_000)
                        if (answer.isCompleted) return@launch
                        checkLive()
                        try { transport.publish(request.copy(tags = request.tags.map { it.toList() })) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { /* No receipt: retry within the same bounded exchange. */ }
                    }
                }
                try { answer.await().also { checkLive() } }
                finally { listener.cancel(); monitor.cancel(); offers.cancel() }
            }
        }
    } finally {
        key?.fill(0)
        LiveAdmissionOwners.release(owner)
    }
}

internal fun boundedAdmissionEvent(event: NostrEvent, contentLimit: Int): Boolean =
    event.id.length == 64 && event.pubkey.length == 64 && event.sig.length == 128 &&
        event.content.length <= contentLimit && event.tags.size <= 8 &&
        event.tags.all { it.size <= 4 && it.all { v -> v.length <= 256 } }
