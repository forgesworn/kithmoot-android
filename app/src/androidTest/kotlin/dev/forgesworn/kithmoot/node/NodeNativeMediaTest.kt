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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assume.assumeTrue
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.storage.RoomStorage
import org.webrtc.VideoTrack
import org.webrtc.VideoSink
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import dev.forgesworn.kithmoot.media.resolveRemoteByRole
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
    private val temporaryTurnFailures = AtomicInteger()
    /** Opt-in guest for a real browser keeper. The fixture passes only its
     * ask-only invitation and test CA; no keeper/room secret enters config. */
    @Test fun browserKeeperAndNativeGuestExchangeRelayMedia() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertTrue(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val path = InstrumentationRegistry.getArguments().getString("nodeConfig")
        assumeTrue("Private browser/native fixture was not requested", path != null)
        val config = Json.parseToJsonElement(File(requireNotNull(path)).readText()).jsonObject
        val link = config["browserLink"]?.jsonPrimitive?.content
        assumeTrue("Browser guest mode was not requested", link != null)
        val context = instrumentation.targetContext
        for (permission in listOf(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO))
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        val now = { System.currentTimeMillis() / 1000 }
        val node = requireNotNull(NodeRooms.parseLink(requireNotNull(link), now()))
        val http = nativeHttp(config.getValue("certificate").jsonPrimitive.content, config.getValue("port").jsonPrimitive.int)
        val client = NodeClient(http, now)
        val store = NodeStore(NativeNodeMemoryStorage(), NativeNodeMemoryStorage(), NativeNodeMemoryStorage())
        val identity = runBlocking { NodeJoinIdentityStore(NativeNodeMemoryStorage()).identity(node, null, now, { true }) }
        val sockets = OkHttpRelaySockets(http)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val teardown = mutableListOf<() -> Unit>()
        var session: RoomSession? = null
        var stage = "ask browser keeper"
        try { runBlocking { withTimeout(480_000) {
            val admitted = joinNodeAsGuest(node, identity, store, sockets, { true }, now, name = "Native guest")
            val secret = admitted.roomSecret
            val room = deriveRoom(secret); secret.fill(0)
            val signer = LocalSigner(identity.deviceSecretKey)
            val transport = NodeRoomTransport.create(node, signer, NodeDoor.ROOM, sockets, scope, now)
            teardown += transport::close; transport.start()
            withTimeout(10_000) { transport.connected.first { node.relay in it } }
            val live = RoomSession(room, identity, transport, scope, authority = node.authority,
                nodeRoom = node, now = now, epochProbe = false, requireFreshEpoch = true,
                epochGate = { _, _ -> EpochGateResult.COMMITTED })
            session = live
            val relayed = AtomicInteger(); val leaked = AtomicInteger()
            scope.launch(start = CoroutineStart.UNDISPATCHED) { live.signals.collect { signal ->
                (listOfNotNull(signal.body.candidate) + signal.body.candidates.orEmpty() +
                    signal.body.sdp.orEmpty().lineSequence().map { it.trim() }.filter { it.startsWith("a=candidate:") }.map { it.removePrefix("a=") }.toList()).filter { it.isNotBlank() }.forEach {
                    if (" typ relay" in it) relayed.incrementAndGet() else leaked.incrementAndGet()
                }
            } }
            stage = "fresh browser Root epoch"; live.join()
            val unavailable = java.util.concurrent.atomic.AtomicBoolean(false)
            val watch = NodeDeviceAccess(node, identity.participant, identity.devicePubkey, store,
                { id -> client.access(node, id, signer) }, { true }, {}, { store.forgetRoom(node.room) }, {}, now)
            teardown += watch::close
            val call = NodeManagedCall.create(context, live, signer, watch, client, scope, { unavailable.set(true) },
                reconnectSignalling = transport::reconnect, awaitSignalling = transport::awaitReady)
            teardown += call::dispose
            watch.start(scope)
            call.engine.start()
            assertNotNull(call.engine.localMedia.startCamera())
            assertNotNull(call.engine.localMedia.startMicrophone())
            stage = "browser/native media"
            live.sendChatConfirmed("Native guest joined browser room")
            scope.launch { repeat(18) {
                delay(5000)
                println("Browser/native ICE: states=${call.engine.connections.value.values} remoteTracks=${call.engine.remoteTracks.value.map { it.role + ":" + it.track.kind() }} relayCandidates=${relayed.get()} nonRelayCandidates=${leaked.get()}")
            } }
            // Use the production UI resolver: profile1 binds via authenticated
            // roster adverts; profile2 additionally declares the transceiver role.
            val frames = AtomicInteger(); val sink = VideoSink { frames.incrementAndGet() }
            var attached: VideoTrack? = null
            val renderer = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                combine(call.engine.remoteTracks, live.participants) { tracks, people ->
                    resolveRemoteByRole(remote = tracks.filter { it.track is VideoTrack },
                        device = { it.device }, trackId = { it.trackId }, receiving = { it.receiving },
                        roleForTrackId = { device, id -> people.flatMap { it.tracks }.firstOrNull { it.device == device && it.trackId == id }?.role },
                        valueFor = { it.track as VideoTrack }, declaredRole = { it.role },
                        advertisedRoles = { device -> listOf(Roles.CAMERA, Roles.SCREEN).filter { role -> people.flatMap { it.tracks }.any { it.device == device && it.role == role } } })
                }.collect { values ->
                    val next = values.entries.firstOrNull { it.key.endsWith("|${Roles.CAMERA}") }?.value
                    if (next !== attached) {
                        attached?.let { old -> runCatching { old.removeSink(sink) } }
                        attached = next; next?.addSink(sink)
                    }
                }
            }
            try {
                withTimeout(30_000) { while (frames.get() < 20) delay(100) }
                live.sendChatConfirmed("Native guest receives browser video")
                val issuerBefore = turnResponses.get()
                temporaryTurnFailures.set(1)
                call.recoverNetwork()
                withTimeout(20_000) {
                    while (turnResponses.get() <= issuerBefore || frames.get() < 40) delay(100)
                }
                assertFalse("Temporary issuer failure must not terminate the call", unavailable.get())
                assertEquals(0, temporaryTurnFailures.get())
                live.sendChatConfirmed("Native guest recovered temporary issuer failure")
                val renewalBefore = turnResponses.get()
                val started = android.os.SystemClock.elapsedRealtime()
                var previous = frames.get()
                val audioCounters = java.util.IdentityHashMap<Any, Long>()
                var totalAudio = 0L
                repeat(150) { sample ->
                    delay(2_000)
                    assertTrue("Browser video must keep arriving", frames.get() > previous)
                    previous = frames.get(); assertEquals(0, leaked.get())
                    assertFalse("Native access must remain active", unavailable.get())
                    var newAudio = 0L
                    for ((peer, progress) in call.engine.receivedMediaProgress()) {
                        val packets = progress.filter { it.mid == "0" && it.flow == dev.forgesworn.kithmoot.media.RtpFlow.INBOUND }.sumOf { it.counter }
                        val before = audioCounters.put(peer, packets) ?: 0
                        newAudio += if (packets < before) packets else packets - before
                    }
                    assertTrue("Browser microphone packets must advance in every sample", newAudio > 0)
                    totalAudio += newAudio
                    if (sample % 15 == 14) println("Browser/native media: seconds=${(android.os.SystemClock.elapsedRealtime() - started) / 1000} receivedFrames=${frames.get()} audioPackets=$totalAudio relayCandidates=${relayed.get()}")
                }
                assertTrue(relayed.get() > 0); assertEquals(0, leaked.get())
                assertTrue("Native TURN must renew after route recovery during sustained media", turnResponses.get() > renewalBefore)
                live.sendChatConfirmed("Native guest completed 300 seconds of browser video")
                stage = "browser removes native guest"
                withTimeout(45_000) { while (!unavailable.get()) delay(100) }
                assertTrue(call.engine.connections.value.isEmpty())
                assertTrue(call.engine.localMedia.tracks.value.isEmpty())
                assertTrue(call.engine.remoteTracks.value.isEmpty())
                assertNull(store.custody(identity.participant, node.room))
                println("Browser/native removal: native media and private custody cleared")
            } finally { renderer.cancelAndJoin(); attached?.let { runCatching { it.removeSink(sink) } }; room.roomKey.fill(0) }
        } } } catch (error: Throwable) { throw AssertionError("Browser/native stage: $stage", error) }
        finally { teardown.asReversed().forEach { runCatching(it) }; runCatching { session?.leave() }; scope.cancel(); identity.deviceSecretKey.fill(0) }
    }

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
            if (chain.request().url.encodedPath == "/v1/credentials" && temporaryTurnFailures.getAndUpdate { (it - 1).coerceAtLeast(0) } > 0) {
                return@addInterceptor okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(503).message("Temporary fixture outage").body("{}".toResponseBody("application/json".toMediaType())).build()
            }
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
