package dev.forgesworn.kithmoot.ui.room

import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.*
import java.util.concurrent.CountDownLatch

data class SharedScreen(val participant: String, val device: String)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScreenShareViewer(
    track: VideoTrack?, eglBase: EglBase?, title: String, inPictureInPicture: Boolean,
    onPopOut: (() -> Unit)?, onClose: () -> Unit,
) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var aspect by remember { mutableFloatStateOf(16f / 9f) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val fittedWidth = minOf(size.width.toFloat(), size.height * aspect)
    val fittedHeight = fittedWidth / aspect
    fun constrain(offset: Offset, scale: Float): Offset {
        val x = ((fittedWidth * scale - size.width) / 2).coerceAtLeast(0f)
        val y = ((fittedHeight * scale - size.height) / 2).coerceAtLeast(0f)
        return Offset(offset.x.coerceIn(-x, x), offset.y.coerceIn(-y, y))
    }
    fun setZoom(value: Float) { zoom = value.coerceIn(1f, 8f); pan = constrain(pan, zoom) }
    LaunchedEffect(size, aspect, inPictureInPicture) { if (inPictureInPicture) { zoom = 1f; pan = Offset.Zero } else pan = constrain(pan, zoom) }
    BackHandler(enabled = !inPictureInPicture, onBack = onClose)
    Column(Modifier.fillMaxSize().background(Color.Black).then(if (inPictureInPicture) Modifier else Modifier.systemBarsPadding())) {
        if (!inPictureInPicture) {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                    FlowRow(verticalArrangement = Arrangement.Center, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { setZoom(zoom / 1.25f) }, enabled = track != null && zoom > 1f, modifier = Modifier.semantics { contentDescription = "Zoom out" }) { Text("−") }
                        Text("${(zoom * 100).toInt()}%", Modifier.padding(vertical = 14.dp))
                        TextButton(onClick = { setZoom(zoom * 1.25f) }, enabled = track != null && zoom < 8f, modifier = Modifier.semantics { contentDescription = "Zoom in" }) { Text("+") }
                        TextButton(onClick = { setZoom(1f); pan = Offset.Zero }) { Text("Fit to screen") }
                        if (onPopOut != null) TextButton(onClick = onPopOut) { Text("Pop out") }
                        TextButton(onClick = onClose) { Text("Close viewer") }
                    }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().onSizeChanged { size = it }
            .semantics { contentDescription = "Shared screen, ${(zoom * 100).toInt()} percent zoom. Pinch to zoom and drag to pan." }
            .pointerInput(fittedWidth, fittedHeight) {
                detectTransformGestures { _, movement, scale, _ -> setZoom(zoom * scale); pan = constrain(pan + movement, zoom) }
            }, contentAlignment = Alignment.Center) {
            if (track != null && eglBase != null) {
                val density = LocalDensity.current
                ScreenTexture(track, eglBase, onAspect = { aspect = it }, modifier = Modifier
                    .size(with(density) { fittedWidth.toDp() }, with(density) { fittedHeight.toDp() })
                    .graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y })
            } else Text("Screen sharing has stopped or is reconnecting.", color = Color.White, modifier = Modifier.padding(24.dp))
        }
        if (!inPictureInPicture) Text("Pinch to zoom. Drag to move around. Fit to screen resets the view.", color = Color.White, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
    }
}

/** A TextureView participates in clipping and transforms, unlike a separate SurfaceView layer. */
@Composable
private fun ScreenTexture(track: VideoTrack, eglBase: EglBase, onAspect: (Float) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val callback by rememberUpdatedState(onAspect)
    val view = remember(track, eglBase) {
        object : TextureView(context), VideoSink, TextureView.SurfaceTextureListener {
            private val renderer = EglRenderer("KithMoot expanded screen")
            private var lastWidth = 0
            private var lastHeight = 0
            init { renderer.init(eglBase.eglBaseContext, EglBase.CONFIG_PLAIN, GlRectDrawer()); surfaceTextureListener = this; isOpaque = false }
            override fun onFrame(frame: VideoFrame) {
                if (lastWidth != frame.rotatedWidth || lastHeight != frame.rotatedHeight) {
                    lastWidth = frame.rotatedWidth; lastHeight = frame.rotatedHeight
                    val ratio = lastWidth.toFloat() / lastHeight.coerceAtLeast(1)
                    post { callback(ratio) }
                }
                renderer.onFrame(frame)
            }
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) { renderer.createEglSurface(texture) }
            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) { renderer.setLayoutAspectRatio(width.toFloat() / height.coerceAtLeast(1)) }
            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                val released = CountDownLatch(1); renderer.releaseEglSurface { released.countDown() }; ThreadUtils.awaitUninterruptibly(released); return true
            }
            fun release() { renderer.release() }
        }
    }
    DisposableEffect(track, view) {
        track.addSink(view)
        onDispose { runCatching { track.removeSink(view) }; view.release() }
    }
    AndroidView(factory = { view }, modifier = modifier)
}
