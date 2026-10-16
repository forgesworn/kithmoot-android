package dev.forgesworn.kithmoot.epoch

import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.protocol.EpochRequest
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.RoomEpoch
import dev.forgesworn.kithmoot.protocol.RoomPolicy
import dev.forgesworn.kithmoot.protocol.decodeEpochRequest
import dev.forgesworn.kithmoot.protocol.encodeEpochGrant

/** The creator's stable-room recovery endpoint, backed only by durable epoch state. */
class EpochRecoveryResponder(
    private val vault: EpochVault,
    private val stableRoom: String,
    authoritySecretKey: ByteArray,
    /** The epoch-0 room key: what a request has to prove it holds before it is answered. */
    roomKey: ByteArray,
    private val policy: RoomPolicy?,
    /** A conference room's end: a grant lapses with the room. */
    private val ends: Long? = null,
    private val now: () -> Long,
    /**
     * Whether the room knows this participant (kithmoot#207). Asked only once somebody has been
     * removed; then anybody else is answered `unknown`, which is not final: a member lets them
     * in, and their next ask is granted.
     */
    private val known: (String) -> Boolean,
    /** The member list to put in a grant, so the requester's own desk knows who the room knows. */
    private val members: () -> List<String>? = { null },
    /** Somebody the room does not know asked: once per participant, again every minute. */
    private val onUnknown: (EpochRequest) -> Unit = {},
) {
    private val authoritySecretKey = authoritySecretKey.copyOf()
    private val roomKey = roomKey.copyOf()
    private val authority = Schnorr.publicKeyHex(authoritySecretKey)
    /** Requests already answered `unknown`, answered again only once their participant is known. */
    private val waiting = LinkedHashSet<String>()
    private val reported = LinkedHashMap<String, Long>()
    /** When each participant was last told `removed` or `closed`: a refusal is final, so once an hour is plenty. */
    private val refusedAt = LinkedHashMap<String, Long>()
    /** When each participant was last told `unknown`. */
    private val unknownAt = LinkedHashMap<String, Long>()
    /** Every refusal of any kind answered in the last minute, oldest first. */
    private val refusals = ArrayDeque<Long>()

    /**
     * The signed answer to [event], or null when it is not answered.
     *
     * Only a grant is answered every time. A refusal goes to each participant at most once per
     * [REFUSED_ANSWER_EVERY_SECONDS] (`removed`, `closed`) or [UNKNOWN_ANSWER_EVERY_SECONDS]
     * (`unknown`), and to nobody past [MAX_REFUSALS_PER_MINUTE] a minute in all, so a removed or
     * stranger's device cannot use this endpoint to ping the creator's phone on demand. Staying
     * silent is safe for every requester: an unanswered request is exactly what a creator who is
     * offline looks like, and a requester already waits that out.
     */
    fun answer(event: NostrEvent): NostrEvent? {
        val request = decodeEpochRequest(event, stableRoom, authoritySecretKey, roomKey, now(), policy) ?: return null
        val durable = vault.get(stableRoom) ?: return null
        require(durable.authority == authority) { "room authority conflicts with the recovery signer" }
        val refused = when {
            durable.phase == EpochPhase.CLOSED -> "closed"
            durable.phase == EpochPhase.REMOVED || durable.removed.any {
                it.equals(request.participant, ignoreCase = true)
            } -> "removed"
            durable.removed.isNotEmpty() && !known(request.participant.lowercase()) -> "unknown"
            else -> null
        }
        val participant = request.participant.lowercase()
        synchronized(waiting) {
            val at = now()
            if (refused == "unknown") {
                if (!waiting.add(request.request)) return null
                if (waiting.size > MAX_TRACKED) waiting.remove(waiting.first())
                val last = reported[request.participant]
                if (last == null || at - last >= REPORT_UNKNOWN_EVERY_SECONDS) {
                    reported[request.participant] = at
                    if (reported.size > MAX_TRACKED) reported.remove(reported.keys.first())
                    onUnknown(request)
                }
            } else {
                waiting.remove(request.request)
                if (refused == null) reported.remove(request.participant)
            }
            if (refused == null) {
                refusedAt.remove(participant); unknownAt.remove(participant)
            } else {
                val (seen, every) = if (refused == "unknown") unknownAt to UNKNOWN_ANSWER_EVERY_SECONDS
                    else refusedAt to REFUSED_ANSWER_EVERY_SECONDS
                val last = seen[participant]
                if (last != null && at - last < every && at >= last) return null
                while (refusals.isNotEmpty() && at - refusals.first() >= 60) refusals.removeFirst()
                if (refusals.size >= MAX_REFUSALS_PER_MINUTE) return null
                refusals.addLast(at)
                // Re-inserted so the map's first key is always the stalest one to forget.
                seen.remove(participant); seen[participant] = at
                if (seen.size > MAX_TRACKED) seen.remove(seen.keys.first())
            }
        }
        return encodeEpochGrant(
            roomId = stableRoom,
            authoritySecretKey = authoritySecretKey,
            device = request.device,
            request = request.request,
            now = now(),
            epoch = if (refused == null) RoomEpoch(durable.currentEpoch, durable.currentSecret) else null,
            removed = durable.removed,
            refused = refused,
            members = if (refused == null) members() else null,
            roomEnds = ends,
        )
    }
}

/** A participant told `removed` or `closed` hears it again at most this often. */
const val REFUSED_ANSWER_EVERY_SECONDS = 3_600L

/** A participant told `unknown` hears it again at most this often, matching the "let them in?" prompt. */
const val UNKNOWN_ANSWER_EVERY_SECONDS = REPORT_UNKNOWN_EVERY_SECONDS

/** All refusals together, per minute: what a crowd of fresh identities can draw out of this phone. */
const val MAX_REFUSALS_PER_MINUTE = 16

/** How many participants and requests each of the responder's memories keeps. */
private const val MAX_TRACKED = 256
