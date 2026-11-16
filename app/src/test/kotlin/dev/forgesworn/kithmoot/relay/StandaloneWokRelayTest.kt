package dev.forgesworn.kithmoot.relay

import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.account.npubOf
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.protocol.CircleGrantStatus
import dev.forgesworn.kithmoot.protocol.CircleGrantTerms
import dev.forgesworn.kithmoot.protocol.Events
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assume.assumeTrue
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StandaloneWokRelayTest {
    @Test fun `native Wok grant publication readback and revocation`() {
        val binary = System.getenv("WOKD_BIN")
        assumeTrue("Set WOKD_BIN to run the real Wok socket acceptance", binary != null)
        val port = ServerSocket(0).use { it.localPort }
        val root = Files.createTempDirectory("standalone-wok-relay").toFile()
        val keeper = LocalSigner(ByteArray(32) { 1 })
        val guest = LocalSigner("02".repeat(32).hexToBytes())
        val device = LocalSigner("03".repeat(32).hexToBytes())
        val server = "wss://relay.example.org/events"
        val config = File(root, "wokd.toml")
        config.writeText("""listen = "127.0.0.1:$port"
url = "$server"
data_dir = "${File(root, "state")}"
max_store_bytes = 67108864
min_free_bytes = 0
members = ["${npubOf(keeper.pubkey)}"]
""")
        var process: Process? = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            process = ProcessBuilder(binary, "--config", config.absolutePath)
                .redirectErrorStream(true).redirectOutput(File(root, "wok.log")).start()
            awaitListener(process, port)
            val sockets = RelaySocketFactory { url, listener ->
                OkHttpRelaySockets().open(url.replace("wss://relay.example.org", "ws://127.0.0.1:$port"), listener)
            }
            val vectors = Json.parseToJsonElement(File("../protocol/src/test/resources/room-grant-vectors.json").readText()).jsonObject
            val now = System.currentTimeMillis() / 1000
            val terms = CircleGrantTerms(server, "11".repeat(32), guest.pubkey, device.pubkey,
                vectors.getValue("grantId").jsonPrimitive.content, now + 3600)
            val keeperPool = StandaloneWokRelay(sockets).pool(server, keeper, scope)
            val guestPool = StandaloneWokRelay(sockets).pool(server, guest, scope)
            try {
                runBlocking {
                    val active = keeper.sign(24242, now, terms.tags(CircleGrantStatus.ACTIVE), "")
                    val revoked = keeper.sign(24242, now + 1, terms.tags(CircleGrantStatus.REVOKED), "")
                    withTimeout(20_000) {
                        keeperPool.start()
                        keeperPool.connected.first { server in it }
                    }
                    assertTrue(keeperPool.publishConfirmed(active))
                    assertTrue(keeperPool.publishConfirmed(active))
                    guestPool.start()
                    withTimeout(20_000) { guestPool.connected.first { server in it } }
                    val note = Events.sign(device.secretKeyForStorage(), 1460, System.currentTimeMillis() / 1000,
                        listOf(listOf("d", "11".repeat(32))), "native scoped fixture")
                    assertTrue(guestPool.publishConfirmed(note))
                    val stored = guestPool.queryStored(listOf(Filter(kinds = listOf(1460), tags = mapOf("#d" to listOf("11".repeat(32))))))
                    assertEquals(listOf(note.id), stored.map { it.id })
                    assertTrue(keeperPool.publishConfirmed(revoked))
                    val refused = Events.sign(device.secretKeyForStorage(), 1460, System.currentTimeMillis() / 1000 + 1,
                        listOf(listOf("d", "11".repeat(32))), "revoked fixture")
                    assertFalse(guestPool.publishConfirmed(refused))
                }
            } finally {
                keeperPool.stop(); guestPool.stop()
            }
        } catch (error: Throwable) {
            println(File(root, "wok.log").readText())
            throw error
        } finally {
            scope.cancel()
            process?.destroy()
            root.deleteRecursively()
        }
    }

    private fun awaitListener(process: Process, port: Int) {
        repeat(100) {
            check(process.isAlive) { "wokd exited before listening" }
            try { Socket("127.0.0.1", port).close(); return } catch (_: Exception) { Thread.sleep(50) }
        }
        error("wokd did not listen")
    }
}
