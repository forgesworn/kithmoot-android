package dev.forgesworn.kithmoot.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.account.ConsentDecision
import dev.forgesworn.kithmoot.account.ConsentPrompt
import dev.forgesworn.kithmoot.account.ConsentScope
import dev.forgesworn.kithmoot.account.MlsVault
import dev.forgesworn.kithmoot.account.RendezvousReceipt
import dev.forgesworn.kithmoot.account.StoredRendezvousChild
import dev.forgesworn.kithmoot.account.VaultResult
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.mls.RoomStatus
import dev.forgesworn.kithmoot.mls.VmlsGrantLedger
import dev.forgesworn.kithmoot.mls.VmlsRole
import dev.forgesworn.kithmoot.mls.VmlsRoomStore
import dev.forgesworn.kithmoot.mls.VmlsRuntime
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P3-03b-3 lab (not CI): the app's VMLS runtime itself against a real
 * `bothyd` with VMLS on (see [VmlsLab]). It pairs with the box by the code
 * the box shows, enrols the keeper's MLS device, grants it, and checks the
 * box answers with VMLS (decisions 15 and 16); creates a room and drives it
 * through the foreground rounds; sends; and picks the room up again in a
 * new runtime over the same stores, as after a restart. The vault's
 * consent is asked once per kind of request, then kept.
 *
 * Run by `scripts/lab-vmls-box.sh` with `fixture_control`; skipped without it.
 */
@RunWith(AndroidJUnit4::class)
class VmlsRuntimeLabTest {
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val control get() = arguments.getString("fixture_control")?.removeSuffix("/")
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as KithMootApplication
    private lateinit var context: Context
    private lateinit var prefix: String
    private val random = SecureRandom()

    @Before fun setup() {
        assumeTrue("run by scripts/lab-vmls-box.sh", control != null)
        context = ApplicationProvider.getApplicationContext()
        prefix = "kithmoot.vmls-runtime.${UUID.randomUUID().toString().take(8)}"
    }

    @After fun cleanup() {
        if (control == null) return
        context.noBackupFilesDir.listFiles().orEmpty().filter { it.name.startsWith(prefix) }.forEach { it.delete() }
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keys.aliases().toList().filter { it.startsWith(prefix) }.forEach(keys::deleteEntry)
    }

    @Test fun the_runtime_pairs_a_box_creates_a_room_and_drives_it() = runBlocking<Unit> {
        val now = epochSeconds()
        val lab = VmlsLab(app, control!!, arguments.getString("persona") ?: "alice")
        lab.ready()
        val keeper = lab.keeper
        val box = lab.node.toHex()
        val vault = VmlsLab.coordinatedVault(context, "$prefix.keeper", FakeEd25519Witness(), keeper.pubkey, random)
        val rzSecret = secret()
        val rz = Schnorr.publicKey(rzSecret).toHex()
        val asked = mutableListOf<ConsentScope>()
        val rooms = MemoryRoomStorage()
        val grants = MemoryRoomStorage()
        fun runtime() = VmlsRuntime(
            vault, app.linkEngine, VmlsRoomStore(rooms), VmlsGrantLedger(grants),
            rendezvous = { persona ->
                if (persona != keeper.pubkey) null
                else StoredRendezvousChild(RendezvousReceipt(persona, "b".repeat(64), rz, 1, now + 86_400), rzSecret.copyOf())
            },
            quiet = AtomicBoolean(false),
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            prompt = ConsentPrompt { scope -> synchronized(asked) { asked += scope }; ConsentDecision.Approve },
        )
        val runtime = runtime()

        // The box's code pairs this phone; the keeper's device is enrolled, granted and answered with VMLS.
        runtime.pairing(keeper, lab.pairingCode())
        val route = checkNotNull(runtime.store.route(keeper.pubkey, box))
        val device = (vault.device(vault.context(VmlsRuntime.PRINCIPAL, keeper.pubkey)) as VaultResult.Ok).value
        assertNotNull(device.credential)
        assertEquals(1, VmlsGrantLedger(grants).all().count { it.box == box && it.device == device.device })
        // The app's sweep of unconsented Link routes keeps it (it runs at every start).
        assertTrue(route.routeId in runtime.routeIds())
        // Pairing again keeps the route: one per persona per box.
        runtime.pairing(keeper, lab.pairingCode())
        assertEquals(route, runtime.store.route(keeper.pubkey, box))

        // A room, created and driven.
        val room = runtime.create(keeper.pubkey, box, "Lab room")
        assertEquals(VmlsRole.KEEPER, room.role)
        // Its binding lasts a week, past the engine's day-long update window: no Update is due yet.
        repeat(3) { runtime.foregroundRounds(keeper.pubkey) }
        val driven = checkNotNull(runtime.room(keeper.pubkey, room.session))
        assertEquals(RoomStatus.Ready, driven.status)
        val epoch = checkNotNull(driven.epoch)
        assertTrue(driven.members.isEmpty())

        // A message leaves at the next round; the room stays ready.
        runtime.send(keeper.pubkey, room.session, "hello from the runtime")
        repeat(2) { runtime.foregroundRounds(keeper.pubkey) }
        assertEquals(RoomStatus.Ready, runtime.room(keeper.pubkey, room.session)!!.status)

        // A box a room uses is not forgotten.
        val refused = runCatching { runtime.forgetting(keeper, box) }.exceptionOrNull()
        assertTrue("$refused", refused is IllegalStateException)

        // A new runtime over the same stores, as after a restart, seeds the room from its engine.
        val again = runtime()
        again.foregroundRounds(keeper.pubkey)
        val reopened = checkNotNull(again.room(keeper.pubkey, room.session))
        assertEquals(RoomStatus.Ready, reopened.status)
        assertEquals(epoch, reopened.epoch)

        // Consent was asked once for signing and once for box requests, then kept.
        assertEquals(listOf(MlsVault.BOX_METHOD, MlsVault.SIGN_METHOD).sorted(), synchronized(asked) { asked.map { it.method }.sorted() })
        assertTrue(asked.all { it.homeBox == box && it.device == device.device })
    }

    private fun epochSeconds() = System.currentTimeMillis() / 1000
    private fun secret(): ByteArray { while (true) { val k = ByteArray(32).also(random::nextBytes); if (runCatching { Schnorr.publicKey(k) }.isSuccess) return k } }
}
