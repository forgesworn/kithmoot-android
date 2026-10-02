package dev.forgesworn.kithmoot.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.account.MlsVault
import dev.forgesworn.kithmoot.account.MlsVaultUnavailableException
import dev.forgesworn.kithmoot.account.VaultResult
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The MLS device vault over the real Keystore-backed stores (P3-02). */
@RunWith(AndroidJUnit4::class)
class MlsVaultStorageTest {
    private lateinit var context: Context
    private lateinit var prefix: String
    private val principal = "dev.forgesworn.kithmoot"
    private val identity = LocalSigner(ByteArray(32).also { SecureRandom().nextBytes(it) })
    private val now = System.currentTimeMillis() / 1000

    private fun files(): List<File> = context.noBackupFilesDir.listFiles().orEmpty().filter { it.name.startsWith("$prefix.") }
    private fun vault() = MlsVault(AndroidMlsVaultStores(context, prefix))

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        prefix = "kithmoot.mls-vault-test.${UUID.randomUUID().toString().take(8)}"
    }

    @After fun cleanup() {
        files().forEach { it.delete() }
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keys.aliases().toList().filter { it.startsWith("$prefix.") }.forEach(keys::deleteEntry)
    }

    @Test fun an_enrolled_device_survives_a_new_vault_and_nothing_is_on_disk_in_the_clear() = runBlocking {
        val first = vault()
        val enrolled = first.enrol(first.context(principal, identity.pubkey), identity, now + 86_400) as VaultResult.Ok
        val second = vault()
        val shown = second.device(second.context(principal, identity.pubkey)) as VaultResult.Ok
        assertEquals(enrolled.value, shown.value)
        assertEquals(first.installationId(), second.installationId())
        val stored = files()
        assertEquals(2, stored.size)
        for (file in stored) {
            assertTrue(file.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath + "/"))
            val text = String(file.readBytes(), Charsets.ISO_8859_1)
            assertFalse(file.name.contains(identity.pubkey))
            assertFalse(text.contains(identity.pubkey))
            assertFalse(text.contains(enrolled.value.device))
            assertFalse(text.contains("scope") || text.contains("credential") || text.contains("journal"))
        }
        // The wrapping keys are Keystore keys, which never export their bytes.
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val entries = keys.aliases().toList().filter { it.startsWith("$prefix.") }
        assertEquals(2, entries.size)
        assertTrue(entries.all { keys.getKey(it, null).encoded == null })
    }

    @Test fun a_damaged_record_refuses_rather_than_falling_back() {
        runBlocking { vault().let { it.enrol(it.context(principal, identity.pubkey), identity, now + 86_400) } }
        val record = files().single { it.name.contains(".persona.") }
        val bytes = record.readBytes()
        bytes[bytes.size - 1] = (bytes.last().toInt() xor 1).toByte()
        record.writeBytes(bytes)
        try {
            runBlocking { vault().let { it.device(it.context(principal, identity.pubkey)) } }
            fail("A damaged record must refuse")
        } catch (_: MlsVaultUnavailableException) { }
    }

    @Test fun a_restored_older_record_refuses_so_a_revocation_cannot_be_rolled_back() {
        val enrolled = runBlocking {
            vault().let { (it.enrol(it.context(principal, identity.pubkey), identity, now + 86_400) as VaultResult.Ok).value }
        }
        val record = files().single { it.name.contains(".persona.") }
        val older = record.readBytes()
        runBlocking { vault().let { assertTrue(it.revokeCredential(it.context(principal, identity.pubkey), enrolled.credentialId) is VaultResult.Ok) } }
        val newer = record.readBytes()
        record.writeBytes(older)
        try {
            runBlocking { vault().let { it.device(it.context(principal, identity.pubkey)) } }
            fail("A rolled-back record must refuse, not reopen")
        } catch (_: MlsVaultUnavailableException) { }
        // The current version still opens once it is back.
        record.writeBytes(newer)
        val shown = runBlocking { vault().let { it.device(it.context(principal, identity.pubkey)) } }
        assertTrue(shown is VaultResult.Ok)
        assertArrayEquals(newer, record.readBytes())
    }

    @Test fun clearing_removes_the_record_and_its_keys() = runBlocking {
        val v = vault()
        v.enrol(v.context(principal, identity.pubkey), identity, now + 86_400)
        v.clear(identity.pubkey)
        assertTrue(files().none { it.name.contains(".persona.") })
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertTrue(keys.aliases().toList().none { it.startsWith("$prefix.persona.") })
        assertTrue(v.device(v.context(principal, identity.pubkey)) is VaultResult.Refused)
    }
}
