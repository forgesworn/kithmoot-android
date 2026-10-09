package dev.forgesworn.kithmoot.ui

import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.*
import dev.forgesworn.kithmoot.session.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Actual ViewModel, encrypted stores and RoomSessions. The radio byte boundary
 * is injected; no physical BLE or LoRa claim follows from this fixture. */
@RunWith(AndroidJUnit4::class)
class FreshNearbyEntryTest {
    private suspend fun await(label: String, predicate: () -> Boolean) {
        try { withTimeout(90_000) { while (!predicate()) delay(25) } }
        catch (error: TimeoutCancellationException) { throw AssertionError(label, error) }
    }

    @Test fun lost_request_and_reply_recover_then_chat_and_saved_reopen_use_nearby_only() = runBlocking {
        val f = Fixture(loss = true)
        try {
            f.start()
            f.main { f.model.joinNearbyFromUrl(f.url, f.descriptor) }
            await("request reaches byte boundary") { f.requests.isNotEmpty() }
            assertNull(f.app.savedRooms.get(f.room.roomId))
            await("fresh nearby entry opens: ${f.model.start.value.error}") { f.model.stage.value == Stage.ROOM || f.model.start.value.error != null }
            assertNull(f.model.start.value.error)
            assertEquals(Stage.ROOM, f.model.stage.value)
            assertEquals(3, f.requests.size)
            assertEquals(1, f.requests.map { it.id }.distinct().size)
            assertEquals(2, f.answers.size)
            assertEquals(1, f.answers.map { it.id }.distinct().size)
            val saved = f.app.savedRooms.get(f.room.roomId)!!
            assertEquals(RoomRoute.NEARBY, saved.route)
            assertFalse(saved.viaAccount)
            assertFalse(f.model.room.value.profilesEnabled)
            assertFalse(f.model.room.value.mediaRunning)
            assertEquals(0, f.server.requestCount)
            f.main { f.model.sendChat("phone over nearby") }
            await("root receives phone chat") { f.root.chat.value.any { it.body == "phone over nearby" } }
            f.root.sendChat("reply over nearby")
            await("phone receives root reply") { f.model.room.value.chat.any { it.body == "reply over nearby" } }
            f.main { f.model.leave() }
            await("phone leaves") { !f.model.start.value.busy && f.model.stage.value == Stage.START }
            f.main { f.model.reopenRoom(saved.id) }
            await("saved nearby room reopens") { f.model.stage.value == Stage.ROOM }
            assertEquals(saved.participant, f.model.room.value.selfParticipant)
            assertEquals(saved.devicePubkey, f.model.room.value.selfDevice)
            assertEquals(RoomRoute.NEARBY, f.model.room.value.route)
            assertEquals(0, f.server.requestCount)
        } finally { f.close() }
    }

    @Test fun cancellation_at_the_epoch_gate_leaves_no_saved_room_or_presence() = runBlocking {
        val f = Fixture(answerEpoch = false)
        try {
            f.start(); f.main { f.model.joinNearbyFromUrl(f.url, f.descriptor) }
            await("root epoch gate reached") { f.epochRequests.isNotEmpty() || f.model.start.value.error != null }
            assertNull(f.model.start.value.error)
            assertNull(f.app.savedRooms.get(f.room.roomId))
            assertFalse(f.phoneEvents.any { it.kind == KIND_ROSTER })
            f.main { f.model.stopOpening() }
            await("cancel closes all radio owners") { f.radios.isNotEmpty() && f.radios.all { it.closed } }
            assertNull(f.app.savedRooms.get(f.room.roomId))
            assertEquals(Stage.START, f.model.stage.value)
            assertFalse(f.phoneEvents.any { it.kind == KIND_ROSTER })
            assertEquals(0, f.server.requestCount)
        } finally { f.close() }
    }

    @Test fun fresh_mixed_loss_recovery_adopts_one_pool_and_each_path_carries_the_same_chat() = runBlocking {
        val f = Fixture(loss = true, mixed = true)
        try {
            f.start(); f.main { f.model.joinNearbyFromUrl(f.url, f.descriptor, RoomRoute.MIXED) }
            await("mixed challenge reaches radio") { f.requests.isNotEmpty() }
            assertNull(f.app.savedRooms.get(f.room.roomId))
            await("mixed entry opens") { f.model.stage.value == Stage.ROOM || f.model.start.value.error != null }
            assertNull(f.model.start.value.error); assertEquals(Stage.ROOM, f.model.stage.value)
            assertEquals(3, f.requests.size); assertEquals(1, f.requests.map { it.id }.distinct().size)
            assertEquals(2, f.answers.size); assertEquals(1, f.answers.map { it.id }.distinct().size)
            assertEquals(2, f.relayConnections.get()) // Keeper and phone; no replacement/profile pool.
            assertEquals(1, f.linkOwners.get())
            assertEquals(1, f.radios.count { !it.closed }) // Epoch confirmation may reset the engine queue.
            val saved = f.app.savedRooms.get(f.room.roomId)!!
            assertEquals(RoomRoute.MIXED, saved.route); assertFalse(saved.viaAccount)
            f.main { f.model.sendChat("mixed both lanes") }
            await("keeper receives mixed message") { f.root.chat.value.any { it.body == "mixed both lanes" } }
            val radioChat = f.phoneEvents.last { it.kind == KIND_CHAT }
            await("same signed chat reaches relay") { f.relayWrites.any { it.id == radioChat.id } }
            f.root.sendChat("mixed keeper reply")
            await("phone receives one mixed reply") { f.model.room.value.chat.count { it.body == "mixed keeper reply" } == 1 }
            f.meshEnabled = false
            f.main { f.model.sendChat("Internet while Bluetooth unavailable") }
            await("relay lane carries phone message") { f.root.chat.value.any { it.body == "Internet while Bluetooth unavailable" } }
            f.root.sendChat("Internet reply")
            await("relay lane carries reply") { f.model.room.value.chat.any { it.body == "Internet reply" } }
            f.meshEnabled = true; f.relayEnabled = false
            f.main { f.model.sendChat("Bluetooth while Internet unavailable") }
            await("mesh lane carries phone message") { f.root.chat.value.any { it.body == "Bluetooth while Internet unavailable" } }
            f.root.sendChat("Bluetooth reply")
            await("mesh lane carries reply") { f.model.room.value.chat.any { it.body == "Bluetooth reply" } }
            f.relayEnabled = true
            f.main { f.model.leave() }
            await("mixed phone closes both paths") { !f.model.start.value.busy && f.model.stage.value == Stage.START && f.radios.all { it.closed } && f.relaySockets.size == 1 }
            f.main { f.model.reopenRoom(saved.id) }
            await("saved mixed room reopens") { f.model.stage.value == Stage.ROOM }
            assertEquals(saved.participant, f.model.room.value.selfParticipant)
            assertEquals(saved.devicePubkey, f.model.room.value.selfDevice)
            assertEquals(RoomRoute.MIXED, f.model.room.value.route)
            assertEquals(3, f.relayConnections.get()) // Exactly one new phone pool.
        } finally { f.close() }
    }

    @Test fun mixed_cancellation_at_root_gate_closes_both_provisional_paths() = runBlocking {
        val f = Fixture(mixed = true, answerEpoch = false)
        try {
            f.start(); f.main { f.model.joinNearbyFromUrl(f.url, f.descriptor, RoomRoute.MIXED) }
            await("mixed root confirmation begins") { f.epochRequests.isNotEmpty() || f.model.start.value.error != null }
            assertNull(f.model.start.value.error); assertNull(f.app.savedRooms.get(f.room.roomId))
            assertFalse(f.phoneEvents.any { it.kind == KIND_ROSTER })
            f.main { f.model.stopOpening() }
            await("mixed provisional owners close") { !f.model.start.value.busy && f.radios.all { it.closed } && f.relaySockets.size == 1 }
            assertNull(f.app.savedRooms.get(f.room.roomId)); assertEquals(Stage.START, f.model.stage.value)
            assertFalse(f.phoneEvents.any { it.kind == KIND_ROSTER })
        } finally { f.close() }
    }

    @Test fun mixed_refuses_invalid_relays_before_radio_and_changed_signed_relays_before_saving() = runBlocking {
        val f = Fixture(mixed = true, mismatchedRelays = true)
        try {
            f.start()
            for ((invalidRelays, explanation) in listOf(
                emptyList<String>() to "needs one to 8 relays",
                listOf("ws://remote.fixture.invalid/") to "Use encrypted wss:// room relays",
                (1..9).map { "wss://relay$it.fixture.invalid/" } to "needs one to 8 relays")) {
                val invalid = encodeInvitationUrl("https://fixture.invalid/j/", f.host.invitation, invalidRelays)
                f.main { f.model.joinNearbyFromUrl(invalid, f.descriptor, RoomRoute.MIXED) }
                await("invalid mixed relays refused") { f.model.start.value.error != null && !f.model.start.value.busy }
                assertTrue(f.model.start.value.error, f.model.start.value.error!!.contains(explanation))
                assertTrue(f.radios.isEmpty()); assertEquals(1, f.relayConnections.get())
            }
            f.main { f.model.joinNearbyFromUrl(f.url, f.descriptor, RoomRoute.MIXED) }
            await("signed mismatch refused and closed") { f.model.start.value.error != null && !f.model.start.value.busy && f.radios.isNotEmpty() && f.radios.all { it.closed } && f.relaySockets.size == 1 }
            assertTrue(f.model.start.value.error, f.model.start.value.error!!.contains("relay list differs"))
            assertNull(f.app.savedRooms.get(f.room.roomId)); assertTrue(f.epochRequests.isEmpty())
        } finally { f.close() }
    }

    @Test fun background_during_challenge_cancels_without_fallback_and_invalid_code_never_opens_radio() = runBlocking {
        val f = Fixture(answerInvitation = false)
        try {
            f.start(); f.main { f.model.joinNearbyFromUrl(f.url, "invalid") }
            await("invalid code refused") { f.model.start.value.error != null && !f.model.start.value.busy }
            assertTrue(f.radios.isEmpty())
            f.main { f.model.joinNearbyFromUrl(f.url, f.descriptor) }
            await("challenge sent") { f.requests.isNotEmpty() }
            f.main { f.model.setAppVisible(false) }
            await("background closes provisional radio") { f.radios.all { it.closed } }
            assertNull(f.app.savedRooms.get(f.room.roomId))
            assertFalse(f.phoneEvents.any { it.kind == KIND_ROSTER })
            assertEquals(0, f.server.requestCount)
        } finally { f.close() }
    }

    internal class Fixture(val loss: Boolean = false, val answerEpoch: Boolean = true, val answerInvitation: Boolean = true,
        val mixed: Boolean = false, val mismatchedRelays: Boolean = false, val rootInternetOnly: Boolean = false) {
        val app = ApplicationProvider.getApplicationContext<KithMootApplication>()
        val server = MockWebServer().also { it.start() }
        val secret = Entropy.bytes(32)
        val host = createRoomInvitation(persistent = true)
        val room = deriveRoom(secret)
        val relays = listOf(server.url("/").toString().replace("http:", "ws:"))
        val url = encodeInvitationUrl("https://fixture.invalid/j/", host.invitation, relays)
        val context = LivePersistentContext(host.invitation, room.roomId)
        val descriptor = encodeLivePersistentDescriptor(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val requests = CopyOnWriteArrayList<NostrEvent>()
        val answers = CopyOnWriteArrayList<NostrEvent>()
        val epochRequests = CopyOnWriteArrayList<NostrEvent>()
        val phoneEvents = CopyOnWriteArrayList<NostrEvent>()
        val radios = CopyOnWriteArrayList<Radio>()
        val relayWrites = CopyOnWriteArrayList<NostrEvent>()
        val relaySockets = ConcurrentHashMap<WebSocket, ConcurrentHashMap<String, List<JsonObject>>>()
        val relayConnections = AtomicInteger()
        val linkOwners = AtomicInteger()
        private val relayRequests = AtomicInteger()
        private val relayAnswers = AtomicInteger()
        private val stored = CopyOnWriteArrayList<NostrEvent>()
        @Volatile var meshEnabled = true
        @Volatile var relayEnabled = true
        private var rootPool: RelayPool? = null
        val store = ViewModelStore()
        lateinit var model: RoomViewModel
        lateinit var root: RoomSession
        var phone: ((RoomBleEvent) -> Unit)? = null
        val listeners = CopyOnWriteArrayList<(ByteArray, String) -> Unit>()
        val link = object : RoomMeshLink {
            override fun subscribe(receive: (ByteArray, String) -> Unit): AutoCloseable {
                listeners += receive; return AutoCloseable { listeners -= receive }
            }
            override fun offer(bytes: ByteArray, to: String?) {
                if (!meshEnabled) return
                val e = event(bytes)
                if (e?.kind == KIND_INVITATION_GRANT) {
                    answers += e
                    if (loss && answers.size == 1) return
                }
                scope.launch { delay(1); phone?.invoke(RoomBleEvent.Frame(bytes.copyOf(), "root")) }
            }
            override suspend fun resetQueued() = Unit
            override fun reachable() = true
            override fun close() = Unit
        }
        val transport = RoomMeshTransport(RoomNearbyDiscovery.scope(room.roomId), link)
        fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
        fun event(bytes: ByteArray): NostrEvent? = RoomMeshWire.decode(bytes)?.takeIf { it.first == RoomMeshWire.EVENT }
            ?.second?.get("event")?.jsonObject?.let(NostrEvent::fromJson)
        suspend fun start() {
            if (mixed) server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    private val subscriptions = ConcurrentHashMap<String, List<JsonObject>>()
                    override fun onOpen(socket: WebSocket, response: okhttp3.Response) {
                        relaySockets[socket] = subscriptions; relayConnections.incrementAndGet()
                    }
                    override fun onMessage(socket: WebSocket, text: String) {
                        val frame = Json.parseToJsonElement(text).jsonArray
                        when (frame[0].jsonPrimitive.content) {
                            "REQ" -> {
                                val id = frame[1].jsonPrimitive.content
                                val filters = frame.drop(2).map { it.jsonObject }; subscriptions[id] = filters
                                stored.filter { event -> filters.any { matches(it, event) } }.forEach { event ->
                                    socket.send(buildJsonArray { add("EVENT"); add(id); add(event.toJson()) }.toString())
                                }
                                socket.send(buildJsonArray { add("EOSE"); add(id) }.toString())
                            }
                            "CLOSE" -> subscriptions.remove(frame[1].jsonPrimitive.content)
                            "EVENT" -> {
                                val event = NostrEvent.fromJson(frame[1]); require(Events.verify(event)); relayWrites += event
                                if (!relayEnabled || (event.kind == KIND_INVITATION_REQUEST && relayRequests.incrementAndGet() == 1 && loss) ||
                                    (event.kind == KIND_INVITATION_GRANT && relayAnswers.incrementAndGet() == 1 && loss)) return
                                if (event.kind == KIND_CHAT && stored.none { it.id == event.id }) stored += event
                                for ((peer, subs) in relaySockets) for ((id, filters) in subs) if (filters.any { matches(it, event) }) {
                                    peer.send(buildJsonArray { add("EVENT"); add(id); add(event.toJson()) }.toString())
                                }
                                socket.send(buildJsonArray { add("OK"); add(event.id); add(true); add("stored fixture event") }.toString())
                            }
                        }
                    }
                    override fun onClosing(socket: WebSocket, code: Int, reason: String) { relaySockets.remove(socket); socket.close(code, reason) }
                    override fun onClosed(socket: WebSocket, code: Int, reason: String) { relaySockets.remove(socket) }
                    override fun onFailure(socket: WebSocket, error: Throwable, response: okhttp3.Response?) { relaySockets.remove(socket) }
                })
            }
            val now = System.currentTimeMillis() / 1000
            val welcome = encodePersistentInvitation(host, secret, now, relays = if (mismatchedRelays) listOf("wss://different.fixture.invalid/") else relays)
            rootPool = if (mixed) RelayPool(relays, OkHttpRelaySockets(), scope).also { it.start() } else null
            val rootTransport: RoomTransport = rootPool?.let { if (rootInternetOnly) it else HybridRoomTransport(transport, it) } ?: transport
            val cache = mutableMapOf<String, NostrEvent>()
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                rootTransport.subscribe(listOf(Filter(kinds = listOf(KIND_INVITATION_REQUEST)))).collect { request ->
                    if (answerInvitation) rootTransport.publish(cache.getOrPut(request.id) {
                        encodeLivePersistentAnswer(context, request, welcome, host.inviterSecretKey, 0, System.currentTimeMillis() / 1000)
                    })
                }
            }
            root = RoomSession(room, PrimaryIdentity.create(room.roomId, now + 3600, now), rootTransport, scope,
                authority = host.invitation.inviter, expectedEpoch = 0, timing = SessionTiming(announceJitterMs = 0),
                epochResponder = { request ->
                    epochRequests += request
                    if (answerEpoch) encodeEpochGrant(room.roomId, host.inviterSecretKey, request.pubkey, request.id,
                        System.currentTimeMillis() / 1000, RoomEpoch(0, secret)) else null
                })
            root.join()
            if (mixed) withTimeout(10_000) { while (relaySockets.size != 1) delay(20) }
            main {
                model = RoomViewModel(app, nearbyLinkFactory = {
                    linkOwners.incrementAndGet()
                    NativeRoomMeshLink({ receive -> Radio(receive).also { radios += it } }, Dispatchers.Main)
                })
                store.put("fresh-nearby", model)
            }
        }
        suspend fun close() {
            main { store.clear() }
            withTimeout(10_000) { while (radios.any { !it.closed }) delay(20) }
            if (::root.isInitialized) root.leave()
            rootPool?.stop(); transport.close(); scope.cancel()
            app.savedRooms.get(room.roomId)?.let { saved ->
                dev.forgesworn.kithmoot.storage.PendingChatVault(app, saved.id, saved.participant, saved.devicePubkey).outbox.clear()
                dev.forgesworn.kithmoot.storage.RoomSharingVault(app, saved.id, saved.participant, saved.devicePubkey).forget()
            }
            app.savedRooms.forget(room.roomId)
            app.roomEpochs.forget(room.roomId)
            server.shutdown(); secret.fill(0); host.inviterSecretKey.fill(0); host.invitation.bearer.fill(0)
        }
        inner class Radio(private val receive: (RoomBleEvent) -> Unit) : RoomBleRadio {
            @Volatile var closed = false
            override fun start(config: RoomBleConfig) { phone = receive; receive(RoomBleEvent.Status(true, 1)) }
            override fun offer(bytes: ByteArray, to: String?): Int {
                if (!meshEnabled) return 0
                val e = event(bytes)
                if (e != null) phoneEvents += e
                if (e?.kind == KIND_INVITATION_REQUEST) {
                    requests += e
                    if (loss && requests.size == 1) return 1
                }
                scope.launch { delay(1); listeners.forEach { it(bytes.copyOf(), "phone") } }
                return 1
            }
            override fun close() { closed = true; if (phone === receive) phone = null }
        }
        private fun matches(filter: JsonObject, event: NostrEvent): Boolean =
            (filter["kinds"]?.jsonArray?.any { it.jsonPrimitive.int == event.kind } != false) &&
                (filter["authors"]?.jsonArray?.any { it.jsonPrimitive.content == event.pubkey } != false) &&
                filter.filterKeys { it.startsWith("#") }.all { (key, values) -> event.tags.any { tag ->
                    tag.size >= 2 && tag[0] == key.drop(1) && values.jsonArray.any { it.jsonPrimitive.content == tag[1] }
                } }
    }
}
