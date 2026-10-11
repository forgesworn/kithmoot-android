package dev.forgesworn.kithmoot.media.recording

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.graphics.Color
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.activity.compose.setContent
import androidx.activity.ComponentActivity
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
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.net.InetAddress
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import javax.net.ssl.*

/** Real controls, TLS socket and signed relay publication. The driver provides
 * a temporary test certificate and an independently qualified synthetic MP4.
 * Default mode uses a Blossom-contract fixture. The guarded real-node driver
 * forwards unchanged requests through TLS to its explicitly allowlisted daemon. */
class RecordingUploadSendUiTest {
    @get:Rule val activity = ActivityScenarioRule(ComponentActivity::class.java)
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
    private val realNode get() = InstrumentationRegistry.getArguments().getString("recordingRealNode") == "true"
    private val hostProxy get() = InstrumentationRegistry.getArguments().getString("recordingHostProxy") == "true"
    private val nodeClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).build()
    private val origin get() = "https://127.0.0.1:39847"

    @Before fun setup() {
        Assume.assumeTrue("Disposable emulator only", Build.HARDWARE in setOf("ranchu", "goldfish"))
        val name = InstrumentationRegistry.getArguments().getString("recordingNetworkFixture")
        Assume.assumeTrue("Run the guarded TLS fixture driver", name != null)
        require(requireNotNull(name).matches(Regex("recording-ui-network-[0-9a-f]{12}")))
        fixture = File(app.noBackupFilesDir, name)
        require(!hostProxy || realNode) { "Host streaming requires the guarded private-node driver" }
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
        if (!hostProxy) server = MockWebServer().apply {
            useHttps(tls.socketFactory, false); protocols = listOf(Protocol.HTTP_1_1)
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method == "GET" && uploadedHash != null && request.path == "/$uploadedHash.bin") {
                        gets.incrementAndGet()
                        check(request.getHeader("Authorization") == null)
                        return if (realNode) forwardToNode(request, null) else MockResponse().setResponseCode(200)
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
                        if (realNode) forwardToNode(request, bytes)
                        else if (redirect) MockResponse().setResponseCode(307).setHeader("Location", "https://127.0.0.1:39848/upload")
                        else MockResponse().setResponseCode(201).setHeader("Content-Type", "application/json")
                            .setBody("""{"url":"$origin/$hash.bin","sha256":"$hash","size":${bytes.size}}""")
                    } catch (_: Exception) { MockResponse().setResponseCode(403) }
                }
            }
            start(InetAddress.getByName("127.0.0.1"), 39847)
        }
        relay = ProjectTestRelay()
        // This chat-only fixture does not restore accounts. Refuse to reset
        // anything except its own repeated-byte synthetic local identity.
        app.accounts.load()?.let { retained ->
            check(retained.method == "local" && retained.pubkey == Schnorr.publicKeyHex(ByteArray(32) { 59 })) {
                "HTTPS fixture refuses an unrelated retained account"
            }
            app.accounts.clear()
        }
        activity.scenario.onActivity { owner ->
            owner.enableEdgeToEdge()
            model = ViewModelProvider(owner, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(type: Class<T>): T =
                    RoomViewModel(app, chatOnly = true, recordingUploadClient = http) as T
            })["recording-https-fixture", RoomViewModel::class.java]
            owner.setContent { KithMootTheme { KithMootApp(model) } }
        }
        ui.home()
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
        var failure: Throwable? = null
        try {
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
        } catch (error: Throwable) { failure = error }
        // A failed UI precondition must not leave the fixed TLS port occupied
        // and substitute bind failures for the following cases' real results.
        val close = listOf<() -> Unit>(
            { redirectTarget?.close() },
            { if (::server.isInitialized) server.close() },
            { if (::relay.isInitialized) relay.close() },
            { nodeClient.dispatcher.executorService.shutdown(); nodeClient.connectionPool.evictAll() },
        )
        for (action in close) try { action() } catch (error: Throwable) {
            val previous = failure
            if (previous == null) failure = error else previous.addSuppressed(error)
        }
        failure?.let { throw it }
    }

    /** Actual daemon response is forwarded, never replaced by a fixture receipt. */
    private fun forwardToNode(request: RecordedRequest, bytes: ByteArray?): MockResponse {
        val builder = Request.Builder().url("http://10.0.2.2:${requireNotNull(InstrumentationRegistry.getArguments().getString("recordingNodePort")).toInt().also { require(it in 1024..65535) }}${request.path}")
            .method(requireNotNull(request.method), bytes?.toRequestBody())
        for (name in request.headers.names()) if (!name.equals("Content-Length", true) &&
            !name.equals("Connection", true)) builder.header(name, requireNotNull(request.getHeader(name)))
        return nodeClient.newCall(builder.build()).execute().use { response ->
            File(fixture, "node-responses.txt").appendText("${request.method} ${response.code}\n")
            MockResponse().setResponseCode(response.code)
                .setHeaders(response.headers)
                .setBody(okio.Buffer().write(response.body?.bytes() ?: byteArrayOf()))
        }
    }

    private fun chooseAndUpload() {
        ui.click("Upload recording"); ui.replace("Recording storage server", origin); ui.click("Get storage key")
        ui.await("public storage identity") { ui.hasText("Copy storage key") }
        val choice = requireNotNull(model.room.value.recordingStorageChoice)
        allowedKey = choice.publicKey
        assertNotEquals(model.start.value.account?.pubkey, allowedKey)
        assertEquals(0, putCount()); assertFalse(ui.enabled("Upload"))
        if (realNode) {
            File(fixture, "storage-public-key.txt").writeText(requireNotNull(allowedKey))
            ui.await("operator explicitly authorises the storage key on its private node", timeoutMs = 120_000) {
                File(fixture, "node-ready").isFile
            }
        }
        ui.click("Allow this encrypted recording to be uploaded to $origin")
        ui.click("Upload")
        ui.await("HTTPS request completed", timeoutMs = if (hostProxy) 660_000 else 60_000) {
            !model.room.value.recordingUploadRunning && putCount() == 1
        }
        if (hostProxy) {
            assertNull("Private daemon Upload must succeed", model.room.value.chatSendError)
            val stats = hostStats()
            val draft = app.recordingShareDrafts.list(requireNotNull(room)).single()
            val attachment = requireNotNull(draft.uploaded)
            uploadedHash = stats.getString("sha256")
            assertEquals(draft.sealed.hash, uploadedHash)
            assertEquals(attachment.sha256, uploadedHash)
            assertEquals(draft.sealed.file.length(), stats.getLong("size"))
            assertEquals(attachment.size, stats.getLong("size"))
        }
    }

    private fun hostStats(): JSONObject = http.newCall(Request.Builder()
        .url("$origin/__fixture/${fixture.name}").build()).execute().use { response ->
        check(response.code == 200) { "Host fixture statistics unavailable" }
        JSONObject(requireNotNull(response.body).string())
    }

    private fun putCount(): Int = if (hostProxy) hostStats().getInt("puts") else puts.get()
    private fun getCount(): Int = if (hostProxy) hostStats().getInt("gets") else gets.get()
    private fun uploadedSize(): Long = if (hostProxy) hostStats().getLong("size")
        else requireNotNull(uploadedBytes).size.toLong()

    private fun digest(file: File): ByteArray {
        val checksum = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { source ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                checksum.update(buffer, 0, count)
            }
        }
        return checksum.digest()
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
        assertNotEquals(message.device, requireNotNull(if (hostProxy) allowedKey else uploadedAuth?.pubkey))
        assertEquals(1, originalChatWrites().distinctBy { it.id }.size)
        assertEquals(original, app.recordings.export.value); assertTrue(original.isFile)
        assertEquals(1, putCount()); assertEquals(0, redirectOffers.get())
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
            if (realNode) assertNull("Private daemon Upload must succeed", model.room.value.chatSendError)
            ui.await("uploaded receipt enables explicit Send") { ui.hasText("Send recording") }
            assertTrue(recipient.chat.value.none { it.attachments.isNotEmpty() })
            assertEquals(0, getCount())
            ui.click("Send recording")
            ui.await("independent relay subscription receives recording") {
                recipient.chat.value.any { it.attachments.any { a -> a.sha256 == uploadedHash } }
            }
            val received = recipient.chat.value.single { it.attachments.isNotEmpty() }
            assertNotEquals(identity.participant, received.participant)
            assertNotEquals(identity.devicePubkey, received.device)
            val attachment = received.attachments.single()
            assertEquals(uploadedHash, attachment.sha256)
            assertEquals(uploadedSize(), attachment.size)
            assertEquals(1, originalChatWrites().distinctBy { it.id }.size)
            assertEquals(0, getCount())
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
            assertEquals("Receiving and rendering must not fetch", 0, getCount())
            ui.click(show)
            ui.await("TLS download authenticates and prepares native playback", timeoutMs = if (hostProxy) 660_000 else 60_000) { ui.enabled("Play recording") && hasClock(0) }
            assertEquals(1, getCount())
            val plaintext = app.recordingPlaybackCache.walkTopDown().filter { it.isFile }.toList()
            assertTrue("Authenticated original MP4 exists only in private playback cache", plaintext.any {
                digest(it).contentEquals(
                    digest(File(fixture, "sample.mp4")))
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
            assertEquals(1, getCount()); assertEquals(1, putCount())
            assertEquals(original, app.recordings.export.value); assertTrue(original.isFile)
        } finally {
            activity.scenario.onActivity { owner -> owner.setContent { KithMootTheme { KithMootApp(model) } } }
            recipient.leave(); pool.stop(); scope.cancel(); peerRoom.roomKey.fill(0)
            identity.deviceSecretKey.fill(0)
        }
    }

    @Test fun separate_process_recipient_receives_and_plays_without_sender_memory() {
        val saved = requireNotNull(app.savedRooms.get(requireNotNull(room)))
        val secret = saved.secret
        try {
            File(fixture, "recipient-seed.json").writeText(JSONObject().put("secret", secret.toHex())
                .put("authority", requireNotNull(saved.authority)).put("relay", relay.url).toString())
        } finally { secret.fill(0) }
        val original = requireNotNull(app.recordings.export.value)
        chooseAndUpload()
        ui.await("uploaded receipt enables explicit Send") { ui.hasText("Send recording") }
        ui.click("Send recording")
        ui.await("sender finishes separate Send") {
            app.recordingShareDrafts.list(requireNotNull(room)).isEmpty() && !model.room.value.mediaBusy
        }
        assertEquals(1, originalChatWrites().distinctBy { it.id }.size)
        assertEquals(0, getCount())
        fun recipientIntent(action: String? = null) = Intent(app, RecordingRecipientFixtureActivity::class.java)
            .putExtra(RecordingRecipientFixtureActivity.FIXTURE, fixture.name)
            .setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val cache = File(app.cacheDir, "recording-playback") // Do not initialise the sender's lazy cache owner.
        try {
            File(fixture, "sender-process.txt").writeText(android.os.Process.myPid().toString())
            app.startActivity(recipientIntent())
            ui.await("recipient joins in separate process") { File(fixture, "recipient-ready.json").isFile }
            val ready = JSONObject(File(fixture, "recipient-ready.json").readText())
            assertNotEquals(android.os.Process.myPid(), ready.getInt("pid"))
            assertEquals(room, ready.getString("room"))
            ui.await("separate process receives through its own relay subscription") {
                File(fixture, "recipient-received.json").isFile
            }
            val received = JSONObject(File(fixture, "recipient-received.json").readText())
            assertEquals(ready.getInt("pid"), received.getInt("pid"))
            assertNotEquals(ready.getString("participant"), received.getString("participant"))
            assertNotEquals(ready.getString("device"), received.getString("device"))
            assertEquals(uploadedHash, received.getString("sha256"))
            assertEquals(uploadedSize(), received.getLong("size"))
            val name = model.room.value.chat.single { it.attachments.isNotEmpty() }.attachments.single().name ?: "Recording"
            val show = "Show recording: $name"
            ui.await("production received chat card in separate process") { ui.hasText(show) }
            SystemClock.sleep(600)
            assertEquals("Receiving and rendering must not fetch", 0, getCount())
            ui.click(show)
            ui.await("separate process authenticates and prepares MP4", timeoutMs = if (hostProxy) 660_000 else 60_000) { ui.enabled("Play recording") && hasClock(0) }
            assertEquals(1, getCount())
            val expected = digest(File(fixture, "sample.mp4"))
            assertTrue(cache.walkTopDown().filter { it.isFile }.any {
                digest(it).contentEquals(expected)
            })
            SystemClock.sleep(600)
            assertTrue("Show does not autoplay", hasClock(0) && ui.hasText("Play recording"))
            ui.click("Play recording")
            ui.await("separate process native decoder advances") { hasActiveRecordingPlayback() }
            ui.await("separate process renders decoded red camera pixels") {
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
            assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation
                .performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
            SystemClock.sleep(500)
            app.startActivity(recipientIntent())
            ui.await("separate recipient background return remains paused") { ui.hasText("Play recording") }
            ui.click("Close recording")
            ui.await("separate recipient chat restored") { ui.hasText(show) }
            ui.await("separate recipient removes private plaintext on Close") { cache.listFiles()?.isEmpty() == true }
            assertEquals(1, getCount()); assertEquals(1, putCount())
            ui.click("Close recipient fixture")
            ui.await("sender room returns") { ui.hasText("Synthetic HTTPS recording") }
            assertEquals(original, app.recordings.export.value); assertTrue(original.isFile)
        } finally {
            app.startActivity(recipientIntent(RecordingRecipientFixtureActivity.CLOSE))
            File(fixture, "recipient-seed.json").delete()
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
        assertEquals(0, redirectOffers.get()); assertEquals(1, putCount())
        assertFalse(ui.hasText("Send recording"))
        assertNotNull(model.room.value.chatSendError)
        val draft = app.recordingShareDrafts.list(requireNotNull(room)).single()
        assertNull(draft.uploaded); assertTrue(draft.sealed.file.isFile)
        assertNotNull(app.recordings.export.value)
        assertTrue(originalChatWrites().isEmpty())
    }
}
