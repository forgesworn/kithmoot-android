package dev.forgesworn.kithmoot.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Authenticated, versioned encryption; Android supplies a non-exportable wrapping key. */
internal class RoomCipher(private val key: (create: Boolean) -> SecretKey) {
    fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        require(cipher.iv.size == 12)
        cipher.updateAAD(AAD)
        return byteArrayOf(1) + cipher.iv + cipher.doFinal(plain)
    }

    fun decrypt(sealed: ByteArray): ByteArray {
        require(sealed.size >= 29 && sealed[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, sealed.copyOfRange(1, 13)))
        cipher.updateAAD(AAD)
        return cipher.doFinal(sealed, 13, sealed.size - 13)
    }

    companion object {
        private val AAD = "KithMoot saved rooms v1".toByteArray(Charsets.UTF_8)
    }
}

/** Used off the main thread through the application's single RoomRepository. */
class EncryptedRoomStorage(context: Context, private val alias: String = "kithmoot.rooms.v1") : RoomStorage {
    private val directory = context.applicationContext.noBackupFilesDir
    private val base = File(directory, "$alias.vault")
    private val file = AtomicFile(base)
    private val cipher = RoomCipher(::key)

    private fun key(create: Boolean): SecretKey {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keys.getKey(alias, null)
        if (existing != null) return existing as SecretKey
        // Losing access to existing ciphertext must never mint a replacement identity.
        if (!create) throw IOException("The saved-room key is unavailable")
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
            generateKey()
        }
    }

    override fun read(): ByteArray? {
        val input = try { file.openRead() } catch (e: FileNotFoundException) {
            if (base.exists() || File(base.path + ".bak").exists()) throw e
            return null
        }
        val sealed = input.use {
            require(it.channel.size() <= 4 * 1024 * 1024 + 64)
            val bytes = it.readBytes()
            require(bytes.size <= 4 * 1024 * 1024 + 64)
            bytes
        }
        return cipher.decrypt(sealed)
    }

    override fun write(value: ByteArray) {
        val sealed = cipher.encrypt(value)
        val output = file.startWrite()
        try {
            output.write(sealed)
            output.fd.sync()
            file.finishWrite(output)
        } catch (e: Exception) {
            file.failWrite(output)
            throw e
        }
        // AtomicFile logs some filesystem errors rather than throwing. Verify its commit.
        if (!file.readFully().contentEquals(sealed)) throw IOException("Saved rooms could not be committed")
    }

    override fun reset() {
        file.delete()
        if (listOf(base, File(base.path + ".bak"), File(base.path + ".new")).any { it.exists() }) {
            throw IOException("Saved rooms could not be deleted")
        }
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
    }
}
