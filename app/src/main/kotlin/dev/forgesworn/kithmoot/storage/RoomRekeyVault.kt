package dev.forgesworn.kithmoot.storage

import android.content.Context
import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.epoch.RoomRekeyBinding
import dev.forgesworn.kithmoot.epoch.RoomRekeyLedger

/** The alias stays bound to the root, independent of route/device edits.
 * A policy mismatch cannot silently obtain new retry credit. Creation is
 * explicit; normal reopening refuses a missing/corrupt journal. */
internal class RoomRekeyVault(context: Context, binding: RoomRekeyBinding) {
    private val owner = binding.owner
    private val storage = EncryptedRoomStorage(context,
        "kithmoot.keeper-rekeys." + Digests.sha256(owner.toByteArray(Charsets.UTF_8)).toHex(),
        RoomRekeyLedger.MAX_FILE_BYTES)
    private val binding = binding
    fun open(initialise: Boolean = false) = RoomRekeyLedger(storage, binding, createIfMissing = initialise)
    /** Explicit room deletion only; never used for policy changes. */
    fun forget() = RoomRekeyLedger.withInactiveOwner(owner) { storage.reset() }
}
