package dev.forgesworn.kithmoot.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.account.EngineCapabilities
import dev.forgesworn.kithmoot.account.EngineSessions
import dev.forgesworn.kithmoot.account.EngineStep
import dev.forgesworn.kithmoot.account.Hosted
import dev.forgesworn.kithmoot.account.ParticipantSigner
import dev.forgesworn.kithmoot.account.SessionHost
import dev.forgesworn.kithmoot.account.VaultResult
import dev.forgesworn.kithmoot.account.hostedStep
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.mls.BoxAnswer
import dev.forgesworn.kithmoot.mls.Registered
import dev.forgesworn.kithmoot.mls.Round
import dev.forgesworn.kithmoot.mls.SessionDriver
import dev.forgesworn.kithmoot.mls.VmlsGrantLedger
import dev.forgesworn.kithmoot.mls.VmlsGrantRecord
import dev.forgesworn.kithmoot.protocol.NostrEvent
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P3-03b-3b-1 lab (not CI): the keeper's own MLS device against a real
 * `bothyd` with VMLS on (see [VmlsLab]). The keeper grants its MLS device
 * the VMLS scope (decision 9) through the sheltered relay, then every box
 * route the driver uses is exercised for real: capabilities, a package
 * registered and withdrawn, and a lone group's Update commit deposited at its
 * slot, its receipt verified by the engine, read back and applied.
 *
 * Run by `scripts/lab-vmls-box.sh` with `fixture_control`; skipped without it.
 */
@RunWith(AndroidJUnit4::class)
class VmlsBoxLabTest {
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val control get() = arguments.getString("fixture_control")?.removeSuffix("/")
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as KithMootApplication
    private lateinit var context: Context
    private lateinit var prefix: String
    private val random = SecureRandom()
    private var credential: NostrEvent? = null

    @Before fun setup() {
        assumeTrue("run by scripts/lab-vmls-box.sh", control != null)
        context = ApplicationProvider.getApplicationContext()
        prefix = "kithmoot.vmls-lab.${UUID.randomUUID().toString().take(8)}"
    }

    @After fun cleanup() {
        if (control == null) return
        context.noBackupFilesDir.listFiles().orEmpty().filter { it.name.startsWith("$prefix.") }.forEach { it.delete() }
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keys.aliases().toList().filter { it.startsWith("$prefix.") }.forEach(keys::deleteEntry)
    }

    @Test fun the_keepers_device_drives_a_group_against_a_real_box() = runBlocking<Unit> {
        val now = System.currentTimeMillis() / 1000
        val lab = VmlsLab(app, control!!, arguments.getString("persona") ?: "alice")
        lab.pair(now)
        val keeper = lab.keeper

        // The keeper's persona, coordinated, with its MLS device enrolled.
        val vault = VmlsLab.coordinatedVault(context, prefix, FakeEd25519Witness(), keeper.pubkey, random)
        val capturing = object : ParticipantSigner by keeper {
            override suspend fun sign(kind: Int, createdAt: Long, tags: List<List<String>>, content: String): NostrEvent =
                keeper.sign(kind, createdAt, tags, content).also { credential = it }
        }
        val device = (vault.enrol(vault.context(VmlsLab.PRINCIPAL, keeper.pubkey), capturing, now + 86_400) as VaultResult.Ok).value.device

        // Before the grant, the box refuses an MLS device it has no grant for.
        val client = lab.client(vault, keeper.pubkey)
        val before = client.capabilities()
        assertEquals(BoxAnswer.Refused(403, "authority", (before as? BoxAnswer.Refused)?.serverTime), before)

        // The keeper grants its MLS device the VMLS scope, through the ledger and the sheltered relay.
        val ledger = VmlsGrantLedger(MemoryRoomStorage())
        val plan = ledger.plan(keeper, keeper.pubkey, lab.node.toHex(), device, now)
        ledger.record(VmlsGrantRecord(lab.node.toHex(), plan))
        lab.publish(keeper, plan.active)

        // Capabilities name the box's installation.
        val capabilities = VmlsLab.eventually { client.capabilities().takeIf { it is BoxAnswer.Ok } }
        val body = Json.parseToJsonElement(String((capabilities as BoxAnswer.Ok<ByteArray>).value)).jsonObject
        assertEquals(lab.installation, body.getValue("installation").jsonPrimitive.content)

        // A keeper device registers and withdraws a package.
        val packageId = bytes(32); val welcome = bytes(32); val sealed = bytes(64)
        assertEquals(true, ((client.registerPackage(packageId, welcome, now + 3_600, sealed) as BoxAnswer.Ok<Registered>).value.fresh))
        assertEquals(false, ((client.registerPackage(packageId, welcome, now + 3_600, sealed) as BoxAnswer.Ok<Registered>).value.fresh))
        assertTrue(client.withdrawPackage(packageId) is BoxAnswer.Ok)

        // A lone group at this box: an Update commit through the driver.
        val sessions = EngineSessions(device.hexToBytes(), Schnorr.publicKey(secret()))
        val host = SessionHost(vault, sessions)
        val created = createGroup(vault, host, sessions, keeper.pubkey, credential!!, now, random, lab.node, lab.installation.hexToBytes())
        val id = (created as Hosted.Released).value.snapshot!!.session
        val driver = SessionDriver(host, client, lab.node, EngineCapabilities)
        val first = driver.round(keeper.pubkey, id, epochSeconds())
        assertTrue("first round: $first", first is Round.Done)

        fun epoch() = runBlocking { (host.step(keeper.pubkey, id) { s -> EngineStep(null, s.inner.epoch()) } as Hosted.Released).value }
        val start = epoch()
        val at = epochSeconds()
        val sign = (host.step(keeper.pubkey, id) { s -> EngineStep(null, s.inner.prepareUpdate(at.toULong(), bindingRequest(credential!!, lab.node, at))) } as Hosted.Released).value
        val signature = signBinding(vault, keeper.pubkey, sign)
        assertTrue(host.step(keeper.pubkey, id) { s -> hostedStep(s.inner.completeUpdate(at.toULong(), sign.operation, signature)) } is Hosted.Released)

        var rounds = 0
        while (epoch() == start && rounds < 5) {
            val round = driver.round(keeper.pubkey, id, epochSeconds())
            assertTrue("round $rounds: $round", round is Round.Done)
            rounds++
        }
        assertEquals("the commit applied after $rounds rounds", start + 1u, epoch())
    }

    private fun epochSeconds() = System.currentTimeMillis() / 1000
    private fun bytes(n: Int) = ByteArray(n).also(random::nextBytes)
    private fun secret(): ByteArray { while (true) { val k = bytes(32); if (runCatching { Schnorr.publicKey(k) }.isSuccess) return k } }
}

/** A room storage in memory, for the lab's ledgers. */
class MemoryRoomStorage : RoomStorage {
    private var value: ByteArray? = null
    @Synchronized override fun read(): ByteArray? = value?.copyOf()
    @Synchronized override fun write(value: ByteArray) { this.value = value.copyOf() }
    @Synchronized override fun reset() { value = null }
}
