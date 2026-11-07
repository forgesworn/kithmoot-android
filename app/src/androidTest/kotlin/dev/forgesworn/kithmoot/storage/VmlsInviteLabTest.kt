package dev.forgesworn.kithmoot.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.account.ConsentDecision
import dev.forgesworn.kithmoot.account.ConsentPrompt
import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.account.MlsVault
import dev.forgesworn.kithmoot.account.ParticipantSigner
import dev.forgesworn.kithmoot.account.RendezvousReceipt
import dev.forgesworn.kithmoot.account.StoredRendezvousChild
import dev.forgesworn.kithmoot.account.VaultResult
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.mls.VmlsGrantLedger
import dev.forgesworn.kithmoot.mls.VmlsInviteStore
import dev.forgesworn.kithmoot.mls.VmlsRoomStore
import dev.forgesworn.kithmoot.mls.VmlsRuntime
import dev.forgesworn.kithmoot.protocol.decodeVmlsInvitationUrl
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Lab regression for a legacy relay invitation, through real runtime signing,
 * pairing and keeper admission against the disposable Bothy fixture. The old
 * admission lacks an independently authenticated MLS group pin: no guest room,
 * native session or membership may be created from it. The trusted-pin positive
 * exchange remains in [VmlsJoinLabTest]. Private preview profiles stay disabled.
 *
 * Run by `scripts/lab-vmls-box.sh` with `fixture_control`; skipped without it.
 */
@RunWith(AndroidJUnit4::class)
class VmlsInviteLabTest {
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val control get() = arguments.getString("fixture_control")?.removeSuffix("/")
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as KithMootApplication
    private lateinit var context: Context
    private lateinit var prefix: String
    private val random = SecureRandom()

    @Before fun setup() {
        assumeTrue("run by scripts/lab-vmls-box.sh", control != null)
        context = ApplicationProvider.getApplicationContext()
        prefix = "kithmoot.vmls-invite.${UUID.randomUUID().toString().take(8)}"
    }

    @After fun cleanup() {
        if (control == null) return
        context.noBackupFilesDir.listFiles().orEmpty().filter { it.name.startsWith(prefix) }.forEach { it.delete() }
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keys.aliases().toList().filter { it.startsWith(prefix) }.forEach(keys::deleteEntry)
    }

    /** One persona's runtime over its own vault and stores, on this app's Link engine. */
    private inner class Side(val signer: ParticipantSigner, name: String, carriers: MemoryCarriers, now: Long) {
        val persona = signer.pubkey
        val vault: MlsVault = runBlocking { VmlsLab.coordinatedVault(context, "$prefix.$name", FakeEd25519Witness(), persona, random) }
        private val rzSecret = secret()
        val rz: String = Schnorr.publicKey(rzSecret).toHex()
        val grants = MemoryRoomStorage()
        val runtime = VmlsRuntime(
            vault, app.linkEngine, VmlsRoomStore(MemoryRoomStorage()), VmlsGrantLedger(grants),
            VmlsInviteStore(MemoryRoomStorage()), { carriers.open() },
            rendezvous = { p -> if (p != persona) null else StoredRendezvousChild(RendezvousReceipt(p, "b".repeat(64), rz, 1, now + 86_400), rzSecret.copyOf()) },
            quiet = AtomicBoolean(false),
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            prompt = ConsentPrompt { ConsentDecision.Approve },
        )
        val device: String get() = (vault.let { runBlocking { it.device(it.context(VmlsRuntime.PRINCIPAL, persona)) } } as VaultResult.Ok).value.device
    }

    @Test fun a_legacy_link_without_a_group_pin_cannot_create_a_guest_session() = runBlocking<Unit> {
        val now = epochSeconds()
        val lab = VmlsLab(app, control!!, arguments.getString("persona") ?: "alice")
        lab.ready()
        val box = lab.node.toHex()
        val carriers = MemoryCarriers()
        val keeper = Side(lab.keeper, "keeper", carriers, now)
        val guest = Side(LocalSigner(secret()), "guest", carriers, now)

        keeper.runtime.pairing(lab.keeper, lab.pairingCode())
        val room = keeper.runtime.create(keeper.persona, box, "Lab kitchen")
        keeper.runtime.foregroundRounds(keeper.persona)
        val url = keeper.runtime.invite(keeper.persona, room.session, "https://kithmoot.app/join", listOf("memory:lab"))
        assertEquals(box, checkNotNull(decodeVmlsInvitationUrl(url)).box)
        val serving = launch(Dispatchers.IO) { keeper.runtime.serveInvites(keeper.persona) }
        try {
            delay(500)
            // The keeper really admits this device. A signed legacy relay admission still lacks
            // an authenticated MLS group id and must not authorize capability creation.
            supervisorScope {
                val joining = async(Dispatchers.IO) { guest.runtime.joining(guest.signer, url, lab.pairingCode()) }
                val ask = withTimeout(60_000) { keeper.runtime.joinAsk.filterNotNull().first() }
                assertEquals("Lab kitchen", ask.room)
                assertEquals(guest.persona, ask.guest)
                assertEquals(guest.device, ask.device)
                assertEquals(box, ask.box)
                keeper.runtime.admitting(lab.keeper, ask, approve = true)
                assertNull(keeper.runtime.joinAsk.value)
                var refusal: IllegalStateException? = null
                try { withTimeout(120_000) { joining.await() } }
                catch (error: IllegalStateException) { refusal = error }
                assertEquals(IllegalStateException::class.java, refusal?.javaClass)
                assertTrue("legacy admission must lack an authenticated group pin", refusal?.message?.contains("authenticated MLS group id") == true)
            }
            assertTrue("no guest room stored", guest.runtime.rooms.value.isEmpty())
            assertTrue("no guest MLS session witnessed", guest.runtime.witnessed(guest.persona)!!.isEmpty())
            assertTrue("no guest membership added", keeper.runtime.room(keeper.persona, room.session)!!.members.values.none { it.device == guest.device })
        } finally {
            serving.cancelAndJoin()
        }
    }

    private fun epochSeconds() = System.currentTimeMillis() / 1000
    private fun secret(): ByteArray { while (true) { val k = ByteArray(32).also(random::nextBytes); if (runCatching { Schnorr.publicKey(k) }.isSuccess) return k } }
}
