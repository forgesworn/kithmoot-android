package dev.forgesworn.kithmoot.media.recording

import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.projects.ProjectTestRelay
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.session.PendingChatState
import dev.forgesworn.kithmoot.session.deriveChatChannel
import dev.forgesworn.kithmoot.session.KIND_CHAT
import dev.forgesworn.kithmoot.storage.RecoveryUi
import dev.forgesworn.kithmoot.ui.KithMootApp
import dev.forgesworn.kithmoot.ui.RoomViewModel
import dev.forgesworn.kithmoot.ui.Stage
import dev.forgesworn.kithmoot.ui.theme.KithMootTheme
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.*

/** Actual Add/Upload/Send and outbox Retry across explicit process death.
 * Synthetic native AAC, one model owner and scoped test TLS. This qualifies
 * retaining-chat handoff, not quiet Retry Send, rekey or physical capture. */
class RecordingSendRecoveryUiTest {
    @get:Rule val activity = ActivityScenarioRule(ComponentActivity::class.java)
    private val app get() = ApplicationProvider.getApplicationContext<KithMootApplication>()
    private val ui = RecoveryUi(useSwipeFallback = false, freshNodes = true)
    private lateinit var model: RoomViewModel
    private var relay: ProjectTestRelay? = null
    private var server: MockWebServer? = null
    private var client: OkHttpClient? = null
    @Volatile private var allowedKey: String? = null
    private val puts = AtomicInteger()
    private val origin = "https://127.0.0.1:39847"

    @After fun close() {
        try {
            if (::model.isInitialized && model.stage.value == Stage.ROOM) {
                activity.scenario.onActivity { model.leave() }
                ui.await("fixture room closed") { model.stage.value == Stage.START && !model.start.value.busy }
            }
        } finally {
            try { relay?.close() } finally {
                try { server?.close() } finally {
                    client?.dispatcher?.executorService?.shutdown()
                    client?.connectionPool?.evictAll()
                }
            }
        }
    }

    @Test fun explicit_send_reopens_its_original_chat_and_retries_identical_ciphertext() {
        Assume.assumeTrue("Disposable emulator only", android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val args = InstrumentationRegistry.getArguments()
        val name = args.getString("recordingNetworkFixture")
        Assume.assumeTrue("Run guarded TLS restart driver", name?.matches(Regex("recording-ui-network-[0-9a-f]{12}")) == true)
        val stage = args.getString("recordingSendRecoveryStage")
        require(stage in setOf("prepare", "verify", "cleanup"))
        val fixture = File(app.noBackupFilesDir, requireNotNull(name))
        val marker = File(fixture, "send-recovery.json")
        if (stage == "cleanup") {
            if (marker.isFile) {
                val room = JSONObject(marker.readText()).getString("room")
                app.forgetRecordingsForRoom(room)
                app.savedRooms.get(room)?.let { app.savedRooms.forget(it.id) }
                check(marker.delete())
            }
            return
        }
        val title = "Synthetic Send ${name.takeLast(12)}"
        if (stage == "prepare") {
            app.accounts.load()?.let {
                check(it.method == "local" && it.pubkey == Schnorr.publicKeyHex(ByteArray(32) { 59 })) {
                    "Send recovery fixture refuses an unrelated retained account"
                }
                app.accounts.clear()
            }
        }
        val http = tlsClient(fixture).also { client = it }
        activity.scenario.onActivity { owner ->
            owner.enableEdgeToEdge()
            model = ViewModelProvider(owner, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(type: Class<T>): T =
                    RoomViewModel(app, recordingUploadClient = http) as T
            })["recording-send-recovery", RoomViewModel::class.java]
            owner.setContent { KithMootTheme { KithMootApp(model) } }
        }
        ui.await("home or retained export") { ui.hasText("KithMoot") || ui.hasText("Recording ready") }
        if (ui.hasText("Recording ready")) ui.click("Later")
        ui.home()
        if (stage == "prepare") {
            app.recordings.export.value?.let(app.recordings::discard)
            app.savedRooms.reset()
            val port = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { it.localPort }
            val wire = ProjectTestRelay(port).also { relay = it }
            activity.scenario.onActivity {
                model.onRelaysChanged(wire.url)
                model.installLocalTestAccount(ByteArray(32) { 59 })
            }
            ui.await("synthetic account and bookmarks") { model.start.value.account != null && model.start.value.roomBookmarks.ready }
            ui.click("New room"); ui.replace("Room name (optional)", title); ui.click("Start a room"); ui.room()
            val room = model.room.value.roomId
            // Retain scoped cleanup ownership before any capture/upload work.
            marker.writeText(JSONObject().put("room", room).toString())
            val export = app.recordings.begin(RecordingFormat.AUDIO, RecordingOrigin(room, "cd".repeat(16), title))
            val audio = AacRecordingFile(export)
            try {
                repeat(100) { audio.write(ShortArray(960)) }
                audio.close()
                assertTrue(export.isFile && export.length() > 0)
            } catch (error: Throwable) { audio.discard(); throw error }
            app.recordings.complete(export)
            ui.click("Add to original chat")
            ui.await("encrypted draft offered") { ui.hasText("Upload recording") }
            ui.click("Upload recording"); ui.replace("Recording storage server", origin)
            ui.click("Get storage key")
            ui.await("separate storage identity") { model.room.value.recordingStorageChoice != null }
            allowedKey = requireNotNull(model.room.value.recordingStorageChoice).publicKey
            assertNotEquals(model.start.value.account?.pubkey, allowedKey)
            assertFalse(ui.enabled("Upload")); assertEquals(0, puts.get())
            ui.click("Allow this encrypted recording to be uploaded to $origin"); ui.click("Upload")
            ui.await("private upload and separate Send") { ui.enabled("Send recording") && !model.room.value.mediaBusy }
            assertEquals(1, puts.get())
            val attachment = requireNotNull(app.recordingShareDrafts.list(room).single().uploaded)
            assertTrue(chatWrites(wire, room).isEmpty())
            // No acknowledgement and no history echo: the event may have been
            // offered, but neither path can accidentally confirm its delivery.
            wire.acknowledge = false; wire.storeReceivedEvents = false
            ui.click("Send recording")
            ui.await("exact durable handoff awaiting confirmation", timeoutMs = 120_000) {
                model.room.value.pendingChats.singleOrNull()?.state == PendingChatState.UNKNOWN &&
                    !model.room.value.chatSending && ui.enabled("Retry")
            }
            val expected = chatWrites(wire, room).distinctBy { it.id }.single()
            assertTrue(Events.verify(expected))
            assertEquals(expected.id, model.room.value.pendingChats.single().id)
            assertTrue(app.recordingShareDrafts.list(room).isEmpty())
            marker.writeText(JSONObject().put("pid", Process.myPid()).put("room", room)
                .put("port", port).put("event", expected.toJson().toString())
                .put("attachmentHash", attachment.sha256).put("export", export.name).put("exportSha256", digest(export))
                .put("events", JSONArray(wire.events.map { it.toJson().toString() })).toString())
        } else {
            val before = JSONObject(marker.readText())
            assertNotEquals(before.getInt("pid"), Process.myPid())
            val room = before.getString("room")
            val expected = NostrEvent.fromJson(Json.parseToJsonElement(before.getString("event")))
            val export = requireNotNull(app.recordings.export.value)
            assertEquals(before.getString("export"), export.name)
            assertEquals(before.getString("exportSha256"), digest(export))
            val wire = ProjectTestRelay(before.getInt("port")).also { relay = it }
            wire.acknowledge = false; wire.storeReceivedEvents = false
            val events = before.getJSONArray("events")
            for (i in 0 until events.length()) wire.publish(NostrEvent.fromJson(Json.parseToJsonElement(events.getString(i))))
            ui.await("restored account and saved room") { model.start.value.account != null && !model.start.value.loadingRooms }
            ui.click(title); ui.room()
            assertEquals(room, model.room.value.roomId)
            ui.await("original unknown message and visible Retry", timeoutMs = 120_000) {
                model.room.value.pendingChats.singleOrNull()?.let { it.id == expected.id && it.state == PendingChatState.UNKNOWN } == true &&
                    ui.enabled("Retry")
            }
            wire.storeReceivedEvents = true; wire.acknowledge = true
            ui.click("Retry")
            ui.await("same message confirmed in original chat", timeoutMs = 120_000) {
                model.room.value.pendingChats.isEmpty() && model.room.value.chat.count {
                    it.attachments.any { a -> a.sha256 == before.getString("attachmentHash") }
                } == 1
            }
            val offers = chatWrites(wire, room)
            assertTrue(offers.isNotEmpty()); assertTrue(offers.all { it == expected && Events.verify(it) })
            assertEquals(1, model.room.value.chat.single { it.attachments.isNotEmpty() }.attachments.size)
            assertEquals(0, puts.get())
            assertEquals(export, app.recordings.export.value)
            assertEquals(before.getString("exportSha256"), digest(export))
            activity.scenario.onActivity { model.leave() }
            ui.await("restored room closed") { model.stage.value == Stage.START && !model.start.value.busy }
            activity.scenario.onActivity { model.signOut() }
            ui.await("synthetic account signed out") { model.start.value.account == null }
        }
    }

    private fun tlsClient(fixture: File): OkHttpClient {
        val keys = KeyStore.getInstance("PKCS12").apply {
            File(fixture, "fixture.p12").inputStream().use { load(it, "synthetic-test-only".toCharArray()) }
        }
        val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, "synthetic-test-only".toCharArray()) }
        val trust = KeyStore.getInstance("PKCS12").apply { load(null); setCertificateEntry("fixture", keys.getCertificate(keys.aliases().nextElement())) }
        val tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
        val tls = SSLContext.getInstance("TLS").apply { init(km.keyManagers, tm.trustManagers, null) }
        server = MockWebServer().apply {
            useHttps(tls.socketFactory, false); protocols = listOf(Protocol.HTTP_1_1)
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method != "PUT" || request.path != "/upload") return MockResponse().setResponseCode(404)
                    puts.incrementAndGet()
                    val auth = NostrEvent.fromJson(Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(
                        requireNotNull(request.getHeader("Authorization")).removePrefix("Nostr ")))))
                    check(Events.verify(auth) && auth.kind == 24242 && auth.pubkey == allowedKey)
                    check(auth.tagValue("t") == "upload" && auth.tagValue("server") == "127.0.0.1")
                    val expiry = requireNotNull(auth.tagValue("expiration")).toLong()
                    check(expiry > System.currentTimeMillis() / 1000 && expiry - auth.createdAt in 1..300)
                    val bytes = request.body.readByteArray()
                    val hash = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
                    check(bytes.copyOfRange(0, 8).contentEquals("FSWNENC2".toByteArray()))
                    check(hash == request.getHeader("X-SHA-256") && auth.tagValue("x") == hash)
                    return MockResponse().setResponseCode(201).setHeader("Content-Type", "application/json")
                        .setBody("""{"url":"$origin/$hash.bin","sha256":"$hash","size":${bytes.size}}""")
                }
            }
            start(InetAddress.getByName("127.0.0.1"), 39847)
        }
        return OkHttpClient.Builder().sslSocketFactory(tls.socketFactory, tm.trustManagers.filterIsInstance<X509TrustManager>().single())
            .protocols(listOf(Protocol.HTTP_1_1)).build()
    }

    private fun chatWrites(wire: ProjectTestRelay, room: String): List<NostrEvent> {
        val secret = requireNotNull(app.savedRooms.get(room)).secret
        val original = try { deriveRoom(secret) } finally { secret.fill(0) }
        try {
            val address = deriveChatChannel(original.roomId, original.roomKey)
            try { return wire.writes.filter { it.kind == KIND_CHAT && it.tagValue("d") == address.id } }
            finally { address.key.fill(0) }
        } finally { original.roomKey.fill(0) }
    }

    private fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toHex()
}
