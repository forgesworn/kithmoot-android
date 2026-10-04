package dev.forgesworn.kithmoot.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.forgesworn.kithmoot.account.ConsentDecision
import dev.forgesworn.kithmoot.account.ConsentPrompt
import dev.forgesworn.kithmoot.account.ConsentScope
import dev.forgesworn.kithmoot.account.CoordinationGenesis
import dev.forgesworn.kithmoot.account.CoordinationStatus
import dev.forgesworn.kithmoot.account.EngineVaultWitness
import dev.forgesworn.kithmoot.account.EnrolledDevice
import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.account.MlsVault
import dev.forgesworn.kithmoot.account.VaultCoordination
import dev.forgesworn.kithmoot.account.VaultRefusal
import dev.forgesworn.kithmoot.account.VaultResult
import dev.forgesworn.kithmoot.account.WitnessChannels
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.vmls.LeafBinding
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The coordinated MLS vault with the real VMLS engine (debug builds), its
 * real Keystore-backed stores, and an in-process witness signing real Ed25519
 * receipts (P3-03b-2).
 */
@RunWith(AndroidJUnit4::class)
class CoordinatedVaultEngineTest {
    private lateinit var context: Context
    private lateinit var prefix: String
    private lateinit var scratch: File
    private lateinit var witness: FakeEd25519Witness
    private val principal = "dev.forgesworn.kithmoot"
    private val random = SecureRandom()
    private val identity = LocalSigner(ByteArray(32).also { random.nextBytes(it) })
    private val now = System.currentTimeMillis() / 1000
    private val homeBox = ByteArray(32).also { random.nextBytes(it) }.toHex()
    private val approve = ConsentPrompt { ConsentDecision.Approve }
    private var credential: NostrEvent? = null

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        prefix = "kithmoot.coord-test.${UUID.randomUUID().toString().take(8)}"
        scratch = File(context.cacheDir, prefix).apply { mkdirs() }
        witness = FakeEd25519Witness()
    }

    @After fun cleanup() {
        context.noBackupFilesDir.listFiles().orEmpty().filter { it.name.startsWith("$prefix.") }.forEach { it.delete() }
        scratch.deleteRecursively()
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keys.aliases().toList().filter { it.startsWith("$prefix.") }.forEach(keys::deleteEntry)
    }

    private val channels = WitnessChannels { _, _ -> witness.channel }

    /** Keystore-backed stores in `noBackupFilesDir`, as production would use. */
    private fun keystoreStores() = AndroidMlsVaultStores(context, prefix)

    /** Software-key stores in [directory], so the whole profile can be copied. */
    private fun softwareStores(directory: File) = AndroidMlsVaultStores(context, prefix, directory, FileSealKeys(File(directory, "keys")))

    private fun vault(stores: AndroidMlsVaultStores) = MlsVault.coordinated(VaultCoordination(stores, channels, EngineVaultWitness()))

    private suspend fun enrolAtBox(v: MlsVault, subject: ByteArray = bytes(32)): CoordinationGenesis {
        val genesis = (v.beginCoordination(identity.pubkey, subject, witness.publicKey) as VaultResult.Ok).value
        witness.enrol(genesis.subject, genesis.initialDigest)
        // The engine verifies the fake witness's Ed25519 receipt here.
        assertEquals(CoordinationStatus.Active, v.coordinationStatus(identity.pubkey, check = true))
        return genesis
    }

    private suspend fun enrolDevice(v: MlsVault): EnrolledDevice {
        val signer = object : dev.forgesworn.kithmoot.account.ParticipantSigner by identity {
            override suspend fun sign(kind: Int, createdAt: Long, tags: List<List<String>>, content: String): NostrEvent =
                identity.sign(kind, createdAt, tags, content).also { credential = it }
        }
        return (v.enrol(v.context(principal, identity.pubkey), signer, now + 86_400) as VaultResult.Ok).value
    }

    @Test fun genesis_enrolment_and_a_signature_are_each_promoted_with_the_engine() = runBlocking<Unit> {
        val stores = keystoreStores()
        val v = vault(stores)
        val genesis = enrolAtBox(v)
        assertEquals(0L, witness.seq(genesis.subject))
        val device = enrolDevice(v)
        assertEquals(1L, witness.seq(genesis.subject))
        val req = request(device)
        val reply = (v.signLeafBindingV1(v.context(principal, identity.pubkey), req, approve) as VaultResult.Ok).value
        assertEquals(2L, witness.seq(genesis.subject))
        assertTrue(Schnorr.verify(reply.signature.hexToBytes(), req.digest().hexToBytes(), device.device.hexToBytes()))

        // A restart reopens the engine over what is really stored: the inner
        // objects kept their exact bytes, so the digest matches the witness.
        val restarted = vault(stores)
        assertEquals(CoordinationStatus.Active, restarted.coordinationStatus(identity.pubkey, check = true))
        val again = restarted.context(principal, identity.pubkey)
        val replayed = (restarted.signLeafBindingV1(again, req, ConsentPrompt { error("a replay never prompts") }) as VaultResult.Ok).value
        assertEquals(reply.signature, replayed.signature)
        assertEquals(2L, witness.seq(genesis.subject))
        // Exactly the coordinated files: never the uncoordinated persona store.
        val names = context.noBackupFilesDir.listFiles().orEmpty().map { it.name }.filter { it.startsWith("$prefix.") }.toSet()
        val coord = names.single { it.endsWith(".vault") && it.startsWith("$prefix.coord.") }.removeSuffix(".vault")
        assertEquals(
            setOf("$prefix.installation.vault", "$prefix.epoch.vault", "$prefix.coord-index.vault", "$coord.vault", "$coord.marker", "$coord.lock"),
            names,
        )
    }

    @Test fun a_witness_signing_with_another_key_never_confirms() = runBlocking<Unit> {
        val v = vault(keystoreStores())
        val genesis = (v.beginCoordination(identity.pubkey, bytes(32), witness.publicKey) as VaultResult.Ok).value
        witness.enrol(genesis.subject, genesis.initialDigest)
        witness.mode = FakeEd25519Witness.Mode.WrongKey
        assertTrue(v.coordinationStatus(identity.pubkey, check = true) is CoordinationStatus.Pending)
        assertEquals(VaultResult.Refused(VaultRefusal.WitnessPending), v.enrol(v.context(principal, identity.pubkey), identity, now + 86_400))
    }

    @Test fun a_held_write_is_finished_by_its_resend_before_the_next_one() = runBlocking<Unit> {
        val v = vault(keystoreStores())
        val genesis = enrolAtBox(v)
        val device = enrolDevice(v)
        val ctx = v.context(principal, identity.pubkey)
        val scope = ConsentScope(principal, identity.pubkey, device.device, homeBox, MlsVault.SIGN_METHOD)
        // The witness commits the approval, but its answer is lost.
        witness.mode = FakeEd25519Witness.Mode.LoseAnswer
        assertEquals(VaultResult.Refused(VaultRefusal.WitnessPending), v.approve(ctx, scope))
        assertEquals(2L, witness.seq(genesis.subject))
        assertTrue(v.coordinationStatus(identity.pubkey) is CoordinationStatus.Pending)
        witness.mode = FakeEd25519Witness.Mode.Up
        // The exact staged candidate is resent and promoted, then the revocation advances on it.
        assertTrue(v.revokeCredential(ctx, bytes(32).toHex()) is VaultResult.Ok)
        assertEquals(3L, witness.seq(genesis.subject))
        val signed = v.signLeafBindingV1(ctx, request(device), ConsentPrompt { ConsentDecision.Deny })
        assertTrue("the approval was promoted with its candidate", signed is VaultResult.Ok<*>)
    }

    @Test fun a_cloned_profile_is_fenced_by_the_witness() = runBlocking<Unit> {
        val original = File(scratch, "original")
        val v = vault(softwareStores(original))
        val genesis = enrolAtBox(v)
        val device = enrolDevice(v)
        // Copy the whole profile, files and keys, as a device image would.
        val copy = File(scratch, "copy")
        original.copyRecursively(copy)
        assertTrue(v.signLeafBindingV1(v.context(principal, identity.pubkey), request(device), approve) is VaultResult.Ok)
        val cloned = vault(softwareStores(copy))
        val c = cloned.context(principal, identity.pubkey)
        assertEquals(VaultResult.Refused(VaultRefusal.RestoreFenced), cloned.signLeafBindingV1(c, request(device), approve))
        assertEquals(CoordinationStatus.Fenced("witness-mismatch", genesis.subject), cloned.coordinationStatus(identity.pubkey))
        assertEquals(2L, witness.seq(genesis.subject))
        // The fence is persisted: a fresh vault over the copy stays fenced.
        val reopened = vault(softwareStores(copy))
        assertEquals(VaultResult.Refused(VaultRefusal.RestoreFenced), reopened.device(reopened.context(principal, identity.pubkey)))
    }

    @Test fun a_restored_older_file_is_fenced_through_the_missing_seal_key_marker() = runBlocking<Unit> {
        val stores = keystoreStores()
        val v = vault(stores)
        val genesis = enrolAtBox(v)
        val device = enrolDevice(v)
        val file = coordinatedFile()
        val older = file.readBytes()
        assertTrue(v.signLeafBindingV1(v.context(principal, identity.pubkey), request(device), approve) is VaultResult.Ok)
        file.writeBytes(older)
        val restored = vault(stores)
        assertEquals(VaultResult.Refused(VaultRefusal.RestoreFenced), restored.device(restored.context(principal, identity.pubkey)))
        assertEquals(CoordinationStatus.Fenced("missing-seal-key", genesis.subject), restored.coordinationStatus(identity.pubkey))
        val marker = File(file.path.removeSuffix(".vault") + ".marker").readText()
        assertTrue(marker.contains("\"fenced\"") && marker.contains(genesis.subject))
        assertFalse(marker.contains(identity.pubkey))
    }

    @Test fun a_witness_behind_is_retired_and_a_cleared_persona_keeps_its_duty_until_retired() = runBlocking<Unit> {
        val stores = keystoreStores()
        val v = vault(stores)
        val genesis = enrolAtBox(v)
        enrolDevice(v)
        // The witness lost state: it is back at genesis.
        witness.subjects.getValue(genesis.subject).seq = 0
        val reopened = vault(stores)
        assertEquals(CoordinationStatus.Fenced("witness-behind", genesis.subject), reopened.coordinationStatus(identity.pubkey))
        // The retiring read and advance ran at open, with the engine: the subject is retired.
        assertTrue(witness.subjects.getValue(genesis.subject).retired)
        witness.mode = FakeEd25519Witness.Mode.Down
        reopened.clear(identity.pubkey)
        assertTrue(coordinatedFile().exists())
        // A fresh vault reopens the cleared file and stays fenced until the duty ends.
        val cleared = vault(stores)
        assertTrue(cleared.coordinationStatus(identity.pubkey) is CoordinationStatus.Fenced)
        assertEquals(VaultResult.Refused(VaultRefusal.Unauthorised), cleared.beginCoordination(identity.pubkey, bytes(32), witness.publicKey))
        witness.mode = FakeEd25519Witness.Mode.Up
        assertEquals(emptyMap<String, Exception>(), cleared.runRetiringDuties())
        assertTrue(context.noBackupFilesDir.listFiles().orEmpty().none { it.name.startsWith("$prefix.coord.") && it.name.endsWith(".vault") })
        assertEquals(CoordinationStatus.NotEnrolled, cleared.coordinationStatus(identity.pubkey))
        enrolAtBox(cleared)
    }

    private fun coordinatedFile(): File =
        context.noBackupFilesDir.listFiles().orEmpty().single { it.name.startsWith("$prefix.coord.") && it.name.endsWith(".vault") }

    private fun request(device: EnrolledDevice): JsonObject {
        val body = TestBindings.unsigned(bytes(32), bytes(32), credential!!, device.device, now + 3_600, homeBox.hexToBytes())
        return buildJsonObject {
            put("v", 1)
            put("operation", bytes(32).toHex())
            put("body", Base64.getEncoder().encodeToString(body))
            put("digest", LeafBinding.digest(body).toHex())
            put("expires_at", now + 300)
        }
    }

    private fun JsonObject.digest(): String = (getValue("digest") as kotlinx.serialization.json.JsonPrimitive).content
    private fun bytes(n: Int) = ByteArray(n).also(random::nextBytes)
}
