package dev.forgesworn.kithmoot.g5

import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.protocol.BothyPairing
import dev.forgesworn.kithmoot.relay.LinkTransportManager
import dev.forgesworn.kithmoot.relay.LinkTransportVault
import dev.forgesworn.kithmoot.relay.ReflectiveLinkTransportRuntime
import dev.forgesworn.kithmoot.storage.RoomStorage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Drives the shipped Android Link binding from a capability served over the
 * runner's loopback control tunnel. The test never receives a pairing URI as
 * a Gradle argument and deliberately does not log it.
 */
class G5NativeRouteTest {
    @Test fun pairs_a_native_route_from_the_local_fixture_control_plane() {
        val control = requireNotNull(InstrumentationRegistry.getArguments().getString("fixture_control")) {
            "fixture_control is required"
        }.removeSuffix("/")
        val pairing = pairing(postJson("$control/pairing").getValue("uri").jsonPrimitive.content)
        val manager = LinkTransportManager(LinkTransportVault(MemoryStorage()), ReflectiveLinkTransportRuntime())
        try {
            val route = manager.pair(pairing.card, pairing.pairingSecret, pairing.expiresAt).get(120, TimeUnit.SECONDS)
            assertTrue(route.routeId.isNotBlank())
            assertEquals(32, route.pairedRouteSecret.size)
        } finally {
            manager.close()
        }
    }

    private fun pairing(uri: String): NativePairing = try {
        BothyPairing.parse(uri, epochSeconds()).let {
            NativePairing(it.card, it.pairingSecret, it.expiresAt)
        }
    } catch (error: IllegalArgumentException) {
        // The ignored loopback diagnostic deliberately uses plain ws over an
        // adb-reversed localhost socket. Production pairing still goes through
        // BothyPairing's WebPKI-only verifier above.
        val root = Json.parseToJsonElement(
            Base64.getUrlDecoder().decode(uri.removePrefix("bothy:")).decodeToString(),
        ).jsonObject
        val card = Base64.getDecoder().decode(root.getValue("card").jsonPrimitive.content)
        require(card.decodeToString().contains("ws://127.0.0.1:")) { throw error }
        NativePairing(
            card,
            root.getValue("secret").jsonPrimitive.content.hexToBytes(),
            root.getValue("exp").jsonPrimitive.content.toLong(),
        )
    }

    private data class NativePairing(val card: ByteArray, val pairingSecret: ByteArray, val expiresAt: Long)

    private fun postJson(url: String) = URL(url).openConnection().let { connection ->
        connection as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            require(connection.responseCode in 200..299) { "fixture control rejected pairing" }
            Json.parseToJsonElement(connection.inputStream.bufferedReader().use { it.readText() }).jsonObject
        } finally { connection.disconnect() }
    }

    private fun epochSeconds() = System.currentTimeMillis() / 1000

    private class MemoryStorage : RoomStorage {
        private var value: ByteArray? = null
        override fun read(): ByteArray? = value?.copyOf()
        override fun write(value: ByteArray) { this.value = value.copyOf() }
        override fun reset() { value = null }
    }
}
