package dev.forgesworn.kithmoot.media

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.SystemClock
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.webrtc.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real native RTP and the actual PWA Peer inside Chromium, on a disposable emulator only. */
class BrowserCallInteropTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    @Test fun browserAndNativeExchangeMediaWithNativePolite() = exchange(true)
    @Test fun browserAndNativeExchangeMediaWithNativeImpolite() = exchange(false)

    @Test fun browserAndNativeExchangeMediaThroughTurn() = exchange(true, true)

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun exchange(nativeLow: Boolean, relayOnly: Boolean = false) {
        assertTrue(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.RECORD_AUDIO)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val failure = AtomicReference<String?>(null)
        val nativeVideo = AtomicInteger(); val browserVideo = AtomicInteger(); val browserAudio = AtomicInteger()
        val expectedCamera = AtomicReference(""); val receivedCamera = AtomicReference("")
        val nativeAudio = AtomicInteger(); val nativeEnergy = AtomicReference(0.0)
        val incoming = Channel<JsonObject>(Channel.UNLIMITED)
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        lateinit var view: WebView
        lateinit var link: PeerLink
        var ready = false
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        val egl = EglBase.create()
        val factory = PeerConnectionFactory.builder().setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext)).createPeerConnectionFactory()
        val servers = if (relayOnly) runBlocking { CallIceServers.resolve() } else emptyList()
        if (relayOnly) assertTrue("TURN credential available", servers.any { it.urls.any { url -> url.startsWith("turn:") } })
        val browserIce = buildJsonArray { servers.forEach { server -> add(buildJsonObject {
            put("urls", buildJsonArray { server.urls.forEach { add(it) } }); put("username", server.username); put("credential", server.password)
        }) } }
        val configuration = PeerConnection.RTCConfiguration(servers).apply {
            if (relayOnly) iceTransportsType = PeerConnection.IceTransportsType.RELAY
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            enableImplicitRollback = false
        }
        val received = java.util.concurrent.ConcurrentHashMap<String, MediaStreamTrack>()
        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) = Unit
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
            override fun onAddStream(stream: MediaStream?) = Unit
            override fun onRemoveStream(stream: MediaStream?) = Unit
            override fun onDataChannel(channel: DataChannel?) = Unit
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate ?: return
                scope.launch { link.onLocalCandidate(IceCandidateData(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)) }
            }
            override fun onRenegotiationNeeded() {
                if (ready) scope.launch { runCatching { link.onNegotiationNeeded() }.onFailure { failure.set(it.toString()) } }
            }
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) { receiver?.track()?.let { received[it.id()] = it } }
            override fun onTrack(transceiver: RtpTransceiver?) = Unit
        }
        val pc = requireNotNull(factory.createPeerConnection(configuration, observer))
        val videoSource = factory.createVideoSource(false)
        val video = factory.createVideoTrack("synthetic-native-camera", videoSource)
        val audioSource = factory.createAudioSource(MediaConstraints())
        val audio = factory.createAudioTrack("synthetic-native-audio", audioSource)
        val routing = CallAudioRouting(context)
        var monitoredVideo: VideoTrack? = null
        val videoSink = VideoSink { nativeVideo.incrementAndGet() }
        fun bindReceivedVideo() {
            val binding = remoteTracksFor("synthetic-browser", pc, received.values.toList()).firstOrNull { it.track is VideoTrack }
            val track = binding?.track as? VideoTrack
            monitoredVideo?.removeSink(videoSink)
            monitoredVideo = track
            receivedCamera.set(binding?.trackId ?: "")
            track?.addSink(videoSink)
        }
        fun js(script: String) = instrumentation.runOnMainSync { view.evaluateJavascript(script, null) }
        link = PeerLink((if (nativeLow) "11" else "22").repeat(32), (if (nativeLow) "22" else "11").repeat(32), WebRtcPeerConnection(pc, ::bindReceivedVideo), "33".repeat(32)) { signal ->
            val body = buildJsonObject { put("type", signal.type); put("roomId", signal.roomId); signal.sdp?.let { put("sdp", it) }; signal.candidate?.let { put("candidate", it) } }
            withContext(Dispatchers.Main) { view.evaluateJavascript("window.receive($body)", null) }
        }
        scope.launch {
            for (body in incoming) runCatching {
                link.onRemoteSignal(body.getValue("type").jsonPrimitive.content, body["sdp"]?.jsonPrimitive?.content, body["candidate"]?.jsonPrimitive?.content)
            }.onFailure { failure.set(it.toString()) }
        }
        val bridge = object {
            @JavascriptInterface fun signal(body: String) { incoming.trySend(Json.parseToJsonElement(body).jsonObject) }
            @JavascriptInterface fun failure(message: String) { failure.set(message) }
            @JavascriptInterface fun ready() { ready = true; scope.launch { runCatching { link.onNegotiationNeeded() }.onFailure { failure.set(it.toString()) } } }
            @JavascriptInterface fun camera(id: String) { expectedCamera.set(id) }
            @JavascriptInterface fun stats(video: Int, audio: Int, state: String) { browserVideo.set(video); browserAudio.set(audio) }
        }
        try {
            pc.addTrack(video, listOf("native")); pc.addTrack(audio, listOf("native"))
            routing.setActive(true)
            scheduler.scheduleAtFixedRate({
                val buffer = JavaI420Buffer.allocate(320, 240)
                for (i in 0 until buffer.dataY.capacity()) buffer.dataY.put(i, 160.toByte())
                for (i in 0 until buffer.dataU.capacity()) buffer.dataU.put(i, 90.toByte())
                for (i in 0 until buffer.dataV.capacity()) buffer.dataV.put(i, 130.toByte())
                val frame = VideoFrame(buffer, 0, System.nanoTime())
                videoSource.capturerObserver.onFrameCaptured(frame); frame.release()
            }, 0, 66, TimeUnit.MILLISECONDS)
            val code = instrumentation.context.assets.open("browser-peer.js").bufferedReader().use { it.readText() }
            activity.scenario.onActivity {
                view = WebView(it); view.settings.javaScriptEnabled = true
                view.settings.mediaPlaybackRequiresUserGesture = false
                view.addJavascriptInterface(bridge, "Native")
                view.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(v: WebView, url: String) { v.evaluateJavascript("window.begin($nativeLow, $browserIce, $relayOnly).catch(e=>Native.failure(String(e)))", null) }
                }
                it.setContentView(view)
                view.loadDataWithBaseURL("https://interop.invalid/", "<html><body><script>$code</script></body></html>", "text/html", "UTF-8", null)
            }
            val deadline = SystemClock.elapsedRealtime() + 45_000
            while (SystemClock.elapsedRealtime() < deadline) {
                failure.get()?.let { fail(it) }
                if (ready) js("window.report && window.report()")
                pc.getStats { report ->
                    report.statsMap.values.filter { it.type == "inbound-rtp" && it.members["kind"] == "audio" }.forEach {
                        nativeAudio.set((it.members["packetsReceived"] as? Number)?.toInt() ?: 0)
                        nativeEnergy.set((it.members["totalAudioEnergy"] as? Number)?.toDouble() ?: 0.0)
                    }
                }
                if (nativeVideo.get() >= 10 && browserVideo.get() >= 10 && nativeAudio.get() > 10 && browserAudio.get() > 10 && nativeEnergy.get() > 0) break
                SystemClock.sleep(100)
            }
            assertTrue("Native decoded remote video: ${nativeVideo.get()}", nativeVideo.get() >= 10)
            assertTrue("Browser decoded native video: ${browserVideo.get()}", browserVideo.get() >= 10)
            assertTrue("Native received audio packets: ${nativeAudio.get()}", nativeAudio.get() > 10)
            assertTrue("Browser received native audio packets: ${browserAudio.get()}", browserAudio.get() > 10)
            assertTrue("Native decoded audible synthetic browser tone: ${nativeEnergy.get()}", nativeEnergy.get() > 0)
            val originalCamera = expectedCamera.get()
            val beforeFrames = nativeVideo.get()
            js("window.changeCamera().catch(e=>Native.failure(String(e)))")
            val changeDeadline = SystemClock.elapsedRealtime() + 20_000
            while (SystemClock.elapsedRealtime() < changeDeadline) {
                failure.get()?.let { fail(it) }
                if (expectedCamera.get() != originalCamera && nativeVideo.get() > beforeFrames + 30 && receivedCamera.get() == expectedCamera.get()) break
                SystemClock.sleep(100)
            }
            assertNotEquals("Browser created a replacement camera", originalCamera, expectedCamera.get())
            assertEquals("Camera roster id must resolve to the live native receiver after switching camera", expectedCamera.get(), receivedCamera.get())
            assertTrue("Replacement camera delivers frames", nativeVideo.get() > beforeFrames + 30)
            val manager = context.getSystemService(AudioManager::class.java)
            assertEquals(AudioManager.MODE_IN_COMMUNICATION, manager.mode)
            assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, manager.communicationDevice?.type)
        } finally {
            ready = false; scheduler.shutdownNow(); scheduler.awaitTermination(2, TimeUnit.SECONDS)
            instrumentation.runOnMainSync { view.destroy() }
            monitoredVideo?.removeSink(videoSink)
            scope.cancel(); link.close(); video.dispose(); videoSource.dispose(); audio.dispose(); audioSource.dispose(); factory.dispose()
            routing.close(); egl.release()
        }
        assertEquals(AudioManager.MODE_NORMAL, context.getSystemService(AudioManager::class.java).mode)
    }
}
