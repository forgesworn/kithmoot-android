package dev.forgesworn.kithmoot.storage

import android.content.Context
import android.util.AtomicFile
import dev.forgesworn.kithmoot.account.CoordinatedVaultStores
import dev.forgesworn.kithmoot.account.MarkerStore
import dev.forgesworn.kithmoot.account.PersonaLock
import dev.forgesworn.kithmoot.account.SnapshotFiles
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The MLS device vault's stores: one [RollbackResistantRoomStorage] per name
 * in `noBackupFilesDir`, so each committed version has its own Keystore AES-GCM
 * key and an older file cannot be opened once a newer one is committed. The
 * vault's AAD is bound into every ciphertext.
 *
 * In coordinated mode (P3-03b-2) each persona's coordinated file instead
 * deletes its superseded key only after the commit, has an unsealed marker and
 * a lock file beside it, and a never-rotated inner key. [directory] and [keys]
 * exist so tests can copy a whole profile with software keys; production uses
 * the defaults.
 */
class AndroidMlsVaultStores(
    context: Context,
    private val prefix: String = "kithmoot.mls-vault.v1",
    directory: File? = null,
    private val keys: SealKeys = AndroidKeyStoreSealKeys,
) : CoordinatedVaultStores {
    init { require(prefix.matches(Regex("[A-Za-z0-9._-]{1,64}"))) }

    private val app = context.applicationContext
    /** Named apart from the [directory] parameter, so the default can never resolve to this property itself. */
    private val root: File = directory ?: app.noBackupFilesDir
    override val lockName: String = prefix
    override val innerKeys: SealKeys get() = keys

    override fun open(name: String, aad: ByteArray): RoomStorage {
        require(name.matches(NAME))
        return RollbackResistantRoomStorage(app, "$prefix.$name", MAX_BYTES, aad, directory = root, keys = keys)
    }

    override fun coordinated(name: String, aad: ByteArray): RoomStorage {
        require(name.matches(COORDINATED))
        return RollbackResistantRoomStorage(app, "$prefix.$name", MAX_COORDINATED_BYTES, aad, deleteSupersededAfterCommit = true, directory = root, keys = keys)
    }

    override fun marker(name: String): MarkerStore {
        require(name.matches(COORDINATED))
        return AtomicMarker(File(root, "$prefix.$name.marker"))
    }

    override fun innerAlias(name: String): String {
        require(name.matches(COORDINATED))
        return "$prefix.$name.inner"
    }

    override fun coordinatedNames(): List<String> {
        val pattern = Regex("^" + Regex.escape("$prefix.") + "(coord\\.[0-9a-f]{32})\\.(vault|vault\\.bak|vault\\.new|marker)$")
        return root.listFiles().orEmpty().mapNotNull { pattern.find(it.name)?.groupValues?.get(1) }.distinct().sorted()
    }

    override fun personaLock(name: String): PersonaLock {
        require(name.matches(COORDINATED))
        return FilePersonaLock(File(root, "$prefix.$name.lock"))
    }

    override fun snapshots(name: String): SnapshotFiles {
        require(name.matches(COORDINATED))
        return AtomicSnapshots(root, "$prefix.$name.snap.")
    }

    private companion object {
        /** A full journal of 1,024 decisions is about 600 KiB. */
        const val MAX_BYTES = 1024 * 1024
        /** Active and staged copies of a full record, with the coordinator state. */
        const val MAX_COORDINATED_BYTES = 4 * 1024 * 1024
        val NAME = Regex("[A-Za-z0-9.-]{1,48}")
        val COORDINATED = Regex("coord\\.[0-9a-f]{32}")
    }
}

/**
 * A persona's session snapshots, each sealed by the vault before it gets
 * here: `<prefix><session hex>.<generation>`, written once with [AtomicFile]
 * and fsync, never rewritten.
 */
private class AtomicSnapshots(private val root: File, private val prefix: String) : SnapshotFiles {
    private fun file(session: String, generation: Long): AtomicFile {
        require(SESSION.matches(session) && generation > 0)
        return AtomicFile(File(root, "$prefix$session.$generation"))
    }

    @Synchronized override fun read(session: String, generation: Long): ByteArray? {
        val file = file(session, generation)
        if (!file.baseFile.exists()) return null
        // A sealed snapshot is at most the engine's 64 MiB plus the seal.
        if (file.baseFile.length() > MAX_SEALED) throw IOException("The snapshot is too large")
        return try {
            file.readFully()
        } catch (error: FileNotFoundException) {
            // Only a file that is really gone is absent: any other open failure
            // (descriptors exhausted, a permission error) is transient, never a fence.
            if (file.baseFile.exists()) throw error
            null
        }
    }

    @Synchronized override fun write(session: String, generation: Long, sealed: ByteArray) {
        require(sealed.size <= MAX_SEALED)
        val file = file(session, generation)
        val output = file.startWrite()
        try {
            output.write(sealed)
            output.fd.sync()
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    @Synchronized override fun delete(session: String, generation: Long) {
        val file = file(session, generation)
        file.delete()
        if (file.baseFile.exists()) throw IOException("The snapshot could not be deleted")
    }

    @Synchronized override fun list(): List<Pair<String, Long>> {
        val pattern = Regex("^" + Regex.escape(prefix) + "([0-9a-f]{64})\\.([1-9][0-9]{0,18})(?:\\.new|\\.bak)?$")
        return root.listFiles().orEmpty().mapNotNull { f ->
            pattern.find(f.name)?.let { m -> m.groupValues[2].toLongOrNull()?.let { m.groupValues[1] to it } }
        }.distinct()
    }

    private companion object {
        val SESSION = Regex("[0-9a-f]{64}")
        const val MAX_SEALED = 64L * 1024 * 1024 + 64
    }
}

/** An unsealed marker, written with [AtomicFile] and fsync. */
private class AtomicMarker(base: File) : MarkerStore {
    private val file = AtomicFile(base)

    @Synchronized override fun read(): ByteArray? = try {
        file.readFully().also { require(it.size <= dev.forgesworn.kithmoot.account.MARKER_MAX_BYTES) }
    } catch (_: FileNotFoundException) {
        null
    }

    @Synchronized override fun write(value: ByteArray) {
        require(value.size <= dev.forgesworn.kithmoot.account.MARKER_MAX_BYTES)
        val output = file.startWrite()
        try {
            output.write(value)
            output.fd.sync()
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    @Synchronized override fun delete() {
        file.delete()
        if (file.baseFile.exists()) throw IOException("The marker could not be deleted")
    }
}

/**
 * A persona's writer lock: a process-wide mutex (so two vault objects in one
 * process never overlap) and a [java.nio.channels.FileLock] on a lock file
 * beside the store, held until the work, witness round trip included, ends.
 */
class FilePersonaLock(private val file: File) : PersonaLock {
    private val mutex = MUTEXES.getOrPut(file.absolutePath) { Mutex() }

    override suspend fun <T> withLock(work: suspend () -> T): T = mutex.withLock {
        val channel = withContext(Dispatchers.IO) { RandomAccessFile(file, "rw").channel }
        try {
            val lock = withContext(Dispatchers.IO) { channel.lock() }
            try { work() } finally { runCatching { lock.release() } }
        } finally {
            runCatching { channel.close() }
        }
    }

    private companion object { val MUTEXES = ConcurrentHashMap<String, Mutex>() }
}
