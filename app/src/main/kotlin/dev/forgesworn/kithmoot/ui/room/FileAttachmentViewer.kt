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
import dev.forgesworn.kithmoot.protocol.AttachmentDecision
import dev.forgesworn.kithmoot.protocol.AttachmentTier
import dev.forgesworn.kithmoot.protocol.SaveTier
import dev.forgesworn.kithmoot.session.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
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

/** The platform's type for a file extension, for [openWithType]. */
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
    groupSource: GroupFileSource? = null,
    /** Who sent it, for the warning's wording. */
    sender: String? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Sweeping the cache folder and making this file's private directory touch the disk, so they happen
    // off the main thread. Nothing can be saved or opened until the request exists, which is at once in practice.
    var request by remember(attachment, client, groupSource) { mutableStateOf<Result<FileAttachmentRequest>?>(null) }
    LaunchedEffect(attachment, client, groupSource) {
        // NonCancellable: a request made just as the dialog closes is still handed to the finally below to be removed.
        val created = withContext(Dispatchers.IO + NonCancellable) {
            runCatching {
                val root = File(context.cacheDir, OPENED_FILES)
                if (client == null) FileAttachmentRequest(attachment, root, groupSource = groupSource)
                else FileAttachmentRequest(attachment, root, client, groupSource)
            }
        }
        request = created
        try { awaitCancellation() } finally { created.getOrNull()?.close() }
    }
    val requestReady = request?.isSuccess == true
    var opened by remember(attachment) { mutableStateOf<OpenedFile?>(null) }
    var busy by remember(attachment) { mutableStateOf(false) }
    var status by remember(attachment) { mutableStateOf<String?>(null) }
    var error by remember(attachment) { mutableStateOf<String?>(null) }
    // Only the copy to the chosen place: closing the dialog then would lose the save, so Close waits for it.
    var saving by remember(attachment) { mutableStateOf(false) }
    /** What the safety classifier says about the downloaded file, as the image viewer applies it. */
    var decision by remember(attachment) { mutableStateOf<AttachmentDecision?>(null) }
    /** An action waiting for the person to accept a warning about this file. */
    var warned by remember(attachment) { mutableStateOf<(suspend (OpenedFile, AttachmentDecision) -> Unit)?>(null) }
    val blocked = decision?.tier == AttachmentTier.BLOCK
    val packageFile = isAndroidPackage(attachment.name, attachment.type) ||
        opened?.let { isAndroidPackage(it.name, it.type) } == true

    /**
     * Download and authenticate once, classify it, then hand the file and the
     * classifier's decision to [then]: never when it is blocked, and for a
     * warned file only once the person has accepted the warning ([accepted]).
     * Whatever [then] needs to name or type the file comes from that decision.
     */
    fun withFile(accepted: Boolean = false, then: suspend (OpenedFile, AttachmentDecision) -> Unit) {
        val owner = request?.getOrNull() ?: return
        if (busy) return
        busy = true; error = null; status = null
        scope.launch {
            try {
                val file = opened ?: runInterruptible(Dispatchers.IO) { owner.download() }.also { opened = it }
                val verdict = decision ?: withContext(Dispatchers.IO) { classifyReceivedFile(file, attachment.name, sender) }.also { decision = it }
                if (verdict.tier == AttachmentTier.BLOCK) { error = verdict.message ?: "KithMoot won’t open or save this file."; return@launch }
                if (verdict.save == SaveTier.WARN && !accepted) { warned = then; return@launch }
                then(file, verdict)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "The file could not be opened." }
            finally { busy = false }
        }
    }

    val saver = rememberLauncherForActivityResult(remember { CreateDocumentFor() }) { uri ->
        val file = opened
        if (uri == null || file == null) return@rememberLauncherForActivityResult
        busy = true; saving = true
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
                // The name it was offered under, never the raw envelope name, which can carry bidi or invisible characters.
                status = "Saved ${decision?.name ?: localFileName(file.name)}."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = "The file could not be saved: ${failure.message ?: "storage unavailable"}." }
            finally { busy = false; saving = false }
        }
    }

    val title = decision?.name ?: opened?.name?.let(::sanitiseAttachmentName) ?: attachment.name ?: "Encrypted file"
    val details = opened?.let { listOf(formatAttachmentBytes(it.size), baseMediaType(it.type)) }
        ?: listOfNotNull(attachment.size?.let { "${formatAttachmentBytes(it)} download" }, attachment.type)
    val storageUnavailable = request?.isFailure == true
    AlertDialog(onDismissRequest = { if (!saving) onClose() },
        // A file name can be long: the panel-title size rather than the dialog default.
        title = { Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (details.isNotEmpty()) Text(details.joinToString(" · "), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (opened == null) "Nothing is downloaded until you choose. The encrypted file is checked against this message before it is opened."
                    else "Checked against this message and decrypted on this phone.")
                if (decision?.tier == AttachmentTier.WARN) decision?.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (packageFile) Text("Android app packages can only be saved. KithMoot does not open or install them.")
                if (busy) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(if (opened == null) "Downloading and checking…" else "Working…", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                (error ?: if (storageUnavailable) "Private file storage is unavailable." else null)
                    ?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(enabled = !busy && requestReady && !blocked, onClick = {
                    // The title and type are the classifier's, not the sender's: a provider adds an extension for the type it is given.
                    withFile { _, verdict -> saver.launch(verdict.name to openWithType(verdict, ::platformMime)) }
                }) { Text("Save") }
                if (!packageFile && !blocked) TextButton(enabled = !busy && requestReady, onClick = {
                    withFile { file, verdict ->
                        // The envelope is the authority: a hint that hid a package does not get it opened.
                        // The check reads the cleaned name the file is saved under, not the raw envelope name.
                        if (isAndroidPackage(file.name, file.type)) { error = "This is an Android app package. Save it instead."; return@withFile }
                        try {
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.images", file.file)
                            // The type is the classifier's, never the sender's: a file it read as text is not handed to a browser as a page.
                            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, openWithType(verdict, ::platformMime))
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            intent.clipData = ClipData.newRawUri(verdict.name, uri)
                            request?.getOrNull()?.keep()
                            context.startActivity(Intent.createChooser(intent, "Open with"))
                        } catch (_: android.content.ActivityNotFoundException) {
                            error = "No app on this phone can open this file. Save it instead."
                        }
                    }
                }) { Text("Open with…") }
                TextButton(enabled = !saving, onClick = onClose) { Text("Close") }
            }
        })
    warned?.let { action ->
        AlertDialog(onDismissRequest = { warned = null },
            title = { Text("Use this file anyway?") },
            text = { Text(decision?.confirmation ?: decision?.message ?: "Only use it if you were expecting it.") },
            confirmButton = { TextButton(onClick = { warned = null; withFile(accepted = true, then = action) }) { Text("Continue") } },
            dismissButton = { TextButton(onClick = { warned = null }) { Text("Cancel") } })
    }
}
