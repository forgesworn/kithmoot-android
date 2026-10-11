package dev.forgesworn.kithmoot.media.recording

import android.os.SystemClock
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.projects.ProjectTestRelay
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.OkHttpRelaySockets
import dev.forgesworn.kithmoot.relay.RelayPool
import dev.forgesworn.kithmoot.session.PrimaryIdentity
import dev.forgesworn.kithmoot.session.RoomSession
import dev.forgesworn.kithmoot.session.SessionTiming
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentHashMap

/** Two production sessions, independently implemented crypto and actual relay
 * sockets. Synthetic identities only; this does not qualify media or phone UI. */
class LiveRecordingBrowserInteropTest {
    class Bridge {
        val ready = AtomicBoolean(false)
        val state = AtomicReference<JsonObject?>(null)
        val failure = AtomicReference<String?>(null)
        val published = AtomicReference<Long?>(null)
        val received = ConcurrentHashMap<String, JsonElement>()
        @JavascriptInterface fun ready(room: String, participant: String, device: String) { ready.set(true) }
        @JavascriptInterface fun state(value: String) {
            state.set(Json.parseToJsonElement(value).takeUnless { it is JsonNull }?.jsonObject)
        }
        @JavascriptInterface fun failure(value: String) { failure.set(value) }
        @JavascriptInterface fun published(id: String, version: Long) { published.set(version) }
        @JavascriptInterface fun received(id: String, value: String) { received[id] = Json.parseToJsonElement(value) }
    }

    @Test fun native_and_browser_sessions_exchange_root_signed_on_off_and_reject_replay() {
        Assume.assumeTrue("Disposable emulator only", android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val room = deriveRoom(ByteArray(32) { 11 })
        val now = System.currentTimeMillis() / 1000
        val root = ByteArray(32) { 7 }
        val authority = Schnorr.publicKeyHex(root)
        val identity = PrimaryIdentity.create(room.roomId, now + 3600, now, root, ByteArray(32) { 8 })
        val bridge = Bridge()
        ProjectTestRelay().use { relay ->
            val pool = RelayPool(listOf(relay.url), OkHttpRelaySockets(), scope)
            val native = RoomSession(room, identity, pool, scope, authority = authority,
                timing = SessionTiming(announceJitterMs = 0))
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                var view: WebView? = null
                fun js(source: String) = instrumentation.runOnMainSync {
                    requireNotNull(view).evaluateJavascript(source, null)
                }
                fun await(label: String, predicate: () -> Boolean) {
                    val deadline = SystemClock.elapsedRealtime() + 20_000
                    while (SystemClock.elapsedRealtime() < deadline) {
                        bridge.failure.get()?.let { fail("Browser session failed: $it") }
                        if (predicate()) return
                        SystemClock.sleep(50)
                    }
                    fail("Timed out: $label")
                }
                try {
                    pool.start()
                    runBlocking { native.join() }
                    val call = "ab".repeat(16)
                    native.setCall(CallMembership(call, now))
                    val source = instrumentation.context.assets.open("browser-recording.js").bufferedReader().use { it.readText() }
                    activity.onActivity {
                        view = WebView(it).also { browser ->
                            browser.settings.javaScriptEnabled = true
                            browser.addJavascriptInterface(bridge, "Native")
                            it.setContentView(browser)
                            browser.loadDataWithBaseURL("http://127.0.0.1/", "<html><body><script>$source</script></body></html>", "text/html", "UTF-8", null)
                        }
                    }
                    // Wait for script evaluation before invoking the production session.
                    val config = buildJsonObject {
                        put("relay", relay.url); put("roomKey", 11); put("authorityKey", 7)
                        put("authority", authority); put("participantKey", 9); put("deviceKey", 10)
                        put("call", call); put("since", now)
                    }
                    await("browser bundle loaded") {
                        js("if(window.recordingInterop && !window.started){window.started=true;window.recordingInterop.start($config).catch(e=>Native.failure(String(e)))}")
                        bridge.ready.get()
                    }
                    await("authenticated browser presence") { native.participants.value.any { it.participant == Schnorr.publicKeyHex(ByteArray(32) { 9 }) } }
                    val id = "12".repeat(16)
                    val version = now * 1000 + 1
                    fun state(on: Boolean, at: Long): LiveRecordingState {
                        val notice = RecordingNotice(on, id, at)
                        val capture = RecordingCaptureNotice(id, at, "gallery", identity.participant, identity.devicePubkey)
                        return LiveRecordingState(SignedRecordingNotice(notice, signRecordingNotice(room.roomId, notice, root)),
                            if (on) SignedRecordingCaptureNotice(capture, signRecordingCaptureNotice(room.roomId, capture, root)) else null)
                    }
                    val on = state(true, version)
                    fun presence(value: LiveRecordingState) = encodeRosterEvent(RosterEntry(identity.participant,
                        identity.devicePubkey, identity.credential, updatedAt = now, recordingProfile = 3,
                        call = CallMembership(call, now), liveRecording = value), room.roomId, room.roomKey, identity.deviceSecretKey)
                    val forged = presence(on.copy(capture = on.capture!!.copy(sig = "00".repeat(64))))
                    relay.publish(forged)
                    await("browser processes forged capture") { bridge.received.containsKey(forged.id) }
                    assertEquals(JsonNull, bridge.received[forged.id])
                    assertTrue(runBlocking { native.publishLiveRecordingConfirmed(on) })
                    await("native On reaches browser session") {
                        js("window.recordingInterop.report()")
                        bridge.state.get()?.get("state")?.jsonObject?.get("recording")?.jsonObject?.get("on")?.jsonPrimitive?.boolean == true
                    }
                    val off = state(false, version + 1)
                    assertTrue(runBlocking { native.publishLiveRecordingConfirmed(off) })
                    await("native Off reaches browser session") {
                        js("window.recordingInterop.report()")
                        bridge.state.get()?.get("state")?.jsonObject?.get("recording")?.jsonObject?.get("version")?.jsonPrimitive?.long == version + 1
                    }
                    val replay = presence(on)
                    relay.publish(replay)
                    await("browser processes replay after Off") { bridge.received.containsKey(replay.id) }
                    val retained = bridge.received[replay.id]!!.jsonObject["state"]!!.jsonObject["recording"]!!.jsonObject
                    assertFalse(retained["on"]!!.jsonPrimitive.boolean)
                    assertEquals(version + 1, retained["version"]!!.jsonPrimitive.long)
                    js("window.recordingInterop.publish(true,'${"34".repeat(16)}',${version + 2}).catch(e=>Native.failure(String(e)))")
                    await("browser On reaches native session") { native.liveRecording.value?.state?.recording?.notice?.version == version + 2 }
                    assertTrue(native.liveRecording.value!!.state.recording.notice.on)
                    assertEquals(Schnorr.publicKeyHex(ByteArray(32) { 9 }), native.liveRecording.value!!.state.capture!!.notice.recorder)
                    js("window.recordingInterop.publish(false,'${"34".repeat(16)}',${version + 3}).catch(e=>Native.failure(String(e)))")
                    await("browser Off reaches native session") { native.liveRecording.value?.state?.recording?.notice?.version == version + 3 }
                    assertFalse(native.liveRecording.value!!.state.recording.notice.on)
                    assertNull(native.liveRecording.value!!.state.capture)
                } finally {
                    runBlocking { native.leave() }
                    pool.stop()
                    activity.onActivity { view?.let { it.removeJavascriptInterface("Native"); it.destroy() } }
                    scope.cancel()
                }
            }
        }
    }
}
