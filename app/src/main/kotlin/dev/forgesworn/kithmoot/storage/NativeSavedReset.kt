package dev.forgesworn.kithmoot.storage

import android.content.Context
import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.epoch.NativeKeeperJournal
import dev.forgesworn.kithmoot.epoch.RoomRekeyBinding
import dev.forgesworn.kithmoot.epoch.RoomRekeyLedger
import java.security.KeyStore

/** IO-only inventory, without decrypting authority or creating any key.
 * The caller holds NativeRoomCreation's lease until index deletion finishes. */
internal object NativeSavedReset {
    data class Inventory(val aliases: Set<String>, val activeOwner: Boolean = false)
    private fun nativeName(name: String) = name.startsWith("kithmoot.keeper-authority.") ||
        name.startsWith("kithmoot.keeper-rekeys.") || name.startsWith("kithmoot.native-creation.")

    fun names(files: List<String>, keys: List<String>): Set<String> =
        (files.map { it.substringBefore(".vault") } + keys).filter(::nativeName).toSet()

    fun inspect(context: Context): Inventory {
        val files = requireNotNull(context.applicationContext.noBackupFilesDir.list()) {
            "Native room state could not be inspected. Your saved data has been kept."
        }.toList()
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.aliases().asSequence().toList()
        return Inventory(names(files, keys), NativeKeeperJournal.hasActiveOwners() || RoomRekeyLedger.hasActiveOwners())
    }

    fun aliases(saved: SavedRoom): Set<String> = saved.nativeAuthority?.let { b ->
        val q = RoomRekeyBinding(b.room, b.authority, b.device, b.meshScope, b.relays, b.route)
        setOf("kithmoot.keeper-authority." + Digests.sha256(b.owner.toByteArray(Charsets.UTF_8)).toHex(),
            "kithmoot.keeper-rekeys." + Digests.sha256(q.owner.toByteArray(Charsets.UTF_8)).toHex())
    }.orEmpty()

    fun records(rooms: RoomRepository, inspect: () -> Inventory): List<SavedRoom> {
        val inventory = inspect() // Errors propagate; unavailable is not absent.
        check(!inventory.activeOwner) { "Leave the native room before resetting saved rooms." }
        val records = try { rooms.list().map { requireNotNull(rooms.get(it.id)) } }
        catch (error: RoomStorageException) {
            if (inventory.aliases.isNotEmpty()) throw RoomRecoveryException(
                "Saved native host state needs recovery before deleting this index. Your saved data has been kept.")
            emptyList() // Explicit legacy-only corrupt reset; no independent native state.
        }
        val known = records.flatMap(::aliases).toSet()
        check((inventory.aliases - known).isEmpty()) {
            "Independent native room state needs recovery before resetting saved rooms. Your saved data has been kept."
        }
        return records
    }

    fun requireCleared(inventory: Inventory) {
        check(!inventory.activeOwner && inventory.aliases.isEmpty()) {
            "Native room cleanup is incomplete. Your saved index has been kept."
        }
    }
}
