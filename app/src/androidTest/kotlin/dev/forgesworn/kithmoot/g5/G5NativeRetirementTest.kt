package dev.forgesworn.kithmoot.g5

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.protocol.BothyPairing
import dev.forgesworn.kithmoot.relay.LinkTransportManager
import dev.forgesworn.kithmoot.relay.LinkTransportVault
import dev.forgesworn.kithmoot.relay.ReflectiveLinkTransportRuntime
import dev.forgesworn.kithmoot.relay.RelaySocketListener
import dev.forgesworn.kithmoot.storage.RoomStorage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Focused native proof for reconnect, authenticated deletion and same-session retry. */
class G5NativeRetirementTest {
    @Test fun retires_after_server_restart_and_repeats_on_the_same_session() {
        val control = requireNotNull(InstrumentationRegistry.getArguments().getString("fixture_control")).removeSuffix("/")
        val pairing = BothyPairing.parse(post("$control/pairing").getValue("uri").jsonPrimitive.content, System.currentTimeMillis() / 1000)
        val manager = LinkTransportManager(LinkTransportVault(MemoryStorage()), ReflectiveLinkTransportRuntime())
        try {
            val route = manager.pair(pairing.card, pairing.pairingSecret, pairing.expiresAt).get(120, TimeUnit.SECONDS)
            assertEquals(1, get("$control/ready").getValue("event_route_count").jsonPrimitive.content.toInt())
            post("$control/restart")

            val opened = AtomicBoolean(false)
            val failure = AtomicReference<String?>(null)
            val linkNode = get("$control/ready").getValue("link_node_id").jsonPrimitive.content
            val socket = manager.open("ws://$linkNode/events", route.routeId, object : RelaySocketListener {
                override fun onOpen() { opened.set(true) }
                override fun onMessage(text: String) = Unit
                override fun onClosed(reason: String) { failure.set(reason) }
            })
            try {
                val deadline = SystemClock.uptimeMillis() + 120_000
                while (!opened.get()) {
                    failure.get()?.let { throw AssertionError("retained route failed after restart: $it") }
                    assertTrue("retained route timed out after restart", SystemClock.uptimeMillis() < deadline)
                    SystemClock.sleep(50)
                }
            } finally { socket.close() }

            manager.retire(route.routeId).get(120, TimeUnit.SECONDS)
            assertEquals(0, get("$control/ready").getValue("event_route_count").jsonPrimitive.content.toInt())
            assertEquals(1, get("$control/ready").getValue("event_route_admission_count").jsonPrimitive.content.toInt())

            val deniedOpen = AtomicBoolean(false)
            val denied = AtomicReference<String?>(null)
            val deniedSocket = manager.open("ws://$linkNode/events", route.routeId, object : RelaySocketListener {
                override fun onOpen() { deniedOpen.set(true) }
                override fun onMessage(text: String) = Unit
                override fun onClosed(reason: String) { denied.set(reason) }
            })
            try {
                val deadline = SystemClock.uptimeMillis() + 120_000
                while (denied.get() == null) {
                    assertTrue("retired route access timed out", SystemClock.uptimeMillis() < deadline)
                    SystemClock.sleep(50)
                }
                assertTrue("retired route retained application access", !deniedOpen.get())
            } finally { deniedSocket.close() }

            manager.retire(route.routeId).get(120, TimeUnit.SECONDS)
            assertTrue(manager.routeIds().contains(route.routeId))
            manager.finalize(route.routeId).get(120, TimeUnit.SECONDS)
            val finalizeDeadline = SystemClock.uptimeMillis() + 10_000
            while (get("$control/ready").getValue("event_route_admission_count").jsonPrimitive.content.toInt() != 0) {
                assertTrue("route finalisation timed out", SystemClock.uptimeMillis() < finalizeDeadline)
                SystemClock.sleep(50)
            }
            manager.remove(route.routeId)
            assertTrue(manager.routeIds().isEmpty())
        } finally { manager.close() }
    }

    private fun get(url: String) = request(url, "GET")
    private fun post(url: String) = request(url, "POST")

    private fun request(url: String, method: String) = URL(url).openConnection().let { raw ->
        val connection = raw as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 120_000
            require(connection.responseCode in 200..299) { "fixture control rejected $url" }
            Json.parseToJsonElement(connection.inputStream.bufferedReader().use { it.readText() }).jsonObject
        } finally { connection.disconnect() }
    }

    private class MemoryStorage : RoomStorage {
        private var value: ByteArray? = null
        override fun read(): ByteArray? = value?.copyOf()
        override fun write(value: ByteArray) { this.value = value.copyOf() }
        override fun reset() { value = null }
    }
}
