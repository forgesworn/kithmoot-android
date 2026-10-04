package dev.forgesworn.kithmoot.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.KeyStore
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * When a rollback-resistant store deletes the superseded Keystore key
 * (P3-03b-2): before the commit by default, which cadence journals, the epoch
 * store and the vault installation rely on; after it only when asked, as the
 * coordinated persona store does.
 */
@RunWith(AndroidJUnit4::class)
class RollbackKeyOrderTest {
    private lateinit var context: Context
    private lateinit var alias: String
    private val base get() = File(context.noBackupFilesDir, "$alias.vault")

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        alias = "kithmoot.key-order-test.${UUID.randomUUID().toString().take(8)}"
    }

    @After fun cleanup() {
        context.noBackupFilesDir.listFiles().orEmpty().filter { it.name.startsWith("$alias.") }.forEach { it.delete() }
        keyStore().aliases().toList().filter { it.startsWith("$alias.") }.forEach { keyStore().deleteEntry(it) }
    }

    @Test fun a_store_built_with_the_defaults_lives_in_no_backup_files() {
        RollbackResistantRoomStorage(context, alias).write("one".toByteArray())
        assertTrue(base.isFile)
        assertEquals(context.noBackupFilesDir.canonicalPath, base.canonicalFile.parentFile!!.canonicalPath)
        assertArrayEquals("one".toByteArray(), RollbackResistantRoomStorage(context, alias).read())
    }

    @Test fun the_vault_stores_built_with_the_defaults_live_in_no_backup_files() {
        AndroidMlsVaultStores(context, alias).open("installation", ByteArray(1)).write("two".toByteArray())
        assertTrue(File(context.noBackupFilesDir, "$alias.installation.vault").isFile)
    }

    @Test fun by_default_the_old_key_goes_before_the_new_version_is_committed() {
        val committedAtDeletion = mutableListOf<ByteArray>()
        val keys = RecordingSealKeys(AndroidKeyStoreSealKeys) { committedAtDeletion += base.readBytes() }
        val storage = RollbackResistantRoomStorage(context, alias, keys = keys)
        storage.write("one".toByteArray())
        val first = base.readBytes()
        storage.write("two".toByteArray())
        // The deletion saw the first version still committed.
        assertEquals(1, committedAtDeletion.size)
        assertArrayEquals(first, committedAtDeletion.single())
        assertEquals(1, entries().size)
        assertArrayEquals("two".toByteArray(), RollbackResistantRoomStorage(context, alias).read())
    }

    @Test fun when_asked_the_old_key_goes_only_after_the_commit() {
        val committedAtDeletion = mutableListOf<ByteArray>()
        val keys = RecordingSealKeys(AndroidKeyStoreSealKeys) { committedAtDeletion += base.readBytes() }
        val storage = RollbackResistantRoomStorage(context, alias, deleteSupersededAfterCommit = true, keys = keys)
        storage.write("one".toByteArray())
        storage.write("two".toByteArray())
        val second = base.readBytes()
        assertEquals(1, committedAtDeletion.size)
        assertArrayEquals(second, committedAtDeletion.single())
        assertEquals(1, entries().size)
    }

    @Test fun a_crash_between_the_commit_and_the_key_deletion_opens_normally_and_sweeps() {
        val keys = RecordingSealKeys(AndroidKeyStoreSealKeys)
        val storage = RollbackResistantRoomStorage(context, alias, deleteSupersededAfterCommit = true, keys = keys)
        storage.write("one".toByteArray())
        keys.failNextDelete = true
        storage.write("two".toByteArray())
        // The new version is committed and both keys survive the "crash".
        assertEquals(2, entries().size)
        val reopened = RollbackResistantRoomStorage(context, alias, deleteSupersededAfterCommit = true)
        assertArrayEquals("two".toByteArray(), reopened.read())
        assertEquals(1, entries().size)
        assertTrue(base.exists())
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun entries() = keyStore().aliases().toList().filter { it.startsWith("$alias.entry.") }
}
