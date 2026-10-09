package dev.forgesworn.kithmoot.ui.room

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import dev.forgesworn.kithmoot.session.*
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MediaComposer(enabled: Boolean, busy: Boolean, attachments: List<ChatAttachment>, onAdd: (Uri, String, Boolean) -> Unit,
    onRemove: (String) -> Unit, showFiles: Boolean = true, showControls: Boolean = true, selectedArtwork: CatalogueImage? = null,
    onArtworkConsumed: () -> Unit = {}, onOpenArtwork: () -> Unit = {}) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf<Uri?>(null) }
    val preferences = remember { context.getSharedPreferences("shared-media-storage", android.content.Context.MODE_PRIVATE) }
    var storage by remember { mutableStateOf(preferences.getString("origin", null) ?: "https://kithmoot.forgesworn.dev") }
    var consent by remember { mutableStateOf(preferences.getString("origin", null) != null && preferences.getInt("disclosure", 0) == 1) }
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> selected = uri }
    if (showControls && (showFiles || busy || attachments.isNotEmpty())) FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(enabled = enabled && !busy && attachments.size < 4, onClick = { picker.launch(arrayOf("image/gif", "image/png", "image/jpeg", "image/webp")) }) { Text("Add image / GIF") }
        TextButton(enabled = enabled && !busy && attachments.size < 4, onClick = onOpenArtwork) { Text("GIFs and stickers") }
        if (busy) Text("Encrypting and uploading…", style = MaterialTheme.typography.bodySmall)
        for (file in attachments) InputChip(selected = true, onClick = { onRemove(file.sha256) }, label = { Text("${file.name ?: "Image"} ×") })
    }
    selected?.let { uri ->
        AlertDialog(onDismissRequest = { selected = null }, title = { Text("Share an encrypted image") }, text = {
            Column {
                Text("The file is encrypted on this phone. The storage server can see your IP address and serves encrypted bytes publicly. Only room members receive the file key.")
                OutlinedTextField(storage, { storage = it; consent = false; preferences.edit().remove("origin").remove("disclosure").apply() }, label = { Text("HTTPS storage server") }, singleLine = true)
                Row { Checkbox(consent, { consent = it }); Text("Allow shared encrypted storage on this server", Modifier.padding(top = 12.dp)) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(enabled = consent && enabled && !busy, onClick = {
            try {
                val origin = mediaStorageOrigin(storage)
                preferences.edit().putString("origin", origin).putInt("disclosure", 1).apply()
                onAdd(uri, origin, true); selected = null; error = null
            } catch (failure: Exception) { error = failure.message }
        }) { Text("Encrypt and upload") } }, dismissButton = { TextButton(onClick = { selected = null }) { Text("Cancel") } })
    }
    LaunchedEffect(selectedArtwork) {
        val image = selectedArtwork ?: return@LaunchedEffect
        try {
            val bytes = withContext(Dispatchers.IO) { downloadCatalogueImage(image, context) }
            try {
                val file = withContext(Dispatchers.IO) {
                    val directory = File(context.cacheDir, "opened-images").apply { mkdirs() }
                    directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 3_600_000 }?.forEach { it.delete() }
                    val extension = if (image.type == "image/gif") ".gif" else ".png"
                    File(directory, UUID.randomUUID().toString() + extension).also { it.writeBytes(bytes) }
                }
                currentCoroutineContext().ensureActive()
                selected = FileProvider.getUriForFile(context, "${context.packageName}.images", file)
                error = null
            } finally { bytes.fill(0) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "Could not add artwork." }
        finally { onArtworkConsumed() }
    }
    if (selected == null) error?.let { Text(it, Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.error) }
}

@Composable
internal fun CatalogueThumbnail(image: CatalogueImage, modifier: Modifier = Modifier.size(64.dp)) {
    if (image.type != "image/gif") {
        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(originalArtworkDrawable(image.slug)), contentDescription = null, modifier = modifier)
        return
    }
    val context = LocalContext.current
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var reducedMotion by remember { mutableStateOf(!android.animation.ValueAnimator.areAnimatorsEnabled()) }
    val preview = cataloguePreviewAsset(image, reducedMotion)
    var drawable by remember(preview) { mutableStateOf<android.graphics.drawable.Drawable?>(null) }
    LaunchedEffect(preview) {
        drawable = withContext(Dispatchers.IO) {
            android.graphics.ImageDecoder.decodeDrawable(android.graphics.ImageDecoder.createSource(context.assets, preview))
        }
    }
    DisposableEffect(drawable, owner) {
        val animation = drawable as? android.graphics.drawable.AnimatedImageDrawable
        fun play() {
            reducedMotion = !android.animation.ValueAnimator.areAnimatorsEnabled()
            if (reducedMotion) animation?.stop() else animation?.start()
        }
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_START) play()
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) animation?.stop()
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) play()
        onDispose { owner.lifecycle.removeObserver(observer); animation?.stop() }
    }
    androidx.compose.ui.viewinterop.AndroidView(factory = { android.widget.ImageView(it).apply { scaleType = android.widget.ImageView.ScaleType.FIT_CENTER } },
        update = { if (it.drawable !== drawable) it.setImageDrawable(drawable) }, modifier = modifier)
}
