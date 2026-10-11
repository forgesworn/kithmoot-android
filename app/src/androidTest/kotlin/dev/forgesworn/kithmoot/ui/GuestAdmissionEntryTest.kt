package dev.forgesworn.kithmoot.ui

import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.session.KIND_CHAT
import dev.forgesworn.kithmoot.session.RoomSession
import dev.forgesworn.kithmoot.session.SessionTiming
import dev.forgesworn.kithmoot.session.PrimaryIdentity
import dev.forgesworn.kithmoot.session.RoomWork
import dev.forgesworn.kithmoot.session.AssignmentStorage
import dev.forgesworn.kithmoot.session.deriveChatChannel
import dev.forgesworn.kithmoot.relay.RelayPool
import dev.forgesworn.kithmoot.relay.OkHttpRelaySockets
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import javax.net.ServerSocketFactory

/** Real entry ViewModel and WebSockets, with a loopback relay and signed replies.
 * No account or existing room is replaced. The initial relay preference is
 * overridden only while the ViewModel captures it, then restored exactly. */
@RunWith(AndroidJUnit4::class)
class GuestAdmissionEntryTest {
    private suspend fun await(label: String, condition: () -> Boolean) {
        try { withTimeout(15_000) { while (!condition()) delay(20) } }
        catch (error: TimeoutCancellationException) { throw AssertionError(label, error) }
    }

    @Test fun previewDoesNotConnectOrPublishAndRequestWaitsForActualAcknowledgement() = runBlocking {
        val f = Fixture()
        try {
            f.start()
            f.main { f.model.joinFromUrl(f.url); f.model.onGuestAdmissionNameChanged("Rowan") }
            delay(300)
            assertEquals(GuestAdmissionPhase.PREVIEW, f.model.guestAdmission.value?.phase)
            assertEquals(0, f.server.requestCount)
            assertTrue(f.events.isEmpty())
            assertNull(f.app.savedRooms.get(f.room.roomId))
            assertFalse(f.model.room.value.onCall)
            assertFalse(f.model.room.value.micOn)
            assertFalse(f.model.room.value.cameraOn)
            f.main { repeat(5) { f.model.requestGuestAdmission() } }
            await("one signed request reaches the relay") { f.requests.isNotEmpty() }
            val request = decodeInvitationRequest(f.requests.first(), f.host.invitation, f.now())!!
            assertEquals("Rowan", request.name)
            assertNull(request.verifiedParticipant)
            assertEquals(GuestAdmissionPhase.SENDING, f.model.guestAdmission.value?.phase)
            assertEquals(1, f.requests.map { it.id }.distinct().size)
            f.acknowledge()
            await("relay acknowledgement changes waiting state") { f.model.guestAdmission.value?.phase == GuestAdmissionPhase.WAITING }
            delay(2_200)
            assertEquals(GuestAdmissionPhase.WAITING, f.model.guestAdmission.value?.phase)
            assertEquals(1, f.requests.map { it.id }.distinct().size)
            assertEquals(Stage.START, f.model.stage.value)
            assertNull(f.app.savedRooms.get(f.room.roomId))
            assertFalse(f.events.any { it.kind == KIND_ROSTER || it.kind == KIND_CHAT })
        } finally { f.close() }
    }

    @Test fun cancelClosesEntryAndLateAuthenticGrantCannotSaveOrOpenTheRoom() = runBlocking {
        val f = Fixture(ack = true)
        try {
            f.start()
            f.main { f.model.joinFromUrl(f.url); f.model.onGuestAdmissionNameChanged("Rowan"); f.model.requestGuestAdmission() }
            await("acknowledged waiting") { f.model.guestAdmission.value?.phase == GuestAdmissionPhase.WAITING }
            val old = decodeInvitationRequest(f.requests.first(), f.host.invitation, f.now())!!
            f.main { f.model.cancelGuestAdmission() }
            await("request subscription closes") { f.sockets.isEmpty() }
            val writes = f.requests.size
            f.reply(encodeInvitationGrant(f.host, old.device, old.requestId, f.secret, f.now()))
            delay(2_200)
            assertEquals(writes, f.requests.size)
            assertEquals(GuestAdmissionPhase.CANCELLED, f.model.guestAdmission.value?.phase)
            assertEquals("Rowan", f.model.guestAdmission.value?.name)
            assertEquals(Stage.START, f.model.stage.value)
            assertNull(f.app.savedRooms.get(f.room.roomId))
            assertFalse(f.model.room.value.onCall)
            assertFalse(f.model.room.value.micOn)
            assertFalse(f.model.room.value.cameraOn)
            assertFalse(f.events.any { it.kind == KIND_ROSTER || it.kind == KIND_CHAT })
        } finally { f.close() }
    }

    @Test fun authenticatedDeclineKeepsDetailsAndRetryRequiresAnotherExplicitRequest() = runBlocking {
        val f = Fixture(ack = true)
        try {
            f.start()
            f.main { f.model.joinFromUrl(f.url); f.model.onGuestAdmissionNameChanged("Rowan"); f.model.requestGuestAdmission() }
            await("waiting for host") { f.model.guestAdmission.value?.phase == GuestAdmissionPhase.WAITING }
            val first = decodeInvitationRequest(f.requests.first(), f.host.invitation, f.now())!!
            f.reply(encodeInvitationDecline(f.host, first.device, "00".repeat(32), f.now()))
            delay(150)
            assertEquals(GuestAdmissionPhase.WAITING, f.model.guestAdmission.value?.phase)
            f.reply(encodeInvitationDecline(f.host, first.device, first.requestId, f.now()))
            await("verified decline") { f.model.guestAdmission.value?.phase == GuestAdmissionPhase.DECLINED && !f.model.start.value.busy }
            val before = f.requests.size
            f.main { f.model.retryGuestAdmission() }
            delay(250)
            assertEquals(GuestAdmissionPhase.PREVIEW, f.model.guestAdmission.value?.phase)
            assertEquals("Rowan", f.model.guestAdmission.value?.name)
            assertEquals(before, f.requests.size)
            f.main { f.model.requestGuestAdmission() }
            await("new deliberate request") { f.requests.map { it.id }.distinct().size == 2 }
            val second = decodeInvitationRequest(f.requests.last(), f.host.invitation, f.now())!!
            assertEquals("Rowan", second.name)
            assertNotEquals(first.device, second.device)
            f.reply(encodeInvitationGrant(f.host, first.device, first.requestId, f.secret, f.now()))
            delay(150)
            assertNull(f.app.savedRooms.get(f.room.roomId))
            assertEquals(Stage.START, f.model.stage.value)
        } finally { f.close() }
    }

    @Test fun anAuthenticGrantOpensOneRoomAndLeaveReturnsToTheHomeScreen() = runBlocking {
        val f = Fixture(ack = true, hostRoom = true)
        try {
            f.start()
            f.main { f.model.joinFromUrl(f.url); f.model.onGuestAdmissionNameChanged("Rowan"); f.model.requestGuestAdmission() }
            await("waiting for the host") { f.model.guestAdmission.value?.phase == GuestAdmissionPhase.WAITING }
            assertNull(f.app.savedRooms.get(f.room.roomId))
            val request = decodeInvitationRequest(f.requests.first(), f.host.invitation, f.now())!!
            f.reply(encodeInvitationGrant(f.host, request.device, request.requestId, f.secret, f.now()))
            await("authorised room entry: ${f.model.start.value.error}") { f.model.stage.value == Stage.ROOM || f.model.start.value.error != null }
            assertNull(f.model.start.value.error)
            assertEquals(Stage.ROOM, f.model.stage.value)
            assertNull(f.model.guestAdmission.value)
            assertEquals(1, f.requests.map { it.id }.distinct().size)
            val saved = f.app.savedRooms.get(f.room.roomId)!!
            assertEquals(f.host.invitation.canonicalInviter, saved.authority)
            assertEquals(saved.devicePubkey, f.model.room.value.selfDevice)
            assertFalse(f.model.room.value.onCall)
            assertFalse(f.model.room.value.micOn)
            assertFalse(f.model.room.value.cameraOn)
            f.main { f.model.leave() }
            await("leave returns to home") { f.model.stage.value == Stage.START && !f.model.start.value.busy }
        } finally { f.close() }
    }

    private fun logoFixture(): LogoImage {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val vectors = Json.parseToJsonElement(assets.open("logo-images-v1.json").bufferedReader().use { it.readText() })
            .jsonObject.getValue("vectors").jsonArray
        return requireNotNull(LogoImages.read(vectors.first { it.jsonObject["accepted"]?.jsonPrimitive?.boolean == true }
            .jsonObject.getValue("payload")))
    }

    @Test fun closedRoomArtworkIsNotQueriedBeforeAdmissionAndStoredLogoCatchesUpAfterGrant() = runBlocking {
        val f = Fixture(ack = true, hostRoom = true, retainControls = true)
        try {
            f.start()
            val image = logoFixture()
            f.rootWork!!.replaceRoomLogo(image)
            val queries = f.controlQueries()
            f.main { f.model.joinFromUrl(f.url); f.model.onGuestAdmissionNameChanged("Logo guest"); f.model.requestGuestAdmission() }
            await("closed room request acknowledged") { f.model.guestAdmission.value?.phase == GuestAdmissionPhase.WAITING }
            assertEquals(queries, f.controlQueries())
            assertEquals(Stage.START, f.model.stage.value); assertNull(f.model.room.value.logo)
            assertNull(f.app.savedRooms.get(f.room.roomId))
            val request = decodeInvitationRequest(f.requests.single(), f.host.invitation, f.now())!!
            f.reply(encodeInvitationGrant(f.host, request.device, request.requestId, f.secret, f.now()))
            await("admitted guest receives stored private logo") { f.model.stage.value == Stage.ROOM && f.model.room.value.logo?.sha256 == image.sha256 }
            assertTrue(f.controlQueries() > queries)
            assertTrue(f.model.room.value.chat.isEmpty())
            assertFalse(f.model.room.value.onCall); assertFalse(f.model.room.value.micOn); assertFalse(f.model.room.value.cameraOn)
            await("accepted room artwork saved for recovery") { f.app.savedRooms.get(f.room.roomId)?.sharedLogo?.record?.image?.sha256 == image.sha256 }
            f.main { f.model.leave() }
            await("guest leaves the room") { f.model.stage.value == Stage.START && !f.model.start.value.busy }
            // Clear the process cache and replace the ViewModel. Recovery must
            // use this device's encrypted saved record, without logo history.
            f.app.roomLogos.forget(f.room.roomId)
            f.replayControls = false
            f.main { f.store.clear(); f.model = RoomViewModel(f.app, chatOnly = true); f.store.put("guest-entry", f.model) }
            await("fresh guest model reads saved rooms") { !f.model.start.value.loadingRooms }
            f.main { f.model.reopenRoom(f.room.roomId) }
            await("fresh model restores private logo without relay history") { f.model.stage.value == Stage.ROOM && f.model.room.value.logo?.sha256 == image.sha256 }
            assertFalse(f.model.room.value.onCall)
        } finally { f.close() }
    }

    @Test fun removalWhileGuestWaitsWinsOverStoredAndLateOlderArtworkAfterAdmission() = runBlocking {
        val f = Fixture(ack = true, hostRoom = true, retainControls = true)
        try {
            f.start()
            f.rootWork!!.replaceRoomLogo(logoFixture())
            val oldImage = f.events.last { it.kind == KIND_CHAT }
            val queries = f.controlQueries()
            f.main { f.model.joinFromUrl(f.url); f.model.requestGuestAdmission() }
            await("guest waits outside artwork channel") { f.model.guestAdmission.value?.phase == GuestAdmissionPhase.WAITING }
            val removal = f.rootWork!!.replaceRoomLogo(null)
            assertEquals(queries, f.controlQueries()); assertNull(f.model.room.value.logo)
            val request = decodeInvitationRequest(f.requests.single(), f.host.invitation, f.now())!!
            f.reply(encodeInvitationGrant(f.host, request.device, request.requestId, f.secret, f.now()))
            await("admitted guest accepts current removal") {
                f.model.stage.value == Stage.ROOM && f.app.savedRooms.get(f.room.roomId)?.let {
                    f.app.roomLogos.seed(it, f.now())?.record?.id == removal.id
                } == true
            }
            f.reply(oldImage)
            delay(250)
            assertNull(f.model.room.value.logo)
            assertTrue(f.model.room.value.chat.isEmpty())
            assertFalse(f.model.room.value.onCall)
        } finally { f.close() }
    }

    internal class Fixture(@Volatile var ack: Boolean = false, val hostRoom: Boolean = false, val retainControls: Boolean = false,
        secretOverride: ByteArray? = null, hostOverride: RoomInvitationHost? = null, private val relayPort: Int = 0) {
        val app = ApplicationProvider.getApplicationContext<KithMootApplication>()
        val host = hostOverride ?: createRoomInvitation()
        val secret = secretOverride?.copyOf() ?: Entropy.bytes(32)
        val room = deriveRoom(secret)
        val events = CopyOnWriteArrayList<NostrEvent>()
        val queries = CopyOnWriteArrayList<JsonObject>()
        @Volatile var replayControls = retainControls
        val requests get() = events.filter { it.kind == KIND_INVITATION_REQUEST }
        val sockets = ConcurrentHashMap<WebSocket, ConcurrentHashMap<String, List<JsonObject>>>()
        val store = ViewModelStore()
        val server = MockWebServer().also {
            // Both sides of a forced restart reuse the same loopback relay.
            it.serverSocketFactory = object : ServerSocketFactory() {
                override fun createServerSocket() = object : ServerSocket() {
                    override fun setReuseAddress(on: Boolean) { super.setReuseAddress(true) }
                }.apply { reuseAddress = true }
                private fun bound(port: Int, backlog: Int, address: InetAddress?) =
                    createServerSocket().apply { bind(InetSocketAddress(address, port), backlog) }
                override fun createServerSocket(port: Int) = bound(port, 50, null)
                override fun createServerSocket(port: Int, backlog: Int) = bound(port, backlog, null)
                override fun createServerSocket(port: Int, backlog: Int, address: InetAddress?) = bound(port, backlog, address)
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var rootPool: RelayPool? = null
        var rootSession: RoomSession? = null
        var rootWork: RoomWork? = null
        lateinit var model: RoomViewModel
        lateinit var url: String
        fun now() = System.currentTimeMillis() / 1_000
        fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
        fun controlQueries(): Int {
            val channel = deriveChatChannel(room.roomId, room.roomKey, "control").id
            return queries.count { filter -> filter["#d"]?.jsonArray?.any { it.jsonPrimitive.content == channel } == true }
        }
        private fun matches(filter: JsonObject, event: NostrEvent): Boolean =
            filter["kinds"]?.jsonArray?.any { it.jsonPrimitive.int == event.kind } != false &&
                filter["authors"]?.jsonArray?.any { it.jsonPrimitive.content == event.pubkey } != false &&
                filter.filterKeys { it.startsWith("#") }.all { (tag, values) -> event.tags.any { row ->
                    row.size >= 2 && row[0] == tag.drop(1) && values.jsonArray.any { it.jsonPrimitive.content == row[1] }
                } }
        suspend fun start() {
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    val subscriptions = ConcurrentHashMap<String, List<JsonObject>>()
                    override fun onOpen(socket: WebSocket, response: okhttp3.Response) { sockets[socket] = subscriptions }
                    override fun onMessage(socket: WebSocket, text: String) {
                        val frame = Json.parseToJsonElement(text).jsonArray
                        when (frame[0].jsonPrimitive.content) {
                            "REQ" -> {
                                val id = frame[1].jsonPrimitive.content
                                subscriptions[id] = frame.drop(2).map { it.jsonObject }
                                queries.addAll(subscriptions.getValue(id))
                                if (replayControls) events.filter { event -> event.kind == KIND_CHAT && subscriptions.getValue(id).any { matches(it, event) } }
                                    .sortedWith(compareBy({ it.createdAt }, { it.id })).forEach { event ->
                                        socket.send(buildJsonArray { add("EVENT"); add(id); add(event.toJson()) }.toString())
                                    }
                                socket.send(buildJsonArray { add("EOSE"); add(id) }.toString())
                            }
                            "CLOSE" -> subscriptions.remove(frame[1].jsonPrimitive.content)
                            "EVENT" -> {
                                val event = NostrEvent.fromJson(frame[1]); require(Events.verify(event)); events += event
                                reply(event)
                                if (ack) ok(socket, event)
                            }
                        }
                    }
                    override fun onClosing(socket: WebSocket, code: Int, reason: String) { sockets.remove(socket); socket.close(code, reason) }
                    override fun onClosed(socket: WebSocket, code: Int, reason: String) { sockets.remove(socket) }
                    override fun onFailure(socket: WebSocket, error: Throwable, response: okhttp3.Response?) { sockets.remove(socket) }
                })
            }
            server.start(relayPort)
            val relays = listOf(server.url("/").toString().replace("http:", "ws:"))
            url = encodeInvitationUrl("https://fixture.invalid/j/", host.invitation, relays)
            if (hostRoom) {
                val transport = RelayPool(relays, OkHttpRelaySockets(), scope).also { rootPool = it; it.start() }
                rootSession = RoomSession(room, PrimaryIdentity.create(room.roomId, now() + 3600, now()), transport, scope,
                    authority = host.invitation.canonicalInviter, expectedEpoch = 0,
                    timing = SessionTiming(announceJitterMs = 0), epochResponder = { request ->
                        encodeEpochGrant(room.roomId, host.inviterSecretKey, request.pubkey, request.id, now(), RoomEpoch(0, secret))
                    }).also { it.join() }
                if (retainControls) {
                    val storage = object : AssignmentStorage {
                        private var journal: String? = null
                        override suspend fun load() = journal
                        override suspend fun save(encrypted: String) { journal = encrypted }
                    }
                    rootWork = RoomWork(room.roomId, room.roomKey, rootSession!!.identity, transport, storage, scope,
                        authority = host.invitation.canonicalInviter).also { it.open() }
                }
            }
            main {
                val preferences = app.getSharedPreferences("kithmoot.display", android.content.Context.MODE_PRIVATE)
                val hadRelays = preferences.contains("relaySettings")
                val previousRelays = preferences.getString("relaySettings", null)
                check(preferences.edit().putString("relaySettings", relays.joinToString("\n")).commit())
                try {
                    model = RoomViewModel(app, chatOnly = true)
                    store.put("guest-entry", model)
                } finally {
                    val restore = preferences.edit()
                    if (hadRelays) restore.putString("relaySettings", previousRelays) else restore.remove("relaySettings")
                    check(restore.commit())
                }
            }
            withTimeout(15_000) { while (model.start.value.loadingRooms) delay(20) }
        }
        private fun ok(socket: WebSocket, event: NostrEvent) {
            socket.send(buildJsonArray { add("OK"); add(event.id); add(true); add("fixture accepted") }.toString())
        }
        fun acknowledge() { ack = true; sockets.keys.forEach { socket -> requests.forEach { ok(socket, it) } } }
        fun reply(event: NostrEvent): Int {
            var delivered = 0
            for ((socket, subscriptions) in sockets) for ((id, filters) in subscriptions) {
                if (filters.any { matches(it, event) } && socket.send(buildJsonArray { add("EVENT"); add(id); add(event.toJson()) }.toString())) delivered++
            }
            return delivered
        }
        suspend fun close() {
            main { store.clear() }
            rootWork?.close()
            rootSession?.leave(); rootPool?.stop(); scope.cancel()
            withTimeout(15_000) { while (sockets.isNotEmpty()) delay(20) }
            if (hostRoom) {
                app.savedRooms.get(room.roomId)?.let { saved ->
                    dev.forgesworn.kithmoot.storage.PendingChatVault(app, saved.id, saved.participant, saved.devicePubkey).outbox.clear()
                    dev.forgesworn.kithmoot.storage.RoomSharingVault(app, saved.id, saved.participant, saved.devicePubkey).forget()
                }
                app.savedRooms.forget(room.roomId); app.roomEpochs.forget(room.roomId)
                app.roomLogos.forget(room.roomId)
            }
            server.shutdown(); secret.fill(0); host.inviterSecretKey.fill(0); host.invitation.bearer.fill(0)
            assertNull("No admission fixture may leave a saved room", app.savedRooms.get(room.roomId))
        }
    }
}
