package dev.forgesworn.kithmoot.media.recording

import android.os.Build
import android.os.SystemClock
import android.graphics.Color
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.projects.ProjectTestRelay
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.deriveRoom
import dev.forgesworn.kithmoot.session.deriveChatChannel
import dev.forgesworn.kithmoot.session.KIND_CHAT
import dev.forgesworn.kithmoot.session.PrimaryIdentity
import dev.forgesworn.kithmoot.session.RoomSession
import dev.forgesworn.kithmoot.session.SessionTiming
import dev.forgesworn.kithmoot.relay.RelayPool
import dev.forgesworn.kithmoot.relay.OkHttpRelaySockets
import dev.forgesworn.kithmoot.ui.room.ChatPane
import dev.forgesworn.kithmoot.ui.room.LocalRecordingAttachmentHttpClient
import kotlinx.coroutines.*
import dev.forgesworn.kithmoot.storage.RecoveryUi
import dev.forgesworn.kithmoot.ui.KithMootApp
import dev.forgesworn.kithmoot.ui.RoomViewModel
import dev.forgesworn.kithmoot.ui.Stage
import dev.forgesworn.kithmoot.ui.theme.KithMootTheme
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.net.InetAddress
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.*

/** Real controls, TLS socket and signed relay publication. The driver provides
 * a temporary test certificate and an independently qualified synthetic MP4.
 * This is a Blossom-contract fixture, not a running Wildbloom daemon. */
class RecordingUploadSendUiTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    private val app get() = ApplicationProvider.getApplicationContext<KithMootApplication>()
    private val ui = RecoveryUi(useSwipeFallback = false)
    private lateinit var model: RoomViewModel
    private lateinit var relay: ProjectTestRelay
    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient
    private lateinit var tls: SSLContext
    private lateinit var fixture: File
    private var room: String? = null
    @Volatile private var allowedKey: String? = null
    @Volatile private var redirect = false
    @Volatile private var uploadedHash: String? = null
    @Volatile private var uploadedAuth: NostrEvent? = null
    @Volatile private var uploadedBytes: ByteArray? = null
    private val gets = AtomicInteger()
    private val puts = AtomicInteger()
    private val redirectOffers = AtomicInteger()
    private var redirectTarget: MockWebServer? = null
    private val origin get() = "https://127.0.0.1:39847"

    @Before fun setup() {
        Assume.assumeTrue("Disposable emulator only", Build.HARDWARE in setOf("ranchu", "goldfish"))
        val name = InstrumentationRegistry.getArguments().getString("recordingNetworkFixture")
        Assume.assumeTrue("Run the guarded TLS fixture driver", name != null)
        require(requireNotNull(name).matches(Regex("recording-ui-network-[0-9a-f]{12}")))
        fixture = File(app.noBackupFilesDir, name)
        val keys = KeyStore.getInstance("PKCS12").apply {
            File(fixture, "fixture.p12").inputStream().use { load(it, "synthetic-test-only".toCharArray()) }
        }
        val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keys, "synthetic-test-only".toCharArray())
        }
        val trust = KeyStore.getInstance("PKCS12").apply {
            load(null); setCertificateEntry("fixture", keys.getCertificate(keys.aliases().nextElement()))
        }
        val tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
        tls = SSLContext.getInstance("TLS").apply { init(km.keyManagers, tm.trustManagers, null) }
        http = OkHttpClient.Builder().sslSocketFactory(tls.socketFactory, tm.trustManagers.filterIsInstance<X509TrustManager>().single())
            .protocols(listOf(Protocol.HTTP_1_1)).build()
        server = MockWebServer().apply {
            useHttps(tls.socketFactory, false); protocols = listOf(Protocol.HTTP_1_1)
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method == "GET" && uploadedHash != null && request.path == "/$uploadedHash.bin") {
                        gets.incrementAndGet()
                        check(request.getHeader("Authorization") == null)
                        return MockResponse().setResponseCode(200)
                            .setBody(okio.Buffer().write(requireNotNull(uploadedBytes)))
                    }
                    if (request.method != "PUT" || request.path != "/upload") return MockResponse().setResponseCode(404)
                    puts.incrementAndGet()
                    return try {
                        val auth = NostrEvent.fromJson(Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(
                            requireNotNull(request.getHeader("Authorization")).removePrefix("Nostr ")))))
                        check(Events.verify(auth) && auth.kind == 24242 && auth.pubkey == allowedKey)
                        check(auth.tagValue("t") == "upload" && auth.tagValue("server") == "127.0.0.1")
                        val expiry = requireNotNull(auth.tagValue("expiration")).toLong()
                        check(expiry > System.currentTimeMillis() / 1000 && expiry - auth.createdAt in 1..300)
                        val bytes = request.body.readByteArray()
                        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
                        check(bytes.size > 73 && bytes.copyOfRange(0, 8).contentEquals("FSWNENC2".toByteArray()))
                        check(hash == request.getHeader("X-SHA-256") && auth.tagValue("x") == hash)
                        uploadedHash = hash; uploadedAuth = auth; uploadedBytes = bytes
                        if (redirect) MockResponse().setResponseCode(307).setHeader("Location", "https://127.0.0.1:39848/upload")
                        else MockResponse().setResponseCode(201).setHeader("Content-Type", "application/json")
                            .setBody("""{"url":"$origin/$hash.bin","sha256":"$hash","size":${bytes.size}}""")
                    } catch (_: Exception) { MockResponse().setResponseCode(403) }
                }
            }
            start(InetAddress.getByName("127.0.0.1"), 39847)
        }
        relay = ProjectTestRelay()
        activity.scenario.onActivity { owner ->
            model = ViewModelProvider(owner, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(type: Class<T>): T =
                    RoomViewModel(app, chatOnly = true, recordingUploadClient = http) as T
            })["recording-https-fixture", RoomViewModel::class.java]
            owner.setContent { KithMootTheme { KithMootApp(model) } }
        }
        ui.home()
        if (app.accounts.load() != null) {
            ui.await("fixture account loaded") { model.start.value.account != null }
            activity.scenario.onActivity { model.signOut() }
            ui.await("fixture account cleared") { model.start.value.account == null }
        }
        app.recordings.export.value?.let(app.recordings::discard)
        app.savedRooms.reset()
        activity.scenario.onActivity {
            model.onRelaysChanged(relay.url)
            model.installLocalTestAccount(ByteArray(32) { 59 })
        }
        ui.await("synthetic HTTPS account") { model.start.value.account != null && model.start.value.roomBookmarks.ready }
        ui.click("New room"); ui.replace("Room name (optional)", "Synthetic HTTPS recording"); ui.click("Start a room"); ui.room()
        room = model.room.value.roomId
        val source = app.recordings.begin(RecordingFormat.VIDEO, RecordingOrigin(requireNotNull(room), "cd".repeat(16), "Synthetic HTTPS recording"))
        File(fixture, "sample.mp4").copyTo(source)
        app.recordings.complete(source)
        ui.click("Add to original chat")
        ui.await("private draft from selected original export") { ui.hasText("Upload recording") }
    }

    @After fun cleanup() {
        if (::model.isInitialized) {
            if (model.stage.value == Stage.ROOM) {
                activity.scenario.onActivity { model.leave() }
                ui.await("fixture room closed") { model.stage.value == Stage.START && !model.start.value.busy }
            }
            room?.let(app::forgetRecordingsForRoom)
            app.recordings.export.value?.let(app.recordings::discard)
            activity.scenario.onActivity { model.signOut() }
            ui.await("fixture account closed") { model.start.value.account == null }
        }
        redirectTarget?.close()
        if (::server.isInitialized) server.close()
        if (::relay.isInitialized) relay.close()
    }

    private fun chooseAndUpload() {
        ui.click("Upload recording"); ui.replace("Recording storage server", origin); ui.click("Get storage key")
        ui.await("public storage identity") { ui.hasText("Copy storage key") }
        val choice = requireNotNull(model.room.value.recordingStorageChoice)
        allowedKey = choice.publicKey
        assertNotEquals(model.start.value.account?.pubkey, allowedKey)
        assertEquals(0, puts.get()); assertFalse(ui.enabled("Upload"))
        ui.click("Allow this encrypted recording to be uploaded to $origin")
        ui.click("Upload")
        ui.await("HTTPS request completed") { !model.room.value.recordingUploadRunning && puts.get() == 1 }
    }

    private fun originalChatWrites(): List<NostrEvent> {
        val secret = requireNotNull(app.savedRooms.get(requireNotNull(room))).secret
        val original = try { deriveRoom(secret) } finally { secret.fill(0) }
        try {
            assertEquals(room, original.roomId)
            val address = deriveChatChannel(original.roomId, original.roomKey)
            try { return relay.writes.filter { it.kind == KIND_CHAT && it.tagValue("d") == address.id } }
            finally { address.key.fill(0) }
        } finally { original.roomKey.fill(0) }
    }

    @Test fun explicit_https_upload_then_send_keeps_local_export_and_uses_only_ciphertext() {
        val original = requireNotNull(app.recordings.export.value)
        chooseAndUpload()
        ui.await("separate Send after retained HTTPS receipt") { ui.hasText("Send recording") }
        val draft = app.recordingShareDrafts.list(requireNotNull(room)).single()
        assertEquals(uploadedHash, draft.uploaded?.sha256)
        assertTrue(model.room.value.chat.none { it.attachments.isNotEmpty() })
        assertTrue(originalChatWrites().isEmpty())
        ui.click("Send recording")
        ui.await("original chat message after explicit Send") {
            model.room.value.chat.any { it.attachments.any { a -> a.sha256 == uploadedHash } } &&
                app.recordingShareDrafts.list(requireNotNull(room)).isEmpty() && !model.room.value.mediaBusy
        }
        val message = model.room.value.chat.single { it.attachments.isNotEmpty() }
        assertEquals(requireNotNull(room), model.room.value.roomId)
        assertNotEquals(message.device, requireNotNull(uploadedAuth).pubkey)
        assertEquals(1, originalChatWrites().distinctBy { it.id }.size)
        assertEquals(original, app.recordings.export.value); assertTrue(original.isFile)
        assertEquals(1, puts.get()); assertEquals(0, redirectOffers.get())
    }


    private fun hasClock(seconds: Int): Boolean {
        fun matches(node: AccessibilityNodeInfo): Boolean {
            if (node.isVisibleToUser && node.text?.toString()?.let {
                it == "0:%02d".format(seconds) || it.startsWith("0:%02d / ".format(seconds))
            } == true) return true
            return (0 until node.childCount).mapNotNull(node::getChild).any(::matches)
        }
        return InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow?.let(::matches) == true
    }

    @Test fun independent_recipient_receives_show_fetches_ciphertext_and_explicit_play_decodes_video() {
        val saved = requireNotNull(app.savedRooms.get(requireNotNull(room)))
        val secret = saved.secret
        val peerRoom = try { deriveRoom(secret) } finally { secret.fill(0) }
        val now = System.currentTimeMillis() / 1000
        val identity = PrimaryIdentity.create(peerRoom.roomId, now + 3600, now,
            ByteArray(32) { 62 }, ByteArray(32) { 72 })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pool = RelayPool(listOf(relay.url), OkHttpRelaySockets(), scope)
        val recipient = RoomSession(peerRoom, identity, pool, scope,
            authority = saved.authority, timing = SessionTiming(announceJitterMs = 0))
        val original = requireNotNull(app.recordings.export.value)
        try {
            pool.start()
            runBlocking { recipient.join() }
            chooseAndUpload()
            ui.await("uploaded receipt enables explicit Send") { ui.hasText("Send recording") }
            assertTrue(recipient.chat.value.none { it.attachments.isNotEmpty() })
            assertEquals(0, gets.get())
            ui.click("Send recording")
            ui.await("independent relay subscription receives recording") {
                recipient.chat.value.any { it.attachments.any { a -> a.sha256 == uploadedHash } }
            }
            val received = recipient.chat.value.single { it.attachments.isNotEmpty() }
            assertNotEquals(identity.participant, received.participant)
            assertNotEquals(identity.devicePubkey, received.device)
            val attachment = received.attachments.single()
            assertEquals(uploadedHash, attachment.sha256)
            assertEquals(requireNotNull(uploadedBytes).size.toLong(), attachment.size)
            assertEquals(1, originalChatWrites().distinctBy { it.id }.size)
            assertEquals(0, gets.get())
            activity.scenario.onActivity { owner -> owner.setContent {
                KithMootTheme {
                    CompositionLocalProvider(LocalRecordingAttachmentHttpClient provides http) {
                        val messages by recipient.chat.collectAsState()
                        ChatPane(messages, identity.participant, onSend = { _, _ -> error("Recipient does not send in this fixture") })
                    }
                }
            } }
            val show = "Show recording: ${attachment.name ?: "Recording"}"
            ui.await("received recording card") { ui.hasText(show) }
            SystemClock.sleep(600)
            assertEquals("Receiving and rendering must not fetch", 0, gets.get())
            ui.click(show)
            ui.await("TLS download authenticates and prepares native playback") { ui.enabled("Play recording") && hasClock(0) }
            assertEquals(1, gets.get())
            val plaintext = app.recordingPlaybackCache.walkTopDown().filter { it.isFile }.toList()
            assertTrue("Authenticated original MP4 exists only in private playback cache", plaintext.any {
                MessageDigest.getInstance("SHA-256").digest(it.readBytes()).contentEquals(
                    MessageDigest.getInstance("SHA-256").digest(File(fixture, "sample.mp4").readBytes()))
            })
            SystemClock.sleep(600)
            assertTrue("Show does not autoplay", hasClock(0) && ui.hasText("Play recording"))
            ui.click("Play recording")
            ui.await("native video decoder advances") { hasActiveRecordingPlayback() }
            ui.await("decoded synthetic red camera visible") {
                val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                try {
                    var red = 0
                    for (y in 0 until screenshot.height step 4) for (x in 0 until screenshot.width step 4) {
                        val pixel = screenshot.getPixel(x, y)
                        if (Color.red(pixel) > 180 && Color.green(pixel) < 80 && Color.blue(pixel) < 80) red++
                    }
                    red > 1000
                } finally { screenshot.recycle() }
            }
            activity.scenario.moveToState(Lifecycle.State.CREATED)
            SystemClock.sleep(500)
            activity.scenario.moveToState(Lifecycle.State.RESUMED)
            ui.await("recipient return remains paused") { ui.hasText("Play recording") }
            ui.click("Close recording")
            ui.await("recipient chat restored") { ui.hasText(show) }
            ui.await("private plaintext removed on close") { app.recordingPlaybackCache.listFiles()?.isEmpty() == true }
            assertEquals(1, gets.get()); assertEquals(1, puts.get())
            assertEquals(original, app.recordings.export.value); assertTrue(original.isFile)
        } finally {
            activity.scenario.onActivity { owner -> owner.setContent { KithMootTheme { KithMootApp(model) } } }
            recipient.leave(); pool.stop(); scope.cancel(); peerRoom.roomKey.fill(0)
            identity.deviceSecretKey.fill(0)
        }
    }

    @Test fun https_redirect_is_not_followed_or_retried_and_cannot_enable_send() {
        redirect = true
        redirectTarget = MockWebServer().apply {
            useHttps(tls.socketFactory, false); protocols = listOf(Protocol.HTTP_1_1)
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    redirectOffers.incrementAndGet(); return MockResponse().setResponseCode(403)
                }
            }
            start(InetAddress.getByName("127.0.0.1"), 39848)
        }
        chooseAndUpload()
        assertEquals(0, redirectOffers.get()); assertEquals(1, puts.get())
        assertFalse(ui.hasText("Send recording"))
        assertNotNull(model.room.value.chatSendError)
        val draft = app.recordingShareDrafts.list(requireNotNull(room)).single()
        assertNull(draft.uploaded); assertTrue(draft.sealed.file.isFile)
        assertNotNull(app.recordings.export.value)
        assertTrue(originalChatWrites().isEmpty())
    }
}
