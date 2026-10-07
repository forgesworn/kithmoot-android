package dev.forgesworn.kithmoot.storage

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.account.ConsentDecision
import dev.forgesworn.kithmoot.account.ConsentPrompt
import dev.forgesworn.kithmoot.account.CoordinationStatus
import dev.forgesworn.kithmoot.account.EngineVaultWitness
import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.account.MlsVault
import dev.forgesworn.kithmoot.account.ParticipantSigner
import dev.forgesworn.kithmoot.account.RendezvousReceipt
import dev.forgesworn.kithmoot.account.StoredRendezvousChild
import dev.forgesworn.kithmoot.account.VaultCoordination
import dev.forgesworn.kithmoot.account.VaultResult
import dev.forgesworn.kithmoot.account.WitnessChannels
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.mls.RelayCarrier
import dev.forgesworn.kithmoot.mls.RoomStatus
import dev.forgesworn.kithmoot.mls.RoomStop
import dev.forgesworn.kithmoot.mls.VmlsGrantLedger
import dev.forgesworn.kithmoot.mls.VmlsGrantState
import dev.forgesworn.kithmoot.mls.VmlsInviteStore
import dev.forgesworn.kithmoot.mls.VmlsRole
import dev.forgesworn.kithmoot.mls.VmlsRoom
import dev.forgesworn.kithmoot.mls.VmlsRoomStore
import dev.forgesworn.kithmoot.mls.VmlsRuntime
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P3-03b-3's run on two emulators and one `bothyd` (not CI): a keeper on one
 * emulator, a guest on the other, the box's claimed fixture between them
 * (see [VmlsLab]), and the invitation over a real Nostr relay through the
 * app's [RelayCarrier]. Each `am instrument` runs one [step] in a new
 * process; the persona, vault, witness and stores persist on the device
 * between steps, so every step is also a restart. `scripts/lab-vmls-two.sh`
 * orders the steps and passes what one device learnt to the other.
 *
 * Skipped without `fixture_control` and `role`.
 */
@RunWith(AndroidJUnit4::class)
class VmlsTwoDeviceLabTest {
    private val arguments get() = InstrumentationRegistry.getArguments()
    private fun arg(name: String): String? = arguments.getString(name)?.takeIf { it.isNotEmpty() }
    private val control get() = arg("fixture_control")?.removeSuffix("/")
    private val role get() = arg("role")
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as KithMootApplication
    private lateinit var context: Context
    private lateinit var dir: File
    private val random = SecureRandom()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var lab: VmlsLab
    private lateinit var signer: ParticipantSigner
    private lateinit var persona: String
    private lateinit var vault: MlsVault
    private lateinit var grants: VmlsGrantLedger
    private lateinit var runtime: VmlsRuntime
    private val number get() = arg("room") ?: "1"

    @Before fun setup() {
        assumeTrue("run by scripts/lab-vmls-two.sh", control != null && role in setOf(KEEPER, GUEST))
        context = ApplicationProvider.getApplicationContext()
        dir = File(context.noBackupFilesDir, "vmls-two-$role").apply { mkdirs() }
        lab = VmlsLab(app, control!!, arg("persona") ?: "alice")
        signer = if (role == KEEPER) lab.keeper else LocalSigner(kept("persona"))
        persona = signer.pubkey
        val prefix = "kithmoot.vmls-two.$role"
        vault = runBlocking { openVault(prefix, FakeEd25519Witness(saved = File(dir, "witness"))) }
        val rzSecret = kept("rz")
        val rz = Schnorr.publicKey(rzSecret).toHex()
        grants = VmlsGrantLedger(EncryptedRoomStorage(app, "$prefix.grants", 1024 * 1024))
        runtime = VmlsRuntime(
            vault, app.linkEngine,
            VmlsRoomStore(EncryptedRoomStorage(app, "$prefix.rooms", 1024 * 1024)), grants,
            VmlsInviteStore(EncryptedRoomStorage(app, "$prefix.links", 256 * 1024)),
            { relays -> RelayCarrier(relays, scope) },
            rendezvous = { p ->
                if (p != persona) null
                else StoredRendezvousChild(RendezvousReceipt(p, "b".repeat(64), rz, 1, epochSeconds() + 86_400), rzSecret.copyOf())
            },
            quiet = AtomicBoolean(false),
            scope = scope,
            prompt = ConsentPrompt { ConsentDecision.Approve },
        )
    }

    @After fun teardown() {
        scope.cancel()
    }

    @Test fun step() = runBlocking<Unit> {
        val step = arg("step")
        log("step $step, room $number")
        when (step) {
            "create" -> create()
            "admit" -> admit()
            "join" -> join()
            "talk" -> talk()
            "queue" -> queue()
            "offline" -> offline()
            "leave" -> leave()
            "prepare-remove" -> prepareRemove()
            "update" -> update()
            "settle" -> settle()
            "removed" -> removed()
            "close" -> close()
            else -> throw AssertionError("unknown step $step")
        }
        log("step $step done: ${runtime.room(persona, session()).orGone()}")
    }

    /** The keeper pairs (once), creates the room and shares its link over the relay. */
    private suspend fun create() {
        check(role == KEEPER)
        lab.ready()
        val box = lab.node.toHex()
        if (runtime.store.route(persona, box) == null) runtime.pairing(signer, lab.pairingCode())
        // A box another session just pushed off the Link relay waits 30 s before it returns (Link spec 3.1).
        val room = VmlsLab.eventually(90) { runCatching { runtime.create(persona, box, name()) }.onFailure { log("create: $it") }.getOrNull() }
        runtime.foregroundRounds(persona)
        val url = runtime.invite(persona, room.session, "https://kithmoot.app/join", listOf(arg("relay")!!))
        File(dir, "room$number").writeText(room.session)
        File(dir, "link$number").writeText(url)
    }

    /** The keeper serves its link, lets the one guest in and drives until the guest's first Update confirms it. */
    private suspend fun admit() {
        check(role == KEEPER)
        val serving = scope.launch { runtime.serveInvites(persona) }
        val ask = withTimeout(300_000) { runtime.joinAsk.filterNotNull().first() }
        log("asked: ${ask.room} by ${ask.guest.take(8)}…, device ${ask.device.take(8)}…")
        assertEquals(name(), ask.room)
        runtime.admitting(signer, ask, approve = true)
        File(dir, "guest-device").writeText(ask.device)
        roundsUntil("the guest confirmed", 120) { room().members.values.any { it.device == ask.device && !it.pending } }
        runtime.retire(persona, session())
        serving.cancelAndJoin()
    }

    /** The guest pairs with the link's box by a fresh code, asks over the relay, joins and sends its first Update. */
    private suspend fun join() {
        check(role == GUEST)
        val joined = withTimeout(600_000) { runtime.joining(signer, arg("link")!!, lab.pairingCode()) }
        assertEquals(VmlsRole.GUEST, joined.role)
        assertEquals(name(), joined.name)
        File(dir, "room$number").writeText(joined.session)
        roundsUntil("joined", 120) { room().joined && room().status == RoomStatus.Ready && !room().sending }
    }

    /** Sends `say` (`|`-separated), then drives until `expect` arrived, each once and in order. */
    private suspend fun talk() {
        val says = arg("say")?.split('|').orEmpty()
        val expected = arg("expect")?.split('|').orEmpty()
        for (text in says) runtime.send(persona, session(), text)
        roundsUntil("sent", 30) { !room().sending && !room().retrying }
        if (expected.isNotEmpty()) roundsUntil("heard ${expected.size}", 90) { heard().containsAll(expected) }
        repeat(arg("rounds")?.toInt() ?: 2) { runtime.foregroundRounds(persona); delay(1_000) }
        assertEquals(expected, heard().filter { it in expected })
    }

    /** Sends and stops before any round: the message waits in the persisted outbox for the next process. */
    private suspend fun queue() {
        runtime.send(persona, session(), arg("say")!!)
        log("queued, not driven: ${room()}")
    }

    /** With the network off: rounds reach no box, deliver nothing and stop nothing. */
    private suspend fun offline() {
        repeat(3) { runtime.foregroundRounds(persona); delay(2_000) }
        assertTrue(heard().isEmpty())
        val room = room()
        assertTrue("still joined: $room", room.joined)
        assertNull("not stopped: $room", room.stop)
    }

    /** The guest leaves: the session is dropped from the vault's manifest and the room forgotten. */
    private suspend fun leave() {
        check(role == GUEST)
        runtime.leaving(persona, session())
        runtime.foregroundRounds(persona)
        assertNull(runtime.store.room(persona, session()))
        assertTrue(session() !in runtime.witnessed(persona)!!)
    }

    /** The keeper's Remove of the guest, made and stored but not deposited: the losing commit. */
    private suspend fun prepareRemove() {
        check(role == KEEPER)
        val device = File(dir, "guest-device").readText()
        val room = live()
        File(dir, "epoch$number").writeText(checkNotNull(room.epoch).toString())
        runtime.removing(persona, session(), room.members.values.single { it.device == device }.leaf)
        assertTrue("a Remove in the outbox: ${room()}", room().sending)
    }

    /** The guest's Update, deposited first: it wins the epoch the keeper's Remove was made for. */
    private suspend fun update() {
        check(role == GUEST)
        val before = checkNotNull(live().epoch)
        runtime.updating(persona, session())
        roundsUntil("the Update accepted", 30) { (room().epoch ?: 0) > before && !room().sending }
        assertEquals(before + 1, room().epoch)
    }

    /** The keeper's Remove loses to the guest's Update, is offered again in the next epoch, and wins it. */
    private suspend fun settle() {
        check(role == KEEPER)
        val before = File(dir, "epoch$number").readText().toLong()
        var lost = live().retrying
        roundsUntil("the guest removed", 60) { room().also { lost = lost || it.retrying }.members.isEmpty() }
        assertTrue("the Remove offered again", lost)
        // The proof of the loss: a Remove made for epoch E is accepted at E + 2, the guest's Update having taken E + 1.
        assertEquals("the guest's Update, then the Remove", before + 2, room().epoch)
        // D1 R2: the removed device is in none of the keeper's rooms here, so the next pass with the keeper's
        // signer revokes its grant at the box, before any close.
        val box = room().box
        val device = File(dir, "guest-device").readText()
        assertEquals(VmlsGrantState.ACTIVE, grants.get(box, device)!!.state)
        runtime.foregroundRounds(persona, signer)
        assertEquals("the removed device's grant revoked", VmlsGrantState.REVOKED, grants.get(box, device)!!.state)
    }

    /** The guest's room says it was removed, and is read-only. */
    private suspend fun removed() {
        check(role == GUEST)
        roundsUntil("removed", 60) { room().stop == RoomStop.Removed }
        assertFalse(room().canSend)
    }

    /**
     * The keeper closes the room (decision 24). The guest's grant is kept
     * while its device is in another of the keeper's rooms at this box
     * (`grant=active`), and revoked otherwise.
     */
    private suspend fun close() {
        check(role == KEEPER)
        val box = room().box
        val device = File(dir, "guest-device").readText()
        runtime.closing(signer, session())
        assertNull(runtime.store.room(persona, session()))
        assertTrue(session() !in runtime.witnessed(persona)!!)
        val keeperDevice = (vault.device(vault.context(VmlsRuntime.PRINCIPAL, persona)) as VaultResult.Ok).value.device
        assertEquals(VmlsGrantState.ACTIVE, grants.get(box, keeperDevice)!!.state)
        val expected = if (arg("grant") == "active") VmlsGrantState.ACTIVE else VmlsGrantState.REVOKED
        assertEquals(expected, grants.get(box, device)!!.state)
    }

    private suspend fun openVault(prefix: String, witness: FakeEd25519Witness): MlsVault {
        val enrolled = File(dir, "enrolled")
        if (!enrolled.exists()) {
            return VmlsLab.coordinatedVault(context, prefix, witness, persona, random).also { enrolled.writeText("1") }
        }
        val vault = MlsVault.coordinated(VaultCoordination(AndroidMlsVaultStores(context, prefix), WitnessChannels { _, _, _ -> witness.channel }, EngineVaultWitness()))
        assertEquals(CoordinationStatus.Active, vault.coordinationStatus(persona, check = true))
        return vault
    }

    private suspend fun roundsUntil(what: String, limit: Int, done: () -> Boolean) {
        var rounds = 0
        while (!done()) {
            if (rounds++ >= limit) throw AssertionError("$role: $what not reached after $limit rounds: ${room()}")
            runtime.foregroundRounds(persona)
            delay(1_000)
        }
        log("$what after $rounds rounds: ${room()}")
    }

    private fun name() = if (number == "1") "Two-device kitchen" else "Two-device porch"
    private fun session() = File(dir, "room$number").readText()
    private fun room(): VmlsRoom = runtime.room(persona, session()) ?: throw AssertionError("$role: room $number is not kept")
    /** The room as the engine holds it: a new process holds it only after a round (the stored room has no epoch or members). */
    private suspend fun live(): VmlsRoom = runtime.foregroundRounds(persona).let { room() }
    private fun heard() = runtime.messages(persona, session()).map { String(it.body) }
    private fun VmlsRoom?.orGone() = this?.let { "epoch ${it.epoch}, ${it.members.size} other members, stop ${it.stop}, status ${it.status}" } ?: "gone"
    private fun log(text: String) { Log.i(TAG, "$role: $text") }

    /** A secret kept under this role's directory: the same persona and rendezvous key in every step. */
    private fun kept(name: String): ByteArray {
        val file = File(dir, name)
        if (file.isFile) return file.readText().hexToBytes()
        while (true) {
            val k = ByteArray(32).also(random::nextBytes)
            if (runCatching { Schnorr.publicKey(k) }.isSuccess) return k.also { file.writeText(it.toHex()) }
        }
    }

    private fun epochSeconds() = System.currentTimeMillis() / 1000

    private companion object {
        const val TAG = "VmlsTwoDevice"
        const val KEEPER = "keeper"
        const val GUEST = "guest"
    }
}
