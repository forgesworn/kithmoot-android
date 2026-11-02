package dev.forgesworn.kithmoot.node

import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.epoch.*
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.*
import dev.forgesworn.kithmoot.session.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.*
import org.junit.Assume.assumeTrue
import java.io.File
import java.net.*
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import javax.net.ssl.*
import kotlin.test.*

/** Opt-in real supervisor/Spry/Shelter protocol journey. No native peer media
 * or public TLS443 reachability is inferred from this loopback transport test. */
class NodeRealBackendTest {
    @Test fun `real node admits fresh epochs chats revokes rekeys closes and proves purge`() {
        val path = System.getenv("E2E_NODE_NATIVE_CONFIG")
        assumeTrue("Private node fixture was not requested", path != null)
        val config = try { Json.parseToJsonElement(File(requireNotNull(path)).readText()).jsonObject }
            catch (_: Exception) { error("Native fixture config unreadable") }
        val port = config.getValue("port").jsonPrimitive.int
        val http = nativeHttp(config.getValue("certificate").jsonPrimitive.content, port)
        val ownerKey = Entropy.bytes(32); val owner = LocalSigner(ownerKey)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val sessions = mutableListOf<RoomSession>()
        val teardown = mutableListOf<() -> Unit>()
        var stage = "pair"
        try { runBlocking { withTimeout(60_000) {
            val now = { System.currentTimeMillis() / 1000 }
            val client = NodeClient(http, now)
            val pair = client.pair(NodePairLink.parse(config.getValue("pairing").jsonPrimitive.content, now()), owner)
            stage = "status"
            assertTrue(client.status(pair, owner).ready)
            val ownerStore = NodeStore(NodeMemoryStorage(), NodeMemoryStorage(), NodeMemoryStorage())
            val guestStore = NodeStore(NodeMemoryStorage(), NodeMemoryStorage(), NodeMemoryStorage())
            val creator = NodeRoomCreator(ownerStore, NodeJoinIdentityStore(NodeMemoryStorage()), client, { true }, now)
            val prepared = creator.prepare(pair, owner)
            stage = "enrol and grant"
            val created = creator.complete(prepared.node.room, owner)
            val node = created.custody.node
            val secret = created.custody.roomSecret
            val room = deriveRoom(secret)
            val ownerVault = EpochVault(NodeMemoryStorage(), NodeMemoryStorage())
            ownerVault.initialise(node.room, node.authority, secret, now())
            secret.fill(0)
            val sockets = OkHttpRelaySockets(http)
            val keeper = NodeKeeperAdmissions(node, owner.pubkey, ownerStore, { event, signer -> client.postGrant(node, event, signer) }, { true }, now)
            val inbox = NodeKeeperInbox(node, owner.pubkey, ownerStore, keeper, ownerVault, sockets, scope, { true }, now)
            teardown += inbox::close; inbox.start()
            val guest = NodeJoinIdentityStore(NodeMemoryStorage()).identity(node, null, now, { true })
            val joining = scope.async { joinNodeAsGuest(node, guest, guestStore, sockets, { true }, now) }
            stage = "guest ask"
            val ask = withTimeout(10_000) { inbox.requests.first { rows -> rows.any { it.device == guest.devicePubkey } }.single { it.device == guest.devicePubkey } }
            assertNull(guestStore.custody(guest.participant, node.room))
            inbox.admit(ask.id)
            stage = "guest allow"
            val admitted = withTimeout(10_000) { joining.await() }
            assertNotNull(guestStore.custody(guest.participant, node.room)?.grant)
            val ownerSigner = LocalSigner(created.identity.deviceSecretKey)
            val guestSigner = LocalSigner(guest.deviceSecretKey)
            val ownerGrant = keeper.state().devices.single { it.device == created.identity.devicePubkey }.grant
            val guestGrant = keeper.state().devices.single { it.device == guest.devicePubkey }.grant
            val ownerAccess = assertIs<NodeAccess.Active>(client.access(node, ownerGrant.grantId, ownerSigner))
            stage = "TURN"
            val login = client.turn(node, ownerAccess, ownerSigner)
            assertEquals(4, login.urls.size)
            assertTrue(login.urls.any { it == "turns:turn.node.example:443?transport=tcp" })
            assertTrue(login.expiresAt in now() + 1..now() + 300)
            val ownerTransport = NodeRoomTransport.create(node, ownerSigner, NodeDoor.ROOM, sockets, scope, now)
            val guestTransport = NodeRoomTransport.create(node, guestSigner, NodeDoor.ROOM, sockets, scope, now)
            teardown += ownerTransport::close; teardown += guestTransport::close
            ownerTransport.start(); guestTransport.start()
            withTimeout(10_000) { ownerTransport.connected.first { node.relay in it } }
            withTimeout(10_000) { guestTransport.connected.first { node.relay in it } }
            val desk = NodeRootEpochDesk(node, owner.pubkey, ownerStore, ownerVault, { true }, now)
            val ownerLive = RoomSession(room, created.identity, ownerTransport, scope, authority = node.authority,
                nodeRoom = node, now = now, epochProbe = false, requireFreshEpoch = true,
                epochResponder = desk::answer, epochGate = { event, notice ->
                    if (notice.closed) ownerVault.terminal(node.room, ownerVault.get(node.room)!!.currentEpoch, notice, event.id, now())
                    else requireNotNull(ownerVault.follow(node.room, notice, event.id, now()))
                    EpochGateResult.COMMITTED
                })
            stage = "owner fresh epoch"
            sessions += ownerLive; ownerLive.join()
            stage = "guest fresh epoch"
            val guestLive = RoomSession(room, guest, guestTransport, scope, authority = node.authority,
                nodeRoom = node, now = now, epochProbe = false, requireFreshEpoch = true,
                epochGate = { _, _ -> error("No guest epoch change expected before revocation") })
            sessions += guestLive; guestLive.join()
            stage = "chat"
            ownerLive.sendChatConfirmed("Owner over the private node")
            withTimeout(10_000) { guestLive.chat.first { rows -> rows.any { it.body == "Owner over the private node" } } }
            guestLive.sendChatConfirmed("Guest over the private node")
            withTimeout(10_000) { ownerLive.chat.first { rows -> rows.any { it.body == "Guest over the private node" } } }
            val binding = RoomRekeyBinding(node.room, node.authority, created.identity.devicePubkey, null, listOf(node.relay), RoomRoute.INTERNET)
            val ledger = RoomRekeyLedger(NodeMemoryStorage(), binding, createIfMissing = true)
            val courier = RoomRekeyCourier.startAuthority(ledger, NativeKeeperEndpoints(binding, null, ownerTransport.observationPool), scope, { true })
            teardown += courier::close
            stage = "remove and rekey"
            NodeKeeperRekeys(node, owner.pubkey, ownerStore, keeper, ownerVault, courier, { true }, now).remove(guest.participant, ownerLive)
            assertEquals(1, ownerLive.epochKeys().epoch)
            assertNull(ownerStore.custody(owner.pubkey, node.room)?.pendingRemoval)
            var guestWiped = false
            val access = NodeDeviceAccess(node, guest.participant, guest.devicePubkey, guestStore,
                { id -> client.access(node, id, guestSigner) }, { true }, {},
                { guestStore.forgetRoom(node.room); guestWiped = true }, { error("Not closed yet") }, now)
            try { assertIs<NodeAccess.Removed>(access.refresh()) } finally { access.close() }
            assertTrue(guestWiped); assertNull(guestStore.custody(guest.participant, node.room))
            assertIs<NodeAccess.Removed>(client.access(node, guestGrant.grantId, guestSigner))
            var ownerWiped = false
            val closer = NodeRoomCloser(node, owner.pubkey, ownerStore, client::close,
                { ownerStore.forgetRoom(node.room); ownerWiped = true }, { true }, now)
            val close = closer.stage(ownerLive::epochKeys)
            stage = "close"
            val operation = NodeEvidence.string(close, "operation")
            closer.retry()
            assertTrue(ownerWiped); assertNull(ownerStore.custody(owner.pubkey, node.room))
            assertEquals(operation, ownerStore.operations(owner.pubkey).single().operation)
            val done = withTimeout(12_000) {
                var progress = client.operation(pair, operation, owner)
                while (progress.phase != "done") { delay(100); progress = client.operation(pair, operation, owner) }
                progress
            }
            assertEquals(operation, requireNotNull(done.receipt).operation)
            assertIs<NodeAccess.Closed>(client.access(node, guestGrant.grantId, guestSigner))
            val status = client.status(pair, owner)
            assertEquals(0L, status.rooms); assertEquals(0L, status.devices)
            assertEquals(node.event.id, admitted.node.event.id)
            room.roomKey.fill(0)
        } } } catch (error: Throwable) {
            throw AssertionError("Native node stage: $stage; HTTP status: ${(error as? NodeRequestException)?.status ?: "none"}", error)
        } finally {
            runBlocking { withContext(NonCancellable) { sessions.asReversed().forEach { runCatching { it.leave() } } } }
            teardown.asReversed().forEach { runCatching(it) }
            scope.cancel(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown(); ownerKey.fill(0)
        }
    }

    private fun nativeHttp(certificate: String, port: Int): OkHttpClient {
        require(port in 1024..65535)
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(certificate.byteInputStream())
        val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("task-node", cert) }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
            .trustManagers.single() as X509TrustManager
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        // URL, Host, SNI and NIP-98 audiences stay node.example:443. Only the
        // test socket destination changes; default hostname verification stays.
        val sockets = object : SocketFactory() {
            override fun createSocket(): Socket = object : Socket() {
                override fun connect(endpoint: SocketAddress, timeout: Int) {
                    val target = endpoint as InetSocketAddress
                    require(target.port == 443 && target.address.isLoopbackAddress)
                    super.connect(InetSocketAddress("127.0.0.1", port), timeout)
                }
                override fun connect(endpoint: SocketAddress) = connect(endpoint, 0)
            }
            override fun createSocket(host: String, port: Int) = createSocket().apply { connect(InetSocketAddress(host, port)) }
            override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int) = createSocket().apply { bind(InetSocketAddress(local, localPort)); connect(InetSocketAddress(host, port)) }
            override fun createSocket(host: InetAddress, port: Int) = createSocket().apply { connect(InetSocketAddress(host, port)) }
            override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int) = createSocket().apply { bind(InetSocketAddress(local, localPort)); connect(InetSocketAddress(host, port)) }
        }
        return OkHttpClient.Builder().socketFactory(sockets).sslSocketFactory(tls.socketFactory, trust)
            .eventListener(object : EventListener() {
                override fun callFailed(call: Call, ioe: java.io.IOException) { println("Native fixture network failure: ${ioe.javaClass.simpleName}: ${ioe.message}") }
            })
            .dns(object : Dns { override fun lookup(hostname: String): List<InetAddress> {
                require(hostname == "node.example")
                return listOf(InetAddress.getByAddress(hostname, byteArrayOf(127, 0, 0, 1)))
            } })
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .callTimeout(10, TimeUnit.SECONDS).build()
    }
}
