package dev.forgesworn.kithmoot.storage

import android.content.Context
import dev.forgesworn.kithmoot.account.MlsVaultStores

/**
 * The MLS device vault's stores: one [RollbackResistantRoomStorage] per name
 * in `noBackupFilesDir`, so each committed version has its own Keystore AES-GCM
 * key and an older file cannot be opened once a newer one is committed. The
 * vault's AAD is bound into every ciphertext.
 */
class AndroidMlsVaultStores(context: Context, private val prefix: String = "kithmoot.mls-vault.v1") : MlsVaultStores {
    init { require(prefix.matches(Regex("[A-Za-z0-9._-]{1,64}"))) }

    private val app = context.applicationContext
    override val lockName: String = prefix

    override fun open(name: String, aad: ByteArray): RoomStorage {
        require(name.matches(Regex("[A-Za-z0-9.-]{1,48}")))
        return RollbackResistantRoomStorage(app, "$prefix.$name", MAX_BYTES, aad)
    }

    private companion object {
        /** A full journal of 1,024 decisions is about 600 KiB. */
        const val MAX_BYTES = 1024 * 1024
    }
}
