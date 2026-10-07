package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.vmls.LeafBinding
import java.security.SecureRandom
import java.util.Base64
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The vault under the restore-witness coordinator (P3-03b-2), against a
 * Kotlin model of the core and a fake witness with bothy's semantics.
 */
class CoordinatedMlsVaultTest {
    private val now = 1_793_577_600L
    private val principal = "dev.forgesworn.kithmoot"
    private val random = SecureRandom()
    private var clock = now

    private lateinit var stores: MemoryCoordinatedStores
    private lateinit var server: FakeWitnessServer
    private lateinit var witness: FakeVaultWitness
    private lateinit var vault: MlsVault
    private lateinit var alice: Signer
    private lateinit var ctx: VaultContext
    private lateinit var subject: ByteArray
    private val homeBox = bytes(32).toHex()
    private val approve = ConsentPrompt { ConsentDecision.Approve }
    private val deny = ConsentPrompt { ConsentDecision.Deny }

    @BeforeTest fun setup() {
        clock = now
        stores = MemoryCoordinatedStores()
        server = FakeWitnessServer(bytes(32))
        witness = FakeVaultWitness()
        vault = vault()
        alice = Signer()
        ctx = vault.context(principal, alice.pubkey)
        subject = bytes(32)
    }

    private fun vault(on: MemoryCoordinatedStores = stores, channels: WitnessChannels = WitnessChannels { _, _, _ -> server.channel }) =
        MlsVault.coordinated(VaultCoordination(on, channels, witness), now = { clock })

    /** Genesis, the keeper's enrol line run at the box, and "Check now". */
    private suspend fun enrolAtBox(v: MlsVault = vault, persona: String = alice.pubkey, s: ByteArray = subject): CoordinationGenesis {
        val genesis = (v.beginCoordination(persona, s, server.key) as VaultResult.Ok).value
        server.enrol(genesis.subject, genesis.initialDigest)
        assertEquals(CoordinationStatus.Active, v.coordinationStatus(persona, check = true))
        return genesis
    }

    private suspend fun enrolDevice(v: MlsVault = vault, signer: Signer = alice): EnrolledDevice =
        (v.enrol(v.context(principal, signer.pubkey), signer, now + 7 * 86_400) as VaultResult.Ok).value

    // ---- genesis and covered writes ----

    @Test fun `genesis is over the empty persona and the device enrolment is the first witnessed advance`() = runBlocking<Unit> {
        val genesis = enrolAtBox()
        assertEquals(subject.toHex(), genesis.subject)
        assertEquals(64, genesis.installation.length)
        assertEquals(0L, server.subjects.getValue(genesis.subject).seq)
        val device = enrolDevice()
        assertEquals(1L, server.subjects.getValue(genesis.subject).seq)
        assertEquals(device, (vault.device(ctx) as VaultResult.Ok).value)
        // The promoted snapshot carries the signed credential, as the enrolment returned it.
        assertEquals(device.device, (vault.device(ctx) as VaultResult.Ok).value.credential?.tags?.single { it[0] == "device" }?.get(1))
        // No persona in any store name; the persona's installation is its own.
        assertTrue(stores.names().none { alice.pubkey in it })
        assertTrue(genesis.installation != vault.installationId())
        // The marker names the subject, for a later retire line.
        assertTrue(String(stores.marker()!!).contains(genesis.subject))
    }

    @Test fun `a second genesis for an enrolled persona is refused`() = runBlocking<Unit> {
        enrolAtBox()
        assertEquals(refused(VaultRefusal.Unauthorised), vault.beginCoordination(alice.pubkey, bytes(32), server.key))
    }

    @Test fun `before genesis nothing is asked of the signer and nothing is written`() = runBlocking<Unit> {
        assertEquals(CoordinationStatus.NotEnrolled, vault.coordinationStatus(alice.pubkey))
        assertEquals(refused(VaultRefusal.WitnessPending), vault.enrol(ctx, alice, now + 3600))
        assertTrue(alice.signed.isEmpty())
        assertEquals(0, stores.coordinatedWrites)
    }

    @Test fun `until the keeper enrols the subject every covered write holds, refused`() = runBlocking<Unit> {
        vault.beginCoordination(alice.pubkey, subject, server.key)
        assertEquals(CoordinationStatus.Pending(refused = true), vault.coordinationStatus(alice.pubkey, check = true))
        assertEquals(refused(VaultRefusal.WitnessPending), vault.enrol(ctx, alice, now + 3600))
        assertTrue(alice.signed.isEmpty())
    }

    @Test fun `a signature leaves only after its journal entry is promoted`() = runBlocking<Unit> {
        enrolAtBox(); val device = enrolDevice()
        val req = request(device)
        val before = server.advances
        val reply = (vault.signLeafBindingV1(ctx, req, approve) as VaultResult.Ok).value
        assertEquals(before + 1, server.advances)
        assertTrue(Schnorr.verify(reply.signature.hexToBytes(), req.text("digest").hexToBytes(), device.device.hexToBytes()))
        assertEquals(2L, server.subjects.getValue(subject.toHex()).seq)
    }

    @Test fun `a durable denial is witnessed too, and its retry replays the denial`() = runBlocking<Unit> {
        enrolAtBox(); val device = enrolDevice()
        val req = request(device)
        assertEquals(refused(VaultRefusal.Denied), vault.signLeafBindingV1(ctx, req, deny))
        assertEquals(2L, server.subjects.getValue(subject.toHex()).seq)
        assertEquals(refused(VaultRefusal.Denied), vault.signLeafBindingV1(ctx, req, approve))
    }

    @Test fun `an unchanged policy write stages nothing and keeps the sealed bytes`() = runBlocking<Unit> {
        enrolAtBox(); val device = enrolDevice()
        val scope = ConsentScope(principal, alice.pubkey, device.device, homeBox, MlsVault.SIGN_METHOD)
        assertIs<VaultResult.Ok<Unit>>(vault.approve(ctx, scope))
        val advances = server.advances
        val sealed = stores.sealed(stores.coordinatedName())!!.value
        assertIs<VaultResult.Ok<Unit>>(vault.approve(ctx, scope))
        assertEquals(advances, server.advances)
        assertTrue(sealed.contentEquals(stores.sealed(stores.coordinatedName())!!.value))
    }

    // ---- held and pending ----

    @Test fun `with the witness down a signature is held and never released`() = runBlocking<Unit> {
        enrolAtBox(); val device = enrolDevice()
        server.mode = FakeWitnessServer.Mode.Down
        assertEquals(refused(VaultRefusal.WitnessPending), vault.signLeafBindingV1(ctx, request(device), approve))
        assertIs<CoordinationStatus.Pending>(vault.coordinationStatus(alice.pubkey))
        // A read that releases nothing stays available while pending.
        assertEquals(device, (vault.device(ctx) as VaultResult.Ok).value)
    }

    @Test fun `a held candidate is finished before the next write, which is built from the promoted record`() = runBlocking<Unit> {
        enrolAtBox(); val device = enrolDevice()
        val scope = ConsentScope(principal, alice.pubkey, device.device, homeBox, MlsVault.SIGN_METHOD)
        // The witness commits the approval but its answer is lost.
        server.mode = FakeWitnessServer.Mode.LoseAnswer
        assertEquals(refused(VaultRefusal.WitnessPending), vault.approve(ctx, scope))
        assertEquals(2L, server.subjects.getValue(subject.toHex()).seq)
        server.mode = FakeWitnessServer.Mode.Up
        assertIs<VaultResult.Ok<Unit>>(vault.revokeCredential(ctx, bytes(32).toHex()))
        // The held approval was promoted first, then the revocation advanced on it.
        assertEquals(3L, server.subjects.getValue(subject.toHex()).seq)
        var asked = false
        val signed = vault.signLeafBindingV1(ctx, request(device), ConsentPrompt { asked = true; ConsentDecision.Deny })
        assertIs<VaultResult.Ok<SignLeafBindingReply>>(signed)
        assertFalse(asked, "the approval survived as part of the promoted record")
    }

    @Test fun `a held signature's identical retry is answered from the promoted candidate, exactly`() = runBlocking<Unit> {
        enrolAtBox(); val device = enrolDevice()
        val req = request(device)
        server.mode = FakeWitnessServer.Mode.LoseAnswer
        assertEquals(refused(VaultRefusal.WitnessPending), vault.signLeafBindingV1(ctx, req, approve))
        server.mode = FakeWitnessServer.Mode.Up
        val reply = (vault.signLeafBindingV1(ctx, req, ConsentPrompt { error("no new prompt") }) as VaultResult.Ok).value
        assertTrue(Schnorr.verify(reply.signature.hexToBytes(), req.text("digest").hexToBytes(), device.device.hexToBytes()))
        assertEquals(2L, server.subjects.getValue(subject.toHex()).seq)
    }

    @Test fun `a witness signing with another key never confirms`() = runBlocking<Unit> {
        enrolAtBox(); val device = enrolDevice()
        server.mode = FakeWitnessServer.Mode.WrongKey
        val restarted = vault()
        assertEquals(refused(VaultRefusal.WitnessPending), restarted.signLeafBindingV1(restarted.context(principal, alice.pubkey), request(device), approve))
        assertEquals(1L, server.subjects.getValue(subject.toHex()).seq)
    }

    @Test fun `the persona lock is held across the round trip and another persona is not blocked`() = runBlocking<Unit> {
        enrolAtBox(); val device = enrolDevice()
        val bob = Signer()
        val bobSubject = bytes(32)
        enrolAtBox(persona = bob.pubkey, s = bobSubject)
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        var holding = true
        server.during = { if (holding) { holding = false; entered.complete(Unit); gate.await() } }
        val scope = ConsentScope(principal, alice.pubkey, device.device, homeBox, MlsVault.SIGN_METHOD)
        val first = async(Dispatchers.Default) { vault.approve(ctx, scope) }
        withTimeout(5_000) { entered.await() }
        val requests = server.requests
        val second = async(Dispatchers.Default) { vault.revokeCredential(ctx, bytes(32).toHex()) }
        // Bob's persona has its own lock: his enrolment finishes while Alice's trip is out.
        withTimeout(5_000) { enrolDevice(signer = bob) }
        repeat(20) { yield() }
        assertFalse(second.isCompleted)
        val bobRequests = server.requests - requests
        gate.complete(Unit)
        assertIs<VaultResult.Ok<Unit>>(first.await())
        assertIs<VaultResult.Ok<Unit>>(second.await())
        assertEquals(1, bobRequests)
    }

    // ---- renewal ----

    @Test fun `a renewal is a witnessed write that keeps the device and verifies`() = runBlocking<Unit> {
        enrolAtBox(); val device = enrolDevice()
        val before = server.advances
        val until = now + LeafBinding.MAX_PERSON_CREDENTIAL_SECONDS - 300
        val renewed = (vault.renewCredential(ctx, alice, until) as VaultResult.Ok).value
        assertEquals(before + 1, server.advances)
        assertEquals(device.device, renewed.device)
        assertEquals(until, renewed.credentialExpiresAt)
        assertEquals(renewed, (vault.device(ctx) as VaultResult.Ok).value)
        assertEquals(until, LeafBinding.verifyPersonCredential(renewed.credential!!, clock, alice.pubkey).expiresAt)
        // The promoted record still signs for the same device.
        assertIs<VaultResult.Ok<SignLeafBindingReply>>(vault.signLeafBindingV1(ctx, request(renewed), approve))
    }

    @Test fun `a renewal is held while the witness is not Ready, and a fenced profile asks nothing`() = runBlocking<Unit> {
        enrolAtBox(); val device = enrolDevice()
        server.mode = FakeWitnessServer.Mode.Down
        // Held: the credential is not renewed, and the stored one is still served.
        assertEquals(refused(VaultRefusal.WitnessPending), vault.renewCredential(ctx, alice, now + 14 * 86_400))
        assertEquals(device, (vault.device(ctx) as VaultResult.Ok).value)
        server.mode = FakeWitnessServer.Mode.Up
        // Fenced: a clone of the profile after the original advanced.
        val clone = stores.copy()
        assertIs<VaultResult.Ok<SignLeafBindingReply>>(vault.signLeafBindingV1(ctx, request(device), approve))
        val copy = vault(clone)
        val asked = alice.signed.size
        assertEquals(refused(VaultRefusal.RestoreFenced), copy.renewCredential(copy.context(principal, alice.pubkey), alice, now + 14 * 86_400))
        assertEquals(asked, alice.signed.size)
    }

    @Test fun `before genesis a renewal is held, with nothing asked of the signer`() = runBlocking<Unit> {
        assertEquals(refused(VaultRefusal.WitnessPending), vault.renewCredential(ctx, alice, now + 3600))
        assertTrue(alice.signed.isEmpty())
    }

    // ---- fences ----

    @Test fun `a cloned profile is fenced when the original advanced`() = runBlocking<Unit> {
        enrolAtBox(); val device = enrolDevice()
        val clone = stores.copy()
        assertIs<VaultResult.Ok<SignLeafBindingReply>>(vault.signLeafBindingV1(ctx, request(device), approve))
        val copy = vault(clone)
        val c = copy.context(principal, alice.pubkey)
        assertEquals(refused(VaultRefusal.RestoreFenced), copy.signLeafBindingV1(c, request(device), approve))
        assertEquals(CoordinationStatus.Fenced("witness-mismatch", subject.toHex()), copy.coordinationStatus(alice.pubkey))
        assertEquals(refused(VaultRefusal.RestoreFenced), copy.device(c))
        // A fence is terminal: a new vault over the clone stays fenced.
        assertEquals(refused(VaultRefusal.RestoreFenced), vault(clone).freshDevice())
    }

    @Test fun `of two cloned profiles exactly one advance wins and the other fences`() = runBlocking<Unit> {
        enrolAtBox(); val device = enrolDevice()
        val clone = stores.copy()
        val copy = vault(clone)
        val c = copy.context(principal, alice.pubkey)
        assertEquals(device, (copy.device(c) as VaultResult.Ok).value)
        assertIs<VaultResult.Ok<SignLeafBindingReply>>(vault.signLeafBindingV1(ctx, request(device), approve))
        // The clone's coordinator was confirmed before the original advanced; its CAS conflicts.
        assertEquals(refused(VaultRefusal.RestoreFenced), copy.revokeCredential(c, bytes(32).toHex()))
        assertEquals(CoordinationStatus.Fenced("witness-conflict", subject.toHex()), copy.coordinationStatus(alice.pubkey))
        assertIs<VaultResult.Ok<Unit>>(vault.revokeCredential(ctx, bytes(32).toHex()))
    }

    @Test fun `a restored older file fences as missing-seal-key and stays fenced`() = runBlocking<Unit> {
        enrolAtBox(); val device = enrolDevice()
        val name = stores.coordinatedName()
        val older = stores.sealed(name)!!
        assertIs<VaultResult.Ok<SignLeafBindingReply>>(vault.signLeafBindingV1(ctx, request(device), approve))
        val newer = stores.sealed(name)!!
        stores.restore(name, older)
        val restarted = vault()
        val c = restarted.context(principal, alice.pubkey)
        assertEquals(refused(VaultRefusal.RestoreFenced), restarted.device(c))
        assertEquals(CoordinationStatus.Fenced("missing-seal-key", subject.toHex()), restarted.coordinationStatus(alice.pubkey))
        assertTrue(String(stores.marker()!!).contains("\"fenced\""))
        // Putting the newer bytes back does not resume a fence.
        stores.restore(name, newer)
        assertEquals(refused(VaultRefusal.RestoreFenced), vault().freshDevice())
    }

    @Test fun `a failed tag fences, a transient Keystore failure stays unavailable`() = runBlocking<Unit> {
        enrolAtBox(); enrolDevice()
        stores.transient = true
        assertFailsWith<MlsVaultUnavailableException> { vault().freshDevice() }
        stores.transient = false
        assertFalse(String(stores.marker()!!).contains("\"fenced\""))
        stores.badTag = true
        val restarted = vault()
        assertEquals(refused(VaultRefusal.RestoreFenced), restarted.device(restarted.context(principal, alice.pubkey)))
        assertEquals(CoordinationStatus.Fenced("missing-seal-key", subject.toHex()), restarted.coordinationStatus(alice.pubkey))
    }

    @Test fun `a lost inner key fences at open`() = runBlocking<Unit> {
        enrolAtBox(); enrolDevice()
        stores.deleteInner()
        val restarted = vault()
        assertEquals(refused(VaultRefusal.RestoreFenced), restarted.device(restarted.context(principal, alice.pubkey)))
        assertTrue(String(stores.marker()!!).contains("missing-seal-key"))
    }

    @Test fun `a witness behind fences, and its retiring advance retires the subject`() = runBlocking<Unit> {
        enrolAtBox(); enrolDevice()
        // The witness lost state: back at genesis.
        server.subjects.getValue(subject.toHex()).seq = 0
        val restarted = vault()
        assertEquals(CoordinationStatus.Fenced("witness-behind", subject.toHex()), restarted.coordinationStatus(alice.pubkey))
        // The duty ran at open: the subject is retired, the duty stands until replaced.
        assertTrue(server.subjects.getValue(subject.toHex()).retired)
        assertTrue(stores.coordinatedNames().isNotEmpty())
    }

    // ---- a confirmation does not last the process (D1 C2) ----

    @Test fun `a check reads the witness again although the persona is confirmed`() = runBlocking<Unit> {
        enrolAtBox(); enrolDevice()
        assertEquals(CoordinationStatus.Active, vault.coordinationStatus(alice.pubkey))
        val before = server.requests
        // Without a check the answer comes from memory.
        assertEquals(CoordinationStatus.Active, vault.coordinationStatus(alice.pubkey))
        assertEquals(before, server.requests)
        assertEquals(CoordinationStatus.Active, vault.coordinationStatus(alice.pubkey, check = true))
        assertEquals(before + 1, server.requests)
    }

    @Test fun `a check after the witness retired the subject fences, where memory still said active`() = runBlocking<Unit> {
        enrolAtBox(); enrolDevice()
        server.subjects.getValue(subject.toHex()).retired = true
        assertEquals(CoordinationStatus.Active, vault.coordinationStatus(alice.pubkey))
        assertEquals(CoordinationStatus.Fenced("witness-retired", subject.toHex()), vault.coordinationStatus(alice.pubkey, check = true))
        // A fence is terminal.
        assertEquals(CoordinationStatus.Fenced("witness-retired", subject.toHex()), vault.coordinationStatus(alice.pubkey))
    }

    @Test fun `a check after another writer advanced the subject fences as a conflict`() = runBlocking<Unit> {
        enrolAtBox(); enrolDevice()
        val sub = server.subjects.getValue(subject.toHex())
        sub.seq += 1; sub.digest = bytes(32)
        val status = vault.coordinationStatus(alice.pubkey, check = true)
        assertIs<CoordinationStatus.Fenced>(status)
    }

    @Test fun `a check the witness cannot answer keeps the state, fences nothing and the next write reads again`() = runBlocking<Unit> {
        enrolAtBox(); val device = enrolDevice()
        server.mode = FakeWitnessServer.Mode.Down
        assertEquals(CoordinationStatus.Active, vault.coordinationStatus(alice.pubkey, check = true))
        // Nothing is released on the strength of a confirmation that was not renewed.
        assertEquals(refused(VaultRefusal.WitnessPending), vault.signLeafBindingV1(ctx, request(device), approve))
        server.mode = FakeWitnessServer.Mode.Up
        assertEquals(CoordinationStatus.Active, vault.coordinationStatus(alice.pubkey, check = true))
        assertIs<VaultResult.Ok<SignLeafBindingReply>>(vault.signLeafBindingV1(ctx, request(device), approve))
    }

    // ---- clear and missing stores ----

    @Test fun `witness-pending is Kotlin-only and the ten section 6_2 strings are unchanged`() {
        assertEquals(null, VaultRefusal.WitnessPending.wire)
        assertEquals(
            listOf("unsupported", "malformed", "unauthorised", "expired", "revoked", "denied", "busy", "stale", "replay", "restore-fenced"),
            VaultRefusal.entries.mapNotNull { it.wire },
        )
    }

    @Test fun `a coordinated vault never touches the uncoordinated persona store`() = runBlocking<Unit> {
        enrolAtBox(); val device = enrolDevice()
        assertEquals(refused(VaultRefusal.Denied), vault.signLeafBindingV1(ctx, request(device), deny))
        assertIs<VaultResult.Ok<SignLeafBindingReply>>(vault.signLeafBindingV1(ctx, request(device), approve))
        server.mode = FakeWitnessServer.Mode.Down
        assertEquals(refused(VaultRefusal.WitnessPending), vault.signLeafBindingV1(ctx, request(device), approve))
        vault.clear(alice.pubkey)
        assertEquals(emptyList(), stores.names().filter { it.startsWith("persona.") })
        assertEquals(setOf(MlsVault.INSTALLATION, MlsVault.EPOCH, MlsVault.INDEX), stores.names().toSet())
        // So an uncoordinated vault over the same stores finds nothing to sign with.
        val legacy = MlsVault(stores, now = { clock })
        assertEquals(refused(VaultRefusal.Unauthorised), legacy.device(legacy.context(principal, alice.pubkey)))
    }

    @Test fun `clear with no duty standing deletes the state but keeps the subject for the retire line`() = runBlocking<Unit> {
        enrolAtBox(); enrolDevice()
        vault.clear(alice.pubkey)
        assertEquals(listOf(stores.coordinatedName()), stores.coordinatedNames())
        assertTrue(stores.names().none { it.startsWith("coord.") })
        assertEquals(CoordinationStatus.Fenced("cleared", subject.toHex()), vault.coordinationStatus(alice.pubkey))
        assertEquals(refused(VaultRefusal.Stale), vault.device(ctx))
        // No re-enrolment until the keeper confirms the old subject was retired.
        assertEquals(refused(VaultRefusal.Unauthorised), vault.beginCoordination(alice.pubkey, bytes(32), server.key))
        assertEquals(refused(VaultRefusal.Unauthorised), vault.prepareCoordination(alice.pubkey))
        assertEquals(refused(VaultRefusal.Unauthorised), vault.keeperConfirmsRetired(alice.pubkey, bytes(32).toHex()))
        assertIs<VaultResult.Ok<Unit>>(vault.keeperConfirmsRetired(alice.pubkey, subject.toHex()))
        assertEquals(CoordinationStatus.NotEnrolled, vault.coordinationStatus(alice.pubkey))
        // Retired ids are tombstones: the old subject is never enrolled again.
        assertEquals(refused(VaultRefusal.Unauthorised), vault.beginCoordination(alice.pubkey, subject, server.key))
        val again = enrolAtBox(s = bytes(32))
        assertTrue(again.subject != subject.toHex())
        assertTrue(String(stores.marker()!!).contains(subject.toHex()))
    }

    @Test fun `a clear interrupted after its marker fence finishes the clear, never resumes`() = runBlocking<Unit> {
        enrolAtBox(); val device = enrolDevice()
        // The crash point: the marker is fenced `cleared`, the file still healthy.
        val name = stores.coordinatedName()
        val marker = Marker.decode(stores.marker(name).read()!!)
        stores.marker(name).write(marker.copy(state = Marker.State.Fenced, reason = "cleared").encode())
        val restarted = vault()
        val c = restarted.context(principal, alice.pubkey)
        assertEquals(refused(VaultRefusal.RestoreFenced), restarted.signLeafBindingV1(c, request(device), approve))
        assertEquals(CoordinationStatus.Fenced("cleared", subject.toHex()), restarted.coordinationStatus(alice.pubkey))
        assertTrue(stores.names().none { it.startsWith("coord.") })
    }

    @Test fun `a keeper's confirmation is refused while a retiring duty stands`() = runBlocking<Unit> {
        enrolAtBox(); enrolDevice()
        server.subjects.getValue(subject.toHex()).seq = 0
        vault().coordinationStatus(alice.pubkey)
        server.mode = FakeWitnessServer.Mode.Down
        vault().clear(alice.pubkey)
        assertEquals(refused(VaultRefusal.Unauthorised), vault().keeperConfirmsRetired(alice.pubkey, subject.toHex()))
        assertTrue(stores.names().any { it.startsWith("coord.") })
    }

    @Test fun `an interrupted genesis is retried with a fresh installation and never reuses its subject`() = runBlocking<Unit> {
        val prepared = (vault.prepareCoordination(alice.pubkey) as VaultResult.Ok).value
        stores.failNextCoordinatedWrite = true
        assertFailsWith<MlsVaultUnavailableException> { vault.beginCoordination(alice.pubkey, subject, server.key) }
        assertEquals(CoordinationStatus.NotEnrolled, vault().coordinationStatus(alice.pubkey))
        val v = vault()
        assertEquals(refused(VaultRefusal.Unauthorised), v.beginCoordination(alice.pubkey, subject, server.key))
        val again = enrolAtBox(v, s = bytes(32))
        assertTrue(again.installation != prepared)
        enrolDevice(v)
    }

    @Test fun `no unsealed marker names the persona, and a lost index fails closed`() = runBlocking<Unit> {
        enrolAtBox(); enrolDevice()
        assertFalse(String(stores.marker()!!).contains(alice.pubkey))
        assertTrue(stores.aads().any { "coord-index" in it })
        stores.remove(MlsVault.INDEX)
        val failures = vault().runRetiringDuties()
        assertEquals(setOf(stores.coordinatedName()), failures.keys)
        assertEquals(subject.toHex(), failures.values.single().subject)
        // Opening the persona re-indexes it; nothing started afresh.
        assertEquals(CoordinationStatus.Active, vault().coordinationStatus(alice.pubkey, check = true))
        assertEquals(emptyMap(), vault().runRetiringDuties())
    }

    @Test fun `a transient failure leaves the index intact, definitive corruption resets it`() = runBlocking<Unit> {
        enrolAtBox(); enrolDevice()
        val bob = Signer()
        stores.transient = true
        assertFailsWith<MlsVaultUnavailableException> { vault.coordinationStatus(bob.pubkey) }
        stores.transient = false
        assertEquals(emptyMap(), vault().runRetiringDuties())
        stores.corrupt(MlsVault.INDEX)
        assertEquals(CoordinationStatus.NotEnrolled, vault.coordinationStatus(bob.pubkey))
        val failures = vault().runRetiringDuties()
        assertEquals(setOf(stores.coordinatedName()), failures.keys)
        assertEquals(subject.toHex(), failures.values.single().subject)
    }

    @Test fun `the marker keeps the most recent tombstones within its bound`() {
        val retired = (1..200).map { Marker.Tombstone("%064x".format(it), "%064x".format(it + 1000)) }
        val encoded = Marker(Marker.State.Superseded, null, "a".repeat(64), "b".repeat(64), "c".repeat(64), retired).encode()
        assertTrue(encoded.size <= Marker.MAX_BYTES)
        val decoded = Marker.decode(encoded)
        assertEquals(Marker.MAX_TOMBSTONES, decoded.retired.size)
        assertEquals(retired.takeLast(Marker.MAX_TOMBSTONES), decoded.retired)
    }

    @Test fun `a healthy persona cannot be superseded without a clear`() = runBlocking<Unit> {
        enrolAtBox(); enrolDevice()
        assertEquals(refused(VaultRefusal.Unauthorised), vault.keeperConfirmsRetired(alice.pubkey, subject.toHex()))
        assertEquals(CoordinationStatus.Active, vault.coordinationStatus(alice.pubkey))
    }

    @Test fun `clear keeps the state, seed and route while a retiring duty stands, until a signed retired`() = runBlocking<Unit> {
        enrolAtBox(); enrolDevice()
        server.subjects.getValue(subject.toHex()).seq = 0
        val restarted = vault()
        assertEquals(CoordinationStatus.Fenced("witness-behind", subject.toHex()), restarted.coordinationStatus(alice.pubkey))
        server.mode = FakeWitnessServer.Mode.Down
        restarted.clear(alice.pubkey)
        assertTrue(stores.names().any { it.startsWith("coord.") })
        assertEquals(CoordinationStatus.Fenced("witness-behind", subject.toHex()), vault().coordinationStatus(alice.pubkey))
        assertEquals(refused(VaultRefusal.Unauthorised), vault().beginCoordination(alice.pubkey, bytes(32), server.key))
        server.mode = FakeWitnessServer.Mode.Up
        // Duties run over every coordinated file on disk, found by its marker.
        assertEquals(emptyMap(), vault().runRetiringDuties())
        assertTrue(stores.names().none { it.startsWith("coord.") })
        assertEquals(CoordinationStatus.NotEnrolled, vault().coordinationStatus(alice.pubkey))
        enrolAtBox(vault(), s = bytes(32))
    }

    @Test fun `retiring duties carry on past a persona that fails`() = runBlocking<Unit> {
        enrolAtBox(); enrolDevice()
        server.subjects.getValue(subject.toHex()).seq = 0
        vault().coordinationStatus(alice.pubkey)
        server.mode = FakeWitnessServer.Mode.Down
        vault().clear(alice.pubkey)
        val junk = "coord." + "0".repeat(32)
        stores.marker(junk).write("not a marker".toByteArray())
        server.mode = FakeWitnessServer.Mode.Up
        val failures = vault().runRetiringDuties()
        assertEquals(setOf(junk), failures.keys)
        assertTrue(stores.names().none { it.startsWith("coord.") })
    }

    @Test fun `a missing vault installation store with coordinated files present is unavailable, never a fresh id`() = runBlocking<Unit> {
        enrolAtBox(); enrolDevice()
        stores.remove(MlsVault.INSTALLATION)
        assertFailsWith<MlsVaultUnavailableException> { vault().freshDevice() }
        assertTrue(MlsVault.INSTALLATION !in stores.names())
    }

    @Test fun `a missing file with its marker present is fenced, never a fresh start`() = runBlocking<Unit> {
        enrolAtBox(); enrolDevice()
        stores.remove(stores.coordinatedName())
        assertEquals(refused(VaultRefusal.RestoreFenced), vault().freshDevice())
        assertEquals(CoordinationStatus.Fenced("missing-file", subject.toHex()), vault().coordinationStatus(alice.pubkey))
        vault().clear(alice.pubkey)
        assertEquals(CoordinationStatus.Fenced("missing-file", subject.toHex()), vault().coordinationStatus(alice.pubkey))
        assertEquals(refused(VaultRefusal.Unauthorised), vault().beginCoordination(alice.pubkey, bytes(32), server.key))
    }

    @Test fun `without a witness engine nothing is written`() {
        val none = object : VaultWitness {
            override fun objectHash(sealed: ByteArray): ByteArray = throw VaultWitnessUnavailableException()
            override fun genesis(subject: ByteArray, installation: ByteArray, witnessKey: ByteArray, active: List<CoordEntry>): CoordGenesis = throw VaultWitnessUnavailableException()
            override fun open(state: ByteArray, active: List<CoordEntry>, staged: List<CoordEntry>?): WitnessCoordinator = throw VaultWitnessUnavailableException()
        }
        val v = MlsVault.coordinated(VaultCoordination(stores, { _, _, _ -> server.channel }, none), now = { clock })
        assertFailsWith<MlsVaultUnavailableException> { runBlocking { v.beginCoordination(alice.pubkey, subject, server.key) } }
        assertEquals(refused(VaultRefusal.WitnessPending), runBlocking { v.enrol(v.context(principal, alice.pubkey), alice, now + 3600) })
        assertTrue(alice.signed.isEmpty())
    }

    // ---- restart signing: the session epoch ----

    @Test fun `an identical signing retry after a restart replays under the same epoch`() = runBlocking<Unit> {
        enrolAtBox(); val device = enrolDevice()
        val req = request(device)
        val first = (vault.signLeafBindingV1(ctx, req, approve) as VaultResult.Ok).value
        val restarted = vault()
        val again = restarted.context(principal, alice.pubkey)
        // The old process's context is gone with it.
        assertEquals(refused(VaultRefusal.Stale), restarted.signLeafBindingV1(ctx, req, approve))
        val replayed = (restarted.signLeafBindingV1(again, req, ConsentPrompt { error("no prompt on replay") }) as VaultResult.Ok).value
        assertEquals(first.signature, replayed.signature)
        assertIs<VaultResult.Ok<SignLeafBindingReply>>(restarted.acceptSignReply(req, replayed))
    }

    @Test fun `a retry after a logout does not replay, before or after a restart`() = runBlocking<Unit> {
        enrolAtBox(); val device = enrolDevice()
        val req = request(device)
        assertIs<VaultResult.Ok<SignLeafBindingReply>>(vault.signLeafBindingV1(ctx, req, approve))
        vault.bump()
        assertEquals(refused(VaultRefusal.Stale), vault.signLeafBindingV1(vault.context(principal, alice.pubkey), req, approve))
        val restarted = vault()
        assertEquals(refused(VaultRefusal.Stale), restarted.signLeafBindingV1(restarted.context(principal, alice.pubkey), req, approve))
    }

    @Test fun `a missing or corrupt epoch store fails safe`() = runBlocking<Unit> {
        enrolAtBox(); val device = enrolDevice()
        val req = request(device)
        assertIs<VaultResult.Ok<SignLeafBindingReply>>(vault.signLeafBindingV1(ctx, req, approve))
        stores.remove(MlsVault.EPOCH)
        val restarted = vault()
        assertEquals(refused(VaultRefusal.Stale), restarted.signLeafBindingV1(restarted.context(principal, alice.pubkey), req, approve))
        val req2 = request(device)
        val v2 = vault()
        assertIs<VaultResult.Ok<SignLeafBindingReply>>(v2.signLeafBindingV1(v2.context(principal, alice.pubkey), req2, approve))
        stores.corrupt(MlsVault.EPOCH)
        val v3 = vault()
        assertEquals(refused(VaultRefusal.Stale), v3.signLeafBindingV1(v3.context(principal, alice.pubkey), req2, approve))
    }

    @Test fun `ECDH keeps the per-process generation, so a restart makes it stale`() = runBlocking<Unit> {
        enrolAtBox()
        val rz = secret()
        val child = StoredRendezvousChild(RendezvousReceipt(alice.pubkey, "b".repeat(64), Schnorr.publicKeyHex(rz), 1, now + 3600), rz.copyOf())
        val req = buildJsonObject { put("v", 1); put("operation", bytes(32).toHex()); put("peer_rz", Schnorr.publicKeyHex(secret())); put("expires_at", now + 300) }
        val reply = (vault.rendezvousEcdhV1(ctx, req) { child } as VaultResult.Ok).value
        val restarted = vault()
        assertEquals(refused(VaultRefusal.Stale), restarted.rendezvousEcdhV1(ctx, req) { error("not read") })
        assertEquals(refused(VaultRefusal.Unauthorised), restarted.acceptEcdhReply(req, reply))
    }

    @Test fun `a logout writes the new epoch before it returns`() = runBlocking<Unit> {
        enrolAtBox()
        val before = assertNotNull(stores.sealed(MlsVault.EPOCH))
        vault.bump()
        val after = assertNotNull(stores.sealed(MlsVault.EPOCH))
        assertFalse(before.value.contentEquals(after.value))
    }

    @Test fun `a sign blocked in consent is stale once the session ends, and nothing is signed or journalled`() = runBlocking<Unit> {
        enrolAtBox(); val device = enrolDevice()
        val req = request(device)
        val end = VaultSessionEnd { vault.bump() }
        val blocked = vault.signLeafBindingV1(ctx, req, ConsentPrompt { end.end(); ConsentDecision.Approve })
        assertEquals(refused(VaultRefusal.Stale), blocked)
        assertFalse(vault.isCurrent(ctx))
        // Nothing was decided, so the next session's retry asks afresh.
        var asked = 0
        val retry = vault.signLeafBindingV1(vault.context(principal, alice.pubkey), req, ConsentPrompt { asked++; ConsentDecision.Approve })
        assertIs<VaultResult.Ok<SignLeafBindingReply>>(retry)
        assertEquals(1, asked)
    }

    @Test fun `a decision journalled before the session ended is stale afterwards, however it is retried`() = runBlocking<Unit> {
        enrolAtBox(); val device = enrolDevice()
        val req = request(device)
        val first = vault.signLeafBindingV1(ctx, req, approve) as VaultResult.Ok
        VaultSessionEnd { vault.bump() }.end()
        // The earlier context, and a fresh one, both meet a stale decision: never a replay of the signature.
        assertEquals(refused(VaultRefusal.Stale), vault.signLeafBindingV1(ctx, req, ConsentPrompt { error("no prompt") }))
        assertEquals(refused(VaultRefusal.Stale), vault.signLeafBindingV1(vault.context(principal, alice.pubkey), req, ConsentPrompt { error("no prompt") }))
        assertEquals(refused(VaultRefusal.Stale), vault.acceptSignReply(req, first.value))
    }

    // ---- helpers ----

    private fun request(device: EnrolledDevice, operation: String = bytes(32).toHex()): JsonObject {
        val credential = alice.signed.last()
        val body = VmlsEncode.unsignedBinding(bytes(32), bytes(32), credential, device.device, now + 86_400, homeBox.hexToBytes())
        return buildJsonObject {
            put("v", 1)
            put("operation", operation)
            put("body", Base64.getEncoder().encodeToString(body))
            put("digest", LeafBinding.digest(body).toHex())
            put("expires_at", now + 300)
        }
    }

    private fun refused(refusal: VaultRefusal) = VaultResult.Refused(refusal)
    private suspend fun MlsVault.freshDevice() = device(context(principal, alice.pubkey))
    private fun bytes(n: Int) = ByteArray(n).also(random::nextBytes)
    private fun secret(): ByteArray { while (true) { val k = bytes(32); if (runCatching { Schnorr.publicKey(k) }.isSuccess) return k } }
    private fun JsonObject.text(name: String) = (getValue(name) as JsonPrimitive).content

    private inner class Signer : ParticipantSigner {
        private val local = LocalSigner(secret())
        val signed = mutableListOf<NostrEvent>()
        override val pubkey: String = local.pubkey
        override val method: String get() = "local"
        override suspend fun sign(kind: Int, createdAt: Long, tags: List<List<String>>, content: String): NostrEvent =
            local.sign(kind, createdAt, tags, content).also { signed += it }
        override suspend fun nip44Encrypt(peer: String, plaintext: String): String = error("unused")
        override suspend fun nip44Decrypt(peer: String, payload: String): String = error("unused")
    }
}
