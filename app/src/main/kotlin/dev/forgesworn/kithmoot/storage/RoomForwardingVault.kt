package dev.forgesworn.kithmoot.storage

import android.content.Context
import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.session.RoomForwardingBinding
import dev.forgesworn.kithmoot.session.RoomForwardingLedger
import dev.forgesworn.kithmoot.session.RoomSharingSelection

/** No-backup, device-encrypted forwarding journal. The alias stays tied to the
 * identity, so changing a route/allowlist cannot silently reset its budget.
 * Initial creation is explicit; normal reopen refuses a missing journal. */
class RoomForwardingVault(context: Context, binding: RoomForwardingBinding, initialise: Boolean = false) {
    private val storage = EncryptedRoomStorage(context,
        "kithmoot.room-forwarding." + Digests.sha256(binding.owner.toByteArray(Charsets.UTF_8)).toHex(),
        RoomForwardingLedger.MAX_FILE_BYTES)
    val ledger = RoomForwardingLedger(storage, binding, createIfMissing = initialise)
}

/** Foreground UI preparation. Both identity-bound stores keep the same aliases
 * through route/author edits; pending policy changes retain all retry debt. */
internal class RoomSharingVault(context: Context, room: String, participant: String, device: String) {
    private val identity = Digests.sha256("$room:$participant:$device".toByteArray(Charsets.UTF_8)).toHex()
    private val journal = EncryptedRoomStorage(context, "kithmoot.room-forwarding.$identity", RoomForwardingLedger.MAX_FILE_BYTES)
    private val selection = RoomSharingSelection(EncryptedRoomStorage(context,
        "kithmoot.sharing-selection.$identity", RoomSharingSelection.MAX_BYTES), room, participant, device)
    fun selection() = selection.read()
    fun prepare(binding: RoomForwardingBinding) = selection.prepare(binding, journal)
}
