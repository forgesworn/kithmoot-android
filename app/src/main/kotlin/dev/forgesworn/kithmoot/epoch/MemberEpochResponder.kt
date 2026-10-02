package dev.forgesworn.kithmoot.epoch

import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexEquals
import dev.forgesworn.kithmoot.crypto.normaliseHex
import dev.forgesworn.kithmoot.protocol.MAX_MEMBER_EPOCH_CHAIN
import dev.forgesworn.kithmoot.protocol.MAX_MEMBER_GRANT_BYTES
import dev.forgesworn.kithmoot.protocol.MemberEpochRequest
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.RoomEpoch
import dev.forgesworn.kithmoot.protocol.RoomPolicy
import dev.forgesworn.kithmoot.protocol.decodeMemberEpochRequest
import dev.forgesworn.kithmoot.protocol.deriveEpoch
import dev.forgesworn.kithmoot.protocol.encodeMemberEpochGrant
import dev.forgesworn.kithmoot.protocol.peekRekeyEpoch
import dev.forgesworn.kithmoot.protocol.readRekeyEvidence
import java.security.SecureRandom

/** The upper bound of a desk's random wait before answering, so several members do not all answer at once. */
const val MEMBER_EPOCH_JITTER_MS = 1_500L

/** What a member can hand on: epochs have+1 .. current and the authority's rekey into each. */
class MemberChain(epochs: List<RoomEpoch>, rekeys: List<NostrEvent>) {
    val epochs = epochs.toList()
    val rekeys = rekeys.toList()
}

/**
 * The chain from [have] to [current] this device can hand on, or null when it cannot hand on
 * the whole of it: more than [MAX_MEMBER_EPOCH_CHAIN] epochs, a secret or an authority rekey
 * missing, or - when this device can read the last rekey itself - a last rekey that carries no
 * commitment, which the requester would refuse. One it cannot read (it joined at this epoch)
 * is offered, and the requester decides. As fold-kit's `hostMemberEpochDesk` builds it.
 */
fun memberGrantChain(
    roomId: String,
    authority: String,
    /** The epoch-0 room key: what reads the rekey into epoch 1. */
    roomKey: ByteArray,
    have: Int,
    current: RoomEpoch,
    secretAt: (Int) -> ByteArray?,
    rekeyAt: (Int) -> NostrEvent?,
): MemberChain? {
    if (have < 0 || have >= current.epoch || current.epoch - have > MAX_MEMBER_EPOCH_CHAIN) return null
    val epochs = mutableListOf<RoomEpoch>()
    val rekeys = mutableListOf<NostrEvent>()
    for (n in have + 1..current.epoch) {
        val secret = if (n == current.epoch) current.secret else secretAt(n)
        val rekey = rekeyAt(n)
        if (secret == null || secret.size != 32 || rekey == null) return null
        if (peekRekeyEpoch(rekey, roomId, authority) != n) return null
        epochs += RoomEpoch(n, secret)
        rekeys += rekey
    }
    val before = current.epoch - 1
    val beforeKey = if (before == 0) roomKey else secretAt(before)?.takeIf { it.size == 32 }?.let { deriveEpoch(RoomEpoch(before, it)).key }
    if (beforeKey != null) {
        val evidence = readRekeyEvidence(rekeys.last(), roomId, authority, before, beforeKey) ?: return null
        if (evidence.commit == null) return null
    }
    return MemberChain(epochs, rekeys)
}

/** What a desk does with one member epoch request. */
sealed interface MemberDeskDecision {
    /** Not for this desk: malformed, a stranger's, already seen, its own, or nothing to hand on. */
    data object Ignore : MemberDeskDecision
    /** A removed participant or a closed room: declined, and nothing is published. */
    data class Refused(val request: MemberEpochRequest, val why: String) : MemberDeskDecision
    /** Answer after [delayMs], by calling [MemberEpochResponder.answer]. */
    data class Answer(val request: MemberEpochRequest, val delayMs: Long) : MemberDeskDecision
}

/**
 * The member desk: what any member at the current epoch runs beside its room session, so a
 * device that missed a rekey can catch up while the authority's device is away. It holds no
 * authority key. It answers only an admitted, credentialled device that is not removed, in a
 * room that is not closed, and only when it can hand over the whole chain from the requester's
 * epoch to its own with the authority's rekeys to prove it. See fold-kit's
 * `docs/member-epoch-catch-up.md`, "Desk rules".
 *
 * Refusals are silent: a member's no is not something a requester could believe, so it is not
 * sent. Each answer waits a random `[0, jitter)` and is dropped if another grant to the same
 * device appears meanwhile - at most once per requesting device, so a stranger posting junk
 * grants costs one round, not the answer.
 *
 * [inStep] on each call is the epoch the room session is in step at (its state is Active and
 * the epoch is above 0), or null; the desk answers only when the durable [current] agrees.
 */
class MemberEpochResponder(
    private val stableRoom: String,
    private val authority: String,
    deviceSecretKey: ByteArray,
    /** The epoch-0 room key: opens requests and checks admission proofs. */
    roomKey: ByteArray,
    private val policy: RoomPolicy?,
    /** Where this device is now, or null while it is not in step. */
    private val current: () -> RoomEpoch?,
    private val secretAt: (Int) -> ByteArray?,
    private val rekeyAt: (Int) -> NostrEvent?,
    /** The cumulative removed set, as the authority's rekeys told this device. */
    private val removed: () -> Collection<String>,
    private val closed: () -> Boolean,
    private val now: () -> Long,
    /** A conference room's end: a grant lapses with the room. */
    private val ends: Long? = null,
    private val jitterMs: Long = MEMBER_EPOCH_JITTER_MS,
    private val random: () -> Double = SecureRandom()::nextDouble,
    private val maxGrantBytes: Int = MAX_MEMBER_GRANT_BYTES,
) {
    private val roomKey = roomKey.copyOf()
    private val self = Schnorr.publicKeyHex(deviceSecretKey)
    private val answered = Bounded()
    /** Requesting devices another member's grant was seen for, since their latest request reached this desk. */
    private val grantSeen = Bounded()
    /** Requesting devices this desk has already stood down for once. */
    private val stoodDown = Bounded()
    /** Ids of the grants this desk published: each has a one-time signer, so they are told apart by id. */
    private val mine = Bounded()

    /** A 20472 seen on the room: notes that somebody answered its device, unless it was this desk. */
    @Synchronized fun onGrant(event: NostrEvent) {
        if (event.id in mine) return
        val to = event.tagValue("p")?.takeIf { HEX.matches(it) } ?: return
        grantSeen += to.normaliseHex()
    }

    @Synchronized fun onRequest(event: NostrEvent, inStep: Int?): MemberDeskDecision {
        if (event.pubkey.hexEquals(self)) return MemberDeskDecision.Ignore
        val request = decodeMemberEpochRequest(event, stableRoom, authority, roomKey, now(), policy) ?: return MemberDeskDecision.Ignore
        if (request.request in answered) return MemberDeskDecision.Ignore
        answered += request.request
        if (closed()) return MemberDeskDecision.Refused(request, "closed")
        if (removed().any { it.hexEquals(request.participant) }) return MemberDeskDecision.Refused(request, "removed")
        val here = inStepCurrent(inStep) ?: return MemberDeskDecision.Ignore
        if (request.have >= here.epoch) return MemberDeskDecision.Ignore
        grantSeen -= request.device
        val delay = if (jitterMs <= 0) 0L else (random() * jitterMs).toLong().coerceIn(0, jitterMs - 1)
        return MemberDeskDecision.Answer(request, delay)
    }

    /** The grant to publish for [request] once its wait is over, or null to stay silent. */
    @Synchronized fun answer(request: MemberEpochRequest, inStep: Int?): NostrEvent? {
        if (request.device in grantSeen && request.device !in stoodDown) {
            stoodDown += request.device
            return null
        }
        stoodDown -= request.device
        // Asked again now the wait is over: the room may have moved.
        if (closed() || removed().any { it.hexEquals(request.participant) }) return null
        val here = inStepCurrent(inStep) ?: return null
        if (request.have >= here.epoch) return null
        val chain = memberGrantChain(stableRoom, authority, roomKey, request.have, here, secretAt, rekeyAt) ?: return null
        val grant = runCatching {
            encodeMemberEpochGrant(stableRoom, request.device, request.request, chain.epochs, chain.rekeys, now(), roomEnds = ends)
        }.getOrNull() ?: return null
        if (grant.toCompactJson().toByteArray(Charsets.UTF_8).size > maxGrantBytes) return null
        mine += grant.id
        return grant
    }

    private fun inStepCurrent(inStep: Int?): RoomEpoch? {
        if (inStep == null || inStep < 1) return null
        return current()?.takeIf { it.epoch == inStep }
    }

    /** An insertion-ordered set that forgets its oldest past 256, as the TypeScript desk's do. */
    private class Bounded {
        private val values = LinkedHashSet<String>()
        operator fun contains(value: String) = value in values
        operator fun plusAssign(value: String) {
            values.remove(value)
            values += value
            if (values.size > 256) values.remove(values.first())
        }
        operator fun minusAssign(value: String) { values.remove(value) }
    }

    private companion object {
        val HEX = Regex("[0-9a-fA-F]{64}")
    }
}

/** The member desk as a room session drives it; the app runs [MemberEpochResponder] off the main thread behind it. */
interface MemberEpochDesk {
    suspend fun onGrant(event: NostrEvent)
    suspend fun onRequest(event: NostrEvent, inStep: Int?): MemberDeskDecision
    suspend fun answer(request: MemberEpochRequest, inStep: Int?): NostrEvent?
}

/** [this] as a [MemberEpochDesk], each call made in [context] (the vault reads storage). */
fun MemberEpochResponder.asDesk(context: kotlin.coroutines.CoroutineContext = kotlin.coroutines.EmptyCoroutineContext): MemberEpochDesk =
    object : MemberEpochDesk {
        override suspend fun onGrant(event: NostrEvent) = kotlinx.coroutines.withContext(context) { this@asDesk.onGrant(event) }
        override suspend fun onRequest(event: NostrEvent, inStep: Int?) = kotlinx.coroutines.withContext(context) { this@asDesk.onRequest(event, inStep) }
        override suspend fun answer(request: MemberEpochRequest, inStep: Int?) = kotlinx.coroutines.withContext(context) { this@asDesk.answer(request, inStep) }
    }
