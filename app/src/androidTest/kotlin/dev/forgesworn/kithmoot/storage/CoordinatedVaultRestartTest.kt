package dev.forgesworn.kithmoot.storage

import android.content.Context
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.account.ConsentDecision
import dev.forgesworn.kithmoot.account.ConsentPrompt
import dev.forgesworn.kithmoot.account.CoordinationStatus
import dev.forgesworn.kithmoot.account.EngineVaultWitness
import dev.forgesworn.kithmoot.account.EnrolledDevice
import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.account.MlsVault
import dev.forgesworn.kithmoot.account.ParticipantSigner
import dev.forgesworn.kithmoot.account.VaultCoordination
import dev.forgesworn.kithmoot.account.VaultRefusal
import dev.forgesworn.kithmoot.account.VaultResult
import dev.forgesworn.kithmoot.account.WitnessAnswer
import dev.forgesworn.kithmoot.account.WitnessChannel
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
import java.util.Properties
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * W01-W04 and a crash between the outer commit and the key deletion
 * (P3-03b-2), across a real process restart: [a_kill] stops each of five
 * personas at its own point in a signature, the script force-stops the app,
 * and [b_recover] runs in a new process. The real engine, Keystore-backed
 * stores, and an Ed25519 witness whose state is kept outside the vault's.
 *
 * Only the exact staged candidate is recoverable, and nothing is returned
 * before it is promoted: with the witness down every retry is held; with it
 * up the retry replays the staged signature without asking for consent, and
 * each subject ends exactly one advance past the device enrolment.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class CoordinatedVaultRestartTest {
    private enum class Case {
        /** W01: killed just after the stage was written; the witness never saw it. */
        AfterStage,
        /** W02: killed after the witness committed the advance, before its answer arrived. */
        AfterWitnessCommit,
        /** W03: killed just after the promotion was written, before the result was returned. */
        AfterPromotion,
        /** W04: the witness committed, but its answer was lost; then a restart. */
        LostAnswer,
        /** Killed after the stage's outer commit, before its superseded key was deleted. */
        BeforeKeyDeletion,
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val state = File(context.filesDir, "coord-restart-test")
    private val saved = File(state, "expected.properties")
    private val principal = "dev.forgesworn.kithmoot"
    private val random = SecureRandom()

    @Test fun a_kill() = runBlocking<Unit> {
        cleanup()
        val witness = FakeEd25519Witness(saved = File(state, "witness"))
        val keys = HookedKeys(AndroidKeyStoreSealKeys)
        val stores = HookedStores(AndroidMlsVaultStores(context, PREFIX, keys = keys))
        var killAfterAdvance = false
        val channel = object : WitnessChannel {
            override suspend fun read(request: ByteArray) = witness.channel.read(request)
            override suspend fun advance(request: ByteArray): WitnessAnswer {
                val answer = witness.channel.advance(request)
                if (killAfterAdvance) {
                    killAfterAdvance = false
                    throw Killed("after the witness committed")
                }
                return answer
            }
        }
        val vault = MlsVault.coordinated(VaultCoordination(stores, WitnessChannels { _, _, _ -> channel }, EngineVaultWitness()))
        val expected = Properties()
        val now = System.currentTimeMillis() / 1000
        for (case in Case.entries) {
            val secret = secret()
            val identity = LocalSigner(secret)
            val genesis = (vault.beginCoordination(identity.pubkey, bytes(32), witness.publicKey) as VaultResult.Ok).value
            witness.enrol(genesis.subject, genesis.initialDigest)
            assertEquals(CoordinationStatus.Active, vault.coordinationStatus(identity.pubkey, check = true))
            var credential: NostrEvent? = null
            val signer = object : ParticipantSigner by identity {
                override suspend fun sign(kind: Int, createdAt: Long, tags: List<List<String>>, content: String): NostrEvent =
                    identity.sign(kind, createdAt, tags, content).also { credential = it }
            }
            val device = (vault.enrol(vault.context(principal, identity.pubkey), signer, now + 86_400) as VaultResult.Ok).value
            assertEquals(1L, witness.seq(genesis.subject))
            val request = request(credential!!, device, now)
            val ctx = vault.context(principal, identity.pubkey)
            val approve = ConsentPrompt { ConsentDecision.Approve }

            when (case) {
                Case.AfterStage -> stores.arm(HookedStores.Point.AfterStage)
                Case.AfterWitnessCommit -> killAfterAdvance = true
                Case.AfterPromotion -> stores.arm(HookedStores.Point.AfterPromotion)
                Case.LostAnswer -> witness.mode = FakeEd25519Witness.Mode.LoseAnswer
                Case.BeforeKeyDeletion -> keys.killNextDelete = true
            }
            if (case == Case.LostAnswer) {
                // Nothing is returned before promotion: the write is held.
                assertEquals(VaultResult.Refused(VaultRefusal.WitnessPending), vault.signLeafBindingV1(ctx, request, approve))
                witness.mode = FakeEd25519Witness.Mode.Up
            } else {
                try {
                    vault.signLeafBindingV1(ctx, request, approve)
                    fail("$case: the signature was returned instead of the process being killed")
                } catch (_: Killed) {
                }
            }
            val name = coordName(identity.pubkey)
            if (case == Case.AfterStage || case == Case.AfterPromotion) assertEquals(name, stores.hit)
            assertEquals(
                "$case: how far the witness got before the kill",
                if (case == Case.AfterStage || case == Case.BeforeKeyDeletion) 1L else 2L,
                witness.seq(genesis.subject),
            )
            // The stage's outer commit landed with both keys still present.
            if (case == Case.BeforeKeyDeletion) assertEquals(2, entries(name).size)
            expected.setProperty("$case.secret", secret.toHex())
            expected.setProperty("$case.subject", genesis.subject)
            expected.setProperty("$case.device", device.device)
            expected.setProperty("$case.request", request.toString())
        }
        expected.setProperty("pid", Process.myPid().toString())
        saved.outputStream().use { expected.store(it, "Synthetic coordinator restart-test values only") }
    }

    @Test fun b_recover() = runBlocking<Unit> {
        assertTrue("Run a_kill first", saved.isFile)
        val expected = Properties().apply { saved.inputStream().use { load(it) } }
        if (InstrumentationRegistry.getArguments().getString("requireRestart") == "true") {
            assertNotEquals(expected.getProperty("pid"), Process.myPid().toString())
        }
        try {
            val witness = FakeEd25519Witness(saved = File(state, "witness"))
            val vault = MlsVault.coordinated(
                VaultCoordination(AndroidMlsVaultStores(context, PREFIX), WitnessChannels { _, _, _ -> witness.channel }, EngineVaultWitness()),
            )
            for (case in Case.entries) {
                val persona = LocalSigner(expected.getProperty("$case.secret").hexToBytes()).pubkey
                val subject = expected.getProperty("$case.subject")
                val request = Json.parseToJsonElement(expected.getProperty("$case.request")).jsonObject
                var asked = false
                val prompt = ConsentPrompt { asked = true; ConsentDecision.Deny }

                // With the witness down nothing is released, staged or promoted.
                witness.mode = FakeEd25519Witness.Mode.Down
                assertEquals("$case", VaultResult.Refused(VaultRefusal.WitnessPending), vault.signLeafBindingV1(vault.context(principal, persona), request, prompt))
                if (case == Case.BeforeKeyDeletion) assertEquals("the stale key was swept at open", 1, entries(coordName(persona)).size)

                // With it up, exactly the staged candidate is finished and promoted.
                witness.mode = FakeEd25519Witness.Mode.Up
                assertEquals("$case", CoordinationStatus.Active, vault.coordinationStatus(persona, check = true))
                val reply = vault.signLeafBindingV1(vault.context(principal, persona), request, prompt)
                assertTrue("$case: $reply", reply is VaultResult.Ok)
                assertFalse("$case: a replay never asks for consent", asked)
                val signature = (reply as VaultResult.Ok).value.signature
                assertTrue("$case", Schnorr.verify(signature.hexToBytes(), request.text("digest").hexToBytes(), expected.getProperty("$case.device").hexToBytes()))
                assertEquals("$case: one advance past the device enrolment, never a second candidate", 2L, witness.seq(subject))
            }

            // A logout ends the session: the same retries no longer replay.
            vault.bump()
            for (case in Case.entries) {
                val persona = LocalSigner(expected.getProperty("$case.secret").hexToBytes()).pubkey
                val request = Json.parseToJsonElement(expected.getProperty("$case.request")).jsonObject
                val prompt = ConsentPrompt { error("a stale retry never asks for consent") }
                assertEquals("$case", VaultResult.Refused(VaultRefusal.Stale), vault.signLeafBindingV1(vault.context(principal, persona), request, prompt))
                assertEquals("$case", 2L, witness.seq(expected.getProperty("$case.subject")))
            }
        } finally {
            cleanup()
        }
    }

    private fun cleanup() {
        context.noBackupFilesDir.listFiles().orEmpty().filter { it.name.startsWith("$PREFIX.") }.forEach { it.delete() }
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keys.aliases().toList().filter { it.startsWith("$PREFIX.") }.forEach(keys::deleteEntry)
        state.deleteRecursively()
    }

    /** The persona's coordinated file name, found by the vault's sealed index rule. */
    private fun coordName(persona: String): String {
        val installation = AndroidMlsVaultStores(context, PREFIX).let { stores ->
            runBlocking { MlsVault.coordinated(VaultCoordination(stores, WitnessChannels { _, _, _ -> null }, EngineVaultWitness())).installationId() }
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(installation.hexToBytes() + "coord|$persona".toByteArray(Charsets.US_ASCII))
        return "coord." + digest.toHex().take(32)
    }

    private fun entries(name: String): List<String> =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.aliases().toList().filter { it.startsWith("$PREFIX.$name.entry.") }

    private fun request(credential: NostrEvent, device: EnrolledDevice, now: Long): JsonObject {
        val body = TestBindings.unsigned(bytes(32), bytes(32), credential, device.device, now + 3_600, bytes(32))
        return buildJsonObject {
            put("v", 1)
            put("operation", bytes(32).toHex())
            put("body", Base64.getEncoder().encodeToString(body))
            put("digest", LeafBinding.digest(body).toHex())
            // The recovery runs in a later process: well within the 600 s bound.
            put("expires_at", now + 590)
        }
    }

    private fun JsonObject.text(name: String) = (getValue(name) as kotlinx.serialization.json.JsonPrimitive).content
    private fun bytes(n: Int) = ByteArray(n).also(random::nextBytes)
    private fun secret(): ByteArray { while (true) { val k = bytes(32); if (runCatching { Schnorr.publicKey(k) }.isSuccess) return k } }

    private companion object { const val PREFIX = "kithmoot.coord-restart" }
}
