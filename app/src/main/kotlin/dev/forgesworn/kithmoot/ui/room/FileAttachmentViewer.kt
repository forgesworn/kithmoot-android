package dev.forgesworn.kithmoot.ui.room

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import dev.forgesworn.kithmoot.session.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File

/** Under the `opened-files/` cache path the FileProvider shares. */
private const val OPENED_FILES = "opened-files"

/** A shared file in a message: its name, then what a tap would download and
 * the sender's stated type. Tapping offers Save or Open with; nothing is
 * fetched until one of those is chosen. It sits inside a chat bubble and
 * takes that bubble's [ink], with [metaInk] for the details line as the
 * bubble's own time does, and a hairline [boundary] rather than a fill. */
@Composable
fun FileAttachmentRow(attachment: ChatAttachment, enabled: Boolean,
    ink: Color = LocalContentColor.current, metaInk: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    boundary: Color = MaterialTheme.colorScheme.outline, onOpen: () -> Unit) {
    val name = attachment.name ?: "Encrypted file"
    val details = listOfNotNull(attachment.size?.let(::formatAttachmentBytes), attachment.type).joinToString(" · ")
    Surface(shape = RoundedCornerShape(8.dp), color = Color.Transparent, contentColor = ink,
        border = BorderStroke(1.dp, boundary),
        modifier = Modifier.padding(vertical = 4.dp).fillMaxWidth()
            .clickable(enabled = enabled, onClickLabel = "Save or open file", onClick = onOpen)
            .semantics(mergeDescendants = true) { contentDescription = listOf("File", name, details).filter(String::isNotEmpty).joinToString(", ") }) {
        Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(if (isAndroidPackage(attachment.name, attachment.type)) Icons.Filled.Android else Icons.AutoMirrored.Filled.InsertDriveFile,
                contentDescription = null, tint = ink)
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge, color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (details.isNotEmpty()) Text(details, style = MaterialTheme.typography.labelMedium,
                    color = metaInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** `ACTION_CREATE_DOCUMENT` with the type and title decided at launch, once
 * the envelope's own name and type are known. */
private class CreateDocumentFor : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.second).putExtra(Intent.EXTRA_TITLE, input.first)
    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = intent?.data?.takeIf { resultCode == Activity.RESULT_OK }
}

/** The platform's type for a file extension, for [viewType]. */
private fun platformMime(extension: String): String? = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)

/**
 * Save writes through the Storage Access Framework to a place the person
 * picks; Open with decrypts into app cache and grants one other app read
 * access to that one file. Neither happens on its own. An Android package
 * is only ever saved: KithMoot does not hand a stranger's installer to the
 * system, whatever the message or the envelope claims it is.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FileAttachmentDialog(attachment: ChatAttachment, onClose: () -> Unit, client: OkHttpClient? = null,
    /** Reads a share held on the room's own storage; null for a room that names none. */
    groupSource: GroupFileSource? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val request = remember(attachment, client, groupSource) {
        runCatching {
            val root = File(context.cacheDir, OPENED_FILES)
            if (client == null) FileAttachmentRequest(attachment, root, groupSource = groupSource)
            else FileAttachmentRequest(attachment, root, client, groupSource)
        }
    }
    var opened by remember(attachment) { mutableStateOf<OpenedFile?>(null) }
    var busy by remember(attachment) { mutableStateOf(false) }
    var status by remember(attachment) { mutableStateOf<String?>(null) }
    var error by remember(attachment) { mutableStateOf(request.exceptionOrNull()?.let { "Private file storage is unavailable." }) }
    DisposableEffect(request) { onDispose { request.getOrNull()?.close() } }
    val packageFile = isAndroidPackage(attachment.name, attachment.type) ||
        opened?.let { isAndroidPackage(it.name, it.type) } == true

    /** Download and authenticate once, then hand the file to [then]. */
    fun withFile(then: suspend (OpenedFile) -> Unit) {
        val owner = request.getOrNull() ?: return
        if (busy) return
        busy = true; error = null; status = null
        scope.launch {
            try {
                val file = opened ?: runInterruptible(Dispatchers.IO) { owner.download() }.also { opened = it }
                then(file)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "The file could not be opened." }
            finally { busy = false }
        }
    }

    val saver = rememberLauncherForActivityResult(remember { CreateDocumentFor() }) { uri ->
        val file = opened
        if (uri == null || file == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val resolver = context.contentResolver
            try {
                withContext(Dispatchers.IO) {
                    try {
                        file.file.inputStream().use { input ->
                            checkNotNull(resolver.openOutputStream(uri, "wt")) { "The save location is unavailable" }
                                .use { output -> input.copyTo(output, 64 * 1024) }
                        }
                    } catch (failure: Exception) {
                        runCatching { DocumentsContract.deleteDocument(resolver, uri) }
                        throw failure
                    }
                }
                // The name it was saved under, never the raw envelope name, which can carry bidi or invisible characters.
                status = "Saved ${localFileName(file.name)}."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = "The file could not be saved: ${failure.message ?: "storage unavailable"}." }
            finally { busy = false }
        }
    }

    val title = opened?.name?.let(::sanitiseAttachmentName) ?: attachment.name ?: "Encrypted file"
    val details = opened?.let { listOf(formatAttachmentBytes(it.size), baseMediaType(it.type)) }
        ?: listOfNotNull(attachment.size?.let { "${formatAttachmentBytes(it)} download" }, attachment.type)
    AlertDialog(onDismissRequest = onClose,
        // A file name can be long: the panel-title size rather than the dialog default.
        title = { Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (details.isNotEmpty()) Text(details.joinToString(" · "), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (opened == null) "Nothing is downloaded until you choose. The encrypted file is checked against this message before it is opened."
                    else "Checked against this message and decrypted on this phone.")
                if (packageFile) Text("Android app packages can only be saved. KithMoot does not open or install them.")
                if (busy) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(if (opened == null) "Downloading and checking…" else "Working…", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(enabled = !busy && request.isSuccess, onClick = {
                    withFile { file ->
                        val type = if (isAndroidPackage(file.name, file.type)) "application/vnd.android.package-archive" else viewType(file.name, file.type, ::platformMime)
                        saver.launch(localFileName(file.name) to type)
                    }
                }) { Text("Save") }
                if (!packageFile) TextButton(enabled = !busy && request.isSuccess, onClick = {
                    withFile { file ->
                        // The envelope is the authority: a hint that hid a package does not get it opened.
                        // The check reads the cleaned name the file is saved under, not the raw envelope name.
                        if (isAndroidPackage(file.name, file.type)) { error = "This is an Android app package. Save it instead."; return@withFile }
                        try {
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.images", file.file)
                            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, viewType(file.name, file.type, ::platformMime))
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            intent.clipData = ClipData.newRawUri(localFileName(file.name), uri)
                            request.getOrNull()?.keep()
                            context.startActivity(Intent.createChooser(intent, "Open with"))
                        } catch (_: android.content.ActivityNotFoundException) {
                            error = "No app on this phone can open this file. Save it instead."
                        }
                    }
                }) { Text("Open with…") }
                TextButton(onClick = onClose) { Text("Close") }
            }
        })
}
