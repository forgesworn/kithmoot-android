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
import dev.forgesworn.kithmoot.mls.RoomStatus
import dev.forgesworn.kithmoot.mls.VmlsGrantLedger
import dev.forgesworn.kithmoot.mls.VmlsInviteStore
import dev.forgesworn.kithmoot.mls.VmlsRole
import dev.forgesworn.kithmoot.mls.VmlsRoomStore
import dev.forgesworn.kithmoot.mls.VmlsRuntime
import dev.forgesworn.kithmoot.protocol.KIND_INVITATION_REQUEST
import dev.forgesworn.kithmoot.protocol.decodeVmlsInvitationUrl
import dev.forgesworn.kithmoot.protocol.encodeVmlsJoinRequest
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
 * P3-03b-3 lab (not CI): a VMLS room's invitation end to end through the
 * app's runtime, against a real `bothyd` with VMLS on (see [VmlsLab]). The
 * keeper (the box's owner) creates a room and shares its link; the guest,
 * another persona in this process, pairs with the box by a fresh code and
 * asks; the keeper is asked (decision 18) and lets it in: its device is
 * granted and it is answered (decision 17); the guest deposits its
 * capability, the keeper adds it, the guest joins from the Welcome, and its
 * first Update confirms it at the keeper. A message goes each way. The
 * gate drops the same device's second ask over the link.
 *
 * The invitation travels in memory between the two runtimes; the relays it
 * travels over in the app are the two-emulator run's.
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
        val runtime = VmlsRuntime(
            vault, app.linkEngine, VmlsRoomStore(MemoryRoomStorage()), VmlsGrantLedger(MemoryRoomStorage()),
            VmlsInviteStore(MemoryRoomStorage()), { carriers.open() },
            rendezvous = { p -> if (p != persona) null else StoredRendezvousChild(RendezvousReceipt(p, "b".repeat(64), rz, 1, now + 86_400), rzSecret.copyOf()) },
            quiet = AtomicBoolean(false),
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            prompt = ConsentPrompt { ConsentDecision.Approve },
        )
        val device: String get() = (vault.let { runBlocking { it.device(it.context(VmlsRuntime.PRINCIPAL, persona)) } } as VaultResult.Ok).value.device
    }

    @Test fun a_guest_asks_by_link_is_let_in_joins_and_they_talk() = runBlocking<Unit> {
        val now = epochSeconds()
        val lab = VmlsLab(app, control!!, arguments.getString("persona") ?: "alice")
        lab.ready()
        val box = lab.node.toHex()
        val carriers = MemoryCarriers()
        val keeper = Side(lab.keeper, "keeper", carriers, now)
        val guest = Side(LocalSigner(secret()), "guest", carriers, now)

        // The keeper's room, and its link.
        keeper.runtime.pairing(lab.keeper, lab.pairingCode())
        val room = keeper.runtime.create(keeper.persona, box, "Lab kitchen")
        keeper.runtime.foregroundRounds(keeper.persona)
        val url = keeper.runtime.invite(keeper.persona, room.session, "https://kithmoot.app/join", listOf("memory:lab"))
        val link = checkNotNull(decodeVmlsInvitationUrl(url))
        assertEquals(box, link.box)
        val serving = launch(Dispatchers.IO) { keeper.runtime.serveInvites(keeper.persona) }
        delay(500)

        // The guest pairs with the link's box by a fresh code and asks; the keeper is asked about this device.
        val joining = async(Dispatchers.IO) { guest.runtime.joining(guest.signer, url, lab.pairingCode()) }
        val ask = withTimeout(60_000) { keeper.runtime.joinAsk.filterNotNull().first() }
        assertEquals("Lab kitchen", ask.room)
        assertEquals(guest.persona, ask.guest)
        assertEquals(guest.device, ask.device)
        assertEquals(box, ask.box)
        keeper.runtime.admitting(lab.keeper, ask, approve = true)
        assertNull(keeper.runtime.joinAsk.value)
        val joined = withTimeout(120_000) { joining.await() }
        assertEquals(VmlsRole.GUEST, joined.role)
        assertEquals("Lab kitchen", joined.name)

        // The guest's capability, the keeper's add and Welcome, the guest's join and first Update (decision 12).
        var rounds = 0
        fun guestRoom() = guest.runtime.room(guest.persona, joined.session)!!
        fun keeperRoom() = keeper.runtime.room(keeper.persona, room.session)!!
        while (!(guestRoom().joined && keeperRoom().members.values.any { it.device == guest.device && !it.pending } && guestRoom().status == RoomStatus.Ready) && rounds < 20) {
            guest.runtime.foregroundRounds(guest.persona)
            keeper.runtime.foregroundRounds(keeper.persona)
            delay(1_000)
            rounds++
        }
        assertTrue("joined after $rounds rounds: ${guestRoom().status}", guestRoom().joined)
        assertTrue("confirmed at the keeper: ${keeperRoom().members}", keeperRoom().members.values.any { it.device == guest.device && !it.pending })
        assertEquals(keeperRoom().epoch, guestRoom().epoch)

        // A message each way.
        keeper.runtime.send(keeper.persona, room.session, "hello from the keeper")
        guest.runtime.send(guest.persona, joined.session, "hello from the guest")
        rounds = 0
        while ((guest.runtime.messages(guest.persona, joined.session).isEmpty() || keeper.runtime.messages(keeper.persona, room.session).isEmpty()) && rounds < 6) {
            keeper.runtime.foregroundRounds(keeper.persona)
            guest.runtime.foregroundRounds(guest.persona)
            rounds++
        }
        assertEquals(listOf("hello from the keeper"), guest.runtime.messages(guest.persona, joined.session).map { String(it.body) })
        assertEquals(listOf("hello from the guest"), keeper.runtime.messages(keeper.persona, room.session).map { String(it.body) })

        // The same device asking again over the same link is dropped unseen (decision 18).
        val credential = (guest.vault.device(guest.vault.context(VmlsRuntime.PRINCIPAL, guest.persona)) as VaultResult.Ok).value.credential!!
        val again = encodeVmlsJoinRequest(link.invitation, secret(), credential, guest.rz, epochSeconds())
        assertEquals(KIND_INVITATION_REQUEST, again.kind)
        carriers.open().publish(again)
        delay(2_000)
        assertNull("asked once per device per link", keeper.runtime.joinAsk.value)
        serving.cancelAndJoin()
    }

    private fun epochSeconds() = System.currentTimeMillis() / 1000
    private fun secret(): ByteArray { while (true) { val k = ByteArray(32).also(random::nextBytes); if (runCatching { Schnorr.publicKey(k) }.isSuccess) return k } }
}
