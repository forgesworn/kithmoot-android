package dev.forgesworn.kithmoot.ui.room

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * One video track on screen.
 *
 * The renderer is tied to the track by [DisposableEffect] rather than by the
 * `AndroidView` update block: a sink that is added twice renders twice as fast
 * and a sink that is never removed keeps the track - and the peer connection
 * behind it - alive after the tile has gone. Both are silent leaks.
 */
@Composable
fun VideoSurface(
    track: VideoTrack,
    eglBase: EglBase,
    modifier: Modifier = Modifier,
    mirror: Boolean = false,
    fill: Boolean = true,
) {
    val renderer = rememberRenderer(eglBase, mirror, fill)

    DisposableEffect(track, renderer) {
        runCatching { track.addSink(renderer) }
        onDispose { runCatching { track.removeSink(renderer) } }
    }

    AndroidView(factory = { renderer }, modifier = modifier)
}

@Composable
private fun rememberRenderer(eglBase: EglBase, mirror: Boolean, fill: Boolean): SurfaceViewRenderer {
    val context = androidx.compose.ui.platform.LocalContext.current
    val renderer = androidx.compose.runtime.remember(eglBase) {
        SurfaceViewRenderer(context).apply {
            runCatching { init(eglBase.eglBaseContext, null) }
            setEnableHardwareScaler(true)
        }
    }
    androidx.compose.runtime.SideEffect {
        renderer.setMirror(mirror)
        // A shared screen is letterboxed rather than cropped. Cropping a slide to
        // fill a tile cuts the edges off the thing being shown.
        renderer.setScalingType(
            if (fill) RendererCommon.ScalingType.SCALE_ASPECT_FILL else RendererCommon.ScalingType.SCALE_ASPECT_FIT,
        )
    }
    DisposableEffect(renderer) {
        onDispose { runCatching { renderer.release() } }
    }
    return renderer
}
