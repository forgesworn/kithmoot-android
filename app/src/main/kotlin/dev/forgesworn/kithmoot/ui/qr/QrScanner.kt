package dev.forgesworn.kithmoot.ui.qr

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A single QR reader for sensitive setup links. It decodes wholly on the
 * device and passes a value onwards only after [accept] confirms that this
 * screen is interested in it. The caller still validates the full value at
 * the action boundary before it opens any relay or persists anything.
 */
@Composable
fun QrScanner(
    accept: (String) -> Boolean,
    onDecoded: (String) -> Unit,
    prompt: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val requestPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasCameraPermission = granted }

    if (!hasCameraPermission) {
        Text(prompt)
        Button(onClick = { requestPermission.launch(Manifest.permission.CAMERA) }) {
            Text("Allow camera")
        }
        return
    }

    // Decoding is synchronous, so it runs on its own thread, never the main one.
    val scanner = remember { FrameScanner() }
    val accepted = remember { AtomicBoolean(false) }
    val executor = remember { ContextCompat.getMainExecutor(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var camera by remember { mutableStateOf<Camera?>(null) }
    var binding by remember { mutableStateOf<ScannerBinding?>(null) }
    var zoomRatio by remember { mutableStateOf(1f) }
    val disposed = remember { AtomicBoolean(false) }

    DisposableEffect(scanner) {
        disposed.set(false)
        onDispose {
            disposed.set(true)
            binding?.let { scannerBinding ->
                scannerBinding.provider.unbind(scannerBinding.preview, scannerBinding.analysis)
            }
            scanner.close()
        }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AndroidView(
            // The prior portrait 3:4 preview made the cancel/action controls
            // disappear below a bottom sheet on shorter phones. A 4:3 view is
            // still ample for QR detection but keeps the whole scanner usable.
            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).aspectRatio(4f / 3f),
            factory = { viewContext ->
                val previewView = PreviewView(viewContext)
                val providerFuture = ProcessCameraProvider.getInstance(viewContext)
                providerFuture.addListener(
                    {
                        val provider = providerFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        analysis.setAnalyzer(scanner.executor) { imageProxy ->
                            val value = try {
                                scanner.decode(imageProxy)?.trim()?.takeIf(accept)
                            } finally {
                                imageProxy.close()
                            }
                            if (value != null && accepted.compareAndSet(false, true)) {
                                executor.execute { if (!disposed.get()) onDecoded(value) }
                            }
                        }
                        if (disposed.get()) {
                            provider.unbind(preview, analysis)
                            return@addListener
                        }
                        val boundCamera = provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis,
                        )
                        binding = ScannerBinding(provider, preview, analysis)
                        camera = boundCamera
                        zoomRatio = boundCamera.cameraInfo.zoomState.value?.zoomRatio ?: 1f
                    },
                    executor,
                )
                previewView
            },
        )
        val zoomState = camera?.cameraInfo?.zoomState?.value
        val zoomLabel = String.format(Locale.ROOT, "%.1f", zoomRatio)
        Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = {
                    zoomState?.let { state ->
                        zoomRatio = steppedZoom(zoomRatio, state.minZoomRatio, state.maxZoomRatio, 1f / ZOOM_STEP)
                        camera?.cameraControl?.setZoomRatio(zoomRatio)
                    }
                },
                enabled = zoomState != null && zoomRatio > zoomState.minZoomRatio,
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Zoom QR camera out" },
            ) { Text("−") }
            Spacer(Modifier.width(12.dp))
            Text("Zoom ${zoomLabel}×", modifier = Modifier.semantics { contentDescription = "QR camera zoom $zoomLabel times" })
            Spacer(Modifier.width(12.dp))
            OutlinedButton(
                onClick = {
                    zoomState?.let { state ->
                        zoomRatio = steppedZoom(zoomRatio, state.minZoomRatio, state.maxZoomRatio, ZOOM_STEP)
                        camera?.cameraControl?.setZoomRatio(zoomRatio)
                    }
                },
                enabled = zoomState != null && zoomRatio < zoomState.maxZoomRatio,
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Zoom QR camera in" },
            ) { Text("+") }
        }
    }
}

private const val ZOOM_STEP = 1.5f

private data class ScannerBinding(
    val provider: ProcessCameraProvider,
    val preview: Preview,
    val analysis: ImageAnalysis,
)

/** Keep an explicit zoom control within the lens's advertised safe range. */
internal fun steppedZoom(current: Float, minimum: Float, maximum: Float, multiplier: Float): Float =
    (current * multiplier).coerceIn(minimum, maximum)

/**
 * Owns the analysis thread and the decoder's reusable buffer. Frames arrive
 * one at a time (STRATEGY_KEEP_ONLY_LATEST), so neither needs locking.
 */
private class FrameScanner {
    val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "kithmoot-qr").apply { isDaemon = true }
    }
    private val decoder = QrFrameDecoder()
    private var luma = ByteArray(0)

    fun decode(imageProxy: ImageProxy): String? {
        val plane = imageProxy.planes.firstOrNull() ?: return null
        if (plane.pixelStride != 1) return null
        val width = imageProxy.width
        val height = imageProxy.height
        val rowStride = plane.rowStride
        val buffer = plane.buffer.duplicate().apply { rewind() }
        val needed = rowStride * (height - 1) + width
        if (buffer.remaining() < needed) return null
        if (luma.size < needed) luma = ByteArray(needed)
        buffer.get(luma, 0, needed)
        return decoder.decode(luma, width, height, rowStride)
    }

    fun close() {
        executor.shutdown()
    }
}
