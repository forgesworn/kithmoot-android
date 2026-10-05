package dev.forgesworn.kithmoot.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.forgesworn.kithmoot.account.ConsentDecision
import dev.forgesworn.kithmoot.account.ConsentPrompt
import dev.forgesworn.kithmoot.account.CoordinationStatus
import dev.forgesworn.kithmoot.account.EngineCapabilities
import dev.forgesworn.kithmoot.account.EngineSession
import dev.forgesworn.kithmoot.account.EngineSessions
import dev.forgesworn.kithmoot.account.EngineStep
import dev.forgesworn.kithmoot.account.EngineVaultWitness
import dev.forgesworn.kithmoot.account.Hosted
import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.account.MlsVault
import dev.forgesworn.kithmoot.account.ParticipantSigner
import dev.forgesworn.kithmoot.account.SessionHost
import dev.forgesworn.kithmoot.account.VaultCoordination
import dev.forgesworn.kithmoot.account.VaultResult
import dev.forgesworn.kithmoot.account.WitnessChannels
import dev.forgesworn.kithmoot.account.hostedStep
import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.mls.Round
import dev.forgesworn.kithmoot.mls.SessionDriver
import dev.forgesworn.kithmoot.mls.VmlsBoxClient
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.relay.LinkJsonRequest
import dev.forgesworn.kithmoot.relay.LinkJsonResponse
import dev.forgesworn.kithmoot.relay.LinkJsonTransport
import dev.forgesworn.kithmoot.relay.LinkPathState
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.runBlocking
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The driver loop with the real engine (P3-03b-3a): a lone-member group's
 * Update commit is deposited at its commit slot, the box's Ed25519 receipt is
 * verified by the engine, the slot is read back through its status and the
 * commit applies, every step witnessed. The box is a fake in memory, signing
 * receipts with the key the group pinned as its home box.
 */
@RunWith(AndroidJUnit4::class)
class SessionDriverEngineTest {
    private lateinit var context: Context
    private lateinit var prefix: String
    private lateinit var witness: FakeEd25519Witness
    private val random = SecureRandom()
    private val identity = LocalSigner(secret())
    private var credential: NostrEvent? = null

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        prefix = "kithmoot.driver-test.${UUID.randomUUID().toString().take(8)}"
        witness = FakeEd25519Witness()
    }

    @After fun cleanup() {
        context.noBackupFilesDir.listFiles().orEmpty().filter { it.name.startsWith("$prefix.") }.forEach { it.delete() }
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keys.aliases().toList().filter { it.startsWith("$prefix.") }.forEach(keys::deleteEntry)
    }

    @Test fun an_update_commit_is_deposited_verified_read_back_and_applied() = runBlocking<Unit> {
        val vault = MlsVault.coordinated(VaultCoordination(AndroidMlsVaultStores(context, prefix), WitnessChannels { _, _, _ -> witness.channel }, EngineVaultWitness()))
        val genesis = (vault.beginCoordination(identity.pubkey, bytes(32), witness.publicKey) as VaultResult.Ok).value
        witness.enrol(genesis.subject, genesis.initialDigest)
        assertEquals(CoordinationStatus.Active, vault.coordinationStatus(identity.pubkey, check = true))
        val signer = object : ParticipantSigner by identity {
            override suspend fun sign(kind: Int, createdAt: Long, tags: List<List<String>>, content: String): NostrEvent =
                identity.sign(kind, createdAt, tags, content).also { credential = it }
        }
        val device = (vault.enrol(vault.context(PRINCIPAL, identity.pubkey), signer, System.currentTimeMillis() / 1000 + 86_400) as VaultResult.Ok).value
        val box = SigningBox(Ed25519PrivateKeyParameters(random), bytes(32))
        val now = System.currentTimeMillis() / 1000

        val sessions = EngineSessions(device.device.hexToBytes(), Schnorr.publicKey(secret()))
        val host = SessionHost(vault, sessions)
        val created = createGroup(vault, host, sessions, identity.pubkey, credential!!, now, random, box.node, box.installation)
        val id = (created as Hosted.Released).value.snapshot!!.session
        val client = VmlsBoxClient(box, "route", box.node.toHex(), { vault.signBoxRequestV1(vault.context(PRINCIPAL, identity.pubkey), it, ConsentPrompt { ConsentDecision.Approve }) })
        val driver = SessionDriver(host, client, box.node, EngineCapabilities)
        assertTrue(driver.round(identity.pubkey, id, now) is Round.Done)

        fun epoch() = runBlocking { (host.step(identity.pubkey, id) { s -> EngineStep(null, s.inner.epoch()) } as Hosted.Released).value }
        val before = epoch()
        // An Update: the engine prepares it, the vault signs its binding, the commit is staged in the outbox.
        val sign = (host.step(identity.pubkey, id) { s -> EngineStep(null, s.inner.prepareUpdate(now.toULong(), bindingRequest(credential!!, box.node, now))) } as Hosted.Released).value
        val signature = signBinding(vault, identity.pubkey, sign)
        val committed = host.step(identity.pubkey, id) { s -> hostedStep(s.inner.completeUpdate(now.toULong(), sign.operation, signature)) }
        assertTrue(committed is Hosted.Released)
        assertEquals(before, epoch())

        // Up to three rounds: deposit and receipt, then the slot read back and applied.
        var rounds = 0
        while (epoch() == before && rounds < 3) {
            assertTrue(driver.round(identity.pubkey, id, now + 1 + rounds) is Round.Done)
            rounds++
        }
        assertEquals(before + 1u, epoch())
        assertTrue("the commit went to a slot", box.slotDeposits > 0)
        assertTrue("the slot was read back", box.statusReads > 0)
    }

    private fun bytes(n: Int) = ByteArray(n).also(random::nextBytes)
    private fun secret(): ByteArray { while (true) { val k = bytes(32); if (runCatching { Schnorr.publicKey(k) }.isSuccess) return k } }

    private companion object { const val PRINCIPAL = "dev.forgesworn.kithmoot" }
}

/**
 * A home box in memory with Bothy's `/vmls/v1/` answers: first-writer-wins
 * slots and real slot receipts, signed with Ed25519 over vmls-core's
 * `slot_digest` under [installation].
 */
class SigningBox(private val key: Ed25519PrivateKeyParameters, val installation: ByteArray) : LinkJsonTransport {
    val node: ByteArray = key.generatePublicKey().encoded
    private val records = mutableMapOf<String, MutableList<ByteArray>>()
    private val acked = mutableSetOf<String>()
    private val slots = mutableMapOf<String, Pair<Long, ByteArray>>()
    var slotDeposits = 0
    var statusReads = 0
    private val path = LinkPathState("direct", null, "1.2.3.4:5", "test")
    private val b64 = Base64.getEncoder()

    private fun receipt(slot: ByteArray, attempt: Long, hash: ByteArray): String {
        val attemptBytes = ByteArray(4) { (attempt shr (24 - 8 * it)).toByte() }
        val digest = Digests.sha256("VMLS/1 slot receipt".toByteArray() + installation + slot + attemptBytes + hash)
        val signature = Ed25519Signer().run { init(true, key); update(digest, 0, digest.size); generateSignature() }
        return b64.encodeToString(byteArrayOf(1) + node + installation + slot + attemptBytes + hash + signature)
    }

    private fun reply(status: Int, body: String) = LinkJsonResponse(status, body.toByteArray(), path)

    @Synchronized override fun request(request: LinkJsonRequest): CompletableFuture<LinkJsonResponse> = CompletableFuture.completedFuture(answer(request))

    private fun answer(request: LinkJsonRequest): LinkJsonResponse {
        val parts = request.path.removePrefix("/vmls/v1/").split('/')
        return when {
            parts[0] == "capabilities" -> reply(200, """{"v":1,"security_contract":1,"slot_receipts":1,"fork_evidence":1,"restore_fence":1,"installation":"${installation.toHex()}"}""")
            parts[0] == "mailboxes" -> {
                val list = records.getOrPut(parts[1]) { mutableListOf() }
                val duplicate = list.any { it.contentEquals(request.body) }
                if (!duplicate) list += request.body
                reply(if (duplicate) 200 else 201, """{"v":1,"code":"${if (duplicate) "duplicate" else "stored"}","server_time":1,"receipt":"${Digests.sha256(request.body).toHex()}"}""")
            }
            parts[0] == "slots" && parts.size == 3 -> {
                slotDeposits++
                val slot = parts[1].hexToBytes()
                val attempt = parts[2].toLong()
                val (winner, envelope) = slots.getOrPut(parts[1]) { attempt to request.body }
                val hash = Digests.sha256(envelope)
                val code = when { winner != attempt || !envelope.contentEquals(request.body) -> "taken"; else -> "won" }
                reply(if (code == "won") 201 else 409, """{"v":1,"code":"$code","server_time":1,"attempt":$winner,"receipt":"${hash.toHex()}","signed_receipt":"${receipt(slot, winner, hash)}"}""")
            }
            parts[0] == "slots" -> {
                statusReads++
                val slot = parts[1].hexToBytes()
                val asked = parts[2].toLong()
                val (winner, envelope) = slots[parts[1]] ?: return reply(200, """{"v":1,"code":"empty","server_time":1}""")
                val hash = Digests.sha256(envelope)
                val code = if (winner == asked) "filled" else "void"
                val env = if (code == "filled") ""","envelope":"${b64.encodeToString(envelope)}"""" else ""
                reply(200, """{"v":1,"code":"$code","server_time":1,"attempt":$winner,"receipt":"${hash.toHex()}","signed_receipt":"${receipt(slot, winner, hash)}"$env}""")
            }
            parts[0] == "fetch" -> {
                val asked = Regex("\"([0-9a-f]{64})\"").findAll(String(request.body)).map { it.groupValues[1] }.toList()
                val items = asked.flatMap { m -> records[m].orEmpty().filter { Digests.sha256(it).toHex() !in acked }.map { m to it } }
                    .joinToString(",") { (m, e) -> """{"mailbox":"$m","receipt":"${Digests.sha256(e).toHex()}","envelope":"${b64.encodeToString(e)}"}""" }
                reply(200, """{"v":1,"code":"ok","server_time":1,"records":[$items],"next":null}""")
            }
            parts[0] == "ack" -> {
                val receipts = Regex("\"receipt\":\"([0-9a-f]{64})\"").findAll(String(request.body)).map { it.groupValues[1] }.toList()
                acked += receipts
                reply(200, """{"v":1,"code":"deleted","server_time":1,"acked":${receipts.size}}""")
            }
            else -> reply(404, "")
        }
    }
}
