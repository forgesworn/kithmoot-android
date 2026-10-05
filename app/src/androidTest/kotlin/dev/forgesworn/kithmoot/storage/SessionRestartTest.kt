package dev.forgesworn.kithmoot.storage

import android.content.Context
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.account.CoordinationStatus
import dev.forgesworn.kithmoot.account.EngineSessions
import dev.forgesworn.kithmoot.account.EngineVaultWitness
import dev.forgesworn.kithmoot.account.Hosted
import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.account.MlsVault
import dev.forgesworn.kithmoot.account.ParticipantSigner
import dev.forgesworn.kithmoot.account.SessionHost
import dev.forgesworn.kithmoot.account.VaultCoordination
import dev.forgesworn.kithmoot.account.VaultResult
import dev.forgesworn.kithmoot.account.WitnessAnswer
import dev.forgesworn.kithmoot.account.WitnessChannel
import dev.forgesworn.kithmoot.account.WitnessChannels
import dev.forgesworn.kithmoot.account.hostedStep
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.NostrEvent
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Properties
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * A session step killed at each point of the host's order (P3-03b-3a), across
 * a real process restart: [a_kill] stops each of four personas' first send at
 * its own point, the script force-stops the app, and [b_recover] runs in a
 * new process with the real engine, Keystore-backed stores and an Ed25519
 * witness whose state is kept outside the vault's.
 *
 * Nothing is released while the witness is down. With it up, the session
 * opens at exactly the generation the witness holds: the created one when
 * the kill came before the step was staged, the step's own once it was
 * staged (a surviving stage is finished, never rebuilt) or witnessed.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class SessionRestartTest {
    /** [stepWitnessed]: the witness had the step at the kill. [recovered]: the step is current after recovery. */
    private enum class Case(val stepWitnessed: Boolean, val recovered: Boolean) {
        /** The snapshot file is written; no candidate names it yet. */
        AfterSnapshot(false, false),
        /** The candidate is staged; the witness never saw it, so recovery finishes it. */
        AfterStage(false, true),
        /** The witness committed the advance; its answer never arrived. */
        AfterWitnessCommit(true, true),
        /** The promotion is written; `commit_ack` never ran. */
        AfterPromotion(true, true),
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val state = File(context.filesDir, "session-restart-test")
    private val saved = File(state, "expected.properties")
    private val principal = "dev.forgesworn.kithmoot"
    private val random = SecureRandom()

    @Test fun a_kill() = runBlocking<Unit> {
        cleanup()
        val witness = FakeEd25519Witness(saved = File(state, "witness"))
        val stores = HookedStores(AndroidMlsVaultStores(context, PREFIX))
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
            val rz = Schnorr.publicKey(secret())
            val sessions = EngineSessions(device.device.hexToBytes(), rz)
            val host = SessionHost(vault, sessions)
            val created = createGroup(vault, host, sessions, identity.pubkey, credential!!, now, random)
            val id = (created as Hosted.Released).value.snapshot!!.session
            val generation = (host.sessions(identity.pubkey) as Hosted.Released).value.getValue(id.toHex())
            // Genesis, the device, the signature and the group: each one advance.
            val seq = witness.seq(genesis.subject)
            assertEquals(3L, seq)

            when (case) {
                Case.AfterSnapshot -> stores.arm(HookedStores.Point.AfterSnapshot)
                Case.AfterStage -> stores.arm(HookedStores.Point.AfterStage)
                Case.AfterWitnessCommit -> killAfterAdvance = true
                Case.AfterPromotion -> stores.arm(HookedStores.Point.AfterPromotion)
            }
            try {
                host.step(identity.pubkey, id) { s -> hostedStep(s.inner.send("killed".toByteArray())) }
                fail("$case: the step was released instead of the process being killed")
            } catch (_: Killed) {
            }
            assertEquals("$case: how far the witness got before the kill", if (case.stepWitnessed) seq + 1 else seq, witness.seq(genesis.subject))
            expected.setProperty("$case.secret", secret.toHex())
            expected.setProperty("$case.subject", genesis.subject)
            expected.setProperty("$case.device", device.device)
            expected.setProperty("$case.rz", rz.toHex())
            expected.setProperty("$case.session", id.toHex())
            expected.setProperty("$case.generation", generation.toString())
            expected.setProperty("$case.seq", seq.toString())
        }
        expected.setProperty("pid", Process.myPid().toString())
        saved.outputStream().use { expected.store(it, "Synthetic session restart-test values only") }
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
                val id = expected.getProperty("$case.session")
                val created = expected.getProperty("$case.generation").toLong()
                val seq = expected.getProperty("$case.seq").toLong()
                val host = SessionHost(vault, EngineSessions(expected.getProperty("$case.device").hexToBytes(), expected.getProperty("$case.rz").hexToBytes()))

                // With the witness down nothing is opened, stepped or released.
                witness.mode = FakeEd25519Witness.Mode.Down
                assertEquals("$case", Hosted.Held, host.step(persona, id.hexToBytes()) { s -> hostedStep(s.inner.send("down".toByteArray())) })

                // With it up the session opens at exactly the witnessed generation.
                witness.mode = FakeEd25519Witness.Mode.Up
                val witnessed = if (case.recovered) created + 1 else created
                assertEquals("$case", mapOf(id to witnessed), (host.sessions(persona) as Hosted.Released).value)
                assertEquals("$case: the killed step advanced the witness at most once", if (case.recovered) seq + 1 else seq, witness.seq(subject))
                val sent = host.step(persona, id.hexToBytes()) { s -> hostedStep(s.inner.send("after".toByteArray())) }
                assertTrue("$case: $sent", sent is Hosted.Released)
                assertEquals("$case", witnessed + 1, (sent as Hosted.Released).value.snapshot!!.generation.toLong())
                assertEquals("$case", mapOf(id to witnessed + 1), (host.sessions(persona) as Hosted.Released).value)
                // Only the promoted generation's snapshot is left on disk.
                val files = context.noBackupFilesDir.listFiles().orEmpty().map { it.name }.filter { ".snap.$id." in it && it.startsWith("$PREFIX.") }
                assertEquals("$case: $files", listOf(witnessed + 1), files.map { it.substringAfterLast('.').toLong() })
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

    private fun bytes(n: Int) = ByteArray(n).also(random::nextBytes)
    private fun secret(): ByteArray { while (true) { val k = bytes(32); if (runCatching { Schnorr.publicKey(k) }.isSuccess) return k } }

    private companion object { const val PREFIX = "kithmoot.session-restart" }
}
