package dev.forgesworn.kithmoot.ui.room

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas as PixelCanvas
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.forgesworn.kithmoot.protocol.LogoImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

/** Captures the exact session and epoch that opened the editor. */
class RoomLogoEditScope internal constructor(
    val name: String,
    val image: LogoImage?,
    internal val isCurrent: () -> Boolean,
    internal val save: suspend (LogoImage?, () -> Boolean) -> Unit,
)

internal suspend fun decodeLogoSource(resolver: ContentResolver, uri: Uri): Bitmap {
    var owned: Bitmap? = null
    try {
        withContext(Dispatchers.IO) { owned = readLogoSource(resolver, uri) }
        return requireNotNull(owned).also { owned = null }
    } finally { owned?.recycle() }
}

private suspend fun readLogoSource(resolver: ContentResolver, uri: Uri): Bitmap {
    require(uri.scheme == "content") { "Choose an image using Android's photo picker." }
    val bytes = requireNotNull(resolver.openInputStream(uri)) { "This image is no longer available. Choose it again." }
        .use(LogoPixels::readSource)
    return decodeLogoBytes(bytes)
}

internal suspend fun decodeLogoBytes(bytes: ByteArray): Bitmap {
    LogoPixels.checkStillSource(bytes)
    currentCoroutineContext().ensureActive()
    // ImageDecoder applies EXIF orientation before the crop. The header callback
    // rejects oversized input before pixel allocation, then bounds our working copy.
    val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
        LogoPixels.checkDimensions(info.size.width, info.size.height)
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        val scale = minOf(1.0, 2048.0 / maxOf(info.size.width, info.size.height))
        decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
        decoder.setOnPartialImageListener { false }
    }
    try { currentCoroutineContext().ensureActive(); return bitmap } catch (cancelled: CancellationException) { bitmap.recycle(); throw cancelled }
}

private fun drawLogoCrop(canvas: PixelCanvas, source: Bitmap, crop: LogoPixels.Crop, width: Float, height: Float) {
    val saved = canvas.save()
    try {
        canvas.clipRect(0f, 0f, width, height)
        canvas.scale(width / crop.side, height / crop.side)
        canvas.translate(-crop.left, -crop.top)
        canvas.drawBitmap(source, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    } finally { canvas.restoreToCount(saved) }
}

internal suspend fun encodeLogoCrop(source: Bitmap, zoom: Float, x: Float, y: Float): LogoImage = withContext(Dispatchers.Default) {
    val crop = LogoPixels.crop(source.width, source.height, zoom, x, y)
    for (side in listOf(256, 192, 128, 96)) {
        ensureActive()
        val pixels = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888, true, ColorSpace.get(ColorSpace.Named.SRGB))
        try {
            drawLogoCrop(PixelCanvas(pixels), source, crop, side.toFloat(), side.toFloat())
            val values = IntArray(side * side)
            pixels.getPixels(values, 0, side, 0, 0, side, side)
            require(values.any { it ushr 24 != 0 }) { "The crop is transparent. Choose a visible part of the image." }
            for (quality in listOf(88, 70)) {
                ensureActive()
                val encoded = ByteArrayOutputStream()
                check(pixels.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, encoded))
                val logo = runCatching { LogoPixels.fromFreshEncoding(encoded.toByteArray(), "image/webp") }.getOrNull()
                if (logo != null) return@withContext logo
            }
            val png = ByteArrayOutputStream()
            check(pixels.compress(Bitmap.CompressFormat.PNG, 100, png))
            val logo = runCatching { LogoPixels.fromFreshEncoding(png.toByteArray(), "image/png") }.getOrNull()
            if (logo != null) return@withContext logo
        } finally { pixels.recycle() }
    }
    error("This crop is too detailed to share. Try zooming in or choose a simpler image.")
}

@Composable
fun RoomLogoEditor(edit: RoomLogoEditScope, onClose: () -> Unit) {
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    val alive = remember(edit) { AtomicBoolean(true) }
    var current by remember(edit) { mutableStateOf(edit.isCurrent()) }
    var bitmap by remember(edit) { mutableStateOf<Bitmap?>(null) }
    var zoom by remember(edit) { mutableFloatStateOf(1f) }
    var x by remember(edit) { mutableFloatStateOf(.5f) }
    var y by remember(edit) { mutableFloatStateOf(.5f) }
    var busy by remember(edit) { mutableStateOf(false) }
    var status by remember(edit) { mutableStateOf("Choose an image, then preview the square crop.") }
    var loading by remember(edit) { mutableStateOf<Job?>(null) }
    val allowed = { alive.get() && edit.isCurrent() }
    fun close() { alive.set(false); loading?.cancel(); onClose() }
    DisposableEffect(edit) { onDispose { alive.set(false) } }
    LaunchedEffect(edit) {
        while (current) {
            delay(250)
            if (!edit.isCurrent()) { current = false; alive.set(false); loading?.cancel(); busy = false
                status = "This room or its key changed. Close this preview and open the editor again." }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null && allowed()) {
            loading?.cancel(); busy = true; bitmap = null
            status = "Opening image…"
            loading = scope.launch {
                var decoded: Bitmap? = null
                try {
                    decoded = decodeLogoSource(resolver, uri)
                    check(allowed()) { "This room changed. Open the editor again." }
                    bitmap = decoded; decoded = null; zoom = 1f; x = .5f; y = .5f
                    status = "Adjust the crop, then share it with everyone in this room."
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { if (allowed()) status = error.message ?: "This image could not be opened. Choose another." }
                finally { decoded?.recycle(); if (allowed()) busy = false }
            }
        }
    }
    fun share(remove: Boolean) {
        if (busy || !allowed()) return
        busy = true; status = if (remove) "Removing room logo…" else "Preparing and sharing crop…"
        scope.launch {
            try {
                val image = if (remove) null else encodeLogoCrop(requireNotNull(bitmap), zoom, x, y)
                check(allowed()) { "This room changed. Open the editor again." }
                edit.save(image, allowed)
                if (allowed()) close()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (allowed()) status = error.message ?: "The room logo could not be shared. Try again." }
            finally { if (allowed()) busy = false }
        }
    }
    Dialog(onDismissRequest = ::close) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().heightIn(max = 680.dp).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Room logo", style = MaterialTheme.typography.titleLarge)
                Text(edit.name, style = MaterialTheme.typography.titleMedium)
                val source = bitmap
                if (source != null) {
                    val crop = LogoPixels.crop(source.width, source.height, zoom, x, y)
                    Canvas(Modifier.size(224.dp).align(Alignment.CenterHorizontally).semantics { contentDescription = "Room logo crop preview" }) {
                        drawIntoCanvas { canvas -> drawLogoCrop(canvas.nativeCanvas, source, crop, size.width, size.height) }
                    }
                    Text("Zoom")
                    Slider(zoom, { zoom = it }, enabled = current && !busy, valueRange = 1f..4f,
                        modifier = Modifier.semantics { contentDescription = "Zoom" })
                    Text("Horizontal position")
                    Slider(x, { x = it }, enabled = current && !busy, valueRange = 0f..1f,
                        modifier = Modifier.semantics { contentDescription = "Horizontal position" })
                    Text("Vertical position")
                    Slider(y, { y = it }, enabled = current && !busy, valueRange = 0f..1f,
                        modifier = Modifier.semantics { contentDescription = "Vertical position" })
                } else PrivateLogo(edit.image, edit.name, Modifier.align(Alignment.CenterHorizontally).size(96.dp))
                Text(status, style = MaterialTheme.typography.bodyMedium)
                Text("PNG, JPEG or still WebP. Shared as a small image with embedded metadata removed.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    enabled = current && !busy, modifier = Modifier.fillMaxWidth()) { Text(if (source == null) "Choose image" else "Choose another image") }
                Button(onClick = { share(false) }, enabled = current && !busy && source != null,
                    modifier = Modifier.fillMaxWidth()) { Text("Share logo with room") }
                if (edit.image != null) TextButton(onClick = { share(true) }, enabled = current && !busy,
                    modifier = Modifier.fillMaxWidth()) { Text("Remove logo for everyone") }
                TextButton(onClick = ::close, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            }
        }
    }
}
