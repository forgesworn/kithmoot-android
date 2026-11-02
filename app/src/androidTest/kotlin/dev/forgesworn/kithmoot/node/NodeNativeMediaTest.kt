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
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.storage.RoomStorage
import org.webrtc.VideoTrack
import org.webrtc.VideoSink
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.collect
import java.io.File
import java.net.*
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import javax.net.ssl.*
import org.junit.Test
import org.junit.Assert.*

/** Opt-in actual-service native relay media on a task-owned no-Play emulator.
 * Loopback evidence does not prove public TLS443, NAT or physical GrapheneOS. */
class NodeNativeMediaTest {
    private val turnResponses = AtomicInteger()
    @Test fun realNodeRelaysNativeVideoAndRevocationStopsMedia() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val qualifyRenewal = InstrumentationRegistry.getArguments().getString("nodeRenewal") == "true"
        assertTrue(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        for (permission in listOf(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO))
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        val path = InstrumentationRegistry.getArguments().getString("nodeConfig")
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
        try { runBlocking { withTimeout(if (qualifyRenewal) 450_000 else 150_000) {
            val now = { System.currentTimeMillis() / 1000 }
            val client = NodeClient(http, now)
            val pair = client.pair(NodePairLink.parse(config.getValue("pairing").jsonPrimitive.content, now()), owner)
            stage = "status"
            assertTrue(client.status(pair, owner).ready)
            val ownerStore = NodeStore(NativeNodeMemoryStorage(), NativeNodeMemoryStorage(), NativeNodeMemoryStorage())
            val guestStore = NodeStore(NativeNodeMemoryStorage(), NativeNodeMemoryStorage(), NativeNodeMemoryStorage())
            val creator = NodeRoomCreator(ownerStore, NodeJoinIdentityStore(NativeNodeMemoryStorage()), client, { true }, now)
            val prepared = creator.prepare(pair, owner)
            stage = "enrol and grant"
            val created = creator.complete(prepared.node.room, owner)
            val node = created.custody.node
            val secret = created.custody.roomSecret
            val room = deriveRoom(secret)
            val ownerVault = EpochVault(NativeNodeMemoryStorage(), NativeNodeMemoryStorage())
            ownerVault.initialise(node.room, node.authority, secret, now())
            secret.fill(0)
            val sockets = OkHttpRelaySockets(http)
            val keeper = NodeKeeperAdmissions(node, owner.pubkey, ownerStore, { event, signer -> client.postGrant(node, event, signer) }, { true }, now)
            val inbox = NodeKeeperInbox(node, owner.pubkey, ownerStore, keeper, ownerVault, sockets, scope, { true }, now)
            teardown += inbox::close; inbox.start()
            val guest = NodeJoinIdentityStore(NativeNodeMemoryStorage()).identity(node, null, now, { true })
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
            assertTrue(login.expiresAt - requireNotNull(login.issuedAt) in 1..300)
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
            stage = "native relay media"
            val unavailable = java.util.concurrent.atomic.AtomicBoolean(false)
            var guestWiped = false
            val ownerAccessWatch = NodeDeviceAccess(node, owner.pubkey, created.identity.devicePubkey, ownerStore,
                { id -> client.access(node, id, ownerSigner) }, { true }, {}, {}, {}, now)
            val guestAccessWatch = NodeDeviceAccess(node, guest.participant, guest.devicePubkey, guestStore,
                { id -> client.access(node, id, guestSigner) }, { true }, {}, { guestStore.forgetRoom(node.room); guestWiped = true }, { error("Not closed yet") }, now)
            teardown += ownerAccessWatch::close; teardown += guestAccessWatch::close
            val sender = NodeManagedCall.create(context, ownerLive, ownerSigner, ownerAccessWatch, client, scope, { error("Owner call lost access") })
            val receiver = NodeManagedCall.create(context, guestLive, guestSigner, guestAccessWatch, client, scope, { unavailable.set(true) })
            teardown += sender::dispose; teardown += receiver::dispose
            val relayCandidates = AtomicInteger()
            val leakedCandidates = AtomicInteger()
            for (live in listOf(ownerLive, guestLive)) scope.launch {
                live.signals.collect { signal ->
                    val candidates = listOfNotNull(signal.body.candidate) + signal.body.candidates.orEmpty()
                    candidates.filter { it.isNotBlank() }.forEach {
                        if (" typ relay" in it) relayCandidates.incrementAndGet() else leakedCandidates.incrementAndGet()
                    }
                }
            }
            org.webrtc.Logging.enableLogToDebugOutput(org.webrtc.Logging.Severity.LS_WARNING)
            sender.engine.start(); receiver.engine.start()
            scope.launch {
                repeat(12) {
                    delay(5000)
                    println("Native node ICE: sender=${sender.engine.connections.value.values} receiver=${receiver.engine.connections.value.values} relayCandidates=${relayCandidates.get()}")
                }
            }
            assertNotNull(sender.engine.localMedia.startCamera())
            withTimeout(60_000) {
                sender.engine.connections.first { it[guest.devicePubkey] == "connected" }
                receiver.engine.connections.first { it[created.identity.devicePubkey] == "connected" }
            }
            val frames = AtomicInteger()
            val sink = VideoSink { frames.incrementAndGet() }
            val remote = withTimeout(30_000) { receiver.engine.remoteTracks.first { tracks -> tracks.any { it.track is VideoTrack && it.role == Roles.CAMERA } }
                .first { it.track is VideoTrack && it.role == Roles.CAMERA }.track as VideoTrack }
            remote.addSink(sink)
            try {
                withTimeout(30_000) { while (frames.get() < 20) delay(100) }
                assertTrue(relayCandidates.get() > 0)
                assertEquals(0, leakedCandidates.get())
                println("Native node media: connected=true remoteFrames=${frames.get()} relayCandidates=${relayCandidates.get()}")
                if (qualifyRenewal) {
                    stage = "native credential renewal"
                    val before = turnResponses.get()
                    withTimeout(280_000) { while (turnResponses.get() < before + 2) delay(500) }
                    val targetFrames = frames.get() + 20
                    withTimeout(30_000) {
                        sender.engine.connections.first { it[guest.devicePubkey] == "connected" }
                        receiver.engine.connections.first { it[created.identity.devicePubkey] == "connected" }
                        while (frames.get() < targetFrames) delay(100)
                    }
                    assertEquals(0, leakedCandidates.get())
                    println("Native node renewal: both devices renewed; video continued; remoteFrames=${frames.get()}")
                }
            } finally { remote.removeSink(sink) }
            stage = "chat"
            ownerLive.sendChatConfirmed("Owner over the private node")
            withTimeout(10_000) { guestLive.chat.first { rows -> rows.any { it.body == "Owner over the private node" } } }
            guestLive.sendChatConfirmed("Guest over the private node")
            withTimeout(10_000) { ownerLive.chat.first { rows -> rows.any { it.body == "Guest over the private node" } } }
            val binding = RoomRekeyBinding(node.room, node.authority, created.identity.devicePubkey, null, listOf(node.relay), RoomRoute.INTERNET)
            val ledger = RoomRekeyLedger(NativeNodeMemoryStorage(), binding, createIfMissing = true)
            val courier = RoomRekeyCourier.startAuthority(ledger, NativeKeeperEndpoints(binding, null, ownerTransport.observationPool), scope, { true })
            teardown += courier::close
            stage = "remove and rekey"
            NodeKeeperRekeys(node, owner.pubkey, ownerStore, keeper, ownerVault, courier, { true }, now).remove(guest.participant, ownerLive)
            assertEquals(1, ownerLive.epochKeys().epoch)
            assertNull(ownerStore.custody(owner.pubkey, node.room)?.pendingRemoval)
            assertIs<NodeAccess.Removed>(guestAccessWatch.refresh())
            withTimeout(5_000) { while (!unavailable.get()) delay(100) }
            assertTrue(receiver.engine.connections.value.isEmpty())
            assertTrue(receiver.engine.localMedia.tracks.value.isEmpty())
            assertTrue(receiver.engine.remoteTracks.value.isEmpty())
            println("Native node removal: access revoked and native media stopped")
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
        return OkHttpClient.Builder().addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (chain.request().url.encodedPath == "/v1/credentials" && response.isSuccessful) {
                val body = Json.parseToJsonElement(response.peekBody(65536).string()).jsonObject
                val now = System.currentTimeMillis() / 1000
                turnResponses.incrementAndGet()
                println("Native TURN validation: ttl=${body.getValue("expiresAt").jsonPrimitive.long - now} accessTtl=${body.getValue("accessExpiresAt").jsonPrimitive.long - now} urls=${body.getValue("urls").jsonArray.size}")
            }
            response
        }.socketFactory(sockets).sslSocketFactory(tls.socketFactory, trust)
            .eventListener(object : EventListener() {
                override fun callFailed(call: Call, ioe: java.io.IOException) { println("Native fixture network failure: ${ioe.javaClass.simpleName}: ${ioe.message}") }
            })
            .dns(object : Dns { override fun lookup(hostname: String): List<InetAddress> {
                require(hostname == "node.example")
                return listOf(InetAddress.getByAddress(hostname, byteArrayOf(10, 0, 2, 2)))
            } })
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .callTimeout(10, TimeUnit.SECONDS).build()
    }
}

private class NativeNodeMemoryStorage : RoomStorage {
    private var bytes: ByteArray? = null
    @Synchronized override fun read() = bytes?.copyOf()
    @Synchronized override fun write(value: ByteArray) { bytes = value.copyOf() }
    @Synchronized override fun reset() { bytes = null }
}

private inline fun <reified T> assertIs(value: Any?): T { assertTrue(value is T); return value as T }
