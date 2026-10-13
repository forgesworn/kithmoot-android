package dev.forgesworn.kithmoot.ui.room

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
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
    /** Drawn over another video surface, as the floating picture is. Fixed
     *  for the life of the renderer: Android reads it only before the
     *  surface is attached. */
    overlay: Boolean = false,
    onFirstFrame: () -> Unit = {},
) {
    val renderer = rememberRenderer(eglBase, mirror, fill, overlay)
    val aspect = remember(track) { mutableFloatStateOf(16f / 9f) }

    val firstFrame = androidx.compose.runtime.rememberUpdatedState(onFirstFrame)
    DisposableEffect(track, renderer) {
        val reported = java.util.concurrent.atomic.AtomicBoolean(false)
        var lastAspect = 0f
        val sink = org.webrtc.VideoSink { frame ->
            val ratio = frame.rotatedWidth.toFloat() / frame.rotatedHeight.coerceAtLeast(1)
            if (ratio != lastAspect) {
                lastAspect = ratio
                renderer.post { aspect.floatValue = ratio }
            }
            renderer.onFrame(frame)
            if (reported.compareAndSet(false, true)) firstFrame.value()
        }
        runCatching { track.addSink(sink) }
        onDispose { runCatching { track.removeSink(sink) } }
    }

    if (fill) AndroidView(factory = { renderer }, modifier = modifier)
    else BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        // Compose supplies exact dimensions to AndroidView. The renderer's
        // SCALE_ASPECT_FIT only changes its preferred measurement, which
        // cannot letterbox a view forced to fill a square tile. Fit the view
        // itself to the decoded frame, keeping the whole shared desktop.
        val width = minOf(maxWidth, maxHeight * aspect.floatValue)
        AndroidView(factory = { renderer }, modifier = Modifier.size(width, width / aspect.floatValue))
    }
}

@Composable
private fun rememberRenderer(eglBase: EglBase, mirror: Boolean, fill: Boolean, overlay: Boolean): SurfaceViewRenderer {
    val context = androidx.compose.ui.platform.LocalContext.current
    val renderer = androidx.compose.runtime.remember(eglBase, overlay) {
        SurfaceViewRenderer(context).apply {
            // Two surfaces that overlap have no defined order unless one of
            // them says it sits on top.
            if (overlay) setZOrderMediaOverlay(true)
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
