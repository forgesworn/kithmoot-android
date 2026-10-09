package dev.forgesworn.kithmoot.storage

import android.content.Context
import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.epoch.NativeKeeperBinding
import dev.forgesworn.kithmoot.epoch.NativeKeeperCreation
import dev.forgesworn.kithmoot.epoch.NativeKeeperJournal
import dev.forgesworn.kithmoot.protocol.NostrEvent

/** Separate authority state, never a fallback reconstructed from SavedRoom.
 * Device/route edits keep the same alias and refuse a mismatched policy. */
internal class NativeKeeperVault(context: Context, private val binding: NativeKeeperBinding) {
    private val storage = EncryptedRoomStorage(context,
        "kithmoot.keeper-authority." + Digests.sha256(binding.owner.toByteArray(Charsets.UTF_8)).toHex(),
        NativeKeeperJournal.MAX_FILE_BYTES)
    fun create(creation: NativeKeeperCreation, ownerCredential: NostrEvent) = NativeKeeperJournal.create(storage, binding, creation, ownerCredential)
    fun open() = NativeKeeperJournal.open(storage, binding)
    fun forget() = NativeKeeperJournal.withInactiveOwner(binding.owner) { storage.reset() }
}
