package dev.forgesworn.kithmoot.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.epoch.NativeKeeperJournal
import dev.forgesworn.kithmoot.epoch.RoomRekeyBinding
import dev.forgesworn.kithmoot.epoch.RoomRekeyLedger
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.*
import dev.forgesworn.kithmoot.session.*
import dev.forgesworn.kithmoot.storage.EncryptedRoomStorage
import dev.forgesworn.kithmoot.storage.RoomCipher
import dev.forgesworn.kithmoot.storage.SavedRoom
import dev.forgesworn.kithmoot.ui.room.RoomScreen
import dev.forgesworn.kithmoot.ui.start.NewRoomForm
import dev.forgesworn.kithmoot.ui.theme.KithMootTheme
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.*
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.io.File
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ServerSocketFactory
import javax.crypto.SecretKey

/** Only the BLE byte boundary and loopback relay are fixtures. The native
 * authority is created/opened by the actual ViewModel and production stores. */
internal class NativeHostFixture(relayPort: Int = 0, loseGrant: Boolean = false) {
    val app = ApplicationProvider.getApplicationContext<KithMootApplication>()
    private val existingRoomIds = app.savedRooms.list().map { it.id }.toSet()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val radios = CopyOnWriteArrayList<Radio>()
    val phoneEvents = CopyOnWriteArrayList<NostrEvent>()
    val relayWrites = CopyOnWriteArrayList<NostrEvent>()
    val grants = CopyOnWriteArrayList<NostrEvent>()
    val stored = CopyOnWriteArrayList<NostrEvent>()
    val peers = CopyOnWriteArrayList<RoomSession>()
    private val listeners = CopyOnWriteArrayList<(ByteArray, String) -> Unit>()
    private val sockets = ConcurrentHashMap<WebSocket, ConcurrentHashMap<String, List<JsonObject>>>()
    private val store = ViewModelStore()
    private var grantToLose = loseGrant
    @Volatile private var phone: ((RoomBleEvent) -> Unit)? = null
    @Volatile var welcomeAccepted = true
    @Volatile var retirementAccepted = true
    @Volatile private var nearbyAvailable = true
    val server = MockWebServer().also {
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
        it.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                private val subscriptions = ConcurrentHashMap<String, List<JsonObject>>()
                override fun onOpen(socket: WebSocket, response: okhttp3.Response) { sockets[socket] = subscriptions }
                override fun onMessage(socket: WebSocket, text: String) {
                    val frame = Json.parseToJsonElement(text).jsonArray
                    when (frame[0].jsonPrimitive.content) {
                        "REQ" -> {
                            val id = frame[1].jsonPrimitive.content
                            val filters = frame.drop(2).map { it.jsonObject }
                            subscriptions[id] = filters
                            stored.filter { e -> filters.any { matches(it, e) } }.forEach { e ->
                                socket.send(buildJsonArray { add("EVENT"); add(id); add(e.toJson()) }.toString())
                            }
                            socket.send(buildJsonArray { add("EOSE"); add(id) }.toString())
                        }
                        "CLOSE" -> subscriptions.remove(frame[1].jsonPrimitive.content)
                        "EVENT" -> {
                            val event = NostrEvent.fromJson(frame[1]); require(Events.verify(event))
                            relayWrites += event
                            if (event.kind == 1463 && !welcomeAccepted) return
                            if (event.kind == KIND_INVITATION_RETIREMENT && !retirementAccepted) {
                                socket.send(buildJsonArray { add("OK"); add(event.id); add(false); add("fixture refusal") }.toString())
                                return
                            }
                            if (stored.none { it.id == event.id }) stored += event
                            for ((peer, subs) in sockets) for ((id, filters) in subs) if (filters.any { matches(it, event) }) {
                                peer.send(buildJsonArray { add("EVENT"); add(id); add(event.toJson()) }.toString())
                            }
                            socket.send(buildJsonArray { add("OK"); add(event.id); add(true); add("stored loopback fixture") }.toString())
                        }
                    }
                }
                override fun onClosing(socket: WebSocket, code: Int, reason: String) { sockets.remove(socket); socket.close(code, reason) }
                override fun onClosed(socket: WebSocket, code: Int, reason: String) { sockets.remove(socket) }
                override fun onFailure(socket: WebSocket, error: Throwable, response: okhttp3.Response?) { sockets.remove(socket) }
            })
        }
        // Bind only loopback: this test must never expose its synthetic relay.
        it.start(InetAddress.getByName("127.0.0.1"), relayPort)
    }
    val relays = listOf(canonicalRelayUrl("ws://127.0.0.1:${server.port}/")).also { urls ->
        check(urls.all { isSafeRoomRelayUrl(it) && canonicalRelayUrl(it) == it })
    }
    lateinit var model: RoomViewModel
    var savedRoom: SavedRoom? = null
    private val mesh = CopyOnWriteArrayList<RoomMeshTransport>()

    fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    fun startModel() = main {
        model = RoomViewModel(app, nearbyLinkFactory = {
            NativeRoomMeshLink({ receive -> Radio(receive).also { radios += it } }, Dispatchers.Main)
        })
        store.put("native-host", model)
        model.onRelaysChanged(relays.joinToString("\n"))
        model.onRoomNameChanged("Native host lab")
        check(parseRelays(model.start.value.relays) == relays) { "Native lab endpoint selection changed" }
    }
    suspend fun opened(): SavedRoom {
        await("native host opens through ViewModel") { model.stage.value == Stage.ROOM && !model.start.value.busy || model.start.value.error != null }
        check(model.start.value.error == null) { "Native host entry refused: ${model.lastRoomEntryDiagnostic}; ${publicStatus()}" }
        return requireNotNull(app.savedRooms.get(model.room.value.roomId)).also {
            check(!it.route.internet || it.relays == relays) { "Native lab saved endpoint changed" }
            savedRoom = it
        }
    }

    private fun publicStatus(): String {
        val room = model.room.value
        val hosting = room.nativeHosting
        val command = when (room.notice) {
            "Room hosting changed. Open the confirmation again." -> "stale"
            "Room update could not complete. Inspect the hosting state before trying again." -> "failed"
            "Room update saved. Hosting state does not confirm delivery to members." -> "saved"
            else -> "none"
        }
        return "stage=${model.stage.value} busy=${model.start.value.busy} " +
            "host=${model.nativeHostState()} observation=${hosting?.status} epoch=${hosting?.epoch} " +
            "revision=${hosting?.revision} originals=${hosting?.pendingOriginals?.size} " +
            "commandBusy=${room.nativeHostingBusy} command=$command radioOwners=${radios.count { !it.closed }} " +
            "offers=${phoneEvents.size} relayWrites=${relayWrites.size} " +
            "chatError=${room.chatSendError != null} pending=${room.pendingChats.size} " +
            "failure=${model.nativeHostFailureDiagnostic()}"
    }

    fun receiverStatus(saved: SavedRoom): String {
        val receiver = app.roomEpochs.get(saved.id) ?: return "receiver=missing"
        try {
            return "receiverPhase=${receiver.phase} receiverEpoch=${receiver.currentEpoch} " +
                "receiverPending=${receiver.pending != null}"
        } finally {
            receiver.currentSecret.fill(0)
            receiver.pending?.secret?.fill(0)
        }
    }

    suspend fun awaitHost(label: String, diagnostic: () -> String = { "" }, predicate: () -> Boolean) {
        try { await(label, predicate) }
        catch (error: AssertionError) {
            // Only explicit public counters/phases supplied by the fixture;
            // never stringify an authority/receiver snapshot or event body.
            val details = runCatching(diagnostic).getOrDefault("public diagnostic unavailable")
            throw AssertionError("$label; ${publicStatus()}; $details", error)
        }
    }

    /** Fixed synthetic member keys, never the application's root signer. */
    fun identity(saved: SavedRoom, at: Long, guest: Boolean = false): PrimaryIdentity = PrimaryIdentity.create(
        saved.id, at + 3600, at,
        participantSecretKey = ByteArray(32).apply { this[31] = if (guest) 3 else 1 },
        deviceSecretKey = ByteArray(32).apply { this[31] = if (guest) 4 else 2 })

    suspend fun join(saved: SavedRoom, at: Long, guest: Boolean = false,
        who: RoomIdentity = identity(saved, at, guest)): RoomSession {
        val transport = RoomMeshTransport(RoomNearbyDiscovery.scope(saved.id), object : RoomMeshLink {
            override fun subscribe(receive: (ByteArray, String) -> Unit): AutoCloseable {
                listeners += receive; return AutoCloseable { listeners -= receive }
            }
            override fun offer(bytes: ByteArray, to: String?) {
                scope.launch { delay(1); phone?.invoke(RoomBleEvent.Frame(bytes.copyOf(), "member")) }
            }
            override suspend fun resetQueued() = Unit
            override fun reachable() = phone != null
            override fun close() = Unit
        }).also { mesh += it }
        val secret = saved.secret
        try {
            val derived = deriveRoom(secret)
            val invitation = requireNotNull(saved.invitation).invitation
            try {
                return joinLivePersistentRoom(invitation,
                    encodeLivePersistentDescriptor(LivePersistentContext(invitation, saved.id)), who.devicePubkey, transport) { proof ->
                    RoomSession(derived, who, transport, scope, authority = saved.authority,
                        expectedEpoch = proof.epochHint.toInt(), requireFreshEpoch = true,
                        epochGate = { _, _ -> EpochGateResult.COMMITTED }, timing = SessionTiming(announceJitterMs = 0))
                }.also { peers += it }
            } finally { invitation.bearer.fill(0) }
        } finally { secret.fill(0) }
    }

    inner class Radio(private val receive: (RoomBleEvent) -> Unit) : RoomBleRadio {
        @Volatile var closed = false
        override fun start(config: RoomBleConfig) { phone = receive; status() }
        fun status() { if (!closed) receive(RoomBleEvent.Status(true, if (nearbyAvailable) 1 else 0)) }
        override fun offer(bytes: ByteArray, to: String?): Int {
            if (!nearbyAvailable) return 0
            val e = RoomMeshWire.decode(bytes)?.takeIf { it.first == RoomMeshWire.EVENT }
                ?.second?.get("event")?.jsonObject?.let(NostrEvent::fromJson)
            if (e != null) phoneEvents += e
            if (e?.kind == KIND_INVITATION_GRANT) {
                grants += e
                if (grantToLose) { grantToLose = false; return 1 }
            }
            scope.launch { delay(1); if (!closed) listeners.forEach { it(bytes.copyOf(), "host") } }
            return 1
        }
        override fun close() { closed = true; if (phone === receive) phone = null }
    }

    fun setNearbyAvailable(available: Boolean) {
        nearbyAvailable = available
        radios.filter { !it.closed }.forEach { it.status() }
    }

    fun sourceStore(saved: SavedRoom): EncryptedRoomStorage {
        val binding = requireNotNull(saved.nativeAuthority)
        return EncryptedRoomStorage(app, "kithmoot.keeper-authority." + Digests.sha256(binding.owner.toByteArray(Charsets.UTF_8)).toHex(),
            NativeKeeperJournal.MAX_FILE_BYTES)
    }
    fun courierStore(saved: SavedRoom): EncryptedRoomStorage {
        val b = requireNotNull(saved.nativeAuthority)
        val q = RoomRekeyBinding(b.room, b.authority, b.device, b.meshScope, b.relays, b.route)
        return EncryptedRoomStorage(app, "kithmoot.keeper-rekeys." + Digests.sha256(q.owner.toByteArray(Charsets.UTF_8)).toHex(),
            RoomRekeyLedger.MAX_FILE_BYTES)
    }
    /** Read only the committed base descriptor. AtomicFile.openRead can delete
     * a writer's .new file; never invoke its recovery while that owner is live.
     * A rename cannot change an already opened descriptor. No authority writer
     * or replacement wrapping key is acquired by this observation. */
    fun source(saved: SavedRoom): JsonObject {
        val b = requireNotNull(saved.nativeAuthority)
        val alias = "kithmoot.keeper-authority." + Digests.sha256(b.owner.toByteArray(Charsets.UTF_8)).toHex()
        val sealed = File(app.noBackupFilesDir, "$alias.vault").inputStream().use {
            check(it.channel.size() <= NativeKeeperJournal.MAX_FILE_BYTES + 64)
            it.readBytes().also { bytes -> check(bytes.size <= NativeKeeperJournal.MAX_FILE_BYTES + 64) }
        }
        try {
            val bytes = RoomCipher(key = { create ->
                check(!create)
                requireNotNull(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(alias, null)) as SecretKey
            }).decrypt(sealed)
            try { return Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject }
            finally { bytes.fill(0) }
        } finally { sealed.fill(0) }
    }
    suspend fun close() {
        try {
            peers.forEach { it.leave() }
            if (::model.isInitialized) {
                // Entry can fail after the actual source/index commit, before
                // opened() returns. Still delete only this fixture's new host.
                val created = app.savedRooms.list().filter { it.id !in existingRoomIds }
                    .mapNotNull { app.savedRooms.get(it.id) }
                    .filter { it.nativeAuthority != null && it.name == "Native host lab" }
                val saved = (listOfNotNull(savedRoom) + created).distinctBy { it.id }
                main { model.leave() }
                await("native host paths close before deletion") { !model.start.value.busy && model.stage.value == Stage.START && radios.all { it.closed } }
                for (owned in saved) {
                    main { model.forgetRoom(owned.id) }
                    await("native host actual cleanup commits") { !model.start.value.busy && app.savedRooms.get(owned.id) == null }
                    check(sourceStore(owned).read() == null) { "Native source survived deletion" }
                    check(courierStore(owned).read() == null) { "Native courier survived deletion" }
                }
            }
        } finally {
            try { main { store.clear() }; mesh.forEach { it.close() } }
            finally { scope.cancel(); server.shutdown() }
        }
    }

    companion object {
        suspend fun await(label: String, predicate: () -> Boolean) {
            try { withTimeout(60_000) { while (!predicate()) delay(25) } }
            catch (error: TimeoutCancellationException) { throw AssertionError(label, error) }
        }
        private fun matches(filter: JsonObject, event: NostrEvent): Boolean =
            (filter["kinds"]?.jsonArray?.any { it.jsonPrimitive.int == event.kind } != false) &&
                (filter["authors"]?.jsonArray?.any { it.jsonPrimitive.content == event.pubkey } != false) &&
                filter.filterKeys { it.startsWith("#") }.all { (key, values) -> event.tags.any { tag ->
                    tag.size >= 2 && tag[0] == key.drop(1) && values.jsonArray.any { it.jsonPrimitive.content == tag[1] }
                } }
    }
}

/** Render production creation and approval controls against the actual owner. */
internal fun ComposeContentTestRule.showNativeHost(f: NativeHostFixture) = setContent {
    KithMootTheme {
        val start by f.model.start.collectAsState()
        val stage by f.model.stage.collectAsState()
        val room by f.model.room.collectAsState()
        if (stage == Stage.ROOM) RoomScreen(room, emptyMap(), null,
            {}, {}, {}, {}, {}, {}, {}, f.model::leave,
            chat = {}, onAnswerLetIn = f.model::answerLetIn,
            onChangeNativeRoomKey = f.model::changeNativeRoomKey,
            onRemoveNativeRoomMember = f.model::removeNativeRoomMember,
            onRetireNativeInvitation = f.model::retireNativeInvitation,
            onResendNativeRetirement = f.model::resendNativeRetirement,
            onRecoverNativePending = f.model::recoverNativePendingUpdate,
            onCanShareInvitation = f.model::canShareRoomInvitation)
        else Column(Modifier.verticalScroll(rememberScrollState())) {
            NewRoomForm(start.roomName, f.model::onRoomNameChanged, start.anonymousMode, f.model::onAnonymousModeChanged,
                enabled = !start.busy, busy = start.busy, error = start.error, onStartRoom = f.model::startRoom,
                onStartNearby = f.model::startNearbyRoom)
        }
    }
}
