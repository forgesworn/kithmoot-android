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
import dev.forgesworn.kithmoot.session.*
import kotlinx.coroutines.*

/** One attach action for photos and every other file: the system document
 * picker opened for any type, rather than a row of per-kind buttons. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MediaComposer(enabled: Boolean, busy: Boolean, attachments: List<ChatAttachment>, onAdd: (Uri, String, Boolean) -> Unit,
    onRemove: (String) -> Unit, showFiles: Boolean = true, showControls: Boolean = true,
    artworkEnabled: Boolean = true, onOpenArtwork: () -> Unit = {},
    uploading: Boolean = false, onCancelUpload: () -> Unit = {}) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf<Uri?>(null) }
    val preferences = remember { context.getSharedPreferences("shared-media-storage", android.content.Context.MODE_PRIVATE) }
    var storage by remember { mutableStateOf(preferences.getString("origin", null) ?: "https://kithmoot.forgesworn.dev") }
    var consent by remember { mutableStateOf(preferences.getString("origin", null) != null && preferences.getInt("disclosure", 0) == 1) }
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> selected = uri }
    if (showControls && (showFiles || busy || attachments.isNotEmpty())) FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(enabled = enabled && !busy && attachments.size < 4, onClick = { picker.launch(arrayOf("*/*")) }) { Text("Add photo or file") }
        TextButton(enabled = artworkEnabled, onClick = onOpenArtwork) { Text("GIFs and stickers") }
        if (busy) Text("Encrypting and uploading…", Modifier.align(androidx.compose.ui.Alignment.CenterVertically), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (uploading) TextButton(onClick = onCancelUpload) { Text("Stop upload") }
        for (file in attachments) InputChip(selected = true, onClick = { onRemove(file.sha256) }, label = { Text("${file.name ?: "File"} ×") })
    }
    selected?.let { uri ->
        // Name and size from the provider, so a file too big to send is said
        // before consent rather than after it has been read.
        var picked by remember(uri) { mutableStateOf<Pair<String?, Long?>?>(null) }
        LaunchedEffect(uri) {
            picked = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                        if (!cursor.moveToFirst()) null else (if (cursor.isNull(0)) null else cursor.getString(0)) to (if (cursor.isNull(1)) null else cursor.getLong(1))
                    }
                }.getOrNull() ?: (null to null)
            }
        }
        val tooLarge = (picked?.second ?: 0L) > MAX_ATTACHMENT_SOURCE_BYTES
        AlertDialog(onDismissRequest = { selected = null }, title = { Text("Share an encrypted file") }, text = {
            Column {
                picked?.let { (name, size) ->
                    Text(listOfNotNull(sanitiseAttachmentName(name), size?.takeIf { it > 0 }?.let(::formatAttachmentBytes)).joinToString(" · ").ifEmpty { "Selected file" },
                        style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                if (tooLarge) Text("This file is larger than 256 MiB, which is as much as a room will send.", color = MaterialTheme.colorScheme.error)
                Text("Photos and other files up to 256 MiB. The file is encrypted on this phone. The storage server can see your IP address and serves encrypted bytes publicly. Only room members receive the file key.")
                OutlinedTextField(storage, { storage = it; consent = false; preferences.edit().remove("origin").remove("disclosure").apply() }, label = { Text("HTTPS storage server") }, singleLine = true)
                Row { Checkbox(consent, { consent = it }); Text("Allow shared encrypted storage on this server", Modifier.padding(top = 12.dp)) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(enabled = consent && enabled && !busy && !tooLarge, onClick = {
            try {
                val origin = mediaStorageOrigin(storage)
                preferences.edit().putString("origin", origin).putInt("disclosure", 1).apply()
                onAdd(uri, origin, true); selected = null; error = null
            } catch (failure: Exception) { error = failure.message }
        }) { Text("Encrypt and upload") } }, dismissButton = { TextButton(onClick = { selected = null }) { Text("Cancel") } })
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
