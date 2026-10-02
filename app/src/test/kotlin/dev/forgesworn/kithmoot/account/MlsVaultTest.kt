package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.createDeviceCredential
import dev.forgesworn.kithmoot.storage.RoomStorage
import dev.forgesworn.kithmoot.vmls.LeafBinding
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class MlsVaultTest {
    private val now = 1_793_577_600L
    private val principal = "dev.forgesworn.kithmoot"
    private val random = SecureRandom()

    private var clock = now
    private lateinit var stores: MemoryStores
    private lateinit var vault: MlsVault
    private lateinit var alice: RecordingSigner
    private lateinit var ctx: VaultContext
    private lateinit var device: String
    private lateinit var credential: NostrEvent
    private val homeBox = bytes(32).toHex()
    private val approve = ConsentPrompt { ConsentDecision.Approve }
    private val deny = ConsentPrompt { ConsentDecision.Deny }

    @BeforeTest fun enrolAlice() = runBlocking {
        clock = now
        stores = MemoryStores()
        vault = MlsVault(stores, now = { clock })
        alice = RecordingSigner()
        ctx = vault.context(principal, alice.pubkey)
        val enrolled = vault.enrol(ctx, alice, now + 7 * 86_400)
        device = (enrolled as VaultResult.Ok).value.device
        credential = alice.signed.single()
    }

    // ---- enrolment and storage ----

    @Test fun `enrols a device under a person credential and keeps no persona in store names`() = runBlocking {
        assertTrue(listOf("scope", "person") in credential.tags)
        assertTrue(listOf("device", device) in credential.tags)
        assertTrue(listOf("d", alice.pubkey) in credential.tags)
        assertTrue(stores.names().none { alice.pubkey in it || device in it })
        // Every persona value is bound to the persona and installation.
        val installation = vault.installationId()
        val bound = stores.aads().filter { "|persona|" in it }
        assertTrue(bound.isNotEmpty() && bound.all { alice.pubkey in it && installation in it })
        val shown = vault.device(ctx) as VaultResult.Ok
        assertEquals(device, shown.value.device)
        assertEquals(alice.pubkey, shown.value.persona)
    }

    @Test fun `replaces an enrolled device only when asked to`() = runBlocking {
        assertEquals(refused(VaultRefusal.Unauthorised), vault.enrol(ctx, alice, now + 3600))
        val replaced = vault.enrol(ctx, alice, now + 3600, replace = true) as VaultResult.Ok
        assertNotEquals(device, replaced.value.device)
    }

    @Test fun `refuses enrolment for another persona than the signer`() = runBlocking {
        assertEquals(refused(VaultRefusal.Unauthorised), vault.enrol(ctx, RecordingSigner(), now + 3600))
    }

    @Test fun `refuses a credential the signer altered`() = runBlocking {
        val v = MlsVault(MemoryStores(), now = { clock })
        val sly = RecordingSigner(alterTags = { tags -> tags.filterNot { it.first() == "scope" } })
        assertEquals(refused(VaultRefusal.Denied), v.enrol(v.context(principal, sly.pubkey), sly, now + 3600))
    }

    @Test fun `fails closed on a corrupt record, never a fallback`() {
        stores.corruptAll()
        assertFailsWith<MlsVaultUnavailableException> { runBlocking { vault.signLeafBindingV1(ctx, request(), approve) } }
    }

    @Test fun `a record moved to another persona's store does not open`() {
        val bob = RecordingSigner()
        runBlocking { vault.enrol(vault.context(principal, bob.pubkey), bob, now + 3600) }
        stores.swapPersonaRecords()
        assertFailsWith<MlsVaultUnavailableException> { runBlocking { vault.device(ctx) } }
    }

    // ---- signLeafBindingV1 ----

    @Test fun `signs a checked body once consent is given, and the signature verifies`() = runBlocking {
        val req = request()
        val reply = (vault.signLeafBindingV1(ctx, req, approve) as VaultResult.Ok).value
        assertEquals(req.text("operation"), reply.operation)
        assertEquals(req.text("digest"), reply.digest)
        assertEquals(device, reply.device)
        assertTrue(Schnorr.verify(reply.signature.hexToBytes(), req.text("digest").hexToBytes(), device.hexToBytes()))
        assertTrue(vault.acceptSignReply(req, reply) is VaultResult.Ok)
    }

    @Test fun `replays an identical retry without asking again, and refuses a changed one`() = runBlocking {
        val req = request()
        val first = (vault.signLeafBindingV1(ctx, req, approve) as VaultResult.Ok).value
        var asked = 0
        val again = (vault.signLeafBindingV1(ctx, JsonObject(req.toMap()), ConsentPrompt { asked++; ConsentDecision.Approve }) as VaultResult.Ok).value
        assertEquals(0, asked)
        assertEquals(first.signature, again.signature)
        assertEquals(refused(VaultRefusal.Replay), vault.signLeafBindingV1(ctx, request(operation = req.text("operation")), approve))
    }

    @Test fun `S18 refuses a room credential used as a person credential, before asking`() = runBlocking {
        val room = createDeviceCredential(alice.secret, device, "a".repeat(64), now + 3600, createdAt = now)
        var asked = false
        val result = vault.signLeafBindingV1(ctx, request(credential = room), ConsentPrompt { asked = true; ConsentDecision.Approve })
        assertEquals(refused(VaultRefusal.Unauthorised), result)
        assertFalse(asked)
    }

    @Test fun `S19 refuses another device key or another person`() = runBlocking {
        val other = Schnorr.publicKeyHex(secret())
        assertEquals(refused(VaultRefusal.Unauthorised), vault.signLeafBindingV1(ctx, request(device = other), approve))
        val bob = RecordingSigner()
        val bobs = bob.sign(20460, now, listOf(listOf("d", bob.pubkey), listOf("device", device), listOf("expiration", "${now + 3600}"), listOf("scope", "person")), "")
        assertEquals(refused(VaultRefusal.Unauthorised), vault.signLeafBindingV1(ctx, request(credential = bobs), approve))
    }

    @Test fun `S20 no generic digest - a mismatched digest, a signed binding, extra fields or another version`() = runBlocking {
        val req = request()
        assertEquals(refused(VaultRefusal.Malformed), vault.signLeafBindingV1(ctx, req.with("digest", JsonPrimitive(bytes(32).toHex())), approve))
        val body = Base64.getDecoder().decode(req.text("body"))
        val signed = byteArrayOf(0xa8.toByte()) + body.copyOfRange(1, body.size) + byteArrayOf(0x08, 0x58, 0x40) + ByteArray(64)
        val signedReq = req.with("operation", JsonPrimitive(bytes(32).toHex())).with("body", JsonPrimitive(Base64.getEncoder().encodeToString(signed)))
        assertEquals(refused(VaultRefusal.Malformed), vault.signLeafBindingV1(ctx, signedReq, approve))
        assertEquals(refused(VaultRefusal.Malformed), vault.signLeafBindingV1(ctx, req.with("extra", JsonPrimitive(1)), approve))
        assertEquals(refused(VaultRefusal.Unsupported), vault.signLeafBindingV1(ctx, req.with("v", JsonPrimitive(2)), approve))
        assertEquals(refused(VaultRefusal.Unsupported), vault.signLeafBindingV1(ctx, req.with("v", JsonPrimitive("1")), approve))
        assertEquals(refused(VaultRefusal.Malformed), vault.signLeafBindingV1(ctx, req.with("body", JsonPrimitive(req.text("body").trimEnd('='))), approve))
        assertEquals(refused(VaultRefusal.Malformed), vault.signLeafBindingV1(ctx, req.with("expires_at", JsonPrimitive("${now + 300}")), approve))
        assertEquals(refused(VaultRefusal.Malformed), vault.signLeafBindingV1(ctx, req.with("operation", JsonPrimitive(bytes(32).toHex().uppercase())), approve))
    }

    @Test fun `bounds the operation deadline inclusively`() = runBlocking {
        assertTrue(vault.signLeafBindingV1(ctx, request(deadline = now + 600), approve) is VaultResult.Ok)
        assertEquals(refused(VaultRefusal.Malformed), vault.signLeafBindingV1(ctx, request(deadline = now + 601), approve))
        assertEquals(refused(VaultRefusal.Expired), vault.signLeafBindingV1(ctx, request(deadline = now - 1), approve))
    }

    @Test fun `S21 a new home box needs new consent, and an approved scope is not asked again`() = runBlocking {
        vault.approve(ctx, scopeFor())
        var asked = 0
        val prompt = ConsentPrompt { asked++; ConsentDecision.Deny }
        assertTrue(vault.signLeafBindingV1(ctx, request(), prompt) is VaultResult.Ok)
        assertEquals(0, asked)
        assertEquals(refused(VaultRefusal.Denied), vault.signLeafBindingV1(ctx, request(homeBox = bytes(32).toHex()), prompt))
        assertEquals(1, asked)
        // A withdrawn approval asks again.
        vault.withdraw(ctx, scopeFor())
        assertEquals(refused(VaultRefusal.Denied), vault.signLeafBindingV1(ctx, request(), prompt))
        assertEquals(2, asked)
    }

    @Test fun `S22 success, then revocation, then an identical retry is refused`() = runBlocking {
        val req = request()
        assertTrue(vault.signLeafBindingV1(ctx, req, approve) is VaultResult.Ok)
        val id = (vault.device(ctx) as VaultResult.Ok).value.credentialId
        vault.revokeCredential(ctx, id)
        assertEquals(refused(VaultRefusal.Revoked), vault.signLeafBindingV1(ctx, req, approve))
    }

    @Test fun `S22 a cached success refuses once its deadline passes`() = runBlocking {
        val req = request()
        assertTrue(vault.signLeafBindingV1(ctx, req, approve) is VaultResult.Ok)
        clock = req.long("expires_at") + 1
        assertEquals(refused(VaultRefusal.Expired), vault.signLeafBindingV1(ctx, req, approve))
    }

    @Test fun `S23 a denial stays denied on retry, and a new operation can be approved`() = runBlocking {
        val req = request()
        assertEquals(refused(VaultRefusal.Denied), vault.signLeafBindingV1(ctx, req, deny))
        var asked = false
        assertEquals(refused(VaultRefusal.Denied), vault.signLeafBindingV1(ctx, req, ConsentPrompt { asked = true; ConsentDecision.Approve }))
        assertFalse(asked)
        assertTrue(vault.signLeafBindingV1(ctx, request(), approve) is VaultResult.Ok)
    }

    @Test fun `a prompt that throws is a denial`() = runBlocking {
        assertEquals(refused(VaultRefusal.Denied), vault.signLeafBindingV1(ctx, request(), ConsentPrompt { error("gone") }))
    }

    @Test fun `S25 and E06 a persona switch during consent makes the operation and its replies stale`() = runBlocking {
        val before = request()
        val earlier = (vault.signLeafBindingV1(ctx, before, approve) as VaultResult.Ok).value
        // A new home box, so the prompt is asked; the switch happens during it.
        val result = vault.signLeafBindingV1(ctx, request(homeBox = bytes(32).toHex()), ConsentPrompt { vault.bump(); ConsentDecision.Approve })
        assertEquals(refused(VaultRefusal.Stale), result)
        assertEquals(refused(VaultRefusal.Stale), vault.acceptSignReply(before, earlier))
        assertEquals(refused(VaultRefusal.Stale), vault.signLeafBindingV1(ctx, request(), approve))
        val fresh = vault.context(principal, alice.pubkey)
        assertTrue(vault.signLeafBindingV1(fresh, request(), approve) is VaultResult.Ok)
        // A retry of the earlier operation from the new generation is stale,
        // not a replay of a signature made under the old one.
        assertEquals(refused(VaultRefusal.Stale), vault.signLeafBindingV1(fresh, before, approve))
    }

    @Test fun `a restart makes an earlier operation stale, never a replay`() = runBlocking {
        val req = request()
        assertTrue(vault.signLeafBindingV1(ctx, req, approve) is VaultResult.Ok)
        val restarted = MlsVault(stores, now = { clock })
        val again = restarted.context(principal, alice.pubkey)
        assertEquals(refused(VaultRefusal.Stale), restarted.signLeafBindingV1(again, req, approve))
        assertTrue(restarted.signLeafBindingV1(again, request(), approve) is VaultResult.Ok)
    }

    @Test fun `refuses a reply it did not make, or one for another request`() = runBlocking {
        val req = request()
        val reply = (vault.signLeafBindingV1(ctx, req, approve) as VaultResult.Ok).value
        val copy = SignLeafBindingReply(reply.operation, reply.digest, reply.device, reply.signature)
        assertEquals(refused(VaultRefusal.Unauthorised), vault.acceptSignReply(req, copy))
        assertEquals(refused(VaultRefusal.Replay), vault.acceptSignReply(request(), reply))
        assertEquals(refused(VaultRefusal.Unauthorised), MlsVault(stores, now = { clock }).acceptSignReply(req, reply))
    }

    @Test fun `caps live journal records at 1,024 with busy, never evicting`() = runBlocking {
        vault.approve(ctx, scopeFor())
        repeat(MlsVault.MAX_JOURNAL_RECORDS) { i ->
            val result = vault.signLeafBindingV1(ctx, request(), approve)
            assertTrue(result is VaultResult.Ok, "record $i: $result")
        }
        assertEquals(refused(VaultRefusal.Busy), vault.signLeafBindingV1(ctx, request(), approve))
        clock = now + 301
        assertTrue(vault.signLeafBindingV1(ctx, request(deadline = now + 600), approve) is VaultResult.Ok)
    }

    // ---- one writer (S24) ----

    @Test fun `S24 two vault objects over one store sign one operation once`() = runBlocking {
        val second = MlsVault(stores, now = { clock })
        val req = request()
        vault.approve(ctx, scopeFor())
        val results = listOf(vault to ctx, second to second.context(principal, alice.pubkey)).map { (v, c) ->
            async(Dispatchers.Default) { v.signLeafBindingV1(c, req, approve) }
        }.awaitAll()
        // One signs; the other finds its journal entry. Two signatures would
        // differ (BIP-340 auxiliary randomness), and the second vault's
        // generation differs, so it is refused stale rather than replayed.
        val ok = results.filterIsInstance<VaultResult.Ok<SignLeafBindingReply>>()
        assertEquals(1, ok.size, "$results")
        assertEquals(refused(VaultRefusal.Stale), results.single { it !is VaultResult.Ok })
    }

    @Test fun `S24 a vault waits while another holds the lock`() = runBlocking {
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = async(Dispatchers.Default) {
            MlsVaultLocks.named(stores.lockName).lock()
            held.complete(Unit)
            release.await()
            MlsVaultLocks.named(stores.lockName).unlock()
        }
        held.await()
        val second = MlsVault(stores, now = { clock })
        val waiting = async(Dispatchers.Default) { second.device(second.context(principal, alice.pubkey)) }
        delay(200)
        assertFalse(waiting.isCompleted)
        release.complete(Unit)
        holder.await()
        assertTrue(waiting.await() is VaultResult.Ok)
    }

    // ---- rendezvousEcdhV1 ----

    private val rzSecret = secret()
    private val ownRz = Schnorr.publicKeyHex(rzSecret)
    private val peerSecret = secret()
    private val peerRz = Schnorr.publicKeyHex(peerSecret)

    private fun child(identity: String): suspend () -> StoredRendezvousChild? = {
        StoredRendezvousChild(RendezvousReceipt(identity, "b".repeat(64), ownRz, 1, now + 3600), rzSecret.copyOf())
    }

    private fun ecdh(peer: String = peerRz, deadline: Long = now + 300) = buildJsonObject {
        put("v", 1); put("operation", bytes(32).toHex()); put("peer_rz", peer); put("expires_at", deadline)
    }

    @Test fun `computes the shared x-coordinate with the provisioned child`() = runBlocking {
        val req = ecdh()
        val reply = (vault.rendezvousEcdhV1(ctx, req, child(alice.pubkey)) as VaultResult.Ok).value
        assertEquals(Schnorr.sharedPointX(peerSecret, ownRz.hexToBytes()).toHex(), reply.sharedX)
        assertEquals(peerRz, reply.peerRz)
        assertEquals(ownRz, reply.ownRz)
        assertEquals(req.text("operation"), reply.operation)
        assertFalse(reply.sharedX in reply.toString())
        assertTrue(vault.acceptEcdhReply(req, reply) is VaultResult.Ok)
    }

    @Test fun `E04 only a reply this vault made is accepted, so no substituted value gets through`() = runBlocking {
        val req = ecdh()
        val reply = (vault.rendezvousEcdhV1(ctx, req, child(alice.pubkey)) as VaultResult.Ok).value
        // The defence is the vault's own record of the reply object, not any
        // check of `shared_x`: a forged value, and even a copy carrying the
        // right value, are both refused. The reply's fields cannot be changed.
        val forged = RendezvousEcdhReply(reply.operation, reply.peerRz, reply.ownRz, bytes(32).toHex())
        val copy = RendezvousEcdhReply(reply.operation, reply.peerRz, reply.ownRz, reply.sharedX)
        assertEquals(refused(VaultRefusal.Unauthorised), vault.acceptEcdhReply(req, forged))
        assertEquals(refused(VaultRefusal.Unauthorised), vault.acceptEcdhReply(req, copy))
        assertTrue(vault.acceptEcdhReply(req, reply) is VaultResult.Ok)
    }

    @Test fun `E05 a reply for another peer, an off-curve peer, or another account is refused`() = runBlocking {
        val req = ecdh()
        val reply = (vault.rendezvousEcdhV1(ctx, req, child(alice.pubkey)) as VaultResult.Ok).value
        val other = Schnorr.publicKeyHex(secret())
        assertEquals(refused(VaultRefusal.Replay), vault.acceptEcdhReply(req.with("peer_rz", JsonPrimitive(other)), reply))
        var resolved = false
        assertEquals(refused(VaultRefusal.Malformed), vault.rendezvousEcdhV1(ctx, ecdh(peer = "f".repeat(64))) { resolved = true; null })
        assertFalse(resolved, "the child is not read for an off-curve peer")
        assertEquals(refused(VaultRefusal.Unauthorised), vault.rendezvousEcdhV1(ctx, ecdh(), child(RecordingSigner().pubkey)))
        assertEquals(refused(VaultRefusal.Malformed), vault.rendezvousEcdhV1(ctx, ecdh(peer = ownRz), child(alice.pubkey)))
    }

    @Test fun `E06 a reply from a previous vault generation is stale`() = runBlocking {
        val req = ecdh()
        val reply = (vault.rendezvousEcdhV1(ctx, req, child(alice.pubkey)) as VaultResult.Ok).value
        vault.bump()
        assertEquals(refused(VaultRefusal.Stale), vault.acceptEcdhReply(req, reply))
    }

    @Test fun `wipes the child it read`() = runBlocking {
        val stored = StoredRendezvousChild(RendezvousReceipt(alice.pubkey, "b".repeat(64), ownRz, 1, now + 3600), rzSecret.copyOf())
        assertTrue(vault.rendezvousEcdhV1(ctx, ecdh()) { stored } is VaultResult.Ok)
        assertTrue(stored.copyScalar().all { it == 0.toByte() })
    }

    // ---- generation and clearing ----

    @Test fun `follows the app account generation as well as its own bumps`() = runBlocking {
        var app = 3L
        val v = MlsVault(MemoryStores(), now = { now }, appGeneration = { app })
        val c = v.context(principal, alice.pubkey)
        assertTrue(v.enrol(c, alice, now + 3600) is VaultResult.Ok)
        app++
        assertEquals(refused(VaultRefusal.Stale), v.device(c))
        assertTrue(v.device(v.context(principal, alice.pubkey)) is VaultResult.Ok)
    }

    @Test fun `clearing removes the device and makes pending operations stale`() = runBlocking {
        vault.clear(alice.pubkey)
        assertEquals(refused(VaultRefusal.Stale), vault.device(ctx))
        assertEquals(refused(VaultRefusal.Unauthorised), vault.device(vault.context(principal, alice.pubkey)))
    }

    // ---- helpers ----

    private fun request(
        credential: NostrEvent = this.credential,
        device: String = this.device,
        homeBox: String = this.homeBox,
        expiresAt: Long = now + 86_400,
        operation: String = bytes(32).toHex(),
        deadline: Long = now + 300,
    ): JsonObject {
        val body = VmlsEncode.unsignedBinding(bytes(32), bytes(32), credential, device, expiresAt, homeBox.hexToBytes())
        return buildJsonObject {
            put("v", 1)
            put("operation", operation)
            put("body", Base64.getEncoder().encodeToString(body))
            put("digest", LeafBinding.digest(body).toHex())
            put("expires_at", deadline)
        }
    }

    private fun scopeFor(box: String = homeBox) = ConsentScope(principal, alice.pubkey, device, box, MlsVault.SIGN_METHOD)
    private fun refused(refusal: VaultRefusal) = VaultResult.Refused(refusal)
    private fun bytes(n: Int) = ByteArray(n).also(random::nextBytes)
    private fun secret(): ByteArray { while (true) { val k = bytes(32); if (runCatching { Schnorr.publicKey(k) }.isSuccess) return k } }
    private fun JsonObject.text(name: String) = (getValue(name) as JsonPrimitive).content
    private fun JsonObject.long(name: String) = text(name).toLong()
    private fun JsonObject.with(name: String, value: JsonPrimitive) = JsonObject(toMutableMap().apply { put(name, value) })

    /** An identity signer that remembers the credentials it signed. */
    private inner class RecordingSigner(
        val secret: ByteArray = secret(),
        private val alterTags: (List<List<String>>) -> List<List<String>> = { it },
    ) : ParticipantSigner {
        private val local = LocalSigner(secret)
        val signed = mutableListOf<NostrEvent>()
        override val pubkey: String = local.pubkey
        override val method: String get() = "local"
        override suspend fun sign(kind: Int, createdAt: Long, tags: List<List<String>>, content: String): NostrEvent =
            local.sign(kind, createdAt, alterTags(tags), content).also { signed += it }
        override suspend fun nip44Encrypt(peer: String, plaintext: String): String = error("unused")
        override suspend fun nip44Decrypt(peer: String, payload: String): String = error("unused")
    }

    /**
     * Stores in memory that behave like AEAD: a value opens only with the AAD
     * it was written with. The Keystore cipher itself is covered by the
     * instrumented `MlsVaultStorageTest`.
     */
    private class MemoryStores : MlsVaultStores {
        override val lockName = "test-" + UUID.randomUUID()
        private val values = mutableMapOf<String, Pair<ByteArray, ByteArray>>()

        fun names() = synchronized(values) { values.keys.toList() }
        fun aads() = synchronized(values) { values.values.map { String(it.first, Charsets.US_ASCII) } }
        fun corruptAll() = synchronized(values) { values.values.forEach { it.second[it.second.size - 1] = (it.second.last().toInt() xor 1).toByte() } }
        fun swapPersonaRecords() = synchronized(values) {
            val personas = values.keys.filter { it.startsWith("persona.") }
            require(personas.size == 2)
            val (a, b) = personas
            // A sealed value carries the AAD it was sealed with.
            val va = values.getValue(a)
            values[a] = values.getValue(b)
            values[b] = va
        }

        override fun open(name: String, aad: ByteArray): RoomStorage = object : RoomStorage {
            override fun read(): ByteArray? = synchronized(values) {
                val (bound, value) = values[name] ?: return null
                check(bound.contentEquals(aad)) { "AEAD tag mismatch" }
                // The last byte stands in for the tag: a flipped one does not open.
                check(value.isEmpty() || value.last() == TAG) { "AEAD tag mismatch" }
                value.copyOf(value.size - 1)
            }
            override fun write(value: ByteArray) = synchronized(values) { values[name] = aad.copyOf() to (value + TAG) }
            override fun reset() { synchronized(values) { values.remove(name) } }
        }

        private companion object { const val TAG: Byte = 0x5a }
    }
}
