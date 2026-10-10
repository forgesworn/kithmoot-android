package dev.forgesworn.kithmoot.ui.room

import android.media.AudioAttributes
import android.widget.VideoView
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/** Entered through the recipient's explicit Show action. No remote URI is
 * given to the player; all bytes and metadata authenticate before playback. */
@Composable
fun RecordingAttachmentViewer(attachment: ChatAttachment, onClose: () -> Unit, client: OkHttpClient? = null) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val request = remember(attachment, client) {
        runCatching {
            val cache = (context.applicationContext as KithMootApplication).recordingPlaybackCache
            if (client == null) RecordingAttachmentRequest(attachment, cache)
            else RecordingAttachmentRequest(attachment, cache, client)
        }
    }
    var opened by remember(attachment) { mutableStateOf<OpenedFile?>(null) }
    var error by remember(attachment) { mutableStateOf(request.exceptionOrNull()?.message) }
    var player by remember(attachment) { mutableStateOf<VideoView?>(null) }
    var prepared by remember(attachment) { mutableStateOf(false) }
    var playing by remember(attachment) { mutableStateOf(false) }
    var completed by remember(attachment) { mutableStateOf(false) }
    var duration by remember(attachment) { mutableIntStateOf(0) }
    var position by remember(attachment) { mutableIntStateOf(0) }
    DisposableEffect(request) { onDispose { request.getOrNull()?.close() } }
    LaunchedEffect(request) {
        val download = request.getOrNull() ?: return@LaunchedEffect
        try { opened = withContext(Dispatchers.IO) { download.download() } }
        catch (cancelled: CancellationException) { download.close(); throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "The recording could not be opened." }
    }
    DisposableEffect(player, lifecycle) {
        val view = player
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                view?.pause()
                playing = false
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); view?.pause() }
    }
    LaunchedEffect(player, prepared) {
        val view = player ?: return@LaunchedEffect
        if (!prepared) return@LaunchedEffect
        while (isActive) {
            position = view.currentPosition.let { if (duration > 0) it.coerceIn(0, duration) else it.coerceAtLeast(0) }
            playing = view.isPlaying
            delay(250)
        }
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
                    Text(recordingClock(position) + if (duration > 0) " / ${recordingClock(duration)}" else "")
                    if (duration > 0) Slider(value = position.toFloat(), valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                        enabled = prepared, onValueChange = { value ->
                            position = value.toInt(); player?.seekTo(position)
                        })
                    Button(enabled = prepared, onClick = {
                        val view = player ?: return@Button
                        if (view.isPlaying) { view.pause(); playing = false }
                        else if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                            if (completed || (duration > 0 && position >= duration)) view.seekTo(0)
                            completed = false
                            view.start(); playing = true
                        }
                    }) { Text(if (playing) "Pause recording" else "Play recording") }
                    Spacer(Modifier.height(8.dp))
                    AndroidView(factory = { owner ->
                        VideoView(owner).apply {
                            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                            setOnPreparedListener { media ->
                                duration = media.duration.coerceAtLeast(0)
                                prepared = true
                                completed = false
                            }
                            setOnCompletionListener { media ->
                                playing = false; completed = true
                                position = if (duration > 0) duration else maxOf(position, media.currentPosition)
                            }
                            setOnErrorListener { _, _, _ ->
                                prepared = false; playing = false
                                error = "This device could not play the recording."; true
                            }
                            setVideoPath(file.file.absolutePath)
                            player = this
                        }
                    }, onReset = null, onRelease = { view ->
                        view.stopPlayback()
                        if (player === view) player = null
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
