package dev.forgesworn.kithmoot.node

import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.*
import dev.forgesworn.kithmoot.session.*
import dev.forgesworn.kithmoot.storage.RoomStorage
import dev.forgesworn.kithmoot.storage.SavedRoom
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.*
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.SocketFactory
import javax.net.ssl.*

/** Real Android protocol/runtime on a disposable emulator and private native
 * relay. Every identity and room in this opt-in fixture is synthetic. */
class ExistingNodeInteropTest {
    @Test fun existingDeviceFollowsBrowserAndRejoinsWithKeeperOffline() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val path = InstrumentationRegistry.getArguments().getString("nodeConfig")
        assumeTrue("Explicit existing-room fixture was not requested", path != null)
        require(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val config = Json.parseToJsonElement(File(requireNotNull(path)).readText()).jsonObject
        val now = { System.currentTimeMillis() / 1000 }
        val secret = config.getValue("secret").jsonPrimitive.content.hexToBytes()
        val ownerKey = config.getValue("ownerKey").jsonPrimitive.content.hexToBytes()
        val room = deriveRoom(secret)
        val identity = PrimaryIdentity.create(room.roomId, now() + 86400, now(), participantSecretKey = ownerKey)
        val root = config.getValue("authority").jsonPrimitive.content
        val sourceRelay = "wss://node.example/source"
        val sourceLink = encodeJoinUrl("https://app.example/j/", secret, listOf(sourceRelay))
        var saved = SavedRoom.create(secret, identity, sourceLink, listOf(sourceRelay), "Migration fixture", now(), null, root)
        val http = fixtureHttp(config.getValue("certificate").jsonPrimitive.content, config.getValue("port").jsonPrimitive.int)
        val sockets = OkHttpRelaySockets(http)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val pool = RelayPool(listOf(sourceRelay), sockets, scope)
        val store = NodeStore(InteropMemoryStorage(), InteropMemoryStorage(), InteropMemoryStorage())
        var session: RoomSession? = null
        var work: RoomWork? = null
        var target: NodeRoomTransport? = null
        try { withTimeout(180_000) {
            pool.start()
            val old = RoomSession(room, identity, pool, scope, authority = root, callBellEnabled = false)
            session = old; old.join()
            val destination = CompletableDeferred<NodeRoomRecord>()
            work = RoomWork(room.roomId, room.roomKey, identity, pool, object : AssignmentStorage {
                override suspend fun load(): String? = null
                override suspend fun save(encrypted: String) = Unit
            }, scope, authority = root, onNodeDestination = { raw ->
                val node = NodeRooms.read(raw, now(), root)
                if (node != null && existingNodeMatches(saved, node)) destination.complete(node)
            }).also { it.open() }
            old.sendChatConfirmed("Android before move")
            val node = destination.await()
            val signer = LocalSigner(identity.deviceSecretKey)
            val client = NodeClient(http, now)
            val access = client.access(node, null, signer) as NodeAccess.Active
            val moved = acceptExistingNodeDestination(saved, identity, node, access.grant.event.toJson(), old.epochKeys().epoch, store, now())
            assertEquals(saved.id, moved.id)
            assertEquals(saved.devicePubkey, moved.devicePubkey)
            assertEquals(saved.json.getValue("identity"), moved.json.getValue("identity"))
            saved = moved
            val originals = old.nodeMigrationHistory()
            val privateTransport = NodeRoomTransport.create(node, signer, NodeDoor.ROOM, sockets, scope, now)
            target = privateTransport; privateTransport.start(); check(privateTransport.awaitReady())
            copyExistingNodeHistory(originals, privateTransport, { true }, now)
            work?.close(); work = null; old.leave(); pool.stop()
            val live = RoomSession(room, identity, privateTransport, scope, authority = root, nodeRoom = node, callBellEnabled = false)
            session = live; live.join()
            withTimeout(30_000) { live.chat.first { messages -> messages.any { it.body == "Before move from device 0" } } }
            live.sendChatConfirmed("Android after move")
            withTimeout(60_000) { live.chat.first { messages -> messages.any { it.body == "Keeper offline; reconnect Android" } } }
            live.leave(); privateTransport.close()
            val held = requireNotNull(store.custody(identity.participant, room.roomId))
            assertEquals(identity.devicePubkey, held.device)
            assertTrue(client.access(node, null, signer) is NodeAccess.Active)
            val reconnected = NodeRoomTransport.create(node, signer, NodeDoor.ROOM, sockets, scope, now)
            target = reconnected; reconnected.start(); check(reconnected.awaitReady())
            val returning = RoomSession(room, identity, reconnected, scope, authority = root, nodeRoom = node, callBellEnabled = false)
            session = returning; returning.join(); returning.sendChatConfirmed("Android returned with original identity")
            println("Existing room Android migration, private history and keeper-offline reconnect passed")
        } } finally {
            work?.close(); session?.leave(); target?.close(); pool.stop(); scope.cancel()
            http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown(); secret.fill(0); ownerKey.fill(0)
        }
    }

    private fun fixtureHttp(certificate: String, port: Int): OkHttpClient {
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(certificate.byteInputStream())
        val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("fixture", cert) }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }.trustManagers.single() as X509TrustManager
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        val sockets = object : SocketFactory() {
            override fun createSocket(): Socket = object : Socket() {
                override fun connect(endpoint: SocketAddress, timeout: Int) {
                    val target = endpoint as InetSocketAddress
                    require(target.port == 443 && target.address.hostAddress == "10.0.2.2")
                    super.connect(InetSocketAddress("10.0.2.2", port), timeout)
                }
                override fun connect(endpoint: SocketAddress) = connect(endpoint, 0)
            }
            override fun createSocket(host: String, port: Int) = createSocket().apply { connect(InetSocketAddress(host, port)) }
            override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int) = createSocket().apply { bind(InetSocketAddress(local, localPort)); connect(InetSocketAddress(host, port)) }
            override fun createSocket(host: InetAddress, port: Int) = createSocket().apply { connect(InetSocketAddress(host, port)) }
            override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int) = createSocket().apply { bind(InetSocketAddress(local, localPort)); connect(InetSocketAddress(host, port)) }
        }
        return OkHttpClient.Builder().socketFactory(sockets).sslSocketFactory(tls.socketFactory, trust)
            .dns { hostname -> require(hostname == "node.example"); listOf(InetAddress.getByAddress(hostname, byteArrayOf(10, 0, 2, 2))) }
            .followRedirects(false).followSslRedirects(false).build()
    }
}

private class InteropMemoryStorage : RoomStorage {
    private var bytes: ByteArray? = null
    @Synchronized override fun read() = bytes?.copyOf()
    @Synchronized override fun write(value: ByteArray) { bytes = value.copyOf() }
    @Synchronized override fun reset() { bytes = null }
}
