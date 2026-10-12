package dev.forgesworn.kithmoot.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import java.io.File
import java.security.KeyStore
import java.util.UUID

/** Real Android Keystore and AtomicFile; no substitute key provider. */
@RunWith(AndroidJUnit4::class)
class MissingRoomIndexStorageTest {
    private lateinit var context: Context
    private lateinit var alias: String
    private lateinit var storage: EncryptedRoomStorage
    private lateinit var receiver: RollbackResistantRoomStorage
    private lateinit var members: EncryptedRoomStorage
    private val plain = "{\"version\":1,\"rooms\":[]}".toByteArray()
    private val index get() = File(context.noBackupFilesDir, "$alias.vault")
    private fun aliases() = KeyStore.getInstance("AndroidKeyStore").run {
        load(null); aliases().toList().toSet()
    }

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        alias = "kithmoot.test.missing-index.${UUID.randomUUID()}"
        storage = EncryptedRoomStorage(context, alias)
        receiver = RollbackResistantRoomStorage(context, "$alias.receiver")
        members = EncryptedRoomStorage(context, "$alias.members")
    }

    @After fun cleanup() {
        storage.reset(); receiver.reset(); members.reset()
        assertTrue(aliases().none { it == alias || it.startsWith("$alias.") })
    }

    @Test fun first_use_reads_empty_without_creating_a_key_or_file() {
        val before = aliases()
        assertNull(storage.read())
        assertTrue(RoomRepository(EncryptedRoomStorage(context, alias)).list().isEmpty())
        assertFalse(index.exists())
        assertEquals(before, aliases())
    }

    @Test fun missing_index_refuses_without_touching_receiver_members_or_keys_and_restores_exactly() {
        storage.write(plain)
        receiver.write("receiver fixture".toByteArray())
        members.write("member fixture".toByteArray())
        val sealed = index.readBytes()
        val receiverFile = File(context.noBackupFilesDir, "$alias.receiver.vault")
        val membersFile = File(context.noBackupFilesDir, "$alias.members.vault")
        val receiverBefore = receiverFile.readBytes()
        val membersBefore = membersFile.readBytes()
        val before = aliases()
        try {
            assertTrue(before.contains(alias))
            assertTrue(index.delete())
            val reopened = EncryptedRoomStorage(context, alias)
            assertThrows(java.io.IOException::class.java) { reopened.read() }
            assertThrows(RoomStorageException::class.java) { RoomRepository(reopened).list() }
            assertFalse(index.exists())
            assertEquals(before, aliases())
            assertArrayEquals(receiverBefore, receiverFile.readBytes())
            assertArrayEquals(membersBefore, membersFile.readBytes())
            index.writeBytes(sealed)
            assertArrayEquals(plain, EncryptedRoomStorage(context, alias).read())
            assertArrayEquals(sealed, index.readBytes())
            assertEquals(before, aliases())
        } finally {
            sealed.fill(0); receiverBefore.fill(0); membersBefore.fill(0)
        }
    }

    @Test fun explicit_reset_of_a_missing_index_removes_its_key_and_allows_fresh_use() {
        storage.write(plain)
        assertTrue(index.delete())
        assertThrows(RoomStorageException::class.java) { RoomRepository(storage).list() }
        RoomRepository(storage).reset()
        assertFalse(aliases().contains(alias))
        assertFalse(index.exists())
        assertTrue(RoomRepository(EncryptedRoomStorage(context, alias)).list().isEmpty())
        storage.write(plain)
        assertArrayEquals(plain, EncryptedRoomStorage(context, alias).read())
        assertTrue(aliases().contains(alias))
    }
}
