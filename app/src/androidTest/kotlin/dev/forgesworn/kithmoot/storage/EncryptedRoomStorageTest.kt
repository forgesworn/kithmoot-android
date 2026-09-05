package dev.forgesworn.kithmoot.storage

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.*

@RunWith(AndroidJUnit4::class)
class EncryptedRoomStorageTest {
    private lateinit var context: Context
    private lateinit var alias: String
    private lateinit var storage: EncryptedRoomStorage
    private val plain = "{\"version\":1,\"rooms\":[],\"fixture\":\"private-room-access\"}".toByteArray()
    private val file get() = File(context.noBackupFilesDir, "$alias.vault")

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        alias = "kithmoot.test.${UUID.randomUUID()}"
        storage = EncryptedRoomStorage(context, alias)
    }
    @After fun cleanup() {
        File(file.path + ".new").deleteRecursively()
        storage.reset()
    }

    @Test fun encrypted_data_survives_a_new_storage_instance_and_stays_out_of_backups() {
        storage.write(plain)
        assertArrayEquals(plain, EncryptedRoomStorage(context, alias).read())
        assertFalse(file.readText().contains("private-room-access"))
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        assertTrue(file.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath + "/"))
        val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(alias, null)
        assertNull(key.encoded)
    }

    @Test fun a_missing_keystore_key_preserves_ciphertext_until_explicit_reset() {
        storage.write(plain)
        val sealed = file.readBytes()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        val repository = RoomRepository(EncryptedRoomStorage(context, alias))
        try { repository.list(); fail("Missing key must block recovery") } catch (_: RoomStorageException) { }
        assertArrayEquals(sealed, file.readBytes())
        repository.reset()
        assertTrue(repository.list().isEmpty())
        storage.write(plain)
        assertArrayEquals(plain, storage.read())
    }

    @Test fun damaged_ciphertext_is_not_replaced_with_an_empty_room_list() {
        storage.write(plain)
        val damaged = file.readBytes().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        file.writeBytes(damaged)
        try { RoomRepository(storage).list(); fail("Tampering must fail") } catch (_: RoomStorageException) { }
        assertArrayEquals(damaged, file.readBytes())
    }

    @Test fun a_failed_atomic_write_leaves_the_previous_file_readable() {
        storage.write(plain)
        val obstruction = File(file.path + ".new").apply { mkdir() }
        File(obstruction, "keep").writeText("Test write obstruction")
        try { storage.write("replacement".toByteArray()); fail("The write should fail") } catch (_: java.io.IOException) { }
        obstruction.deleteRecursively()
        assertArrayEquals(plain, storage.read())
    }
}
