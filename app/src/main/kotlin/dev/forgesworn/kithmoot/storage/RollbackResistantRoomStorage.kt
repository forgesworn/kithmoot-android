package dev.forgesworn.kithmoot.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * One live Android Keystore entry per committed journal version. Replacing a
 * value deletes every older entry, so restoring an earlier ciphertext or
 * deleting the current file cannot make delegated counters look unused.
 * [aad] is bound into every ciphertext; it is not stored, so a reader must
 * supply the same value.
 *
 * By default the superseded key is deleted *before* the new version is
 * committed, which cadence journals, the epoch store and the MLS vault's
 * installation store depend on: a crash in that interval can make the store
 * unavailable but never revives an older version. [deleteSupersededAfterCommit]
 * reverses that order for the coordinated MLS persona store alone (P3-03b-2),
 * whose rollback detection is the restore witness's job: a crash then leaves
 * either the old file with its key or the new file with its key, and [read]
 * sweeps the stale one. [directory] and [keys] are seams for tests that copy a
 * whole profile; production uses `noBackupFilesDir` and AndroidKeyStore.
 */
class RollbackResistantRoomStorage(
    context: Context,
    private val alias: String,
    private val maxPlaintextBytes: Int = 4 * 1024 * 1024,
    private val aad: ByteArray = RoomCipher.AAD,
    private val deleteSupersededAfterCommit: Boolean = false,
    directory: File? = null,
    private val keys: SealKeys = AndroidKeyStoreSealKeys,
) : RoomStorage {
    init {
        require(alias.matches(Regex("[A-Za-z0-9._-]{1,128}")))
        require(maxPlaintextBytes in 1..32 * 1024 * 1024)
    }

    private val directory = directory ?: context.applicationContext.noBackupFilesDir
    private val base = File(directory, "$alias.vault")
    private val file = AtomicFile(base)
    private val entryPrefix = "$alias.entry."

    @Synchronized override fun read(): ByteArray? {
        val packed = try {
            file.openRead().use {
                require(it.channel.size() <= maxPlaintextBytes + MAX_OVERHEAD)
                it.readBytes().also { bytes -> require(bytes.size <= maxPlaintextBytes + MAX_OVERHEAD) }
            }
        } catch (error: FileNotFoundException) {
            if (base.exists() || File(base.path + ".bak").exists()) throw error
            // With deletion after the commit, a crash before the very first
            // commit can leave a key with no file. The coordinated store
            // decides what a missing file means from its own marker.
            if (!deleteSupersededAfterCommit && entries().isNotEmpty()) throw error
            return null
        }
        try {
            val (entry, sealed) = unpack(packed)
            try {
                val plain = RoomCipher(aad) { create -> key(entry, create) }.decrypt(sealed)
                require(plain.size <= maxPlaintextBytes)
                deleteStaleEntries(entry)
                return plain
            } finally {
                sealed.fill(0)
            }
        } finally {
            packed.fill(0)
        }
    }

    @Synchronized override fun write(value: ByteArray) {
        require(value.size <= maxPlaintextBytes)
        read()?.fill(0)
        val entry = entryPrefix + UUID.randomUUID().toString().replace("-", "")
        val sealed = RoomCipher(aad) { create -> key(entry, create) }.encrypt(value)
        val packed = pack(entry, sealed)
        sealed.fill(0)
        var committed = false
        try {
            val output = file.startWrite()
            try {
                output.write(packed)
                output.fd.sync()
                // Retire the previous key before AtomicFile makes this version
                // authoritative. A crash in this narrow interval may make the
                // journal unavailable, but can never revive delegated counters.
                if (!deleteSupersededAfterCommit) deleteStaleEntries(entry)
                file.finishWrite(output)
                committed = true
            } catch (error: Exception) {
                file.failWrite(output)
                throw error
            }
            if (!file.readFully().contentEquals(packed)) throw IOException("Cadence journal could not be committed")
            if (deleteSupersededAfterCommit) {
                // The new version is committed with its key. A failure (or a
                // crash) here leaves a stale key, which the next read sweeps.
                try { deleteStaleEntries(entry) } catch (_: Exception) { }
            }
        } catch (error: Exception) {
            if (!committed) deleteEntry(entry)
            throw error
        } finally {
            packed.fill(0)
        }
    }

    @Synchronized override fun reset() {
        file.delete()
        if (listOf(base, File(base.path + ".bak"), File(base.path + ".new")).any { it.exists() }) {
            throw IOException("Cadence journal could not be deleted")
        }
        for (entry in entries()) deleteEntry(entry)
    }

    private fun pack(entry: String, sealed: ByteArray): ByteArray {
        val name = entry.toByteArray(StandardCharsets.US_ASCII)
        require(name.size in 1..MAX_ENTRY_BYTES)
        return byteArrayOf(FORMAT, (name.size ushr 8).toByte(), name.size.toByte()) + name + sealed
    }

    private fun unpack(value: ByteArray): Pair<String, ByteArray> {
        require(value.size >= 3 + 1 + 29 && value[0] == FORMAT)
        val size = ((value[1].toInt() and 0xff) shl 8) or (value[2].toInt() and 0xff)
        require(size in 1..MAX_ENTRY_BYTES && value.size >= 3 + size + 29)
        val entry = value.copyOfRange(3, 3 + size).toString(StandardCharsets.US_ASCII)
        require(entry.startsWith(entryPrefix) && entry.removePrefix(entryPrefix).matches(ENTRY_SUFFIX))
        return entry to value.copyOfRange(3 + size, value.size)
    }

    private fun key(entry: String, create: Boolean): SecretKey {
        keys.get(entry)?.let { return it }
        if (!create) throw SealKeyMissingException("The cadence journal key is unavailable")
        return keys.create(entry)
    }

    private fun entries(): List<String> = keys.aliases().filter { it.startsWith(entryPrefix) }
    private fun deleteStaleEntries(current: String) = entries().filterNot { it == current }.forEach(::deleteEntry)
    private fun deleteEntry(entry: String) = keys.delete(entry)

    private companion object {
        const val FORMAT: Byte = 1
        const val MAX_ENTRY_BYTES = 192
        const val MAX_OVERHEAD = 512
        val ENTRY_SUFFIX = Regex("[0-9a-f]{32}")
    }
}

/**
 * Where a store's AES-GCM sealing keys live. [get] answers null only when the
 * alias is definitively absent; a transient Keystore failure throws.
 */
interface SealKeys {
    fun get(alias: String): SecretKey?
    fun create(alias: String): SecretKey
    fun delete(alias: String)
    fun aliases(): List<String>
}

/** Non-exportable AES-256-GCM keys in AndroidKeyStore. */
object AndroidKeyStoreSealKeys : SealKeys {
    override fun get(alias: String): SecretKey? = keyStore().getKey(alias, null) as SecretKey?

    override fun create(alias: String): SecretKey =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }

    override fun delete(alias: String) = keyStore().deleteEntry(alias)
    override fun aliases(): List<String> = keyStore().aliases().toList()
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
}

/** A sealed file is present but its key alias is absent: definitive evidence, never a transient failure. */
class SealKeyMissingException(message: String) : IOException(message)
