package dev.forgesworn.kithmoot.storage

import android.content.Context
import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.session.RoomForwardingBinding
import dev.forgesworn.kithmoot.session.RoomForwardingLedger

/** No-backup, device-encrypted forwarding journal. The alias stays tied to the
 * identity, so changing a route/allowlist cannot silently reset its budget.
 * Initial creation is explicit; normal reopen refuses a missing journal. */
class RoomForwardingVault(context: Context, binding: RoomForwardingBinding, initialise: Boolean = false) {
    private val storage = EncryptedRoomStorage(context,
        "kithmoot.room-forwarding." + Digests.sha256(binding.owner.toByteArray(Charsets.UTF_8)).toHex(),
        RoomForwardingLedger.MAX_FILE_BYTES)
    val ledger = RoomForwardingLedger(storage, binding, createIfMissing = initialise)
}
