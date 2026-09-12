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
 */
class RollbackResistantRoomStorage(
    context: Context,
    private val alias: String,
    private val maxPlaintextBytes: Int = 4 * 1024 * 1024,
) : RoomStorage {
    init {
        require(alias.matches(Regex("[A-Za-z0-9._-]{1,128}")))
        require(maxPlaintextBytes in 1..32 * 1024 * 1024)
    }

    private val directory = context.applicationContext.noBackupFilesDir
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
            if (base.exists() || File(base.path + ".bak").exists() || entries().isNotEmpty()) throw error
            return null
        }
        try {
            val (entry, sealed) = unpack(packed)
            try {
                val plain = RoomCipher { create -> key(entry, create) }.decrypt(sealed)
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
        val sealed = RoomCipher { create -> key(entry, create) }.encrypt(value)
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
                deleteStaleEntries(entry)
                file.finishWrite(output)
                committed = true
            } catch (error: Exception) {
                file.failWrite(output)
                throw error
            }
            if (!file.readFully().contentEquals(packed)) throw IOException("Cadence journal could not be committed")
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
        val keys = keyStore()
        val existing = keys.getKey(entry, null)
        if (existing != null) return existing as SecretKey
        if (!create) throw IOException("The cadence journal key is unavailable")
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(entry, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private fun entries(): List<String> = keyStore().aliases().toList().filter { it.startsWith(entryPrefix) }
    private fun deleteStaleEntries(current: String) = entries().filterNot { it == current }.forEach(::deleteEntry)
    private fun deleteEntry(entry: String) = keyStore().deleteEntry(entry)
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private companion object {
        const val FORMAT: Byte = 1
        const val MAX_ENTRY_BYTES = 192
        const val MAX_OVERHEAD = 512
        val ENTRY_SUFFIX = Regex("[0-9a-f]{32}")
    }
}
