package dev.forgesworn.kithmoot.ui.room

import dev.forgesworn.kithmoot.lab.MetadataHttp
import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.forgesworn.kithmoot.media.attachment.DecodedAttachment
import dev.forgesworn.kithmoot.media.attachment.decodeAttachmentIsolated
import dev.forgesworn.kithmoot.protocol.AttachmentDecision
import dev.forgesworn.kithmoot.protocol.AttachmentInput
import dev.forgesworn.kithmoot.protocol.AttachmentSafety
import dev.forgesworn.kithmoot.protocol.AttachmentTier
import dev.forgesworn.kithmoot.protocol.PreviewKind
import dev.forgesworn.kithmoot.protocol.SaveTier
import dev.forgesworn.kithmoot.session.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

private class OpenedView(val file: OpenedAttachment, val decision: AttachmentDecision, val decoded: DecodedAttachment?, val text: String?)

private val attachmentHttp = MetadataHttp.builder().followRedirects(false).followSslRedirects(false)
    .callTimeout(30, TimeUnit.SECONDS).build()

/** Downloads happen only after a tap. No room key, cookies or identity header
 * is sent to the file host. Only approved plaintext reaches the isolated decoder. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AttachmentViewer(attachment: ChatAttachment, onClose: () -> Unit,
    /** A file on the room's own storage is read with a signed token; null bytes mean any other file, fetched as before. */
    fetchGroup: (suspend (ChatAttachment) -> ByteArray?)? = null,
    sender: String? = null) {
    if (recordingPlaybackMime(attachment.type) != null) {
        RecordingAttachmentViewer(attachment, onClose)
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var opened by remember(attachment) { mutableStateOf<OpenedAttachment?>(null) }
    var decision by remember(attachment) { mutableStateOf<AttachmentDecision?>(null) }
    var image by remember(attachment) { mutableStateOf<DecodedAttachment?>(null) }
    var error by remember(attachment) { mutableStateOf<String?>(null) }
    var document by remember(attachment) { mutableStateOf<String?>(null) }
    var copyNote by remember(attachment) { mutableStateOf<String?>(null) }
    var confirmOpen by remember(attachment) { mutableStateOf(false) }
    val textMode = decision?.preview == PreviewKind.TEXT || (decision == null && isTextDocument(attachment))
    val clipboard = LocalClipboardManager.current
    var zoom by remember(attachment) { mutableFloatStateOf(1f) }
    var pan by remember(attachment) { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var frame by remember(attachment) { mutableIntStateOf(0) }
    var lifecycleStarted by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val call = remember(attachment) { attachmentHttp.newCall(Request.Builder().url(attachment.url).build()) }
    DisposableEffect(attachment) { onDispose { call.cancel() } }
    DisposableEffect(opened) { onDispose { opened?.bytes?.fill(0) } }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) lifecycleStarted = true
            if (event == Lifecycle.Event.ON_STOP) lifecycleStarted = false
        }
        lifecycle.addObserver(observer)
        lifecycleStarted = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(image, lifecycleStarted) {
        val decoded = image ?: return@LaunchedEffect
        if (!lifecycleStarted || decoded.frames.size <= 1) return@LaunchedEffect
        while (true) {
            delay(decoded.delaysMs[frame].toLong())
            frame = (frame + 1) % decoded.frames.size
        }
    }
    LaunchedEffect(attachment, sender) {
        var decrypted: OpenedAttachment? = null
        var decoded: DecodedAttachment? = null
        var assessed: AttachmentDecision? = null
        try {
            val result = withContext(Dispatchers.IO) {
                require(attachment.size == null || attachment.size <= MAX_IMAGE_ENVELOPE_BYTES) { "This attachment exceeds the 32 MiB image viewing limit." }
                val file = run {
                    val bytes = fetchGroup?.invoke(attachment) ?: call.execute().use { response ->
                        check(response.isSuccessful) { goneFileNote(attachment.url, response.code) ?: "The attachment host could not supply the image." }
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
                    try { openAttachment(bytes, attachment) } finally { bytes.fill(0) }
                }
                decrypted = file
                val verdict = AttachmentSafety.classifyAttachment(AttachmentInput(file.bytes, file.name, file.type, attachment.name, sender))
                assessed = verdict
                if (verdict.tier == AttachmentTier.BLOCK) {
                    file.bytes.fill(0)
                    return@withContext OpenedView(file, verdict, null, null)
                }
                when {
                    verdict.preview == PreviewKind.TEXT && verdict.mime == "text/plain" -> {
                        val text = try { String(file.bytes, Charsets.UTF_8) } finally { file.bytes.fill(0) }
                        OpenedView(file, verdict, null, text)
                    }
                    verdict.preview == PreviewKind.IMAGE && verdict.mime in setOf("image/png", "image/jpeg", "image/webp", "image/gif") -> {
                        try {
                            decoded = decodeAttachmentIsolated(context, file.bytes, verdict.mime)
                            OpenedView(file, verdict, decoded, null)
                        } catch (failure: Exception) {
                            decoded?.recycle()
                            decoded = null
                            throw failure
                        }
                    }
                    verdict.preview == PreviewKind.IMAGE -> throw IllegalArgumentException("This file type cannot be displayed in the Android image viewer.")
                    else -> OpenedView(file, verdict, null, null)
                }
            }
            opened = result.file; decision = result.decision; image = result.decoded; document = result.text; frame = 0
            if (result.decision.tier == AttachmentTier.BLOCK) error = result.decision.message
        } catch (cancelled: CancellationException) {
            decrypted?.bytes?.fill(0); decoded?.recycle(); throw cancelled
        } catch (failure: Exception) {
            decrypted?.bytes?.fill(0); decoded?.recycle(); decision = assessed
            error = failure.message ?: if (textMode) "The document could not be opened." else "The image could not be opened."
        }
    }
    fun constrain(offset: Offset, scale: Float): Offset {
        val decoded = image ?: return Offset.Zero
        val fit = minOf(size.width.toFloat() / decoded.width, size.height.toFloat() / decoded.height)
        val x = ((decoded.width * fit * scale - size.width) / 2).coerceAtLeast(0f)
        val y = ((decoded.height * fit * scale - size.height) / 2).coerceAtLeast(0f)
        return Offset(offset.x.coerceIn(-x, x), offset.y.coerceIn(-y, y))
    }
    fun openExternally() {
        val file = opened ?: return
        val verdict = decision ?: return
        if (verdict.tier == AttachmentTier.BLOCK) return
        scope.launch {
            runCatching {
                val path = withContext(Dispatchers.IO) {
                    val directory = File(context.cacheDir, "opened-images").apply { mkdirs() }
                    directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 3_600_000 }?.forEach { it.delete() }
                    File(directory, UUID.randomUUID().toString()).also { it.writeBytes(file.bytes) }
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.images", path)
                val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, verdict.mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                intent.clipData = ClipData.newRawUri(verdict.name, uri)
                context.startActivity(Intent.createChooser(intent, "Open in"))
            }.onFailure { error = "No app could open this file." }
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(12.dp)) {
                Text(decision?.name ?: opened?.name ?: attachment.name ?: if (textMode) "Text document" else "Image attachment", style = MaterialTheme.typography.titleMedium, maxLines = 2)
                FlowRow {
                    if (textMode) TextButton(enabled = document != null, onClick = {
                        val text = document ?: return@TextButton
                        copyNote = runCatching { clipboard.setText(AnnotatedString(text)) }
                            .fold({ "Copied the whole text." }, { "This text is too large to copy in one go. Select part of it instead." })
                    }) { Text("Copy") }
                    else TextButton(enabled = image != null, onClick = { zoom = 1f; pan = Offset.Zero }) { Text("Fit") }
                    if (!textMode) TextButton(enabled = image != null && size.width > 0 && size.height > 0, onClick = {
                        image?.let { zoom = maxOf(1f, maxOf(it.width.toFloat() / size.width, it.height.toFloat() / size.height)); pan = Offset.Zero }
                    }) { Text("Actual size") }
                    if (!textMode && decision?.tier != AttachmentTier.BLOCK) TextButton(enabled = opened != null && decision != null, onClick = {
                        if (decision?.save == SaveTier.WARN) confirmOpen = true else openExternally()
                    }) { Text("Open in…") }
                    TextButton(onClick = onClose) { Text(if (textMode) "Close document" else "Close image") }
                }
                if (decision?.tier == AttachmentTier.WARN) decision?.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                copyNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (textMode) {
                    val text = document
                    if (text == null) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.Center) { if (error == null) CircularProgressIndicator() }
                    else {
                        val pieces = remember(text) { splitDocumentForDisplay(text) }
                        SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
                            LazyColumn(Modifier.fillMaxSize()) { items(pieces) { piece -> Text(piece, style = MaterialTheme.typography.bodyMedium) } }
                        }
                    }
                } else Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().onSizeChanged { size = it; pan = constrain(pan, zoom) }
                    .pointerInput(image, size) {
                        detectTransformGestures { _, movement, scale, _ ->
                            zoom = (zoom * scale).coerceIn(1f, 16f); pan = constrain(pan + movement, zoom)
                        }
                    }, contentAlignment = androidx.compose.ui.Alignment.Center) {
                    image?.let { decoded ->
                        Image(bitmap = decoded.frames[frame].asImageBitmap(), contentDescription = decision?.name ?: "Attached image",
                            contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()
                                .semantics { contentDescription = decision?.name ?: "Attached image" }
                                .graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y })
                    }
                    if (image == null && error == null) {
                        if (decision != null && decision?.preview != PreviewKind.IMAGE) Text("No safe in-app preview is available.")
                        else CircularProgressIndicator()
                    }
                }
            }
        }
    }
    if (confirmOpen) AlertDialog(onDismissRequest = { confirmOpen = false },
        title = { Text("Open this file in another app?") },
        text = { Text(decision?.confirmation ?: decision?.message ?: "Only open it if you were expecting it.") },
        confirmButton = { TextButton(onClick = { confirmOpen = false; openExternally() }) { Text("Open in…") } },
        dismissButton = { TextButton(onClick = { confirmOpen = false }) { Text("Cancel") } })
}
