package dev.forgesworn.kithmoot.ui

import android.Manifest
import android.view.WindowManager
import android.view.WindowInsets
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal fun GuestAdmissionDialog(
    view: GuestAdmissionView,
    openingBusy: Boolean,
    devicesAvailable: Boolean,
    onName: (String) -> Unit,
    onRequest: () -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    devicePreview: GuestDevicePreview? = null,
    permissionAsker: PermissionAsker? = null,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val metrics = remember(context, configuration) { context.getSystemService(WindowManager::class.java).currentWindowMetrics }
    val systemInsets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
    val usableHeight = with(LocalDensity.current) { (metrics.bounds.height() - systemInsets.top - systemInsets.bottom).toDp() }
    val usableWidth = with(LocalDensity.current) { (metrics.bounds.width() - systemInsets.left - systemInsets.right).toDp() }
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val checks = remember(view.id, devicePreview) { devicePreview ?: GuestDeviceChecks(context.applicationContext) }
    val previewView = remember(view.id) { PreviewView(context) }
    var permissionGeneration by remember(view.id) { mutableIntStateOf(0) }
    var cameraOn by remember(view.id) { mutableStateOf(false) }
    var microphoneOn by remember(view.id) { mutableStateOf(false) }
    var level by remember(view.id) { mutableFloatStateOf(0f) }
    var mediaNote by remember(view.id) { mutableStateOf<String?>(null) }
    val reviewing = view.phase == GuestAdmissionPhase.PREVIEW
    val currentReviewing by rememberUpdatedState(reviewing)
    val currentId by rememberUpdatedState(view.id)
    val normalPermission = rememberPermissionAsker { mediaNote = it }
    val permission = permissionAsker ?: normalPermission

    fun stopChecks() {
        permissionGeneration++
        checks.stop()
        cameraOn = false; microphoneOn = false; level = 0f
    }

    fun request() {
        if (!reviewing) return
        stopChecks()
        onRequest()
    }

    DisposableEffect(checks, owner, view.id) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                stopChecks()
                mediaNote = "Device checks stopped while KithMoot was away. Press a check again when you are ready."
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { permissionGeneration++; owner.lifecycle.removeObserver(observer); checks.close() }
    }
    LaunchedEffect(view.phase) { if (!reviewing) stopChecks() }

    val terminal = view.phase in setOf(GuestAdmissionPhase.DECLINED, GuestAdmissionPhase.EXPIRED,
        GuestAdmissionPhase.UNAVAILABLE, GuestAdmissionPhase.REVOKED, GuestAdmissionPhase.ROOM_ENDED, GuestAdmissionPhase.CANCELLED)
    val title = when (view.phase) {
        GuestAdmissionPhase.PREVIEW -> "Review this invitation"
        GuestAdmissionPhase.DECLINED -> "Your request was declined"
        GuestAdmissionPhase.EXPIRED -> "Your request expired"
        GuestAdmissionPhase.UNAVAILABLE -> "This invitation is unavailable"
        GuestAdmissionPhase.REVOKED -> "This invitation was retired"
        GuestAdmissionPhase.ROOM_ENDED -> "This room has ended"
        GuestAdmissionPhase.CANCELLED -> "Your request was cancelled"
        GuestAdmissionPhase.ADMITTED -> "You have been admitted"
        else -> "Request to join"
    }
    val explanation = view.detail ?: when (view.phase) {
        GuestAdmissionPhase.PREVIEW -> "Check your name and devices. Nothing is sent to this room until you request to join."
        GuestAdmissionPhase.SIGNING -> "Confirm your account in your signer. You can cancel while it is open."
        GuestAdmissionPhase.SENDING -> "Sending your request… Waiting for relay confirmation."
        GuestAdmissionPhase.WAITING -> "Your request reached a relay. Waiting for someone in the room to let you in."
        GuestAdmissionPhase.RECONNECTING -> "Your request is not confirmed. Checking the connection and retrying the same request."
        GuestAdmissionPhase.ADMITTED -> "An authorised member admitted you. Opening the room…"
        GuestAdmissionPhase.CANCELLED -> "The request has stopped on this device. A request already sent may still be visible to the host until it expires."
        else -> "Review your details before trying again."
    }

    Dialog(onDismissRequest = { stopChecks(); onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = true)) {
        Surface(Modifier.sizeIn(maxWidth = usableWidth, maxHeight = usableHeight).fillMaxSize()
            .imePadding().padding(12.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                if (view.roomName.isNotBlank()) Text(view.roomName, style = MaterialTheme.typography.titleMedium)
                Text("The label comes from the link. Room contents and call media are available only after admission.",
                    style = MaterialTheme.typography.bodySmall)
                Text(explanation, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                OutlinedTextField(value = view.name, onValueChange = onName, enabled = reviewing,
                    label = { Text("Name for your request") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { request() }))
                if (reviewing) {
                    Text("Device checks are optional and stay on this phone. They stop before your request is sent.", style = MaterialTheme.typography.bodySmall)
                    if (!devicesAvailable) Text("Leave your active call before checking devices here.", style = MaterialTheme.typography.bodySmall)
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = devicesAvailable, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), onClick = {
                            if (cameraOn) { checks.stopCamera(); cameraOn = false }
                            else {
                                val generation = permissionGeneration
                                permission.ask(PermissionAsk(Manifest.permission.CAMERA, "Preview your camera",
                                    "Show your camera on this phone only. This does not join a call or send video to the room.",
                                    "Camera permission was not granted. You can still request to join.", onGranted = {
                                        if (currentReviewing && currentId == view.id && generation == permissionGeneration) {
                                            cameraOn = true; mediaNote = "Starting the local camera…"
                                            checks.startCamera(owner, previewView,
                                                onReady = { mediaNote = "Camera preview stays on this phone." },
                                                onFailure = { cameraOn = false; mediaNote = "The camera could not start. You can still request to join." })
                                        }
                                    }))
                            }
                        }) { Text(if (cameraOn) "Stop camera" else "Preview camera") }
                        OutlinedButton(enabled = devicesAvailable, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), onClick = {
                            if (microphoneOn) { checks.stopMicrophone(); microphoneOn = false; level = 0f }
                            else {
                                val generation = permissionGeneration
                                permission.ask(PermissionAsk(Manifest.permission.RECORD_AUDIO, "Check your microphone",
                                    "Measure the microphone level on this phone only. No sound is sent or saved.",
                                    "Microphone permission was not granted. You can still request to join.", onGranted = {
                                        if (currentReviewing && currentId == view.id && generation == permissionGeneration) {
                                            microphoneOn = true; mediaNote = "Microphone sound is not sent or saved."
                                            checks.startMicrophone(scope, onLevel = { level = it },
                                                onFailure = { microphoneOn = false; level = 0f; mediaNote = "The microphone could not start. You can still request to join." })
                                        }
                                    }))
                            }
                        }) { Text(if (microphoneOn) "Stop microphone" else "Check microphone") }
                    }
                    if (cameraOn) AndroidView(factory = { previewView }, modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).aspectRatio(4f / 3f))
                    if (microphoneOn) {
                        Text("Microphone level", style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(progress = { level }, modifier = Modifier.fillMaxWidth())
                    }
                    mediaNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Button(onClick = { request() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Request to join") }
                    OutlinedButton(onClick = { stopChecks(); onClose() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Close invitation") }
                } else if (terminal) {
                    if (view.phase !in setOf(GuestAdmissionPhase.REVOKED, GuestAdmissionPhase.ROOM_ENDED)) Button(onClick = onRetry, enabled = !openingBusy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Review and retry") }
                    OutlinedButton(onClick = { stopChecks(); onClose() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Back to rooms") }
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    OutlinedButton(onClick = { stopChecks(); onCancel() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Cancel request") }
                }
            }
        }
    }
}
