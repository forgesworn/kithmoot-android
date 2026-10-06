package dev.forgesworn.kithmoot.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.account.ConsentDecision
import dev.forgesworn.kithmoot.account.ConsentPrompt
import dev.forgesworn.kithmoot.account.CoordinationStatus
import dev.forgesworn.kithmoot.account.EngineCapabilities
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
import dev.forgesworn.kithmoot.mls.BoxAnswer
import dev.forgesworn.kithmoot.mls.Registered
import dev.forgesworn.kithmoot.mls.Round
import dev.forgesworn.kithmoot.mls.SessionDriver
import dev.forgesworn.kithmoot.mls.VmlsBoxClient
import dev.forgesworn.kithmoot.protocol.BothyPairing
import dev.forgesworn.kithmoot.protocol.BoxCadence
import dev.forgesworn.kithmoot.protocol.CircleGrantStatus
import dev.forgesworn.kithmoot.protocol.KIND_CIRCLE_EVENT_GRANT
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.VmlsGrantTerms
import dev.forgesworn.kithmoot.relay.ActiveLinkRoute
import dev.forgesworn.kithmoot.relay.HybridRelaySockets
import dev.forgesworn.kithmoot.relay.OkHttpRelaySockets
import dev.forgesworn.kithmoot.relay.RelayAuthenticator
import dev.forgesworn.kithmoot.relay.RelayAuthenticatorProvider
import dev.forgesworn.kithmoot.relay.RelayPool
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P3-03b-3b-1 lab (not CI): the keeper's own MLS device against a real
 * `bothyd` with VMLS on (bothy-node's `g5_fixture` with `G5_VMLS=1`), over an
 * ordinary Link pairing. The keeper grants its MLS device the VMLS scope
 * (decision 9) through the sheltered relay, then every box route the driver
 * uses is exercised for real: capabilities, a package registered and
 * withdrawn, and a lone group's Update commit deposited at its slot, its
 * receipt verified by the engine, read back and applied.
 *
 * Run by `scripts/lab-vmls-box.sh` with `fixture_control`; skipped without it.
 */
@RunWith(AndroidJUnit4::class)
class VmlsBoxLabTest {
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val control get() = arguments.getString("fixture_control")?.removeSuffix("/")
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as KithMootApplication
    private lateinit var context: Context
    private lateinit var prefix: String
    private lateinit var witness: FakeEd25519Witness
    private val random = SecureRandom()
    private var credential: NostrEvent? = null

    @Before fun setup() {
        assumeTrue("run by scripts/lab-vmls-box.sh", control != null)
        context = ApplicationProvider.getApplicationContext()
        prefix = "kithmoot.vmls-lab.${UUID.randomUUID().toString().take(8)}"
        witness = FakeEd25519Witness()
    }

    @After fun cleanup() {
        if (control == null) return
        context.noBackupFilesDir.listFiles().orEmpty().filter { it.name.startsWith("$prefix.") }.forEach { it.delete() }
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keys.aliases().toList().filter { it.startsWith("$prefix.") }.forEach(keys::deleteEntry)
    }

    @Test fun the_keepers_device_drives_a_group_against_a_real_box() = runBlocking<Unit> {
        val now = System.currentTimeMillis() / 1000
        // An ordinary pairing: VMLS answers only on the ordinary router, never a witness-only route.
        val pairing = BothyPairing.parse(post("pairing").getValue("uri").jsonPrimitive.content, now)
        val route = app.linkEngine.pair(pairing.card, pairing.pairingSecret, pairing.expiresAt).get(120, TimeUnit.SECONDS)
        val ready = get("ready")
        assertEquals("true", ready.getValue("vmls").jsonPrimitive.content)
        val node32 = ready.getValue("link_node_id").jsonPrimitive.content
        val node = base32Decode(node32)
        val installation = ready.getValue("vmls_installation").jsonPrimitive.content
        val keeper = LocalSigner(Digests.sha256("kithmoot-g5-fixture-signer-v2:${arguments.getString("persona") ?: "alice"}".toByteArray()))
        assertEquals(ready.getValue("claimed_by").jsonPrimitive.content, keeper.pubkey)

        // The keeper's persona, coordinated, with its MLS device enrolled.
        val vault = MlsVault.coordinated(VaultCoordination(AndroidMlsVaultStores(context, prefix), WitnessChannels { _, _, _ -> witness.channel }, EngineVaultWitness()))
        val genesis = (vault.beginCoordination(keeper.pubkey, bytes(32), witness.publicKey) as VaultResult.Ok).value
        witness.enrol(genesis.subject, genesis.initialDigest)
        assertEquals(CoordinationStatus.Active, vault.coordinationStatus(keeper.pubkey, check = true))
        val capturing = object : ParticipantSigner by keeper {
            override suspend fun sign(kind: Int, createdAt: Long, tags: List<List<String>>, content: String): NostrEvent =
                keeper.sign(kind, createdAt, tags, content).also { credential = it }
        }
        val device = (vault.enrol(vault.context(PRINCIPAL, keeper.pubkey), capturing, now + 86_400) as VaultResult.Ok).value.device

        // The client before the grant: the box refuses an MLS device it has no grant for.
        val client = VmlsBoxClient(app.linkEngine, route.routeId, node.toHex(), {
            vault.signBoxRequestV1(vault.context(PRINCIPAL, keeper.pubkey), it, ConsentPrompt { ConsentDecision.Approve })
        })
        val before = client.capabilities()
        assertTrue("an ungranted device is refused: $before", before is BoxAnswer.Refused)

        // The keeper grants its MLS device the VMLS scope, through the sheltered relay as the keeper.
        val terms = VmlsGrantTerms(BoxCadence.server(node32), keeper.pubkey, device, bytes(16).toHex(), now + 86_400)
        publish(BoxCadence.server(node32), route.routeId, keeper, keeper.sign(KIND_CIRCLE_EVENT_GRANT, now, terms.tags(CircleGrantStatus.ACTIVE), ""))

        // Capabilities name the box's installation.
        val capabilities = eventually { client.capabilities().takeIf { it is BoxAnswer.Ok } }
        val body = Json.parseToJsonElement(String((capabilities as BoxAnswer.Ok<ByteArray>).value)).jsonObject
        assertEquals(installation, body.getValue("installation").jsonPrimitive.content)

        // A keeper device registers and withdraws a package.
        val packageId = bytes(32); val welcome = bytes(32); val sealed = bytes(64)
        assertEquals(true, ((client.registerPackage(packageId, welcome, now + 3_600, sealed) as BoxAnswer.Ok<Registered>).value.fresh))
        assertEquals(false, ((client.registerPackage(packageId, welcome, now + 3_600, sealed) as BoxAnswer.Ok<Registered>).value.fresh))
        assertTrue(client.withdrawPackage(packageId) is BoxAnswer.Ok)

        // A lone group at this box: an Update commit through the driver.
        val sessions = EngineSessions(device.hexToBytes(), Schnorr.publicKey(secret()))
        val host = SessionHost(vault, sessions)
        val created = createGroup(vault, host, sessions, keeper.pubkey, credential!!, now, random, node, installation.hexToBytes())
        val id = (created as Hosted.Released).value.snapshot!!.session
        val driver = SessionDriver(host, client, node, EngineCapabilities)
        val first = driver.round(keeper.pubkey, id, epochSeconds())
        assertTrue("first round: $first", first is Round.Done)

        fun epoch() = runBlocking { (host.step(keeper.pubkey, id) { s -> EngineStep(null, s.inner.epoch()) } as Hosted.Released).value }
        val start = epoch()
        val at = epochSeconds()
        val sign = (host.step(keeper.pubkey, id) { s -> EngineStep(null, s.inner.prepareUpdate(at.toULong(), bindingRequest(credential!!, node, at))) } as Hosted.Released).value
        val signature = signBinding(vault, keeper.pubkey, sign)
        assertTrue(host.step(keeper.pubkey, id) { s -> hostedStep(s.inner.completeUpdate(at.toULong(), sign.operation, signature)) } is Hosted.Released)

        var rounds = 0
        while (epoch() == start && rounds < 5) {
            val round = driver.round(keeper.pubkey, id, epochSeconds())
            assertTrue("round $rounds: $round", round is Round.Done)
            rounds++
        }
        assertEquals("the commit applied after $rounds rounds", start + 1u, epoch())
    }

    /** Publishes [event] to the box's sheltered relay over [routeId], authenticated by NIP-42 as [signer]. */
    private suspend fun publish(url: String, routeId: String, signer: LocalSigner, event: NostrEvent) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val authenticator = object : RelayAuthenticator {
            override val pubkey = signer.pubkey
            override suspend fun sign(url: String, challenge: String) =
                signer.sign(22242, epochSeconds(), listOf(listOf("relay", url), listOf("challenge", challenge)), "")
        }
        val sockets = HybridRelaySockets(OkHttpRelaySockets(), app.linkEngine, ActiveLinkRoute { u -> routeId.takeIf { u == url } })
        val relay = RelayPool(listOf(url), sockets, scope, authenticators = RelayAuthenticatorProvider { u -> authenticator.takeIf { u == url } })
        try {
            relay.start()
            checkNotNull(withTimeoutOrNull(30_000) { relay.connected.first { url in it } }) { "the sheltered relay did not become ready" }
            // A grant answered `pending` waits on the box's witness: the identical event again is safe.
            eventually { runCatching { relay.publishConfirmed(event) }.getOrDefault(false).takeIf { it } }
        } finally {
            relay.stop()
            scope.cancel()
        }
    }

    private suspend fun <T : Any> eventually(attempts: Int = 30, block: suspend () -> T?): T {
        repeat(attempts) { block()?.let { return it }; delay(1_000) }
        throw AssertionError("gave up after $attempts attempts")
    }

    private fun get(path: String) = request(path, "GET")
    private fun post(path: String) = request(path, "POST")
    private fun request(path: String, method: String): JsonObject = (URL("$control/$path").openConnection() as HttpURLConnection).let { connection ->
        try {
            connection.requestMethod = method; connection.connectTimeout = 10_000; connection.readTimeout = 120_000
            require(connection.responseCode in 200..299) { "fixture control rejected $path" }
            Json.parseToJsonElement(connection.inputStream.bufferedReader().use { it.readText() }).jsonObject
        } finally { connection.disconnect() }
    }

    private fun epochSeconds() = System.currentTimeMillis() / 1000
    private fun bytes(n: Int) = ByteArray(n).also(random::nextBytes)
    private fun secret(): ByteArray { while (true) { val k = bytes(32); if (runCatching { Schnorr.publicKey(k) }.isSuccess) return k } }

    private companion object {
        const val PRINCIPAL = "dev.forgesworn.kithmoot"

        /** RFC 4648 base32, lowercase and unpadded: Link's 52-character node id. */
        fun base32Decode(text: String): ByteArray {
            require(text.length == 52)
            val alphabet = "abcdefghijklmnopqrstuvwxyz234567"
            var buffer = 0L; var bits = 0
            val out = java.io.ByteArrayOutputStream()
            for (c in text) {
                val v = alphabet.indexOf(c); require(v >= 0)
                buffer = (buffer shl 5) or v.toLong(); bits += 5
                if (bits >= 8) { bits -= 8; out.write(((buffer shr bits) and 0xff).toInt()) }
            }
            return out.toByteArray().also { require(it.size == 32) }
        }
    }
}
