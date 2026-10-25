package dev.forgesworn.kithmoot.ui.start

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.forgesworn.kithmoot.account.*
import dev.forgesworn.kithmoot.mls.*
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

@Composable
fun KeeperInboxControls(boxes: VmlsBoxes, persona: String, transmitter: String?, routes: List<VmlsBoxView>, signer: () -> ParticipantSigner?) {
    val scope = rememberCoroutineScope(); val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestSigner by rememberUpdatedState(signer)
    val currentActor = latestSigner()
    val foreground = remember(persona) { AtomicBoolean(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    var job by remember(persona) { mutableStateOf<Job?>(null) }
    var token by remember(persona) { mutableStateOf<KeeperWorkflowLease?>(null) }
    var requests by remember(persona) { mutableStateOf<List<Pair<String, RevocationEntry>>>(emptyList()) }
    var permissions by remember(persona) { mutableStateOf<List<KeeperPermissionRecord>>(emptyList()) }
    var selectedKey by remember(persona) { mutableStateOf<String?>(null) }
    var selectedBox by remember(persona) { mutableStateOf(routes.firstOrNull()?.box) }
    var input by remember(persona) { mutableStateOf("") }
    var device by remember(persona) { mutableStateOf("") }
    var days by remember(persona) { mutableStateOf("7") }
    var message by remember(persona) { mutableStateOf<String?>(null) }
    var exported by remember(persona) { mutableStateOf<String?>(null) }
    var confirmPublic by remember(persona) { mutableStateOf(false) }
    val working = job?.isActive == true
    suspend fun reload() { requests = boxes.requestOutbox(persona); permissions = boxes.permissionRecords(persona) }
    fun run(work: suspend (ParticipantSigner, () -> Boolean) -> String) {
        val actor = latestSigner()?.takeIf { it.pubkey == persona } ?: return
        val lease = KeeperWorkflowLease(persona, actor, { latestSigner() }, foreground::get); token?.withdraw(); token = lease; exported = null
        job = scope.launch {
            try {
                val live = lease::current
                check(live()); message = work(actor, live); check(live()); reload()
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (error: Exception) { message = error.message ?: "Operation unconfirmed. Refresh protected status before retrying." }
            finally { lease.withdraw() }
        }
    }
    DisposableEffect(persona, currentActor) {
        onDispose { token?.withdraw(); job?.cancel() }
    }
    DisposableEffect(persona, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { foreground.set(false); token?.withdraw(); job?.cancel() }
            if (event == Lifecycle.Event.ON_START) foreground.set(true)
        }
        lifecycle.addObserver(observer)
        onDispose { foreground.set(false); token?.withdraw(); job?.cancel(); lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(persona) { try { reload() } catch (_: Exception) { message = "Open the confirmed vault, then refresh protected requests." } }
    Text("Private keeper requests (development)", style = MaterialTheme.typography.titleMedium)
    Text("Requires a Bothy node that supports private keeper requests. The selected node sees keeper, transmitting device, grant, expiry and timing. Link relays can observe timing and volume. Pairing or MLS membership does not establish current storage-keeper authority.", style = MaterialTheme.typography.bodySmall)
    TextButton({ run { _, _ -> reload(); "Protected status refreshed." } }, enabled = !working) { Text("Refresh protected requests and permissions") }
    routes.forEach { route -> TextButton({ selectedBox = route.box; input = ""; exported = null }, enabled = !working) {
        Text("${if (selectedBox == route.box) "Selected: " else ""}${route.name} · ${route.box.take(12)}…")
    } }
    val route = routes.singleOrNull { it.box == selectedBox }?.let { VmlsBoxRoute(persona, it.box, it.routeId, it.name) }
    route?.let { Text("Selected node: ${it.box}\nKeeper issuer for preparation: $persona\nSaved route: ${it.routeId}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
    Text("Retained member requests", style = MaterialTheme.typography.titleSmall)
    requests.forEach { (key, entry) ->
        TextButton({ selectedKey = key; input = "" }, enabled = !working) { Text("${if (selectedKey == key) "Selected: " else ""}lost device ${entry.request.device.take(12)}… · keeper ${entry.request.keeper.take(12)}…") }
        Text("Revision ${entry.revision}: " + when { !entry.attempted -> "retained, no transport selected"; entry.sent -> "${if (entry.privateDestination == null) "Public relay" else "Bothy"} ACKed; keeper action unknown"; else -> "attempt unconfirmed; explicit retry required" }, style = MaterialTheme.typography.bodySmall)
        Text(if (entry.earlierPublicAttempt) "A public delivery attempt is retained for this request." else "No public attempt is retained here; this is not proof of no historical traffic.", style = MaterialTheme.typography.bodySmall)
    }
    val selected = requests.singleOrNull { it.first == selectedKey }
    OutlinedTextField(input, { input = it.take(8193) }, label = { Text("Paste signed keeper-inbox-deposit Event JSON") }, modifier = Modifier.fillMaxWidth(), enabled = !working, maxLines = 5)
    val parsed = remember(input, route, selected, transmitter) { runCatching {
        require(route != null && selected != null && transmitter != null)
        KeeperInboxPermissionJson.preview(input, route, selected.second.request.keeper, transmitter, System.currentTimeMillis() / 1000)
    } }
    parsed.getOrNull()?.let { e ->
        Text("Keeper: ${e.pubkey}\nTransmitting device: ${e.tagValue("device")}\nNode: ${route!!.box}\nGrant: ${e.tagValue("grant")}\nExpires: ${Instant.ofEpochSecond(e.tagValue("expiration")!!.toLong())}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        Text("This permission names the transmitting phone, independently of the lost device in the request. Fresh device identification is asked on each send.", style = MaterialTheme.typography.bodySmall)
    }
    if (input.isNotBlank() && parsed.isFailure) Text(parsed.exceptionOrNull()?.message ?: "Select a request and matching route/permission.", color = MaterialTheme.colorScheme.error)
    Button({ val request = selected ?: return@Button; val selectedRoute = route ?: return@Button; val json = input
        run { actor, live -> boxes.sendPrivateRequest(persona, actor, request.first, selectedRoute.box, json, request.second.revision, live) }
    }, enabled = !working && parsed.isSuccess && selected != null) { Text(if (selected?.second?.attempted == true) "Explicit fresh private retry" else "Send privately to selected Bothy") }
    TextButton({ confirmPublic = true }, enabled = !working && selected != null) { Text("Separate public relay action…") }
    if (confirmPublic) AlertDialog(onDismissRequest = { confirmPublic = false }, title = { Text("Use public directory and DM relays?") },
        text = { Text("This separate action reveals public relay connection metadata and delivery timing. It may switch a retained private attempt to Public. No public fallback is automatic.") },
        confirmButton = { TextButton({ confirmPublic = false; val request = selected ?: return@TextButton
            run { actor, live -> boxes.sendPublicRequest(persona, actor, request.first, request.second.revision, live) }
        }) { Text("Explicitly send on public relays") } }, dismissButton = { TextButton({ confirmPublic = false }) { Text("Cancel") } })
    HorizontalDivider()
    Text("Keeper permission custody", style = MaterialTheme.typography.titleSmall)
    Text("As this account, prepare an active permission and a terminal revocation signature for one transmitting device. Both are protected before installation. Only the node decides whether this account is currently a keeper.", style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(device, { device = it.take(65) }, label = { Text("Recipient's transmitting MLS device (64 lowercase hex)") }, enabled = !working, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(days, { days = it.take(3) }, label = { Text("Lifetime in days (1–30)") }, enabled = !working)
    Button({ val selectedRoute = route ?: return@Button; val target = device; val lifetime = days.toIntOrNull() ?: return@Button
        run { actor, live -> val made = boxes.prepareKeeperPermission(actor, selectedRoute.box, target, lifetime, live); "Protected active + terminal revocation prepared (${made.id.take(12)}…). Not installed or exported." }
    }, enabled = !working && route != null && Regex("[0-9a-f]{64}").matches(device) && days.toIntOrNull() in 1..30) { Text("Prepare both signatures (no installation)") }
    permissions.forEach { record ->
        val expired = record.expiresAt <= System.currentTimeMillis() / 1000
        Text("${record.id.take(12)}… · ${record.route.boxName}\nDevice ${record.active.tagValue("device")}\n${if (expired) "EXPIRED; previous status " else ""}${record.status} · revision ${record.revision}\nExpires ${Instant.ofEpochSecond(record.expiresAt)}", style = MaterialTheme.typography.bodySmall)
        Row {
            if (!expired && record.status in setOf(KeeperPermissionStatus.PREPARED, KeeperPermissionStatus.INSTALL_UNKNOWN)) TextButton({ run { actor, live -> boxes.publishKeeperPermission(actor, record.id, record.revision, false, live).let { "${it.status}: exact active bytes retained; ACK is required for export." } } }, enabled = !working) { Text(if (record.status == KeeperPermissionStatus.PREPARED) "Install on node" else "Retry same active bytes") }
            if (!expired && record.status == KeeperPermissionStatus.INSTALLED) TextButton({ run { _, live -> exported = boxes.exportKeeperPermission(persona, record.id, live); "Installed permission ready to copy deliberately." } }, enabled = !working) { Text("Export installed JSON") }
        }
        if (!expired && record.status != KeeperPermissionStatus.REVOKED) TextButton({ run { actor, live -> boxes.publishKeeperPermission(actor, record.id, record.revision, true, live).let { "${it.status}: active installation is now terminally blocked locally. Remote revocation needs ACK." } } }, enabled = !working) { Text(if (record.status == KeeperPermissionStatus.REVOKE_UNKNOWN) "Retry same revocation bytes" else "Revoke on node (terminal)") }
    }
    exported?.let { Text("Signed permission Event JSON", style = MaterialTheme.typography.labelMedium); androidx.compose.foundation.text.selection.SelectionContainer { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) } }
    if (working) TextButton({ token?.withdraw(); job?.cancel(); message = "Stopped. Refresh protected status; an unknown network result is retained." }) { Text("Stop operation") }
    message?.let { Text(it) }
}

@Composable
fun KeeperDeviceAuthDialog(operation: KeeperInboxConsent, answer: (ConsentDecision) -> Unit) {
    AlertDialog(onDismissRequest = { answer(ConsentDecision.Deny) }, title = { Text("Identify this transmitting device once?") },
        text = { Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
            Text("This consent applies only to this private send. No stored box/leaf approval is reused.")
            Text("Account ${operation.persona}\nTransmitting device ${operation.device}\nKeeper ${operation.keeper}\nNode ${operation.box}\nDestination ${operation.destination}\nGrant ${operation.grant}\nPermission ${operation.permissionId}\nDeadline ${Instant.ofEpochSecond(operation.expiresAt)}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Text("The node learns the keeper–device link and timing; Link relays observe timing and volume. The permission does not establish current keeper authority.")
        } }, confirmButton = { TextButton({ answer(ConsentDecision.Approve) }) { Text("Allow this send only") } },
        dismissButton = { TextButton({ answer(ConsentDecision.Deny) }) { Text("Deny") } })
}
