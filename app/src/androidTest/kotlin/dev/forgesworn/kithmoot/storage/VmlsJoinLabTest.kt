package dev.forgesworn.kithmoot.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.account.ConsentDecision
import dev.forgesworn.kithmoot.account.ConsentPrompt
import dev.forgesworn.kithmoot.account.EngineCapabilities
import dev.forgesworn.kithmoot.account.EngineJoin
import dev.forgesworn.kithmoot.account.EngineSession
import dev.forgesworn.kithmoot.account.EngineSessions
import dev.forgesworn.kithmoot.account.EngineStep
import dev.forgesworn.kithmoot.account.Hosted
import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.account.MlsVault
import dev.forgesworn.kithmoot.account.ParticipantSigner
import dev.forgesworn.kithmoot.account.RendezvousReceipt
import dev.forgesworn.kithmoot.account.SessionHost
import dev.forgesworn.kithmoot.account.StoredRendezvousChild
import dev.forgesworn.kithmoot.account.VaultResult
import dev.forgesworn.kithmoot.account.addStep
import dev.forgesworn.kithmoot.account.hostedStep
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.mls.AckItem
import dev.forgesworn.kithmoot.mls.BoxAnswer
import dev.forgesworn.kithmoot.mls.FetchPage
import dev.forgesworn.kithmoot.mls.Phase
import dev.forgesworn.kithmoot.mls.Registered
import dev.forgesworn.kithmoot.mls.Round
import dev.forgesworn.kithmoot.mls.SessionDriver
import dev.forgesworn.kithmoot.mls.VmlsBoxClient
import dev.forgesworn.kithmoot.mls.VmlsGrantLedger
import dev.forgesworn.kithmoot.mls.VmlsGrantRecord
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.vmls.ffi.VmlsEvent
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P3-03b-3b-2 lab (not CI): a keeper adds a hosted guest at a real `bothyd`
 * with VMLS on (see [VmlsLab]), both personas in this one process, each with
 * its own coordinated vault, witness, MLS device and session host. The
 * keeper grants the guest's device (decision 8), the guest deposits its
 * capability, the keeper opens it, registers its package (decision 13) and
 * adds it; the guest joins from the Welcome, commits its first Update, which
 * confirms it at the keeper (decision 12), and a message goes each way.
 *
 * Run by `scripts/lab-vmls-box.sh` with `fixture_control`; skipped without it.
 */
@RunWith(AndroidJUnit4::class)
class VmlsJoinLabTest {
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val control get() = arguments.getString("fixture_control")?.removeSuffix("/")
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as KithMootApplication
    private lateinit var context: Context
    private lateinit var prefix: String
    private val random = SecureRandom()

    @Before fun setup() {
        assumeTrue("run by scripts/lab-vmls-box.sh", control != null)
        context = ApplicationProvider.getApplicationContext()
        prefix = "kithmoot.vmls-join.${UUID.randomUUID().toString().take(8)}"
    }

    @After fun cleanup() {
        if (control == null) return
        context.noBackupFilesDir.listFiles().orEmpty().filter { it.name.startsWith(prefix) }.forEach { it.delete() }
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keys.aliases().toList().filter { it.startsWith(prefix) }.forEach(keys::deleteEntry)
    }

    /** One persona's side: its vault, device, rendezvous key, host and driver. */
    private inner class Side(val lab: VmlsLab, val signer: ParticipantSigner, val name: String, now: Long) {
        val vault: MlsVault
        var credential: NostrEvent? = null
        val device: String
        private val rzSecret = secret()
        val rz: ByteArray = Schnorr.publicKey(rzSecret)
        val sessions: EngineSessions
        val host: SessionHost<EngineSession>
        val client: VmlsBoxClient
        val events = mutableListOf<Any>()
        val driver: SessionDriver<EngineSession>
        val join: EngineJoin
        lateinit var session: ByteArray

        init {
            vault = runBlocking { VmlsLab.coordinatedVault(context, "$prefix.$name", FakeEd25519Witness(), signer.pubkey, random) }
            val capturing = object : ParticipantSigner by signer {
                override suspend fun sign(kind: Int, createdAt: Long, tags: List<List<String>>, content: String): NostrEvent =
                    signer.sign(kind, createdAt, tags, content).also { credential = it }
            }
            device = runBlocking { (vault.enrol(vault.context(VmlsLab.PRINCIPAL, signer.pubkey), capturing, now + 86_400) as VaultResult.Ok).value.device }
            sessions = EngineSessions(device.hexToBytes(), rz)
            host = SessionHost(vault, sessions)
            client = lab.client(vault, signer.pubkey)
            driver = SessionDriver(host, client, lab.node, EngineCapabilities, events = { synchronized(events) { events.addAll(it) } })
            join = EngineJoin(vault, sessions, VmlsLab.PRINCIPAL, ConsentPrompt { ConsentDecision.Approve }) {
                StoredRendezvousChild(RendezvousReceipt(signer.pubkey, "b".repeat(64), rz.toHex(), 1, now + 86_400), rzSecret.copyOf())
            }
        }

        suspend fun round(): Round = driver.round(signer.pubkey, session, epochSeconds()).also { assertTrue("$name round: $it", it is Round.Done) }
        fun <T> step(block: (EngineSession) -> EngineStep<T>): T = runBlocking { (host.step(signer.pubkey, session, block) as Hosted.Released).value }
        fun phase(): Phase = step { EngineStep(null, it.phase()) }
        fun epoch(): Long? = step { EngineStep(null, it.epoch()) }
        fun messages(): List<String> = synchronized(events) { events.filterIsInstance<VmlsEvent.Message>().map { String(it.body) } }

        suspend fun update() {
            val at = epochSeconds()
            val sign = step { s -> EngineStep(null, s.inner.prepareUpdate(at.toULong(), bindingRequest(credential!!, lab.node, at))) }
            val signature = signBinding(vault, signer.pubkey, sign)
            step { s -> hostedStep(s.inner.completeUpdate(at.toULong(), sign.operation, signature)) }
        }

        fun send(text: String) { step { s -> hostedStep(s.inner.send(text.toByteArray())) } }
    }

    @Test fun a_keeper_adds_a_hosted_guest_and_they_talk() = runBlocking<Unit> {
        val now = epochSeconds()
        val lab = VmlsLab(app, control!!, arguments.getString("persona") ?: "alice")
        lab.pair(now)
        val keeper = Side(lab, lab.keeper, "keeper", now)
        val guest = Side(lab, LocalSigner(secret()), "guest", now)
        val box = lab.node.toHex()

        // Out of band (decision 8): the guest's device and rendezvous key to the keeper; the keeper's
        // rendezvous key, a counter and its box to the guest. The keeper grants both devices.
        val ledger = VmlsGrantLedger(MemoryRoomStorage())
        val own = ledger.plan(lab.keeper, lab.keeper.pubkey, box, keeper.device, now)
        val hosted = ledger.plan(lab.keeper, guest.signer.pubkey, box, guest.device, now)
        ledger.record(VmlsGrantRecord(box, own)); ledger.record(VmlsGrantRecord(box, hosted))
        lab.publish(lab.keeper, own.active, hosted.active)
        VmlsLab.eventually { guest.client.capabilities().takeIf { it is BoxAnswer.Ok } }
        val counter = 0L

        // The keeper's group.
        val created = createGroup(keeper.vault, keeper.host, keeper.sessions, lab.keeper.pubkey, keeper.credential!!, now, random, lab.node, lab.installation.hexToBytes())
        keeper.session = (created as Hosted.Released).value.snapshot!!.session
        keeper.round()

        // The guest's capability, deposited at the keeper's box by its driver. The keeper opens only one
        // with more than an hour left, inside its binding, inside the device credential's day.
        val joining = guest.join.requestJoin(guest.host, guest.signer.pubkey, bindingRequest(guest.credential!!, lab.node, now, lifetime = 80_000), keeper.rz, counter, now + 72_000, now)
        guest.session = (joining as Hosted.Released).value.snapshot!!.session
        assertEquals(Phase.PendingJoin, guest.phase())
        assertEquals(1, (guest.round() as Round.Done).delivered)

        // The keeper opens it, registers its package (D5, decision 13), acknowledges it, and adds it.
        val introduction = keeper.join.introduction(lab.keeper.pubkey, guest.rz, counter, epochSeconds())
        val fetched = keeper.client.fetch(listOf(introduction.mailbox())) as BoxAnswer.Ok<FetchPage>
        val record = fetched.value.records.single()
        val capability = introduction.openCapability(epochSeconds().toULong(), record.envelope)
        val info = capability.info()
        assertArrayEquals(guest.device.hexToBytes(), info.device)
        val boxNow = fetched.serverTime!!
        assertTrue(VmlsBoxClient.packageAcceptable(boxNow, info.expiresAt.toLong(), VmlsBoxClient.packageCiphertext(info.packageId, info.welcomeMailbox)))
        val registered = keeper.client.registerPackage(info.packageId, info.welcomeMailbox, info.expiresAt.toLong(), VmlsBoxClient.packageCiphertext(info.packageId, info.welcomeMailbox))
        assertEquals(true, (registered as BoxAnswer.Ok<Registered>).value.fresh)
        assertTrue(keeper.client.ack(listOf(AckItem(record.mailbox, record.receipt))) is BoxAnswer.Ok)
        val epochBefore = keeper.epoch()!!
        keeper.step { s -> addStep(s, epochSeconds(), capability) }

        // The keeper's commit wins its slot, applies, and releases the Welcome.
        var rounds = 0
        while (keeper.epoch() == epochBefore && rounds < 5) { keeper.round(); rounds++ }
        assertEquals(epochBefore + 1, keeper.epoch())
        keeper.round()
        assertTrue("the Welcome left the keeper's outbox", keeper.step { EngineStep(null, it.outbox()) }.isEmpty())

        // The guest joins from the Welcome (its box's installation from the driver's capabilities).
        rounds = 0
        while (guest.phase() != Phase.Active && rounds < 5) { guest.round(); rounds++ }
        assertEquals(Phase.Active, guest.phase())
        assertEquals(keeper.epoch(), guest.epoch())
        assertTrue(guest.events.any { it is VmlsEvent.Joined })
        assertTrue("pending until its Update", keeper.step { EngineStep(null, it.inner.members()) }.any { it.pending })

        // The guest's first Update confirms it at the keeper (decision 12).
        guest.update()
        rounds = 0
        while (keeper.step { EngineStep(null, it.inner.members()) }.any { it.pending } && rounds < 6) { guest.round(); keeper.round(); rounds++ }
        assertTrue("confirmed by the guest's Update", keeper.step { EngineStep(null, it.inner.members()) }.none { it.pending })
        assertEquals(keeper.epoch(), guest.epoch())

        // A message each way.
        keeper.send("hello from the keeper")
        guest.send("hello from the guest")
        rounds = 0
        while ((guest.messages().isEmpty() || keeper.messages().isEmpty()) && rounds < 4) { keeper.round(); guest.round(); rounds++ }
        assertEquals(listOf("hello from the keeper"), guest.messages())
        assertEquals(listOf("hello from the guest"), keeper.messages())
    }

    private fun epochSeconds() = System.currentTimeMillis() / 1000
    private fun bytes(n: Int) = ByteArray(n).also(random::nextBytes)
    private fun secret(): ByteArray { while (true) { val k = bytes(32); if (runCatching { Schnorr.publicKey(k) }.isSuccess) return k } }
}
