package dev.forgesworn.kithmoot.ui.room

import android.net.Uri
import androidx.compose.ui.graphics.asImageBitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
    onRemove: (String) -> Unit, insertCredit: (String) -> Unit) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf<Uri?>(null) }
    var catalogue by remember { mutableStateOf(false) }
    val preferences = remember { context.getSharedPreferences("shared-media-storage", android.content.Context.MODE_PRIVATE) }
    var storage by remember { mutableStateOf(preferences.getString("origin", null) ?: "https://kithmoot.forgesworn.dev") }
    var consent by remember { mutableStateOf(preferences.getString("origin", null) != null && preferences.getInt("disclosure", 0) == 1) }
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> selected = uri }
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(enabled = enabled && !busy && attachments.size < 4, onClick = { picker.launch(arrayOf("image/gif", "image/png", "image/jpeg", "image/webp")) }) { Text("Add image / GIF") }
        TextButton(enabled = enabled && !busy && attachments.size < 4, onClick = { catalogue = true }) { Text("GIFs and stickers") }
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
    if (catalogue) CatalogueDialog(onClose = { catalogue = false }) { image ->
        val bytes = withContext(Dispatchers.IO) { downloadCatalogueImage(image) }
        try {
            val file = withContext(Dispatchers.IO) {
                val directory = File(context.cacheDir, "opened-images").apply { mkdirs() }
                directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 3_600_000 }?.forEach { it.delete() }
                val extension = when (image.type) { "image/gif" -> ".gif"; "image/webp" -> ".webp"; else -> ".png" }
                File(directory, UUID.randomUUID().toString() + extension).also { it.writeBytes(bytes) }
            }
            currentCoroutineContext().ensureActive()
            insertCredit("${image.credit}\n${image.source}")
            selected = FileProvider.getUriForFile(context, "${context.packageName}.images", file)
            catalogue = false
        } finally { bytes.fill(0) }
    }
}

@Composable
private fun CatalogueDialog(onClose: () -> Unit, onChoose: suspend (CatalogueImage) -> Unit) {
    var query by remember { mutableStateOf("") }
    var stickers by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf(emptyList<CatalogueImage>()) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = onClose, title = { Text("GIFs and stickers") }, text = {
        Column {
            Text("Search sends your query and IP address to Wikimedia Commons. Selected files are encrypted before sharing.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(query, { query = it.take(80) }, label = { Text("Search GIFs and stickers") }, singleLine = true)
            Row { FilterChip(!stickers, { stickers = false }, label = { Text("GIFs") }); FilterChip(stickers, { stickers = true }, label = { Text("Stickers") }) }
            TextButton(enabled = !busy, onClick = {
                busy = true; status = "Searching…"
                scope.launch {
                    try { results = withContext(Dispatchers.IO) { searchMediaCatalogue(query.ifBlank { "celebration" }, stickers) }; status = if (results.isEmpty()) "No matching files. Try another search." else "Choose an image to add." }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { status = failure.message ?: "The catalogue could not be reached." }
                    finally { busy = false }
                }
            }) { Text("Search catalogue") }
            Text(status, style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.heightIn(max = 260.dp)) {
                items(results, key = { it.url }) { image ->
                    TextButton(enabled = !busy, onClick = {
                        busy = true; status = "Downloading…"
                        scope.launch {
                            try { onChoose(image) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { status = failure.message ?: "Could not add this image." }
                            finally { busy = false }
                        }
                    }) { Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { CatalogueThumbnail(image); Column(Modifier.weight(1f)) { Text(image.name); Text(image.credit, style = MaterialTheme.typography.bodySmall) } } }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onClose) { Text("Close media picker") } })
}

@Composable
private fun CatalogueThumbnail(image: CatalogueImage) {
    var bitmap by remember(image.preview) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(image.preview) {
        val url = image.preview ?: return@LaunchedEffect
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = downloadCataloguePreview(url)
                try {
                    android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                        require(info.size.width in 1..8192 && info.size.height in 1..8192 && info.size.width.toLong() * info.size.height <= 16_000_000)
                        val scale = minOf(1f, 160f / maxOf(info.size.width, info.size.height))
                        decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
                        decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                        decoder.setOnPartialImageListener { false }
                    }
                } finally { bytes.fill(0) }
            }.getOrNull()
        }
    }
    val decoded = bitmap
    if (decoded == null) Text(if (image.type == "image/gif") "GIF" else "Image", Modifier.width(64.dp), style = MaterialTheme.typography.labelMedium)
    else androidx.compose.foundation.Image(decoded.asImageBitmap(), contentDescription = null, modifier = Modifier.size(64.dp))
}
