package dev.forgesworn.kithmoot.storage

import android.content.Context
import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.account.ConsentDecision
import dev.forgesworn.kithmoot.account.ConsentPrompt
import dev.forgesworn.kithmoot.account.CoordinationStatus
import dev.forgesworn.kithmoot.account.EngineVaultWitness
import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.account.MlsVault
import dev.forgesworn.kithmoot.account.ParticipantSigner
import dev.forgesworn.kithmoot.account.VaultCoordination
import dev.forgesworn.kithmoot.account.VaultResult
import dev.forgesworn.kithmoot.account.WitnessChannels
import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.mls.VmlsBoxClient
import dev.forgesworn.kithmoot.protocol.BothyPairing
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.relay.ActiveLinkRoute
import dev.forgesworn.kithmoot.relay.HybridRelaySockets
import dev.forgesworn.kithmoot.relay.OkHttpRelaySockets
import dev.forgesworn.kithmoot.relay.RelayAuthenticator
import dev.forgesworn.kithmoot.relay.RelayAuthenticatorProvider
import dev.forgesworn.kithmoot.relay.RelayPool
import dev.forgesworn.kithmoot.crypto.toHex
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals

/**
 * The real-box VMLS lab's shared steps (P3-03b-3b): bothy-node's claimed
 * `g5_fixture` with `G5_VMLS=1`, reached at [control] over loopback, paired
 * over an ordinary Link pairing (VMLS answers on no other route).
 */
class VmlsLab(private val app: KithMootApplication, private val control: String, persona: String) {
    /** The fixture's claim master: the box's keeper. */
    val keeper = LocalSigner(Digests.sha256("kithmoot-g5-fixture-signer-v2:$persona".toByteArray()))
    lateinit var routeId: String; private set
    lateinit var node32: String; private set
    lateinit var node: ByteArray; private set
    lateinit var installation: String; private set
    val server: String get() = "ws://$node32/events"

    /** A fresh pairing code from the box, as it shows one to a person: single use, ten minutes. */
    fun pairingCode(): String = post("pairing").getValue("uri").jsonPrimitive.content

    /** Reads the box's node and VMLS installation without pairing. */
    fun ready() {
        val ready = get("ready")
        assertEquals("true", ready.getValue("vmls").jsonPrimitive.content)
        node32 = ready.getValue("link_node_id").jsonPrimitive.content
        node = base32Decode(node32)
        installation = ready.getValue("vmls_installation").jsonPrimitive.content
    }

    /** Pairs this app's Link engine with the box and reads its VMLS installation. */
    fun pair(now: Long) {
        val pairing = BothyPairing.parse(post("pairing").getValue("uri").jsonPrimitive.content, now)
        routeId = app.linkEngine.pair(pairing.card, pairing.pairingSecret, pairing.expiresAt).get(120, TimeUnit.SECONDS).routeId
        val ready = get("ready")
        assertEquals("true", ready.getValue("vmls").jsonPrimitive.content)
        assertEquals(ready.getValue("claimed_by").jsonPrimitive.content, keeper.pubkey)
        node32 = ready.getValue("link_node_id").jsonPrimitive.content
        node = base32Decode(node32)
        installation = ready.getValue("vmls_installation").jsonPrimitive.content
    }

    /** A box client for [persona]'s MLS device in [vault], every request approved. */
    fun client(vault: MlsVault, persona: String) = VmlsBoxClient(app.linkEngine, routeId, node.toHex(), {
        vault.signBoxRequestV1(vault.context(PRINCIPAL, persona), it, ConsentPrompt { ConsentDecision.Approve })
    })

    /** Publishes [events] to the box's sheltered relay, authenticated by NIP-42 as [signer]. */
    suspend fun publish(signer: ParticipantSigner, vararg events: NostrEvent) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val authenticator = object : RelayAuthenticator {
            override val pubkey = signer.pubkey
            override suspend fun sign(url: String, challenge: String) =
                signer.sign(22242, System.currentTimeMillis() / 1000, listOf(listOf("relay", url), listOf("challenge", challenge)), "")
        }
        val url = server
        val sockets = HybridRelaySockets(OkHttpRelaySockets(), app.linkEngine, ActiveLinkRoute { u -> routeId.takeIf { u == url } })
        val relay = RelayPool(listOf(url), sockets, scope, authenticators = RelayAuthenticatorProvider { u -> authenticator.takeIf { u == url } })
        try {
            relay.start()
            checkNotNull(withTimeoutOrNull(30_000) { relay.connected.first { url in it } }) { "the sheltered relay did not become ready" }
            // A grant answered `pending` waits on the box's witness: the identical event again is safe.
            for (event in events) eventually { runCatching { relay.publishConfirmed(event) }.getOrDefault(false).takeIf { it } }
        } finally {
            relay.stop()
            scope.cancel()
        }
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

    companion object {
        const val PRINCIPAL = "dev.forgesworn.kithmoot"

        /** A coordinated vault for [persona] under [prefix], its witness enrolled; Active. */
        suspend fun coordinatedVault(context: Context, prefix: String, witness: FakeEd25519Witness, persona: String, random: java.security.SecureRandom): MlsVault {
            val vault = MlsVault.coordinated(VaultCoordination(AndroidMlsVaultStores(context, prefix), WitnessChannels { _, _, _ -> witness.channel }, EngineVaultWitness()))
            val genesis = (vault.beginCoordination(persona, ByteArray(32).also(random::nextBytes), witness.publicKey) as VaultResult.Ok).value
            witness.enrol(genesis.subject, genesis.initialDigest)
            assertEquals(CoordinationStatus.Active, vault.coordinationStatus(persona, check = true))
            return vault
        }

        suspend fun <T : Any> eventually(attempts: Int = 30, block: suspend () -> T?): T {
            repeat(attempts) { block()?.let { return it }; delay(1_000) }
            throw AssertionError("gave up after $attempts attempts")
        }

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
