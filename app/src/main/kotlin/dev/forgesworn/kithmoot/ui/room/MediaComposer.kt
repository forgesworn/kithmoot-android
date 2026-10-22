package dev.forgesworn.kithmoot.ui.room

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.session.*
import kotlinx.coroutines.*

/**
 * The file controls under the composer. A picked image goes to [onPick], which
 * decides where it goes (a chosen server at once, the one-time notice, or the
 * uploads-off line): nothing here asks for a server or consent any more.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MediaComposer(enabled: Boolean, busy: Boolean, attachments: List<ChatAttachment>, onPick: (Uri) -> Unit,
    onRemove: (String) -> Unit, showFiles: Boolean = true, showControls: Boolean = true,
    artworkEnabled: Boolean = true, onOpenArtwork: () -> Unit = {}) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(onPick) }
    if (showControls && (showFiles || busy || attachments.isNotEmpty())) FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(enabled = enabled && !busy && attachments.size < 4, onClick = { picker.launch(arrayOf("image/gif", "image/png", "image/jpeg", "image/webp")) }) { Text("Add image / GIF") }
        TextButton(enabled = artworkEnabled, onClick = onOpenArtwork) { Text("GIFs and stickers") }
        if (busy) Text("Encrypting and uploading…", style = MaterialTheme.typography.bodySmall)
        for (file in attachments) InputChip(selected = true, onClick = { onRemove(file.sha256) }, label = { Text("${file.name ?: "Image"} ×") })
    }
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
