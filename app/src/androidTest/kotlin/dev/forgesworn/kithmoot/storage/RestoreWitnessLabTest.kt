package dev.forgesworn.kithmoot.storage

import android.content.Context
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.account.ConsentDecision
import dev.forgesworn.kithmoot.account.ConsentPrompt
import dev.forgesworn.kithmoot.account.CoordinationStatus
import dev.forgesworn.kithmoot.account.EnrolledDevice
import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.account.MlsVault
import dev.forgesworn.kithmoot.account.ParticipantSigner
import dev.forgesworn.kithmoot.account.PersonaLinks
import dev.forgesworn.kithmoot.account.VaultCoordination
import dev.forgesworn.kithmoot.account.VaultRefusal
import dev.forgesworn.kithmoot.account.VaultResult
import dev.forgesworn.kithmoot.account.WitnessEnrolment
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.BothyPairing
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.relay.ReflectiveLinkTransportRuntime
import dev.forgesworn.kithmoot.vmls.LeafBinding
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * The P3-03b-2 lab run against a real `bothyd` (C8): the persona's own Link
 * engine, a witness-only pairing, real receipts signed by the box's node key,
 * a real restart, and two cloned profiles whose engines share one Link node
 * id. Driven by `scripts/lab-restore-witness.sh`, which runs the box and the
 * keeper's commands; never part of CI, and skipped without `-e witnessLab true`.
 *
 * The profile uses software keys so it can be cloned. Every value written
 * outside the vault (the enrol line, ids, the request) is public at the box.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class RestoreWitnessLabTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val args = InstrumentationRegistry.getArguments()
    private val lab = File(context.filesDir, "witness-lab")
    private val profile = File(lab, "profile")
    private val saved = File(lab, "lab.properties")
    /** Where the script collects the keeper's enrol line. */
    private val out = File(context.getExternalFilesDir(null), "witness-lab").apply { mkdirs() }
    private val principal = "dev.forgesworn.kithmoot"
    private val random = SecureRandom()

    private fun lab() = assumeTrue("A lab run only: -e witnessLab true", args.getString("witnessLab") == "true")

    private fun stores(directory: File) = AndroidMlsVaultStores(context, PREFIX, directory, FileSealKeys(File(directory, "keys")))

    private fun vault(directory: File, links: PersonaLinks) = MlsVault.coordinated(VaultCoordination(stores(directory), links))

    private fun links() = PersonaLinks(ReflectiveLinkTransportRuntime(), quiet = { false })

    private fun load() = Properties().apply { saved.inputStream().use { load(it) } }
    private fun Properties.save() = saved.outputStream().use { store(it, "Lab values; all public at the box") }

    /** Pairs with the box's witness-only code, takes genesis, and leaves the keeper's enrol line. */
    @Test fun a_pair_and_begin() = runBlocking<Unit> {
        lab()
        val code = requireNotNull(args.getString("pairingCode")) { "-e pairingCode bothy:…" }
        lab.deleteRecursively()
        out.listFiles().orEmpty().forEach { it.delete() }
        val secret = secret()
        val persona = LocalSigner(secret).pubkey
        links().use { links ->
            val v = vault(profile, links)
            assertTrue(v.prepareCoordination(persona) is VaultResult.Ok)
            val paired = v.pairWitness(persona) { seed ->
                val pairing = BothyPairing.parse(code.trim(), System.currentTimeMillis() / 1000)
                withTimeout(90_000) {
                    try { links.pair(seed, pairing).await() } catch (error: ExecutionException) { throw error.cause ?: error }
                }
            }
            assertEquals(VaultResult.Ok(Unit), paired)
            val genesis = (v.beginCoordination(persona, bytes(32)) as VaultResult.Ok).value
            val enrolled = v.witnessEnrolment(persona) as WitnessEnrolment.Enrolled
            val line = requireNotNull(enrolled.line)
            assertTrue(line.contains("--subject ${genesis.subject}") && line.contains("--initial-digest ${genesis.initialDigest}"))
            // Paired but not enrolled at the box: every covered write holds.
            val before = v.coordinationStatus(persona, check = true)
            assertTrue("$before", before is CoordinationStatus.Pending)
            Properties().apply {
                setProperty("secret", secret.toHex())
                setProperty("subject", genesis.subject)
                setProperty("box", enrolled.box ?: "")
                setProperty("pending", before.toString())
            }.save()
            File(out, "enrol-line.txt").writeText(line + "\n")
            File(out, "subject.txt").writeText(genesis.subject + "\n")
        }
    }

    /** After the keeper ran the enrol line: active, then the device enrolment and a signature, each witnessed. */
    @Test fun b_active_enrol_and_sign() = runBlocking<Unit> {
        lab()
        val expected = load()
        val identity = LocalSigner(expected.getProperty("secret").hexToBytes())
        links().use { links ->
            val v = vault(profile, links)
            assertEquals(CoordinationStatus.Active, v.coordinationStatus(identity.pubkey, check = true))
            var credential: NostrEvent? = null
            val signer = object : ParticipantSigner by identity {
                override suspend fun sign(kind: Int, createdAt: Long, tags: List<List<String>>, content: String): NostrEvent =
                    identity.sign(kind, createdAt, tags, content).also { credential = it }
            }
            val now = System.currentTimeMillis() / 1000
            val device = (v.enrol(v.context(principal, identity.pubkey), signer, now + 86_400) as VaultResult.Ok).value
            val request = request(credential!!, device)
            val reply = (v.signLeafBindingV1(v.context(principal, identity.pubkey), request, ConsentPrompt { ConsentDecision.Approve }) as VaultResult.Ok).value
            assertTrue(Schnorr.verify(reply.signature.hexToBytes(), request.text("digest").hexToBytes(), device.device.hexToBytes()))
            assertEquals(CoordinationStatus.Active, v.coordinationStatus(identity.pubkey, check = true))
            expected.setProperty("credential", credential!!.toJson().toString())
            expected.setProperty("device", device.device)
            expected.setProperty("request", request.toString())
            expected.setProperty("signature", reply.signature)
            expected.setProperty("pid", Process.myPid().toString())
            expected.save()
        }
    }

    /**
     * In a new process: the identical retry replays from the promoted record.
     * Then two clones of the profile, each with its own engine on the same
     * writer seed (one Link node id), write at once: exactly one wins.
     */
    @Test fun c_restart_then_two_clones() = runBlocking<Unit> {
        lab()
        val expected = load()
        if (args.getString("requireRestart") == "true") assertNotEquals(expected.getProperty("pid"), Process.myPid().toString())
        val identity = LocalSigner(expected.getProperty("secret").hexToBytes())
        val persona = identity.pubkey
        links().use { links ->
            val v = vault(profile, links)
            assertEquals(CoordinationStatus.Active, v.coordinationStatus(persona, check = true))
            val request = Json.parseToJsonElement(expected.getProperty("request")).jsonObject
            var asked = false
            val replayed = v.signLeafBindingV1(v.context(principal, persona), request, ConsentPrompt { asked = true; ConsentDecision.Deny })
            assertEquals(expected.getProperty("signature"), (replayed as VaultResult.Ok).value.signature)
            assertFalse(asked)
        }

        val credential = NostrEvent.fromJson(Json.parseToJsonElement(expected.getProperty("credential")).jsonObject)
        val device = EnrolledDevice(persona, expected.getProperty("device"), "", 0)
        val clones = listOf("first", "second").map { name -> File(lab, name).also { it.deleteRecursively(); profile.copyRecursively(it) } }
        val engines = clones.map { links() }
        try {
            val vaults = clones.zip(engines).map { (directory, links) -> vault(directory, links) }
            val approve = ConsentPrompt { ConsentDecision.Approve }
            val requests = vaults.map { request(credential, device) }
            // Rounds of writes at once from every clone not yet decided, until each has won or is fenced.
            // A held write releases nothing; it is retried with the same request.
            val outcome = arrayOfNulls<VaultResult<*>>(vaults.size)
            val rounds = mutableListOf<String>()
            repeat(15) {
                val open = vaults.indices.filter { outcome[it] == null }
                if (open.isEmpty()) return@repeat
                val results = open.map { i ->
                    async(Dispatchers.IO) { i to vaults[i].signLeafBindingV1(vaults[i].context(principal, persona), requests[i], approve) }
                }.awaitAll()
                rounds += results.joinToString { (i, r) -> "$i:$r" }
                for ((i, result) in results) {
                    assertTrue("$result", result is VaultResult.Ok<*> || result == VaultResult.Refused(VaultRefusal.RestoreFenced) || result == VaultResult.Refused(VaultRefusal.WitnessPending))
                    if (result != VaultResult.Refused(VaultRefusal.WitnessPending)) outcome[i] = result
                }
                assertTrue(rounds.toString(), outcome.count { it is VaultResult.Ok<*> } <= 1)
                if (outcome.any { it == null }) delay(2_000)
            }
            assertEquals(rounds.toString(), 1, outcome.count { it is VaultResult.Ok<*> })
            assertEquals(rounds.toString(), 1, outcome.count { it == VaultResult.Refused(VaultRefusal.RestoreFenced) })
            val winner = outcome.indexOfFirst { it is VaultResult.Ok<*> }
            val statuses = vaults.map { it.coordinationStatus(persona, check = true) }
            assertEquals(CoordinationStatus.Active, statuses[winner])
            assertTrue("$statuses", statuses[1 - winner] is CoordinationStatus.Fenced)
            val summary = "rounds $rounds; then $statuses"
            expected.setProperty("clones", summary)
            expected.save()
            File(out, "clones.txt").writeText(summary + "\n")
        } finally {
            engines.forEach { it.close() }
        }
    }

    private fun request(credential: NostrEvent, device: EnrolledDevice): JsonObject {
        val now = System.currentTimeMillis() / 1000
        val body = TestBindings.unsigned(bytes(32), bytes(32), credential, device.device, now + 3_600, bytes(32))
        return buildJsonObject {
            put("v", 1)
            put("operation", bytes(32).toHex())
            put("body", Base64.getEncoder().encodeToString(body))
            put("digest", LeafBinding.digest(body).toHex())
            put("expires_at", now + 590)
        }
    }

    private fun JsonObject.text(name: String) = (getValue(name) as JsonPrimitive).content
    private fun bytes(n: Int) = ByteArray(n).also(random::nextBytes)
    private fun secret(): ByteArray { while (true) { val k = bytes(32); if (runCatching { Schnorr.publicKey(k) }.isSuccess) return k } }

    private companion object { const val PREFIX = "kithmoot.witness-lab" }
}
