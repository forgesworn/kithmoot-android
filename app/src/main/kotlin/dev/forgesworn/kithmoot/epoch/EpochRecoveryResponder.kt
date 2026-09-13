package dev.forgesworn.kithmoot.epoch

import dev.forgesworn.kithmoot.crypto.Schnorr
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
    private val policy: RoomPolicy?,
    private val now: () -> Long,
) {
    private val authoritySecretKey = authoritySecretKey.copyOf()
    private val authority = Schnorr.publicKeyHex(authoritySecretKey)

    fun answer(event: NostrEvent): NostrEvent? {
        val request = decodeEpochRequest(event, stableRoom, authoritySecretKey, now(), policy) ?: return null
        val durable = vault.get(stableRoom) ?: return null
        require(durable.authority == authority) { "room authority conflicts with the recovery signer" }
        val refused = when {
            durable.phase == EpochPhase.CLOSED -> "closed"
            durable.phase == EpochPhase.REMOVED || durable.removed.any {
                it.equals(request.participant, ignoreCase = true)
            } -> "removed"
            else -> null
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
        )
    }
}
