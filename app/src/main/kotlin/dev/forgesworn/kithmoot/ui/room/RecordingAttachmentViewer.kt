package dev.forgesworn.kithmoot.ui.room

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.forgesworn.kithmoot.session.ChatAttachment
import dev.forgesworn.kithmoot.session.OpenedFile
import dev.forgesworn.kithmoot.session.RecordingAttachmentRequest
import dev.forgesworn.kithmoot.session.recordingPlaybackMime
import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.media.recording.IsolatedRecordingPlayer
import dev.forgesworn.kithmoot.media.recording.RecordingPlaybackState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/** Scoped transport override; absent in production, which uses the strict HTTPS client. */
internal val LocalRecordingAttachmentHttpClient = staticCompositionLocalOf<OkHttpClient?> { null }

/** Entered through the recipient's explicit Show action. No remote URI or media
 * decoder reaches the app process; authenticated bytes play in an isolated service. */
@Composable
fun RecordingAttachmentViewer(attachment: ChatAttachment, onClose: () -> Unit, client: OkHttpClient? = null) {
    val http = client ?: LocalRecordingAttachmentHttpClient.current
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val request = remember(attachment, http) {
        runCatching {
            val cache = (context.applicationContext as KithMootApplication).recordingPlaybackCache
            if (http == null) RecordingAttachmentRequest(attachment, cache)
            else RecordingAttachmentRequest(attachment, cache, http)
        }
    }
    var opened by remember(attachment) { mutableStateOf<OpenedFile?>(null) }
    var error by remember(attachment) { mutableStateOf(request.exceptionOrNull()?.message) }
    var player by remember(attachment) { mutableStateOf<IsolatedRecordingPlayer?>(null) }
    var playback by remember(attachment) { mutableStateOf(RecordingPlaybackState()) }
    DisposableEffect(request) { onDispose { request.getOrNull()?.close() } }
    DisposableEffect(player) {
        val isolated = player
        onDispose { isolated?.close() }
    }
    LaunchedEffect(request) {
        val download = request.getOrNull() ?: return@LaunchedEffect
        try {
            val file = withContext(Dispatchers.IO) { download.download() }
            opened = file
            val isolated = IsolatedRecordingPlayer(context,
                onState = { playback = it },
                onError = { error = it })
            player = isolated
            isolated.open(file)
        } catch (cancelled: CancellationException) {
            player?.close()
            download.close()
            throw cancelled
        } catch (failure: Exception) {
            player?.close()
            error = failure.message ?: "The recording could not be opened."
        }
    }
    DisposableEffect(player, lifecycle) {
        val isolated = player
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) isolated?.pause()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); isolated?.pause() }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)) {
                Text(opened?.name ?: attachment.name ?: "Recording", style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = onClose) { Text("Close recording") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                val file = opened
                if (file == null && error == null) CircularProgressIndicator()
                if (file != null && error == null) {
                    Text(recordingClock(playback.positionMs) + if (playback.durationMs > 0) " / ${recordingClock(playback.durationMs)}" else "")
                    if (playback.durationMs > 0) Slider(value = playback.positionMs.toFloat(),
                        valueRange = 0f..playback.durationMs.coerceAtLeast(1).toFloat(),
                        enabled = playback.prepared, onValueChange = { value -> player?.seekTo(value.toInt()) })
                    Button(enabled = playback.prepared, onClick = {
                        val isolated = player ?: return@Button
                        if (playback.playing) isolated.pause()
                        else if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) isolated.play()
                    }) { Text(if (playback.playing) "Pause recording" else "Play recording") }
                    Spacer(Modifier.height(8.dp))
                    AndroidView(factory = { owner ->
                        SurfaceView(owner).apply {
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) = player?.setSurface(holder.surface) ?: Unit
                                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                                override fun surfaceDestroyed(holder: SurfaceHolder) = player?.setSurface(null) ?: Unit
                            })
                        }
                    }, onReset = null, onRelease = {
                        player?.setSurface(null)
                    }, modifier = if (recordingPlaybackMime(file.type)?.startsWith("video/") == true) Modifier.fillMaxWidth().weight(1f)
                        else Modifier.fillMaxWidth().height(1.dp))
                }
            }
        }
    }
}

private fun recordingClock(milliseconds: Int): String {
    val seconds = milliseconds / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
