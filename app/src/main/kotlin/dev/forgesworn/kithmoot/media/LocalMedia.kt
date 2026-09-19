package dev.forgesworn.kithmoot.media

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.view.WindowManager
import dev.forgesworn.kithmoot.media.effects.BackgroundChoice
import dev.forgesworn.kithmoot.media.effects.BackgroundProcessor
import dev.forgesworn.kithmoot.media.effects.FrameCompositor
import dev.forgesworn.kithmoot.media.effects.ReducedMotion
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
data class LocalTrack(
    val track: MediaStreamTrack,
    val role: String,
    /**
     * This device silenced the track at the source: `setEnabled(false)`, not a
     * listener's own volume choice.
     *
     * It travels on the roster so the room can show who is muted without
     * guessing from an absent track - a device with everything switched off
     * looks exactly like a device that is muted, and only one of them is still
     * in the conversation. Mute is deliberately not the same act as releasing
     * the microphone: the track stays live, so the slot carrying it keeps
     * progressing and the health ladder has something to measure.
     */
    val muted: Boolean = false,
    val microphoneOn: Boolean = false,
    val microphoneMuted: Boolean = false,
) {
    val trackId: String get() = track.id()
}

/** The same track, as the fixed-slot machine wants it: a role and something to
 *  hand the sender. See `SlotTrack`. */
fun LocalTrack.slot(): SlotTrack = SlotTrack(role = role, media = track)

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
    private val playbackAudio: PlaybackAudio = PlaybackAudio(),
) {

    private val _tracks = MutableStateFlow<List<LocalTrack>>(emptyList())
    val tracks: StateFlow<List<LocalTrack>> = _tracks.asStateFlow()

    private var microphoneRequested = false
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null

    private var cameraCapturer: CameraVideoCapturer? = null
    private var cameraSource: VideoSource? = null
    private var cameraTrack: VideoTrack? = null
    private var cameraHelper: SurfaceTextureHelper? = null
    private var frontFacing = true

    /**
     * What is drawn behind the person, when anything is.
     *
     * Made with the camera and destroyed with it, which is most of the battery
     * answer: a segmentation model is not left loaded for a camera that is off.
     * Held here rather than in the view model because the processor belongs to
     * the `VideoSource`, and the source's life is this class's business.
     */
    private var background: BackgroundProcessor? = null
    private var backgroundChoice = BackgroundChoice()
    private var appVisible = true

    /** Told when the compositor has given up on a run of frames. */
    var onBackgroundTrouble: ((String) -> Unit)? = null

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

    val microphoneTrack: AudioTrack? get() = audioTrack?.takeIf { microphoneRequested }
    val localCameraTrack: VideoTrack? get() = cameraTrack
    val localScreenTrack: VideoTrack? get() = screenTrack

    @Synchronized
    fun startMicrophone(): AudioTrack? {
        microphoneRequested = true
        micMuted = false
        playbackAudio.microphone = true
        val track = ensureAudioTrack()
        track.setEnabled(true)
        publish()
        return track
    }

    private fun ensureAudioTrack(): AudioTrack {
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
        return track
    }

    @Synchronized
    fun stopMicrophone() {
        microphoneRequested = false
        playbackAudio.microphone = false
        micMuted = false
        if (!playbackAudio.active) releaseAudioTrack()
        publish()
    }

    private fun releaseAudioTrack() {
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
        // Set before the capturer starts, so there is no window in which a
        // frame reaches the encoder without having been through the rule in
        // `routeFor`. With no scene chosen it forwards frames untouched.
        source.setVideoProcessor(newBackgroundProcessor())
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
        // The source has to go before the processor: a frame arriving after the
        // compositor has given its bitmaps back would find nothing to draw on.
        runCatching { cameraSource?.setVideoProcessor(null) }
        runCatching { cameraSource?.dispose() }
        runCatching { background?.close() }
        background = null
        cameraCapturer = null
        cameraHelper = null
        cameraSource = null
        cameraTrack = null
        publish()
    }

    @Synchronized
    fun switchCamera() {
        frontFacing = !frontFacing
        background?.setFrontFacing(frontFacing)
        cameraCapturer?.switchCamera(null)
    }

    /**
     * Put a sea behind the person, or take it away again.
     *
     * Remembered even with the camera off, so turning the camera back on comes
     * up with the background the person last chose rather than with their room.
     */
    @Synchronized
    fun setBackground(choice: BackgroundChoice) {
        backgroundChoice = choice
        background?.setChoice(choice)
    }

    @Synchronized
    fun background(): BackgroundChoice = backgroundChoice

    /**
     * The application is, or is not, in front of the person.
     *
     * Going away stops the segmentation model; coming back starts it on the
     * next frame. While it is away and a background is chosen, nothing at all
     * is published from the camera - not the room, and not a stale composite.
     */
    @Synchronized
    fun setAppVisible(visible: Boolean) {
        appVisible = visible
        background?.setVisible(visible)
    }

    private fun newBackgroundProcessor(): BackgroundProcessor {
        val made = BackgroundProcessor(
            composer = FrameCompositor(
                context = context,
                reducedMotion = ReducedMotion(context)::on,
            ),
            onTrouble = { message -> onBackgroundTrouble?.invoke(message) },
        )
        made.setChoice(backgroundChoice)
        made.setFrontFacing(frontFacing)
        made.setVisible(appVisible)
        background = made
        return made
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
    fun startScreenShare(permission: Intent, shareAudio: Boolean = true): VideoTrack? {
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
        screenCapturer = capturer
        screenHelper = helper
        screenSource = source
        val track = try {
            capturer.initialize(helper, context, source.capturerObserver)
            val size = screenSize()
            capturer.startCapture(size.first, size.second, SCREEN_FPS)
            factory.createVideoTrack(trackId(Roles.SCREEN), source)
        } catch (failure: Exception) { stopScreenShare(); throw failure }
        screenTrack = track
        if (shareAudio && androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            try {
                playbackAudio.start(checkNotNull(capturer.mediaProjection))
                ensureAudioTrack().setEnabled(true)
            } catch (_: Exception) {
                playbackAudio.stop()
                onBackgroundTrouble?.invoke("Screen shared without sound: Android could not start app audio capture.")
            }
        } else if (shareAudio) onBackgroundTrouble?.invoke("Screen shared without sound. Allow audio recording to share app sound.")
        publish()
        return track
    }

    @Synchronized
    fun stopScreenShare() {
        playbackAudio.stop()
        if (!microphoneRequested) releaseAudioTrack() else audioTrack?.setEnabled(!micMuted)
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

    /**
     * Silence or unsilence this device's microphone without releasing it.
     *
     * Returns false when there is no microphone running to mute. The track
     * stays attached and advertised, which is what tells the room the person is
     * still here and quiet rather than gone - and, on a profile-2 pair, what
     * keeps the slot carrying it alive for the health ladder to measure.
     */
    @Synchronized
    fun setMicrophoneMuted(muted: Boolean): Boolean {
        val track = microphoneTrack ?: return false
        if (micMuted == muted) return true
        micMuted = muted
        playbackAudio.microphone = !muted
        runCatching { track.setEnabled(playbackAudio.active || !muted) }
        // Republished at once, so the roster says so on this mute rather than
        // on the next thing that happens to change.
        publish()
        return true
    }

    /** Whether this device's microphone is silenced at the source. */
    @get:Synchronized
    var micMuted: Boolean = false
        private set

    private fun publish() {
        _tracks.value = buildList {
            audioTrack?.let { add(LocalTrack(it,
                if (playbackAudio.active) Roles.SCREEN_AUDIO else Roles.MIC,
                muted = micMuted && !playbackAudio.active,
                microphoneOn = microphoneRequested, microphoneMuted = micMuted)) }
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
