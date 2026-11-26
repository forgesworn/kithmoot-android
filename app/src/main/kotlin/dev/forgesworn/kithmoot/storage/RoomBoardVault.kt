package dev.forgesworn.kithmoot.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import dev.forgesworn.kithmoot.board.RoomBoardBridge
import dev.forgesworn.kithmoot.board.RoomBoardCopyStore
import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.toHex
import java.io.File
import java.io.IOException
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * This phone's copy of one room's board, for one participant: a directory of records, each one Yjs update the
 * board page wrote, sealed under a key of the room's own that Android does not let leave the phone. A record is
 * named by the SHA-256 of its bytes, written whole or not at all, and never rewritten. Removing a room removes
 * the directory and the key (`RoomWipeStep.ROOM_BOARD`).
 */
class RoomBoardVault internal constructor(private val directory: File, private val cipher: RoomCipher, private val alias: String) : RoomBoardCopyStore {

    constructor(context: Context, roomId: String, participant: String) : this(name(roomId, participant), context)
    private constructor(name: String, context: Context) :
        this(File(context.applicationContext.noBackupFilesDir, "$name.board"), RoomCipher(aad(name)) { create -> key(name, create) }, name)

    override fun read(): Pair<List<Pair<String, ByteArray>>, Int> = synchronized(lock(directory)) {
        val files = directory.listFiles { file -> RECORD.matches(file.name) }?.sortedBy { it.name } ?: return@synchronized emptyList<Pair<String, ByteArray>>() to 0
        var unread = 0
        val records = mutableListOf<Pair<String, ByteArray>>()
        for (file in files.take(MAX_RECORDS)) {
            try {
                val sealed = AtomicFile(file).readFully()
                require(sealed.size <= MAX_RECORD_BYTES + 64)
                val update = cipher.decrypt(sealed)
                // Left exactly as it is, and never counted among the records the board may remove.
                require(RoomBoardBridge.recordId(update) == file.name)
                records += file.name to update
            } catch (_: Exception) { unread++ }
        }
        unread += maxOf(0, files.size - MAX_RECORDS)
        records to unread
    }

    override fun add(update: ByteArray): String = synchronized(lock(directory)) {
        require(update.isNotEmpty() && update.size <= MAX_RECORD_BYTES) { "This board is too large to keep on this phone" }
        val id = RoomBoardBridge.recordId(update)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("The board could not be kept on this phone")
        val file = AtomicFile(File(directory, id))
        if (File(directory, id).exists()) return@synchronized id
        val sealed = cipher.encrypt(update)
        val output = file.startWrite()
        try {
            output.write(sealed)
            output.fd.sync()
            file.finishWrite(output)
        } catch (e: Exception) {
            file.failWrite(output)
            throw e
        }
        if (!file.readFully().contentEquals(sealed)) throw IOException("The board could not be kept on this phone")
        id
    }

    override fun remove(ids: List<String>): Unit = synchronized(lock(directory)) {
        for (id in ids) if (RECORD.matches(id)) AtomicFile(File(directory, id)).delete()
    }

    /** Remove every record and the key they are sealed under. */
    fun reset(): Unit = synchronized(lock(directory)) {
        directory.listFiles()?.forEach { it.delete() }
        directory.delete()
        if (directory.exists()) throw IOException("The board could not be removed from this phone")
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
    }

    companion object {
        private val RECORD = Regex("[0-9a-f]{64}")
        private const val MAX_RECORDS = 4096
        /** A whole board can be kept as one record: KithMoot's limit for one is about 120 MB of pieces; this is a phone. */
        private const val MAX_RECORD_BYTES = 32 * 1024 * 1024
        private val locks = HashMap<String, Any>()

        private fun lock(directory: File): Any = synchronized(locks) { locks.getOrPut(directory.canonicalPath) { Any() } }

        internal fun name(roomId: String, participant: String) =
            "kithmoot.board." + Digests.sha256("$roomId:$participant".toByteArray(Charsets.UTF_8)).toHex()

        private fun aad(name: String) = "KithMoot room board copy v1:$name".toByteArray(Charsets.UTF_8)

        private fun key(alias: String, create: Boolean): SecretKey {
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (keys.getKey(alias, null) as? SecretKey)?.let { return it }
            if (!create) throw IOException("The board's key on this phone is unavailable")
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
    }
}
