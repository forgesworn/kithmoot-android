package dev.forgesworn.kithmoot.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.forgesworn.kithmoot.account.ConsentDecision
import dev.forgesworn.kithmoot.account.ConsentPrompt
import dev.forgesworn.kithmoot.account.CoordinationStatus
import dev.forgesworn.kithmoot.account.EngineSession
import dev.forgesworn.kithmoot.account.EngineSessions
import dev.forgesworn.kithmoot.account.EngineVaultWitness
import dev.forgesworn.kithmoot.account.EnrolledDevice
import dev.forgesworn.kithmoot.account.Hosted
import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.account.MlsVault
import dev.forgesworn.kithmoot.account.SessionHost
import dev.forgesworn.kithmoot.account.SignLeafBindingReply
import dev.forgesworn.kithmoot.account.VaultCoordination
import dev.forgesworn.kithmoot.account.VaultResult
import dev.forgesworn.kithmoot.account.WitnessChannels
import dev.forgesworn.kithmoot.account.hostedStep
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.vmls.ffi.VmlsBindingRequest
import dev.forgesworn.vmls.ffi.VmlsCredential
import dev.forgesworn.vmls.ffi.VmlsStep
import dev.forgesworn.vmls.ffi.prepareCreate
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A real engine session under the persona's coordinator (P3-03b-3a), with
 * Keystore-backed stores and a witness signing real Ed25519 receipts: the
 * group is created with the vault's own leaf binding signature, and every
 * step is witnessed before it is acknowledged.
 */
@RunWith(AndroidJUnit4::class)
class SessionHostEngineTest {
    private lateinit var context: Context
    private lateinit var prefix: String
    private lateinit var witness: FakeEd25519Witness
    private val principal = "dev.forgesworn.kithmoot"
    private val random = SecureRandom()
    private val identity = LocalSigner(ByteArray(32).also { random.nextBytes(it) })
    private val now = System.currentTimeMillis() / 1000
    private val homeBox = bytes(32)
    private var credential: NostrEvent? = null

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        prefix = "kithmoot.session-test.${UUID.randomUUID().toString().take(8)}"
        witness = FakeEd25519Witness()
    }

    @After fun cleanup() {
        assertEquals("the fake witness parsed every request", 0, witness.malformed)
        context.noBackupFilesDir.listFiles().orEmpty().filter { it.name.startsWith("$prefix.") }.forEach { it.delete() }
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keys.aliases().toList().filter { it.startsWith("$prefix.") }.forEach(keys::deleteEntry)
    }

    private fun vault() = MlsVault.coordinated(
        VaultCoordination(AndroidMlsVaultStores(context, prefix), WitnessChannels { _, _, _ -> witness.channel }, EngineVaultWitness()),
    )

    private suspend fun enrolled(v: MlsVault): EnrolledDevice {
        val genesis = (v.beginCoordination(identity.pubkey, bytes(32), witness.publicKey) as VaultResult.Ok).value
        witness.enrol(genesis.subject, genesis.initialDigest)
        assertEquals(CoordinationStatus.Active, v.coordinationStatus(identity.pubkey, check = true))
        val signer = object : dev.forgesworn.kithmoot.account.ParticipantSigner by identity {
            override suspend fun sign(kind: Int, createdAt: Long, tags: List<List<String>>, content: String): NostrEvent =
                identity.sign(kind, createdAt, tags, content).also { credential = it }
        }
        return (v.enrol(v.context(principal, identity.pubkey), signer, now + 86_400) as VaultResult.Ok).value
    }

    /** Prepares a group, has the vault sign its leaf binding, and creates it under the host. */
    private suspend fun create(v: MlsVault, host: SessionHost<EngineSession>, sessions: EngineSessions): Hosted<VmlsStep> {
        val event = credential!!
        val request = VmlsBindingRequest(
            VmlsCredential(event.pubkey.hexToBytes(), event.createdAt.toULong(), event.tags, event.content, event.sig.hexToBytes()),
            homeBox, (now + 3_600).toULong(),
        )
        val pending = prepareCreate(sessions.platform, now.toULong(), request, bytes(32))
        val sign = pending.request()
        val reply = v.signLeafBindingV1(
            v.context(principal, identity.pubkey),
            buildJsonObject {
                put("v", 1)
                put("operation", sign.operation.toHex())
                put("body", Base64.getEncoder().encodeToString(sign.body))
                put("digest", sign.digest.toHex())
                put("expires_at", sign.expiresAt.toLong())
            },
            ConsentPrompt { ConsentDecision.Approve },
        )
        val signature = ((reply as VaultResult.Ok<SignLeafBindingReply>).value.signature).hexToBytes()
        return host.create(identity.pubkey) {
            val created = pending.complete(now.toULong(), sign.operation, signature)
            EngineSession(created.session) to hostedStep(created.step)
        }
    }

    @Test fun a_created_group_and_each_send_are_witnessed_before_release() = runBlocking<Unit> {
        val vault = vault()
        val device = enrolled(vault)
        val sessions = EngineSessions(device.device.hexToBytes(), Schnorr.publicKey(bytes(32)))
        val host = SessionHost(vault, sessions)
        val created = create(vault, host, sessions)
        assertTrue(created is Hosted.Released)
        val id = (created as Hosted.Released).value.snapshot!!.session
        val marks = (host.sessions(identity.pubkey) as Hosted.Released).value
        assertEquals(1, marks.size)
        val first = marks.getValue(id.toHex())

        // A lone member's message reaches no other leaf, but it still moves the session's state.
        val sent = host.step(identity.pubkey, id) { s -> hostedStep(s.inner.send("hello".toByteArray())) }
        assertTrue(sent is Hosted.Released)
        val step = (sent as Hosted.Released).value
        assertEquals(first + 1, step.snapshot!!.generation.toLong())
        assertEquals(first + 1, (host.sessions(identity.pubkey) as Hosted.Released).value.getValue(id.toHex()))
        // Acknowledged: the session sends again at once.
        assertTrue(host.step(identity.pubkey, id) { s -> hostedStep(s.inner.send("again".toByteArray())) } is Hosted.Released)
    }

    @Test fun a_send_held_offline_is_promoted_after_a_restart_and_the_session_reopens_at_it() = runBlocking<Unit> {
        val vault = vault()
        val device = enrolled(vault)
        val rz = Schnorr.publicKey(bytes(32))
        val sessions = EngineSessions(device.device.hexToBytes(), rz)
        val host = SessionHost(vault, sessions)
        val id = ((create(vault, host, sessions) as Hosted.Released).value).snapshot!!.session
        val first = (host.sessions(identity.pubkey) as Hosted.Released).value.getValue(id.toHex())

        witness.mode = FakeEd25519Witness.Mode.Down
        assertEquals(Hosted.Held, host.step(identity.pubkey, id) { s -> hostedStep(s.inner.send("held".toByteArray())) })
        witness.mode = FakeEd25519Witness.Mode.Up

        // A new process: the staged send is promoted, and the engine opens at its generation, acknowledged.
        val restarted = SessionHost(vault(), EngineSessions(device.device.hexToBytes(), rz))
        assertEquals(first + 1, (restarted.sessions(identity.pubkey) as Hosted.Released).value.getValue(id.toHex()))
        val outbox = restarted.step(identity.pubkey, id) { s -> dev.forgesworn.kithmoot.account.EngineStep(null, s.inner.outbox()) }
        assertTrue(outbox is Hosted.Released)
        assertTrue(restarted.step(identity.pubkey, id) { s -> hostedStep(s.inner.send("after".toByteArray())) } is Hosted.Released)
    }

    private fun bytes(n: Int) = ByteArray(n).also(random::nextBytes)
}
