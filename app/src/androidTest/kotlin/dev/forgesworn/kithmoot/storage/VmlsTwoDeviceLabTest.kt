package dev.forgesworn.kithmoot.storage

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.mls.BoxAnswer
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
import dev.forgesworn.kithmoot.mls.VmlsRemovalView
import dev.forgesworn.kithmoot.mls.VmlsRenewal
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
import org.junit.Assert.assertNotNull
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
 * orders the steps and passes what one device learnt to the other. With a
 * third emulator (`third`), the guest's person on a second device, it also
 * runs M06 and M07 (P3-05b follow-ups).
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
        assumeTrue("run by scripts/lab-vmls-two.sh", control != null && role in setOf(KEEPER, GUEST, THIRD))
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
            // A short grace: settle checks the grant outlives a pass before it, revoke one after it.
            removedGraceSeconds = if (arg("p308") == "true") 86400 else REMOVED_GRACE_SECONDS,
            // The renew step widens the windows past the 30-day lifetimes, so the credential and grants renew now.
            credentialRenewSeconds = if (arg("step") == "renew") RENEW_NOW_SECONDS else VmlsRenewal.CREDENTIAL_RENEW_SECONDS,
            grantRenewSeconds = if (arg("step") == "renew") RENEW_NOW_SECONDS else VmlsRuntime.GRANT_RENEW_SECONDS,
            requestCarriers = { relays, actor -> RelayCarrier(relays, scope, actor) },
        )
        runtime.requestDirectory(listOfNotNull(arg("relay")))
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
            "revoke" -> revoke()
            "renew" -> renew()
            "close" -> close()
            "compromise" -> compromise()
            "cut-off" -> cutOff()
            "contain" -> contain()
            "remove-device" -> removeDevice()
            "stale" -> stale()
            "heard-stale" -> heardStale()
            "removed-reader" -> removedReader()
            "remove-person" -> removePerson()
            "request-setup" -> requestSetup()
            "self-request" -> selfRequest()
            "accept-request" -> acceptRequest()
            "request-replay" -> requestReplay()
            else -> throw AssertionError("unknown step $step")
        }
        log("step $step done: ${runtime.room(persona, session()).orGone()}")
    }

    /** The keeper pairs (once), creates the room and shares its link over the relay. */
    private suspend fun requestSetup() {
        check(role == KEEPER)
        val relay = arg("relay")!!
        val event = signer.sign(dev.forgesworn.kithmoot.protocol.KIND_DM_RELAYS, epochSeconds(), listOf(listOf("relay", relay)), "")
        RelayCarrier(listOf(relay), scope).use { assertTrue("DM relay list accepted", it.publish(event)) }
    }

    private suspend fun selfRequest() {
        check(role == GUEST)
        live()
        val own = (vault.device(vault.context("dev.forgesworn.kithmoot", persona)) as VaultResult.Ok).value.device
        val tablet = room().members.values.single { it.identity == persona && it.device != own }
        assertTrue("the room exposes another own device before a request populates any cache",
            tablet.device in runtime.rooms.value.single { it.session == session() }.requestDevices)
        assertFalse("the current phone is never reportable", own in runtime.rooms.value.single { it.session == session() }.requestDevices)
        runtime.requestDevice(signer, session(), tablet.device)
        roundsUntil("the member's own Remove", 90) { room().members.values.none { it.device == tablet.device } }
        val entry = runtime.removals(persona, session()).single()
        assertTrue(entry.grants.single().contains("requested"))
        assertFalse(entry.grants.single().contains("revoked at the box"))
        assertTrue(entry.mls.contains("applied"))
        // A user can explicitly resend after a missed/declined signer prompt at the keeper.
        runtime.requestDevice(signer, session(), tablet.device)
        log("member Remove witnessed; grant requested only; explicit resend accepted")
    }

    private suspend fun acceptRequest() {
        check(role == KEEPER)
        live()
        runtime.requestRound(signer)
        val ask = runtime.revocationAsks.value.single()
        val tablet = File(dir, "tablet-device").readText()
        assertEquals(tablet, ask.device)
        assertEquals(VmlsGrantState.ACTIVE, grants.get(room().box, tablet)!!.state)
        runtime.acceptRequest(signer, ask.key, true)
        assertEquals(VmlsGrantState.REVOKED, grants.get(room().box, tablet)!!.state)
        assertTrue(runtime.revocationAsks.value.isEmpty())
        log("keeper accepted; tablet grant revoked before 24-hour grace")
    }

    private suspend fun requestReplay() {
        check(role == KEEPER)
        runtime.requestRound(signer)
        assertTrue("no repeated prompt after process restart", runtime.revocationAsks.value.isEmpty())
        assertEquals(VmlsGrantState.REVOKED, grants.get(room().box, File(dir, "tablet-device").readText())!!.state)
        // Crash boundary: box/ledger effects completed, but the witnessed DONE write was lost.
        val ctx = vault.context("dev.forgesworn.kithmoot", persona)
        val book = dev.forgesworn.kithmoot.mls.RevocationBook.decode((vault.revocationRequests(ctx) as VaultResult.Ok).value)
        val key = book.inbox.keys.single()
        book.inbox[key] = book.inbox.getValue(key).copy(decision = dev.forgesworn.kithmoot.mls.RevocationDecision.APPROVED)
        assertTrue(vault.keepRevocationRequests(ctx, book.encode()) is VaultResult.Ok)
        grants.prune(epochSeconds(), epochSeconds())
        runtime.requestRound(signer)
        val repaired = dev.forgesworn.kithmoot.mls.RevocationBook.decode((vault.revocationRequests(ctx) as VaultResult.Ok).value)
        assertEquals(dev.forgesworn.kithmoot.mls.RevocationDecision.DONE, repaired.inbox.getValue(key).decision)
        assertTrue("a pruned grant cannot strand an approved prompt", runtime.revocationAsks.value.isEmpty())
    }

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
        // A second device's ask (M06) is never the first one's, replayed.
        arg("device")?.let { assertTrue("a second device", ask.device != File(dir, "guest-device").readText()) }
        File(dir, arg("device") ?: "guest-device").writeText(ask.device)
        roundsUntil("the guest confirmed", 120) { room().members.values.any { it.device == ask.device && !it.pending } }
        // Kept for a second device's ask (M06).
        if (arg("keep") != "true") runtime.retire(persona, session())
        serving.cancelAndJoin()
    }

    /** The guest pairs with the link's box by a fresh code, asks over the relay, joins and sends its first Update. */
    private suspend fun join() {
        check(role != KEEPER)
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
        // The contrast for cut-off: while its grant is live, the box answers the guest's own signed read.
        if (role == GUEST) VmlsLab.eventually(60) { runtime.boxAnswer(persona, room().box).takeIf { it is BoxAnswer.Ok } }
    }

    /**
     * P3-03b-3d: one pass with the keeper's signer renews its device credential
     * under the same key, and the guest's grant at the box (same id, later
     * expiry), which the box takes. The talk steps after it show the rooms
     * still work on the engine the new credential rebuilt.
     */
    private suspend fun renew() {
        check(role == KEEPER)
        val box = room().box
        val guest = File(dir, "guest-device").readText()
        val before = (vault.device(vault.context(VmlsRuntime.PRINCIPAL, persona)) as VaultResult.Ok).value
        val grant = grants.get(box, guest)!!
        val ownGrant = grants.get(box, before.device)!!
        runtime.foregroundRounds(persona, signer)
        val after = (vault.device(vault.context(VmlsRuntime.PRINCIPAL, persona)) as VaultResult.Ok).value
        assertEquals("the same device key", before.device, after.device)
        assertTrue("a new credential", after.credentialId != before.credentialId)
        assertTrue("a later credential expiry", after.credentialExpiresAt > before.credentialExpiresAt)
        val renewed = grants.get(box, guest)!!
        assertEquals("the same grant id", grant.grantId, renewed.grantId)
        assertTrue("a later grant expiry", renewed.expiration > grant.expiration)
        assertFalse("taken by the box", renewed.unconfirmed)
        val ownRenewed = grants.get(box, before.device)!!
        assertTrue("the keeper's own grant renewed", ownRenewed.expiration > ownGrant.expiration && !ownRenewed.unconfirmed)
        // An Update now binds the leaf under the new credential, and the group takes it.
        val epoch = checkNotNull(live().epoch)
        runtime.updating(persona, session())
        roundsUntil("the Update under the new credential accepted", 30) { (room().epoch ?: 0) > epoch && !room().sending }
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
        // D1 R2: the removal is noted with the grant, which a pass with the keeper's signer leaves live within
        // the grace, so the guest can still fetch its removal.
        val device = File(dir, "guest-device").readText()
        runtime.foregroundRounds(persona, signer)
        val grant = grants.get(room().box, device)!!
        assertEquals("live within the grace", VmlsGrantState.ACTIVE, grant.state)
        assertNotNull("the removal noted", grant.removedAt)
        // P3-05b, M04 on devices: the journal shows the Remove applied and witnessed, the grant still live, and
        // claims only what that state allows (the removed device may still store under its grant).
        val removal = journalled("the Remove committed") { it.mls.contains("applied at this phone and witnessed") }
        assertEquals(listOf("not yet revoked at the box."), removal.grants.map { it.substringAfter(": ") })
        assertTrue("the Remove's claim: ${removal.claim}", removal.claim!!.startsWith("The removed device cannot read messages from later epochs") && removal.claim!!.contains("under its grant"))
    }

    /**
     * The room's single removal once [what] holds of it, the rounds driving it there: with the keeper's
     * [revoking] signer only when a revocation may happen meanwhile.
     */
    private suspend fun journalled(what: String, revoking: Boolean = false, test: (VmlsRemovalView) -> Boolean): VmlsRemovalView {
        var rounds = 0
        while (true) {
            val seen = runtime.removals(persona, session()).singleOrNull()
            if (seen != null && test(seen)) return seen.also { log("$what after $rounds rounds: $it") }
            if (rounds >= 30) log("$what: the outbox deposited again: ${runtime.redeposit(persona, session())}")
            if (rounds++ >= 30) throw AssertionError("$role: $what not reached after 30 rounds: $seen; room ${room()}")
            if (rounds % 5 == 1) log("$what, round $rounds: ${room().orGone()}, sending ${room().sending}, retrying ${room().retrying}, members ${room().members.values.map { it.device.take(8) }}, box ${runCatching { runtime.boxAnswer(persona, room().box) }.map { if (it is BoxAnswer.Ok) "Ok" else it.toString() }.getOrElse { it.toString() }}")
            runtime.foregroundRounds(persona, if (revoking) signer else null)
            delay(1_000)
        }
    }

    /** D1 R2: once the guest has seen its removal, a pass with the keeper's signer revokes its grant, before any close. */
    private suspend fun revoke() {
        check(role == KEEPER)
        val box = room().box
        val device = File(dir, "guest-device").readText()
        val since = grants.get(box, device)!!.removedAt!!
        withTimeout(120_000) {
            while (grants.get(box, device)?.state != VmlsGrantState.REVOKED) {
                runtime.foregroundRounds(persona, signer)
                delay(2_000)
            }
        }
        assertTrue("revoked only after the grace", epochSeconds() >= since + REMOVED_GRACE_SECONDS)
        // P3-05b: the journal takes the box's confirmation, and only now claims both controls done here.
        val removal = journalled("the grant revoked in the journal", revoking = true) { it.grants == listOf(it.grants.single().substringBefore(": ") + ": revoked at the box.") }
        assertEquals("Both are done at this phone and box; other members' offline devices may not have caught up.", removal.claim)
    }

    /**
     * P3-05b part 3, M05 on devices: the keeper takes the guest's device as
     * compromised. Its grant is revoked at once, with no grace and before any
     * Remove (left to the next round here), and the journal claims box access
     * ended and no more: the old leaf may still read the current epoch. The
     * keeper's sends are held meanwhile.
     */
    private suspend fun compromise() {
        check(role == KEEPER)
        val box = room().box
        val device = File(dir, "guest-device").readText()
        val room = live()
        File(dir, "epoch$number").writeText(checkNotNull(room.epoch).toString())
        runtime.removing(persona, session(), room.members.values.single { it.device == device }.leaf, compromised = signer, propose = false)
        assertEquals("revoked at once", VmlsGrantState.REVOKED, grants.get(box, device)?.state)
        assertTrue("no Remove yet: ${room()}", room().members.values.any { it.device == device } && !room().sending)
        val removal = runtime.removals(persona, session()).single()
        assertTrue("MLS pending: $removal", removal.mls.contains("not yet applied"))
        assertEquals(listOf("revoked at the box."), removal.grants.map { it.substringAfter(": ") })
        assertEquals("Box access ended; the old leaf may still read current messages obtained elsewhere.", removal.claim)
        assertTrue("held: $removal", removal.hold!!.contains("held until the Remove"))
        val refused = runCatching { runtime.send(persona, session(), "while-the-device-is-compromised") }.exceptionOrNull()
        assertEquals(VmlsRuntime.HELD, refused?.message)
    }

    /**
     * The compromised guest's message, sent after its grant was revoked: the box refuses the device's own signed
     * read with `authority`, which is where its rounds stop, so nothing it sends is deposited.
     */
    private suspend fun cutOff() {
        check(role == GUEST || role == THIRD)
        val box = room().box
        // Only the grant's refusal is waited for: a passing clock or busy refusal is asked again.
        val refused = VmlsLab.eventually(60) {
            (runtime.boxAnswer(persona, box) as? BoxAnswer.Refused)?.takeIf { it.status == 403 && it.code == "authority" }
        }
        log("box refused: ${refused.status} ${refused.code}")
        runCatching { runtime.send(persona, session(), "from-a-compromised-device") }.onFailure { log("send: $it") }
        repeat(5) { runCatching { runtime.foregroundRounds(persona) }.onFailure { log("round: $it") }; delay(1_000) }
        assertEquals("still refused", refused.code, (runtime.boxAnswer(persona, box) as? BoxAnswer.Refused)?.code)
        log("cut off: ${runtime.room(persona, session()).orGone()}")
    }

    /**
     * The Remove follows the revocation: once it is applied and witnessed the
     * claim is both done, the hold is lifted, and the keeper sends in the new
     * epoch. The compromised device's later message never arrived.
     */
    private suspend fun contain() {
        check(role == KEEPER)
        val device = File(dir, "guest-device").readText()
        val removal = journalled("the Remove committed after the revocation") { it.mls.contains("applied at this phone and witnessed") }
        assertEquals("Both are done at this phone and box; other members' offline devices may not have caught up.", removal.claim)
        assertTrue("resumed: $removal", removal.hold!!.contains("resumed"))
        assertTrue("removed: ${room()}", room().members.values.none { it.device == device })
        assertTrue("a later epoch", checkNotNull(room().epoch) > File(dir, "epoch$number").readText().toLong())
        runtime.send(persona, session(), "after-the-compromise")
        roundsUntil("sent in the new epoch", 30) { !room().sending && !room().retrying }
        assertFalse("nothing from the compromised device", "from-a-compromised-device" in heard())
    }

    /** The guest's room says it was removed, and is read-only. */
    private suspend fun removed() {
        check(role != KEEPER)
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
        val devices = (arg("devices") ?: "guest-device").split(',').map { File(dir, it).readText() }
        runtime.closing(signer, session())
        assertNull(runtime.store.room(persona, session()))
        assertTrue(session() !in runtime.witnessed(persona)!!)
        val keeperDevice = (vault.device(vault.context(VmlsRuntime.PRINCIPAL, persona)) as VaultResult.Ok).value.device
        assertEquals(VmlsGrantState.ACTIVE, grants.get(box, keeperDevice)!!.state)
        val expected = if (arg("grant") == "active") VmlsGrantState.ACTIVE else VmlsGrantState.REVOKED
        // A grant revoked earlier (D1 R2) is pruned by the close: gone from the ledger is revoked.
        for (device in devices) assertEquals(expected, grants.get(box, device)?.state ?: VmlsGrantState.REVOKED)
    }

    /**
     * M06 on devices: the guest's person holds two devices here, its phone and
     * its tablet. The keeper removes the tablet alone (a device, not the
     * person); the phone stays a member. The Remove is committed with the
     * rounds unsigned, so the tablet's grant stays live for M07.
     */
    private suspend fun removeDevice() {
        check(role == KEEPER)
        val phone = File(dir, "guest-device").readText()
        val tablet = File(dir, "tablet-device").readText()
        val room = live()
        val person = room.members.values.single { it.device == tablet }.identity
        assertEquals("one person on both devices", person, room.members.values.single { it.device == phone }.identity)
        File(dir, "epoch$number").writeText(checkNotNull(room.epoch).toString())
        runtime.removing(persona, session(), room.members.values.single { it.device == tablet }.leaf)
        val removal = journalled("the tablet's Remove committed") { it.mls.contains("applied at this phone and witnessed") }
        assertTrue("a device, not the person: ${removal.target}", removal.target.startsWith("Device "))
        assertTrue("the tablet gone: ${room()}", room().members.values.none { it.device == tablet })
        assertTrue("the person's phone still a member: ${room()}", room().members.values.any { it.device == phone && it.identity == person })
        log("the tablet removed: epoch ${room.epoch} to ${room().epoch}")
    }

    /**
     * M07 on devices: the phone, offline across the tablet's Remove (it ran no
     * step since), sends under the old epoch. Its round deposits before it
     * fetches, so the message leaves under the old epoch, and the same round
     * then learns the Remove. Its next message is in the new epoch.
     */
    private suspend fun stale() {
        check(role == GUEST)
        runtime.send(persona, session(), STALE)
        // The engine's word, read for the send without a round: the old epoch.
        val old = checkNotNull(room().epoch)
        roundsUntil("the Remove learnt", 30) { (room().epoch ?: 0) > old && !room().sending && !room().retrying }
        assertNull("still a member: ${room()}", room().stop)
        runtime.send(persona, session(), FRESH)
        // A message leaves with the next round: rounds after it, as talk's.
        repeat(3) { runtime.foregroundRounds(persona); delay(1_000) }
        roundsUntil("sent in the new epoch", 30) { !room().sending && !room().retrying }
    }

    /** The keeper reads both: the stale message in the epoch before the Remove (its mailbox kept), the fresh one after. */
    private suspend fun heardStale() {
        check(role == KEEPER)
        val before = File(dir, "epoch$number").readText().toLong()
        try {
            roundsUntil("heard both", 60) { heard().containsAll(listOf(STALE, FRESH)) }
        } finally {
            log("heard: ${runtime.messages(persona, session()).map { "${String(it.body)}@${it.epoch}" }}")
        }
        val epochs = runtime.messages(persona, session()).associate { String(it.body) to it.epoch }
        assertEquals("the stale message under the old epoch", before, epochs[STALE])
        assertEquals("the fresh message under the new epoch", before + 1, epochs[FRESH])
    }

    /**
     * The removed tablet, back online: it ends removed and never reads the
     * fresh message. Whether it reads the stale one depends on the order it
     * meets the Remove and the message; the engine oracle shows the exposure,
     * and this logs which happened here.
     */
    private suspend fun removedReader() {
        check(role == THIRD)
        roundsUntil("removed", 60) { room().stop == RoomStop.Removed }
        val heard = runtime.messages(persona, session()).associate { String(it.body) to it.epoch }
        log("M07: the removed tablet ${if (STALE in heard) "read the stale message (epoch ${heard[STALE]})" else "did not read the stale message"}; heard ${heard.keys}")
        assertFalse("nothing from the new epoch", FRESH in heard)
    }

    /** M06: the keeper removes the guest's person. The one removal names both devices, both go, and no member is left. */
    private suspend fun removePerson() {
        check(role == KEEPER)
        val phone = File(dir, "guest-device").readText()
        val tablet = File(dir, "tablet-device").readText()
        val room = live()
        val person = room.members.values.single { it.device == phone }.identity
        assertEquals("both devices, one person", setOf(phone, tablet), room.members.values.filter { it.identity == person }.map { it.device }.toSet())
        runtime.removing(persona, session(), person, person = true)
        val removal = journalled("the person's Remove committed") { it.mls.contains("applied at this phone and witnessed") }
        assertTrue("the person, both devices: ${removal.target}", removal.target.startsWith("Person ") && removal.target.endsWith("(2 devices)"))
        assertTrue("no member left: ${room()}", room().members.isEmpty())
        // One Remove names both leaves (the engine oracle shows one Commit); another member's commit may land first.
        log("the person's devices removed: epoch ${room.epoch} to ${room().epoch}")
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

    private fun name() = when (number) { "1" -> "Two-device kitchen"; "2" -> "Two-device porch"; "4" -> "Three-device study"; "5" -> "Three-device attic"; else -> "Two-device hall" }
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
        /** The guest's person on a second device (its persona copied by the script). */
        const val THIRD = "third"
        const val STALE = "stale-after-the-remove"
        const val FRESH = "fresh-after-the-remove"
        /** The lab's grace before a removed device's grant is revoked (the app's is a day). */
        const val REMOVED_GRACE_SECONDS = 30L
        /** Wider than the 30-day lifetimes: a pass renews the credential and grants at once. */
        const val RENEW_NOW_SECONDS = 31L * 86_400
    }
}
