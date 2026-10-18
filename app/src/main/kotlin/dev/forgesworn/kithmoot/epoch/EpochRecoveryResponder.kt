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
        synchronized(waiting) {
            if (refused == "unknown") {
                if (!waiting.add(request.request)) return null
                if (waiting.size > 256) waiting.remove(waiting.first())
                val last = reported[request.participant]
                if (last == null || now() - last >= REPORT_UNKNOWN_EVERY_SECONDS) {
                    reported[request.participant] = now()
                    if (reported.size > 256) reported.remove(reported.keys.first())
                    onUnknown(request)
                }
            } else {
                waiting.remove(request.request)
                if (refused == null) reported.remove(request.participant)
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
