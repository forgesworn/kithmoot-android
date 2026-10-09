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
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

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

    private class Fixture(val loss: Boolean = false, val answerEpoch: Boolean = true, val answerInvitation: Boolean = true) {
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
            val now = System.currentTimeMillis() / 1000
            val welcome = encodePersistentInvitation(host, secret, now, relays = relays)
            val cache = mutableMapOf<String, NostrEvent>()
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                transport.subscribe(listOf(Filter(kinds = listOf(KIND_INVITATION_REQUEST)))).collect { request ->
                    if (answerInvitation) transport.publish(cache.getOrPut(request.id) {
                        encodeLivePersistentAnswer(context, request, welcome, host.inviterSecretKey, 0, System.currentTimeMillis() / 1000)
                    })
                }
            }
            root = RoomSession(room, PrimaryIdentity.create(room.roomId, now + 3600, now), transport, scope,
                authority = host.invitation.inviter, expectedEpoch = 0, timing = SessionTiming(announceJitterMs = 0),
                epochResponder = { request ->
                    epochRequests += request
                    if (answerEpoch) encodeEpochGrant(room.roomId, host.inviterSecretKey, request.pubkey, request.id,
                        System.currentTimeMillis() / 1000, RoomEpoch(0, secret)) else null
                })
            root.join()
            main {
                model = RoomViewModel(app, nearbyLinkFactory = {
                    NativeRoomMeshLink({ receive -> Radio(receive).also { radios += it } }, Dispatchers.Main)
                })
                store.put("fresh-nearby", model)
            }
        }
        suspend fun close() {
            main { store.clear() }
            withTimeout(10_000) { while (radios.any { !it.closed }) delay(20) }
            if (::root.isInitialized) root.leave()
            transport.close(); scope.cancel()
            app.savedRooms.get(room.roomId)?.let { saved ->
                dev.forgesworn.kithmoot.storage.PendingChatVault(app, saved.id, saved.participant, saved.devicePubkey).outbox.clear()
            }
            app.savedRooms.forget(room.roomId)
            app.roomEpochs.forget(room.roomId)
            server.shutdown(); secret.fill(0); host.inviterSecretKey.fill(0); host.invitation.bearer.fill(0)
        }
        inner class Radio(private val receive: (RoomBleEvent) -> Unit) : RoomBleRadio {
            @Volatile var closed = false
            override fun start(config: RoomBleConfig) { phone = receive; receive(RoomBleEvent.Status(true, 1)) }
            override fun offer(bytes: ByteArray, to: String?): Int {
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
    }
}
