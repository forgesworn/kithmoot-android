package dev.forgesworn.kithmoot.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.forgesworn.kithmoot.account.ConsentDecision
import dev.forgesworn.kithmoot.account.ConsentPrompt
import dev.forgesworn.kithmoot.account.ConsentScope
import dev.forgesworn.kithmoot.account.CoordinationGenesis
import dev.forgesworn.kithmoot.account.CoordinationStatus
import dev.forgesworn.kithmoot.account.CoordinatedVaultStores
import dev.forgesworn.kithmoot.account.EngineVaultWitness
import dev.forgesworn.kithmoot.account.EnrolledDevice
import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.account.MlsVault
import dev.forgesworn.kithmoot.account.MlsVaultUnavailableException
import dev.forgesworn.kithmoot.account.PersonaFile
import dev.forgesworn.kithmoot.account.VaultCoordination
import dev.forgesworn.kithmoot.account.VaultRefusal
import dev.forgesworn.kithmoot.account.VaultResult
import dev.forgesworn.kithmoot.account.WitnessChannels
import dev.forgesworn.kithmoot.account.WitnessEnrolment
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The coordinated MLS vault with the real VMLS engine (debug builds), its
 * real Keystore-backed stores, and an in-process witness signing real Ed25519
 * receipts (P3-03b-2). Tests named `wNN_` are the note's acceptance rows;
 * W01-W04 need a process restart and are [CoordinatedVaultRestartTest].
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
        assertEquals("the fake witness parsed every request", 0, witness.malformed)
        context.noBackupFilesDir.listFiles().orEmpty().filter { it.name.startsWith("$prefix.") }.forEach { it.delete() }
        scratch.deleteRecursively()
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keys.aliases().toList().filter { it.startsWith("$prefix.") }.forEach(keys::deleteEntry)
    }

    private val channels = WitnessChannels { _, _, _ -> witness.channel }

    /** Keystore-backed stores in `noBackupFilesDir`, as production would use. */
    private fun keystoreStores() = AndroidMlsVaultStores(context, prefix)

    /** Software-key stores in [directory], so the whole profile can be copied. */
    private fun softwareStores(directory: File) = AndroidMlsVaultStores(context, prefix, directory, FileSealKeys(File(directory, "keys")))

    private fun vault(stores: CoordinatedVaultStores) = MlsVault.coordinated(VaultCoordination(stores, channels, EngineVaultWitness()))

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

    @Test fun w09_a_witness_signing_with_another_key_never_confirms() = runBlocking<Unit> {
        val v = vault(keystoreStores())
        val genesis = (v.beginCoordination(identity.pubkey, bytes(32), witness.publicKey) as VaultResult.Ok).value
        witness.enrol(genesis.subject, genesis.initialDigest)
        witness.mode = FakeEd25519Witness.Mode.WrongKey
        assertTrue(v.coordinationStatus(identity.pubkey, check = true) is CoordinationStatus.Pending)
        assertEquals(VaultResult.Refused(VaultRefusal.WitnessPending), v.enrol(v.context(principal, identity.pubkey), identity, now + 86_400))
        assertEquals(0, witness.advanceCalls)
        assertEquals(0L, witness.seq(genesis.subject))
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

    @Test fun w07_a_profile_copied_with_its_keys_is_fenced_by_the_witness() = runBlocking<Unit> {
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

    @Test fun w05_a_restored_older_file_is_fenced_through_the_missing_seal_key_marker() = runBlocking<Unit> {
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
        // The screen shows the keeper's retire line for exactly that subject.
        assertEquals(WitnessEnrolment.Fenced("missing-seal-key", genesis.subject), restored.witnessEnrolment(identity.pubkey))
        assertEquals(2L, witness.seq(genesis.subject))
    }

    @Test fun w06_the_vault_files_restored_under_the_live_keystore_are_fenced_as_missing_seal_key() = runBlocking<Unit> {
        val stores = keystoreStores()
        val v = vault(stores)
        val genesis = enrolAtBox(v)
        val device = enrolDevice(v)
        val files = context.noBackupFilesDir.listFiles().orEmpty().filter { it.name.startsWith("$prefix.") && it.isFile }
        val snapshot = File(scratch, "files").apply { mkdirs() }
        files.forEach { it.copyTo(File(snapshot, it.name)) }
        assertTrue(v.signLeafBindingV1(v.context(principal, identity.pubkey), request(device), approve) is VaultResult.Ok)
        // Every file put back as it was; the Keystore stays as it is now.
        context.noBackupFilesDir.listFiles().orEmpty().filter { it.name.startsWith("$prefix.") }.forEach { it.delete() }
        snapshot.listFiles().orEmpty().forEach { it.copyTo(File(context.noBackupFilesDir, it.name)) }
        val restored = vault(stores)
        val c = restored.context(principal, identity.pubkey)
        assertEquals(VaultResult.Refused(VaultRefusal.RestoreFenced), restored.signLeafBindingV1(c, request(device), approve))
        assertEquals(CoordinationStatus.Fenced("missing-seal-key", genesis.subject), restored.coordinationStatus(identity.pubkey))
        assertEquals(2L, witness.seq(genesis.subject))
        assertFalse(witness.subjects.getValue(genesis.subject).retired)
    }

    @Test fun w07_a_whole_profile_restored_in_place_with_its_keys_is_fenced_by_the_witness() = runBlocking<Unit> {
        val profile = File(scratch, "profile")
        val v = vault(softwareStores(profile))
        val genesis = enrolAtBox(v)
        val device = enrolDevice(v)
        val snapshot = File(scratch, "snapshot")
        profile.copyRecursively(snapshot)
        assertTrue(v.signLeafBindingV1(v.context(principal, identity.pubkey), request(device), approve) is VaultResult.Ok)
        val newer = witness.subjects.getValue(genesis.subject).digest.copyOf()
        // Files and keys put back as they were, on the same device.
        profile.deleteRecursively()
        snapshot.copyRecursively(profile)
        val restored = vault(softwareStores(profile))
        val c = restored.context(principal, identity.pubkey)
        assertEquals(VaultResult.Refused(VaultRefusal.RestoreFenced), restored.signLeafBindingV1(c, request(device), approve))
        assertEquals(CoordinationStatus.Fenced("witness-mismatch", genesis.subject), restored.coordinationStatus(identity.pubkey))
        // No mark reset: the witness keeps the newer record, and the live subject is not retired.
        assertEquals(2L, witness.seq(genesis.subject))
        assertArrayEquals(newer, witness.subjects.getValue(genesis.subject).digest)
        assertFalse(witness.subjects.getValue(genesis.subject).retired)
    }

    @Test fun w08_of_two_cloned_profiles_exactly_one_write_wins_and_the_other_fences() = runBlocking<Unit> {
        val original = File(scratch, "original")
        val v = vault(softwareStores(original))
        val genesis = enrolAtBox(v)
        val device = enrolDevice(v)
        val clones = listOf("first", "second").map { name ->
            val copy = File(scratch, name).also { original.copyRecursively(it) }
            vault(softwareStores(copy))
        }
        // Both open and confirm first, so the race is decided by the witness's compare-and-swap.
        clones.forEach { assertEquals(CoordinationStatus.Active, it.coordinationStatus(identity.pubkey, check = true)) }
        val advances = witness.advances
        val results = clones.map { clone ->
            async(Dispatchers.IO) { clone.signLeafBindingV1(clone.context(principal, identity.pubkey), request(device), approve) }
        }.awaitAll()
        assertEquals(results.toString(), 1, results.count { it is VaultResult.Ok<*> })
        assertEquals(results.toString(), 1, results.count { it == VaultResult.Refused(VaultRefusal.RestoreFenced) })
        assertEquals(2L, witness.seq(genesis.subject))
        val winner = clones[results.indexOfFirst { it is VaultResult.Ok<*> }]
        val loser = clones[results.indexOfFirst { it !is VaultResult.Ok<*> }]
        assertEquals(CoordinationStatus.Active, winner.coordinationStatus(identity.pubkey, check = true))
        assertEquals(advances + 2, witness.advances)
        assertEquals(CoordinationStatus.Fenced("witness-conflict", genesis.subject), loser.coordinationStatus(identity.pubkey, check = true))
        assertFalse(witness.subjects.getValue(genesis.subject).retired)
    }

    @Test fun w09_with_the_witness_down_nothing_is_signed_or_released() = runBlocking<Unit> {
        val v = vault(keystoreStores())
        val genesis = enrolAtBox(v)
        val device = enrolDevice(v)
        val req = request(device)
        var asked = 0
        val prompt = ConsentPrompt { asked++; ConsentDecision.Approve }
        witness.mode = FakeEd25519Witness.Mode.Down
        assertEquals(VaultResult.Refused(VaultRefusal.WitnessPending), v.signLeafBindingV1(v.context(principal, identity.pubkey), req, prompt))
        // Consent was asked and the signature staged, then held at the advance.
        assertEquals(1, asked)
        assertEquals(CoordinationStatus.Pending(refused = false), v.coordinationStatus(identity.pubkey, check = true))
        assertEquals(1L, witness.seq(genesis.subject))
        // Back up: the held candidate is finished and its signature released, never signed again.
        witness.mode = FakeEd25519Witness.Mode.Up
        assertTrue(v.signLeafBindingV1(v.context(principal, identity.pubkey), req, prompt) is VaultResult.Ok)
        assertEquals(2L, witness.seq(genesis.subject))
        assertEquals(1, asked)
    }

    @Test fun w09_a_box_that_refuses_the_writer_holds_every_write_and_enrols_nothing() = runBlocking<Unit> {
        val v = vault(keystoreStores())
        // Genesis is taken, but the keeper has not run the enrol line yet.
        val genesis = (v.beginCoordination(identity.pubkey, bytes(32), witness.publicKey) as VaultResult.Ok).value
        assertEquals(CoordinationStatus.Pending(refused = true), v.coordinationStatus(identity.pubkey, check = true))
        assertEquals(VaultResult.Refused(VaultRefusal.WitnessPending), v.enrol(v.context(principal, identity.pubkey), identity, now + 86_400))
        assertTrue("no implicit enrolment", witness.subjects.isEmpty())
        assertEquals("nothing is advanced before the keeper enrols", 0, witness.advanceCalls)
        // Enrolled, but now refusing: still held.
        witness.enrol(genesis.subject, genesis.initialDigest)
        witness.mode = FakeEd25519Witness.Mode.Refuse
        assertEquals(CoordinationStatus.Pending(refused = true), v.coordinationStatus(identity.pubkey, check = true))
        assertEquals(VaultResult.Refused(VaultRefusal.WitnessPending), v.enrol(v.context(principal, identity.pubkey), identity, now + 86_400))
        assertEquals(0L, witness.seq(genesis.subject))
        assertEquals(0, witness.advanceCalls)
        witness.mode = FakeEd25519Witness.Mode.Up
        assertEquals(CoordinationStatus.Active, v.coordinationStatus(identity.pubkey, check = true))
    }

    @Test fun w10_a_replayed_read_receipt_never_confirms() = runBlocking<Unit> {
        val stores = keystoreStores()
        val v = vault(stores)
        val genesis = enrolAtBox(v)
        val device = enrolDevice(v)
        // A fresh read at the current seq: the receipt the witness will replay,
        // so only its stale challenge can keep it from confirming.
        assertEquals(CoordinationStatus.Active, vault(stores).coordinationStatus(identity.pubkey, check = true))
        witness.mode = FakeEd25519Witness.Mode.ReplayRead
        val reopened = vault(stores)
        assertEquals(CoordinationStatus.Pending(refused = false), reopened.coordinationStatus(identity.pubkey, check = true))
        // A read that releases nothing stays available while pending; a signature does not.
        assertEquals(VaultResult.Ok(device), reopened.device(reopened.context(principal, identity.pubkey)))
        assertEquals(VaultResult.Refused(VaultRefusal.WitnessPending), reopened.signLeafBindingV1(reopened.context(principal, identity.pubkey), request(device), approve))
        assertEquals(1L, witness.seq(genesis.subject))
    }

    @Test fun w11_a_lost_stage_advances_nothing_and_the_promoted_record_stands() = runBlocking<Unit> {
        val hooked = HookedStores(keystoreStores())
        val v = vault(hooked)
        val genesis = enrolAtBox(v)
        val device = enrolDevice(v)
        val advances = witness.advances
        val req = request(device)
        hooked.arm(HookedStores.Point.FailStage)
        try {
            v.signLeafBindingV1(v.context(principal, identity.pubkey), req, approve)
            fail("a lost stage released a signature")
        } catch (_: MlsVaultUnavailableException) {
        }
        assertTrue(hooked.hit != null)
        assertEquals(advances, witness.advances)
        assertEquals(1L, witness.seq(genesis.subject))
        // After a restart the promoted record stands, and the next write is built on it.
        val reopened = vault(keystoreStores())
        assertEquals(CoordinationStatus.Active, reopened.coordinationStatus(identity.pubkey, check = true))
        assertEquals(VaultResult.Ok(device), reopened.device(reopened.context(principal, identity.pubkey)))
        assertTrue(reopened.signLeafBindingV1(reopened.context(principal, identity.pubkey), req, approve) is VaultResult.Ok)
        assertEquals(2L, witness.seq(genesis.subject))
    }

    @Test fun w12_a_lost_inner_key_fences_and_nothing_is_released() = runBlocking<Unit> {
        val stores = keystoreStores()
        val v = vault(stores)
        val genesis = enrolAtBox(v)
        val device = enrolDevice(v)
        val name = coordinatedFile().name.removePrefix("$prefix.").removeSuffix(".vault")
        stores.innerKeys.delete(stores.innerAlias(name))
        val reopened = vault(stores)
        val c = reopened.context(principal, identity.pubkey)
        assertEquals(VaultResult.Refused(VaultRefusal.RestoreFenced), reopened.signLeafBindingV1(c, request(device), approve))
        assertEquals(VaultResult.Refused(VaultRefusal.RestoreFenced), reopened.device(c))
        assertEquals(CoordinationStatus.Fenced("missing-seal-key", genesis.subject), reopened.coordinationStatus(identity.pubkey))
        assertEquals(1L, witness.seq(genesis.subject))
    }

    @Test fun at_the_last_sequence_number_staging_fences_sequence_exhausted() = runBlocking<Unit> {
        val stores = keystoreStores()
        val v = vault(stores)
        val genesis = enrolAtBox(v)
        val device = enrolDevice(v)
        // Move the persisted coordinator state and the witness to one below
        // the last safe sequence number (2^53 - 1); the record is unchanged.
        val last = (1L shl 53) - 1
        val name = coordinatedFile().name.removePrefix("$prefix.").removeSuffix(".vault")
        val aad = "kithmoot.mls-vault.v1|coord|${identity.pubkey}|${v.installationId()}|1".toByteArray(Charsets.US_ASCII)
        val storage = stores.coordinated(name, aad)
        val file = PersonaFile.decode(storage.read()!!, identity.pubkey)
        val state = Json.parseToJsonElement(file.state!!.decodeToString()).jsonObject
        storage.write(file.next(state = JsonObject(state + ("active_seq" to JsonPrimitive(last - 1))).toString().toByteArray()).encode())
        witness.subjects.getValue(genesis.subject).seq = last - 1

        val reopened = vault(stores)
        val c = reopened.context(principal, identity.pubkey)
        assertEquals(CoordinationStatus.Active, reopened.coordinationStatus(identity.pubkey, check = true))
        // One below the last: witnessed as usual, and the witness reaches the last number.
        assertTrue(reopened.signLeafBindingV1(c, request(device), approve) is VaultResult.Ok)
        assertEquals(last, witness.seq(genesis.subject))
        // At the last: staging fences; nothing is advanced, signed or released.
        val advances = witness.advanceCalls
        assertEquals(VaultResult.Refused(VaultRefusal.RestoreFenced), reopened.signLeafBindingV1(c, request(device), approve))
        assertEquals(advances, witness.advanceCalls)
        assertEquals(last, witness.seq(genesis.subject))
        assertEquals(CoordinationStatus.Fenced("sequence-exhausted", genesis.subject), reopened.coordinationStatus(identity.pubkey))
        // The fence was persisted with the state.
        val again = vault(stores)
        assertEquals(CoordinationStatus.Fenced("sequence-exhausted", genesis.subject), again.coordinationStatus(identity.pubkey, check = true))
        assertEquals(VaultResult.Refused(VaultRefusal.RestoreFenced), again.device(again.context(principal, identity.pubkey)))
    }

    @Test fun a_witness_behind_is_retired_and_a_cleared_persona_keeps_its_duty_until_retired() = runBlocking<Unit> {
        val stores = keystoreStores()
        val v = vault(stores)
        val genesis = enrolAtBox(v)
        enrolDevice(v)
        // The witness lost state: it is back at genesis.
        witness.subjects.getValue(genesis.subject).apply { seq = 0; digest = genesis.initialDigest.hexToBytes() }
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
        assertEquals(emptyMap<String, dev.forgesworn.kithmoot.account.RetiringDutyFailure>(), cleared.runRetiringDuties())
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
