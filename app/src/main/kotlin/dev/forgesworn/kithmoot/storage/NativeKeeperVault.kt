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
internal class NativeKeeperVault private constructor(context: Context, private val binding: NativeKeeperBinding,
    private val savedRoom: SavedRoom?) {
    constructor(context: Context, binding: NativeKeeperBinding) : this(context, binding, null)
    private val storage = EncryptedRoomStorage(context,
        "kithmoot.keeper-authority." + Digests.sha256(binding.owner.toByteArray(Charsets.UTF_8)).toHex(),
        NativeKeeperJournal.MAX_FILE_BYTES)
    fun create(creation: NativeKeeperCreation, ownerCredential: NostrEvent) = NativeKeeperJournal.create(storage, binding, creation, ownerCredential)
    fun open(): NativeKeeperJournal {
        val source = NativeKeeperJournal.open(storage, binding)
        try { savedRoom?.verifyNativeAuthority(source); return source }
        catch (error: Exception) { source.close(); throw error }
    }
    /** Foreground entry alone can inspect an exact retained pending split.
     * The entry still opens receiver/courier and actual index before routes. */
    fun openForEntry(): NativeKeeperJournal {
        val source = NativeKeeperJournal.open(storage, binding)
        try {
            val current = requireNotNull(savedRoom)
            if (source.snapshot().replacement == null) current.verifyNativeAuthority(source)
            else source.requirePendingIndex(current)
            return source
        } catch (error: Exception) { source.close(); throw error }
    }
    fun forget() = NativeKeeperJournal.withInactiveOwner(binding.owner) { storage.reset() }

    companion object {
        /** Reconstructs only the public binding. open() still refuses missing/corrupt authority state. */
        fun forSavedRoom(context: Context, room: SavedRoom) = NativeKeeperVault(context,
            requireNotNull(room.nativeAuthority) { "This saved room has no native authority reference" }, room)
    }
}
