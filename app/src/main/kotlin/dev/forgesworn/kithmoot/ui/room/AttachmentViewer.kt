package dev.forgesworn.kithmoot.ui.room

import android.content.ClipData
import android.content.Intent
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.widget.ImageView
import java.nio.ByteBuffer
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import dev.forgesworn.kithmoot.session.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

private val attachmentHttp = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
    .callTimeout(30, TimeUnit.SECONDS).build()

/** Downloads happen only after a tap. No room key, cookies or identity header
 * is sent to the file host, and decrypted files stay in memory until Open in. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AttachmentViewer(attachment: ChatAttachment, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var opened by remember(attachment) { mutableStateOf<OpenedAttachment?>(null) }
    var image by remember(attachment) { mutableStateOf<android.graphics.drawable.Drawable?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var error by remember(attachment) { mutableStateOf<String?>(null) }
    var zoom by remember(attachment) { mutableFloatStateOf(1f) }
    var pan by remember(attachment) { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val call = remember(attachment) { attachmentHttp.newCall(Request.Builder().url(attachment.url).build()) }
    DisposableEffect(attachment) { onDispose { call.cancel(); (image as? AnimatedImageDrawable)?.stop(); opened?.bytes?.fill(0) } }
    DisposableEffect(image, lifecycle) {
        val animated = image as? AnimatedImageDrawable
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) animated?.start()
            if (event == Lifecycle.Event.ON_STOP) animated?.stop()
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) animated?.start()
        onDispose { lifecycle.removeObserver(observer); animated?.stop() }
    }
    LaunchedEffect(attachment) {
        var decrypted: OpenedAttachment? = null
        try {
            val result = withContext(Dispatchers.IO) {
                require(attachment.size == null || attachment.size <= MAX_IMAGE_ENVELOPE_BYTES) { "This attachment exceeds the 32 MiB image viewing limit." }
                val bytes = call.execute().use { response ->
                    check(response.isSuccessful) { "The attachment host could not supply the image." }
                    val body = checkNotNull(response.body)
                    require(body.contentLength() <= MAX_IMAGE_ENVELOPE_BYTES)
                    body.byteStream().use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val read = input.read(buffer); if (read < 0) break
                            require(output.size() + read <= MAX_IMAGE_ENVELOPE_BYTES) { "Image attachment exceeds 32 MiB." }
                            output.write(buffer, 0, read)
                        }
                        output.toByteArray()
                    }
                }
                val file = openAttachment(bytes, attachment)
                decrypted = file
                try {
                    require(file.type in setOf("image/png", "image/jpeg", "image/webp", "image/gif")) { "This file type cannot be displayed in the Android image viewer." }
                    val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(file.bytes))) { decoder, info, _ ->
                        require(info.size.width in 1..8192 && info.size.height in 1..8192 && info.size.width.toLong() * info.size.height <= 16_000_000) { "This image is too large for the Android viewer (16 megapixels maximum)." }
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        decoder.setOnPartialImageListener { false }
                    }
                    file to drawable
                } catch (failure: Exception) { file.bytes.fill(0); throw failure }
            }
            opened = result.first; image = result.second
        } catch (cancelled: CancellationException) { decrypted?.bytes?.fill(0); throw cancelled }
        catch (failure: Exception) { decrypted?.bytes?.fill(0); error = failure.message ?: "The image could not be opened." }
    }
    fun constrain(offset: Offset, scale: Float): Offset {
        val drawable = image ?: return Offset.Zero
        val fit = minOf(size.width.toFloat() / drawable.intrinsicWidth, size.height.toFloat() / drawable.intrinsicHeight)
        val x = ((drawable.intrinsicWidth * fit * scale - size.width) / 2).coerceAtLeast(0f)
        val y = ((drawable.intrinsicHeight * fit * scale - size.height) / 2).coerceAtLeast(0f)
        return Offset(offset.x.coerceIn(-x, x), offset.y.coerceIn(-y, y))
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(12.dp)) {
                Text(opened?.name ?: attachment.name ?: "Image attachment", style = MaterialTheme.typography.titleMedium, maxLines = 2)
                FlowRow {
                    TextButton(onClick = { zoom = 1f; pan = Offset.Zero }) { Text("Fit") }
                    TextButton(enabled = image != null && size.width > 0 && size.height > 0, onClick = {
                        image?.let { zoom = maxOf(1f, maxOf(it.intrinsicWidth.toFloat() / size.width, it.intrinsicHeight.toFloat() / size.height)); pan = Offset.Zero }
                    }) { Text("Actual size") }
                    TextButton(enabled = opened != null, onClick = {
                        val file = opened ?: return@TextButton
                        scope.launch {
                            runCatching {
                                val path = withContext(Dispatchers.IO) {
                                    val directory = File(context.cacheDir, "opened-images").apply { mkdirs() }
                                    directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 3_600_000 }?.forEach { it.delete() }
                                    File(directory, UUID.randomUUID().toString()).also { it.writeBytes(file.bytes) }
                                }
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.images", path)
                                val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, file.type)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                intent.clipData = ClipData.newRawUri("Image", uri)
                                context.startActivity(Intent.createChooser(intent, "Open image in"))
                            }.onFailure { error = "No image app could open this file." }
                        }
                    }) { Text("Open in…") }
                    TextButton(onClick = onClose) { Text("Close image") }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().onSizeChanged { size = it; pan = constrain(pan, zoom) }
                    .pointerInput(image, size) {
                        detectTransformGestures { _, movement, scale, _ ->
                            zoom = (zoom * scale).coerceIn(1f, 16f); pan = constrain(pan + movement, zoom)
                        }
                    }, contentAlignment = androidx.compose.ui.Alignment.Center) {
                    image?.let { drawable ->
                        AndroidView(factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
                            update = { view -> if (view.drawable !== drawable) view.setImageDrawable(drawable); view.contentDescription = opened?.name ?: "Attached image" },
                            modifier = Modifier.fillMaxSize().semantics { contentDescription = opened?.name ?: "Attached image" }.graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y })
                    }
                    if (image == null && error == null) CircularProgressIndicator()
                }
            }
        }
    }
}
