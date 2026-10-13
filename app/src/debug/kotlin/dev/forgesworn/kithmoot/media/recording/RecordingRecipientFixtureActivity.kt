package dev.forgesworn.kithmoot.media.recording

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.protocol.deriveRoom
import dev.forgesworn.kithmoot.relay.OkHttpRelaySockets
import dev.forgesworn.kithmoot.relay.RelayPool
import dev.forgesworn.kithmoot.session.PrimaryIdentity
import dev.forgesworn.kithmoot.session.RoomSession
import dev.forgesworn.kithmoot.session.SessionTiming
import dev.forgesworn.kithmoot.ui.room.ChatPane
import dev.forgesworn.kithmoot.ui.room.LocalRecordingAttachmentHttpClient
import dev.forgesworn.kithmoot.ui.theme.KithMootTheme
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import kotlin.concurrent.thread
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Non-exported emulator fixture, excluded from release. Receives encrypted
 * chat through its own process/socket; no attachment descriptor is injected. */
class RecordingRecipientFixtureActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var session: RoomSession? = null
    private var pool: RelayPool? = null
    private var http: OkHttpClient? = null
    private var identity: PrimaryIdentity? = null
    private var roomKey: ByteArray? = null
    private var fixtureDirectory: File? = null
    private var lifecycleSequence = 0L

    private fun observeLifecycle(state: String) {
        val fixture = fixtureDirectory ?: return
        val temporary = File(fixture, "recipient-lifecycle.tmp")
        temporary.writeText(JSONObject().put("pid", Process.myPid()).put("state", state)
            .put("sequence", ++lifecycleSequence).toString())
        check(temporary.renameTo(File(fixture, "recipient-lifecycle.json")))
    }

    override fun onResume() {
        super.onResume()
        observeLifecycle("resumed")
    }

    override fun onStop() {
        super.onStop()
        observeLifecycle("stopped")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action == CLOSE) { finish(); return }
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Disposable emulator fixture only" }
        val name = requireNotNull(intent.getStringExtra(FIXTURE))
        require(name.matches(Regex("recording-ui-network-[0-9a-f]{12}")))
        val fixture = File(noBackupFilesDir, name).also { fixtureDirectory = it }
        val seed = JSONObject(File(fixture, "recipient-seed.json").readText())
        val relay = seed.getString("relay")
        require(relay.matches(Regex("ws://127\\.0\\.0\\.1:[0-9]{1,5}/?")))
        val secret = seed.getString("secret").hexToBytes()
        val room = try { deriveRoom(secret) } finally { secret.fill(0) }
        roomKey = room.roomKey
        val now = System.currentTimeMillis() / 1000
        // Explicit synthetic participant/device, independent of the sender.
        val who = PrimaryIdentity.create(room.roomId, now + 3600, now,
            ByteArray(32) { 62 }, ByteArray(32) { 72 }).also { identity = it }
        val keys = KeyStore.getInstance("PKCS12").apply {
            File(fixture, "fixture.p12").inputStream().use { load(it, "synthetic-test-only".toCharArray()) }
        }
        val trust = KeyStore.getInstance("PKCS12").apply {
            load(null); setCertificateEntry("fixture", keys.getCertificate(keys.aliases().nextElement()))
        }
        val tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
        val tls = SSLContext.getInstance("TLS").apply { init(null, tm.trustManagers, null) }
        val client = OkHttpClient.Builder().sslSocketFactory(tls.socketFactory,
            tm.trustManagers.filterIsInstance<X509TrustManager>().single())
            .protocols(listOf(Protocol.HTTP_1_1)).build().also { http = it }
        val connection = RelayPool(listOf(relay), OkHttpRelaySockets(), scope).also { pool = it }
        val recipient = RoomSession(room, who, connection, scope, authority = seed.getString("authority"),
            timing = SessionTiming(announceJitterMs = 0)).also { session = it }
        setContent { KithMootTheme {
            CompositionLocalProvider(LocalRecordingAttachmentHttpClient provides client) {
                val messages by recipient.chat.collectAsState()
                Column(Modifier.fillMaxSize()) {
                    TextButton(onClick = { finish() }) { Text("Close recipient fixture") }
                    ChatPane(messages, who.participant, onSend = { _, _ -> error("Recipient fixture cannot send") },
                        modifier = Modifier.weight(1f))
                }
            }
        } }
        scope.launch {
            connection.start(); recipient.join()
            val ready = File(fixture, "recipient-ready.tmp")
            ready.writeText(JSONObject()
                .put("pid", Process.myPid()).put("participant", who.participant)
                .put("device", who.devicePubkey).put("room", room.roomId).toString())
            check(ready.renameTo(File(fixture, "recipient-ready.json")))
            recipient.chat.collect { messages ->
                val received = messages.firstOrNull { it.attachments.isNotEmpty() } ?: return@collect
                val attachment = received.attachments.single()
                val proof = JSONObject().put("pid", Process.myPid()).put("participant", received.participant)
                    .put("device", received.device).put("sha256", attachment.sha256).put("size", attachment.size)
                val temporary = File(fixture, "recipient-received.tmp")
                temporary.writeText(proof.toString())
                check(temporary.renameTo(File(fixture, "recipient-received.json")))
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == CLOSE) finish()
    }

    override fun onDestroy() {
        val fixture = fixtureDirectory
        val ownedSession = session.also { session = null }
        val ownedPool = pool.also { pool = null }
        val ownedHttp = http.also { http = null }
        val ownedIdentity = identity.also { identity = null }
        val ownedRoomKey = roomKey.also { roomKey = null }
        val roomJob = scope.coroutineContext[Job]
        val idleBefore = ownedHttp?.connectionPool?.idleConnectionCount() ?: 0
        fixtureDirectory = null
        scope.cancel()
        super.onDestroy()
        // A CLOSE intent can create an empty Activity after the real fixture
        // has finished. It must not overwrite the real process's observation.
        if (fixture == null) return
        // This operation owns the detached resources and survives cancellation
        // of the room scope. TLS eviction may write close-notify bytes.
        thread(name = "recording-recipient-cleanup", isDaemon = true) {
            val completed = mutableListOf<String>()
            val errors = org.json.JSONArray()
            fun attempt(label: String, action: () -> Unit) {
                try { action(); completed.add(label) } catch (error: Throwable) {
                    errors.put(JSONObject().put("action", label).put("errorClass", error.javaClass.name))
                }
            }
            fun observation() = JSONObject().put("pid", Process.myPid())
                .put("mainThread", Looper.myLooper() == Looper.getMainLooper())
                .put("idleConnectionsBefore", idleBefore)
            fun write(name: String, value: JSONObject) {
                val temporary = File(fixture, "$name.tmp")
                temporary.writeText(value.toString())
                check(temporary.renameTo(File(fixture, "$name.json")))
            }
            try {
                try { write("recipient-cleanup-started", observation()) } catch (error: Throwable) {
                    errors.put(JSONObject().put("action", "observation").put("errorClass", error.javaClass.name))
                }
                attempt("roomScope") { runBlocking { roomJob?.join() } }
                attempt("session") { ownedSession?.leave() }
                attempt("relay") { ownedPool?.stop() }
                attempt("httpCalls") { ownedHttp?.dispatcher?.cancelAll() }
                attempt("httpExecutor") { ownedHttp?.dispatcher?.executorService?.shutdown() }
                attempt("httpPool") { ownedHttp?.connectionPool?.evictAll() }
            } finally {
                attempt("keys") { ownedIdentity?.deviceSecretKey?.fill(0); ownedRoomKey?.fill(0) }
            }
            write("recipient-cleanup", observation()
                .put("connectionsAfter", ownedHttp?.connectionPool?.connectionCount() ?: 0)
                .put("completedActions", org.json.JSONArray(completed)).put("errors", errors))
        }
    }

    companion object {
        const val FIXTURE = "recordingNetworkFixture"
        const val CLOSE = "dev.forgesworn.kithmoot.CLOSE_RECORDING_RECIPIENT_FIXTURE"
    }
}
