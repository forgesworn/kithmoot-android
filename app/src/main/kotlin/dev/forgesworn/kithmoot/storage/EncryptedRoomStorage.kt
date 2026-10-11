package dev.forgesworn.kithmoot.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.lang.ref.WeakReference
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Authenticated, versioned encryption; Android supplies a non-exportable
 * wrapping key. [aad] binds what a ciphertext is for, so one store's value
 * cannot be opened as another's.
 */
internal class RoomCipher(private val aad: ByteArray = AAD, private val key: (create: Boolean) -> SecretKey) {
    fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        require(cipher.iv.size == 12)
        cipher.updateAAD(aad)
        return byteArrayOf(1) + cipher.iv + cipher.doFinal(plain)
    }

    fun decrypt(sealed: ByteArray): ByteArray {
        require(sealed.size >= 29 && sealed[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, sealed.copyOfRange(1, 13)))
        cipher.updateAAD(aad)
        return cipher.doFinal(sealed, 13, sealed.size - 13)
    }

    companion object {
        val AAD = "KithMoot saved rooms v1".toByteArray(Charsets.UTF_8)
    }
}

/** Process-local transaction monitors shared by live instances of one vault path. */
private object EncryptedRoomStorageMonitors {
    private val monitors = mutableMapOf<String, WeakReference<Any>>()

    @Synchronized fun forFile(file: File): Any {
        val path = file.canonicalPath
        monitors.entries.removeAll { it.value.get() == null }
        return monitors[path]?.get() ?: Any().also { monitors[path] = WeakReference(it) }
    }
}

/** Used off the main thread; separate instances of the same vault share exclusion. */
class EncryptedRoomStorage(context: Context, private val alias: String = "kithmoot.rooms.v1",
    private val maxPlaintextBytes: Int = 4 * 1024 * 1024,
) : RoomStorage {
    init { require(maxPlaintextBytes in 1..32 * 1024 * 1024) }
    private val directory = context.applicationContext.noBackupFilesDir
    private val base = File(directory, "$alias.vault")
    // Keep the registry's weak value strongly owned for this instance's lifetime.
    private val monitor = EncryptedRoomStorageMonitors.forFile(base)
    private val file = AtomicFile(base)
    private val cipher = RoomCipher(key = ::key)

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

    override fun read(): ByteArray? = synchronized(monitor) {
        val input = try { file.openRead() } catch (e: FileNotFoundException) {
            if (base.exists() || File(base.path + ".bak").exists()) throw e
            // A retained key means this store existed. Missing ciphertext is
            // unavailable state, never evidence that every saved room was forgotten.
            if (KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias)) {
                throw IOException("Saved-room ciphertext is unavailable", e)
            }
            return@synchronized null
        }
        val sealed = input.use {
            require(it.channel.size() <= maxPlaintextBytes + 64)
            val bytes = it.readBytes()
            require(bytes.size <= maxPlaintextBytes + 64)
            bytes
        }
        cipher.decrypt(sealed)
    }

    override fun write(value: ByteArray): Unit = synchronized(monitor) {
        require(value.size <= maxPlaintextBytes)
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

    override fun reset(): Unit = synchronized(monitor) {
        file.delete()
        if (listOf(base, File(base.path + ".bak"), File(base.path + ".new")).any { it.exists() }) {
            throw IOException("Saved rooms could not be deleted")
        }
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
    }
}
