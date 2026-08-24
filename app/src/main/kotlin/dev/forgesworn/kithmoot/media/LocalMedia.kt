package dev.forgesworn.kithmoot.media

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.view.WindowManager
import dev.forgesworn.kithmoot.session.Roles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.EglBase
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnectionFactory
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.UUID

/** One track this device is publishing, and what it is for. */
data class LocalTrack(val track: MediaStreamTrack, val role: String) {
    val trackId: String get() = track.id()
}

/**
 * Camera, microphone and screen capture.
 *
 * Screen capture is the reason this application exists at all. Mobile browsers
 * cannot share a screen - `getDisplayMedia` is simply absent on iOS Safari and
 * unreliable on Android Chrome - so a room where somebody needs to show
 * something from a phone has to be joined from an app. Everything else here
 * could have stayed in the browser.
 */
class LocalMedia(
    private val context: Context,
    private val factory: PeerConnectionFactory,
    private val eglBase: EglBase,
) {

    private val _tracks = MutableStateFlow<List<LocalTrack>>(emptyList())
    val tracks: StateFlow<List<LocalTrack>> = _tracks.asStateFlow()

    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null

    private var cameraCapturer: CameraVideoCapturer? = null
    private var cameraSource: VideoSource? = null
    private var cameraTrack: VideoTrack? = null
    private var cameraHelper: SurfaceTextureHelper? = null
    private var frontFacing = true

    private var screenCapturer: VideoCapturer? = null
    private var screenSource: VideoSource? = null
    private var screenTrack: VideoTrack? = null
    private var screenHelper: SurfaceTextureHelper? = null

    /** Set when the screen capturer's projection is torn down by the system. */
    var onScreenShareStopped: (() -> Unit)? = null

    /** Set when the camera stops for a reason this application did not choose. */
    var onCameraLost: (() -> Unit)? = null

    /**
     * The camera can be taken away without being asked for.
     *
     * Android revokes it from a backgrounded process whose foreground service
     * does not claim the `camera` type, and another application can win it
     * outright. Only the two unambiguous losses are acted on: a freeze is a
     * complaint about frame rate, not a camera that has gone, and a close
     * arrives on an ordinary stop as well.
     */
    private val cameraEvents = object : CameraVideoCapturer.CameraEventsHandler {
        override fun onCameraError(error: String?) {
            onCameraLost?.invoke()
        }

        override fun onCameraDisconnected() {
            onCameraLost?.invoke()
        }

        override fun onCameraFreezed(error: String?) = Unit
        override fun onCameraOpening(name: String?) = Unit
        override fun onFirstFrameAvailable() = Unit
        override fun onCameraClosed() = Unit
    }

    val microphoneTrack: AudioTrack? get() = audioTrack
    val localCameraTrack: VideoTrack? get() = cameraTrack
    val localScreenTrack: VideoTrack? get() = screenTrack

    @Synchronized
    fun startMicrophone(): AudioTrack? {
        audioTrack?.let { return it }
        val constraints = MediaConstraints().apply {
            // Left to the platform's hardware canceller where there is one; the
            // software fallbacks cost battery for no benefit on a modern handset.
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        }
        val source = factory.createAudioSource(constraints)
        val track = factory.createAudioTrack(trackId(Roles.MIC), source)
        audioSource = source
        audioTrack = track
        publish()
        return track
    }

    @Synchronized
    fun stopMicrophone() {
        audioTrack?.let { runCatching { it.setEnabled(false) } }
        audioTrack = null
        audioSource?.let { runCatching { it.dispose() } }
        audioSource = null
        publish()
    }

    @Synchronized
    fun startCamera(): VideoTrack? {
        cameraTrack?.let { return it }
        val capturer = createCameraCapturer() ?: return null
        val helper = SurfaceTextureHelper.create("camera-capture", eglBase.eglBaseContext)
        val source = factory.createVideoSource(false)
        capturer.initialize(helper, context, source.capturerObserver)
        capturer.startCapture(CAMERA_WIDTH, CAMERA_HEIGHT, CAMERA_FPS)
        val track = factory.createVideoTrack(trackId(Roles.CAMERA), source)

        cameraCapturer = capturer
        cameraHelper = helper
        cameraSource = source
        cameraTrack = track
        publish()
        return track
    }

    @Synchronized
    fun stopCamera() {
        runCatching { cameraCapturer?.stopCapture() }
        runCatching { cameraCapturer?.dispose() }
        runCatching { cameraHelper?.dispose() }
        runCatching { cameraSource?.dispose() }
        cameraCapturer = null
        cameraHelper = null
        cameraSource = null
        cameraTrack = null
        publish()
    }

    @Synchronized
    fun switchCamera() {
        frontFacing = !frontFacing
        cameraCapturer?.switchCamera(null)
    }

    /**
     * Starts sharing the screen from the consent the user just gave.
     *
     * [permission] is the intent handed back by the MediaProjection consent
     * dialog. It is single-use: the projection it creates dies with the capturer,
     * and sharing again means asking again. A foreground service with the
     * `mediaProjection` type must already be running or the platform refuses to
     * create the projection at all.
     */
    @Synchronized
    fun startScreenShare(permission: Intent): VideoTrack? {
        screenTrack?.let { return it }
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                onScreenShareStopped?.invoke()
            }
        }
        val capturer = ScreenCapturerAndroid(permission, callback)
        val helper = SurfaceTextureHelper.create("screen-capture", eglBase.eglBaseContext)
        // isScreencast = true, so the encoder favours sharpness over frame rate.
        // Text on a shared slide is unreadable otherwise.
        val source = factory.createVideoSource(true)
        capturer.initialize(helper, context, source.capturerObserver)
        val size = screenSize()
        capturer.startCapture(size.first, size.second, SCREEN_FPS)
        val track = factory.createVideoTrack(trackId(Roles.SCREEN), source)

        screenCapturer = capturer
        screenHelper = helper
        screenSource = source
        screenTrack = track
        publish()
        return track
    }

    @Synchronized
    fun stopScreenShare() {
        runCatching { screenCapturer?.stopCapture() }
        runCatching { screenCapturer?.dispose() }
        runCatching { screenHelper?.dispose() }
        runCatching { screenSource?.dispose() }
        screenCapturer = null
        screenHelper = null
        screenSource = null
        screenTrack = null
        publish()
    }

    @Synchronized
    fun releaseAll() {
        stopScreenShare()
        stopCamera()
        stopMicrophone()
    }

    private fun publish() {
        _tracks.value = buildList {
            audioTrack?.let { add(LocalTrack(it, Roles.MIC)) }
            cameraTrack?.let { add(LocalTrack(it, Roles.CAMERA)) }
            screenTrack?.let { add(LocalTrack(it, Roles.SCREEN)) }
        }
    }

    private fun createCameraCapturer(): CameraVideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        val names = enumerator.deviceNames
        val preferred = names.firstOrNull { enumerator.isFrontFacing(it) == frontFacing }
            ?: names.firstOrNull()
            ?: return null
        return enumerator.createCapturer(preferred, cameraEvents)
    }

    @Suppress("DEPRECATION")
    private fun screenSize(): Pair<Int, Int> {
        val metrics = DisplayMetrics()
        val windows = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windows.defaultDisplay.getRealMetrics(metrics)
        // Halved, and rounded to an even number of pixels. A full-resolution
        // handset screen is more than any encoder will keep up with, and an odd
        // dimension breaks the chroma planes of every codec here.
        val width = (metrics.widthPixels / 2) and 1.inv()
        val height = (metrics.heightPixels / 2) and 1.inv()
        return width.coerceAtLeast(320) to height.coerceAtLeast(320)
    }

    private fun trackId(role: String): String = "$role-${UUID.randomUUID()}"

    private companion object {
        const val CAMERA_WIDTH = 1280
        const val CAMERA_HEIGHT = 720
        const val CAMERA_FPS = 30
        const val SCREEN_FPS = 15
    }
}
