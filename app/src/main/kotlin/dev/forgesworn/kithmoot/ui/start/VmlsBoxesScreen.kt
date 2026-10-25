package dev.forgesworn.kithmoot.ui.start

import dev.forgesworn.kithmoot.ui.theme.cappedTitleStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.account.ConsentDecision
import dev.forgesworn.kithmoot.account.ConsentScope
import dev.forgesworn.kithmoot.account.MlsVault
import dev.forgesworn.kithmoot.account.ParticipantSigner
import dev.forgesworn.kithmoot.mls.VmlsBoxView
import dev.forgesworn.kithmoot.mls.VmlsBoxes
import dev.forgesworn.kithmoot.mls.VmlsJoinAsk
import dev.forgesworn.kithmoot.mls.VmlsNeed
import dev.forgesworn.kithmoot.mls.VmlsBoxRoute
import dev.forgesworn.kithmoot.mls.VmlsPrivateInboxConsent
import dev.forgesworn.kithmoot.mls.VmlsInboxMode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import dev.forgesworn.kithmoot.ui.qr.QrScanner

/**
 * The "VMLS boxes" page (P3-03b-3 decisions 15 and 16): the
 * boxes that host the signed-in account's VMLS rooms, each reached by its
 * own ordinary pairing and granted to this phone's MLS device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VmlsBoxesScreen(boxes: VmlsBoxes, persona: String?, signer: () -> ParticipantSigner?, onBack: () -> Unit) {
    val state by boxes.state.collectAsState()
    LaunchedEffect(persona) { boxes.open(persona) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("VMLS boxes", style = cappedTitleStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "A VMLS room lives on a Bothy box you own. This phone pairs with the box, " +
                    "and your account grants this phone's MLS device there.",
                style = MaterialTheme.typography.bodyMedium,
            )
            val busy = state.busy
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            state.notice?.let { Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            if (persona == null) {
                Text("Sign in to pair a box.")
                return@Column
            }
            if (state.persona != persona) {
                CircularProgressIndicator()
                return@Column
            }
            state.needs.firstOrNull()?.let { need ->
                Heading("Not ready yet")
                Text(when (need) {
                    VmlsNeed.SIGN_IN -> "Sign in to pair a box."
                    VmlsNeed.WITNESS -> "First enrol this account's vault at its restore witness, in Settings. VMLS rooms need a vault your box confirms."
                    VmlsNeed.RENDEZVOUS -> "This account has no rendezvous key yet. Provision one from your signer in Settings."
                })
                TextButton({ boxes.open(persona) }, enabled = !busy) { Text("Check again") }
                return@Column
            }
            state.device?.let { device ->
                Column {
                    Text("This phone's MLS device", style = MaterialTheme.typography.labelMedium)
                    Text(short(device), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (state.boxes.isNotEmpty()) {
                KeeperInboxControls(boxes, persona, state.device, state.boxes, signer)
                Heading("Your boxes")
                state.boxes.forEach { box ->
                    BoxRow(box, busy, onCheck = { boxes.check(persona, box.box) }, onForget = {
                        signer()?.let { boxes.forget(it, box.box) }
                    })
                    PrivateKeeperInbox(boxes, persona, box, signer)
                }
                PublicKeeperInbox(boxes, persona, signer)
            }
            if (dev.forgesworn.kithmoot.BuildConfig.DEBUG && dev.forgesworn.kithmoot.BuildConfig.EXPERIMENTAL_INVITATION_MLS) ExperimentalInvitationControls(boxes, persona, signer)
            PairBox(busy) { code -> signer()?.let { boxes.pair(it, code) } }
            JoinRoom(busy) { link, code -> signer()?.let { boxes.join(it, link, code) } }
        }
    }
}

/** One action authorises only the selected persona/route while this screen
 * is foreground. It never reuses device-vault signing consent. */
@Composable
private fun PrivateKeeperInbox(boxes: VmlsBoxes, persona: String, box: VmlsBoxView, signer: () -> ParticipantSigner?) {
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val live = remember(persona, box.routeId) { AtomicBoolean(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    var consent by remember(persona, box.routeId) { mutableStateOf<VmlsPrivateInboxConsent?>(null) }
    var ask by remember(persona, box.routeId) { mutableStateOf<VmlsInboxMode?>(null) }
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    DisposableEffect(persona, box.routeId, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { live.set(false); consent?.withdraw(); ask = null }
            if (event == Lifecycle.Event.ON_START) live.set(true)
        }
        lifecycle.addObserver(observer)
        onDispose { live.set(false); consent?.withdraw(); lifecycle.removeObserver(observer) }
    }
    Text("Keeper inbox at ${box.name}: private checks read requests already held there. Member sending remains separate.", style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton({ ask = VmlsInboxMode.LATEST }, enabled = !working && box.routeId.isNotEmpty()) { Text("Check privately") }
        TextButton({ ask = VmlsInboxMode.RETAINED }, enabled = !working && box.routeId.isNotEmpty()) { Text("Older privately") }
    }
    if (working) TextButton({ consent?.withdraw() }) { Text("Stop private check") }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    ask?.let { mode -> AlertDialog(
        onDismissRequest = { ask = null },
        title = { Text("Identify to ${box.name} for this check?") },
        text = { Text("Account ${short(persona)} will authenticate and decrypt keeper requests on box ${short(box.box)} over its saved Link route. " +
            "The Bothy sees the account and recipient query, ciphertext, timing and volume. Link carriers can observe connection metadata. " +
            "Pairing grants no keeper authority. No public relay fallback is used. " +
            if (mode == VmlsInboxMode.RETAINED) "This resumes an older inclusive boundary; older history may remain incomplete." else "This checks a fresh page; retained older progress is unchanged.") },
        confirmButton = { TextButton({
            ask = null
            val actor = signer()?.takeIf { it.pubkey == persona } ?: return@TextButton
            val route = VmlsBoxRoute(persona, box.box, box.routeId, box.name)
            val permission = VmlsPrivateInboxConsent(route, live::get)
            consent = permission; working = true; message = null
            scope.launch {
                try { message = boxes.checkPrivateInbox(permission, actor, mode) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { message = error.message ?: "Private check unavailable." }
                finally { permission.withdraw(); if (consent === permission) consent = null; working = false }
            }
        }) { Text("Allow this check") } },
        dismissButton = { TextButton({ ask = null }) { Text("Cancel") } },
    ) }
}

@Composable
private fun PublicKeeperInbox(boxes: VmlsBoxes, persona: String, signer: () -> ParticipantSigner?) {
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val live = remember(persona) { AtomicBoolean(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    DisposableEffect(persona, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { live.set(false); job?.cancel() } }
        lifecycle.addObserver(observer)
        onDispose { live.set(false); job?.cancel(); lifecycle.removeObserver(observer) }
    }
    var ask by remember { mutableStateOf<VmlsInboxMode?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    Text("Public keeper checks reveal this account, recipient query, connection address and timing to directory/DM relays. They run only when selected.", style = MaterialTheme.typography.bodySmall)
    Row {
        TextButton({ ask = VmlsInboxMode.LATEST }) { Text("Check public relays") }
        TextButton({ ask = VmlsInboxMode.RETAINED }) { Text("Older public") }
    }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    ask?.let { mode -> AlertDialog(onDismissRequest = { ask = null }, title = { Text("Check public keeper relays?") },
        text = { Text("This reads the public DM relay directory and recipient inbox. A relay may request identification as this account. This action uses public relays.") },
        confirmButton = { TextButton({ ask = null; live.set(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)); job?.cancel(); signer()?.takeIf { it.pubkey == persona }?.let { actor -> job = scope.launch {
            try { message = boxes.checkPublicInbox(persona, actor, mode, live::get) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message = error.message ?: "Public check unavailable." }
        } } }) { Text("Check public relays") } }, dismissButton = { TextButton({ ask = null }) { Text("Cancel") } }) }
}

@Composable
private fun BoxRow(box: VmlsBoxView, busy: Boolean, onCheck: () -> Unit, onForget: () -> Unit) {
    var forgetting by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(box.name, style = MaterialTheme.typography.titleSmall)
        Text(short(box.box), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        Text(
            when (box.vmls) {
                true -> "Hosts VMLS rooms for this account."
                false -> "Does not take this account's grant: only the box's owner can host VMLS rooms on it."
                null -> "Not checked yet."
            } + if (box.rooms > 0) " ${box.rooms} room${if (box.rooms == 1) "" else "s"}." else "",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onCheck, enabled = !busy) { Text("Check") }
            if (box.rooms == 0) TextButton({ forgetting = true }, enabled = !busy) { Text("Forget") }
        }
    }
    if (forgetting) AlertDialog(
        onDismissRequest = { forgetting = false },
        title = { Text("Forget ${box.name}?") },
        text = { Text("This phone's grant at the box is withdrawn and its pairing removed. Pair again with a fresh code to use it.") },
        confirmButton = { TextButton({ forgetting = false; onForget() }) { Text("Forget") } },
        dismissButton = { TextButton({ forgetting = false }) { Text("Cancel") } },
    )
}

@Composable
private fun PairBox(busy: Boolean, onPair: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    Heading("Pair a box")
    Text("On your box, show a pairing code, then scan or paste it. It lasts ten minutes and works once.")
    if (scanning) {
        QrScanner(
            accept = { it.startsWith("bothy:") },
            onDecoded = { scanning = false; onPair(it) },
            prompt = "Allow the camera to scan the box's pairing code.",
            modifier = Modifier.fillMaxWidth().height(280.dp),
        )
        TextButton({ scanning = false }) { Text("Cancel scan") }
    } else {
        Button({ scanning = true }, enabled = !busy) { Text("Scan code") }
    }
    OutlinedTextField(code, { code = it }, Modifier.fillMaxWidth(), label = { Text("Or paste the code") }, singleLine = true)
    Button({ onPair(code); code = "" }, enabled = !busy && code.isNotBlank()) { Text("Pair") }
}

@Composable
private fun JoinRoom(busy: Boolean, onJoin: (String, String) -> Unit) {
    var link by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    Heading("Join a VMLS room")
    Text(
        "Paste the room's link. If this phone has not paired with the room's box yet, its keeper shows you a pairing code from the box: " +
            "paste that too. The keeper is asked, and you wait up to ten minutes for their answer.",
    )
    OutlinedTextField(link, { link = it }, Modifier.fillMaxWidth(), label = { Text("Room link") }, singleLine = true)
    OutlinedTextField(code, { code = it }, Modifier.fillMaxWidth(), label = { Text("Box pairing code, if asked") }, singleLine = true)
    Button({ onJoin(link, code); link = ""; code = "" }, enabled = !busy && link.isNotBlank()) { Text("Ask to join") }
}

/**
 * A guest's request to join the keeper's room (P3-03b-3 decision 18), naming
 * the box that will hold its messages and the device asking. 64 MiB is
 * Bothy's default share for one granted device (`max_grant_bytes`).
 */
@Composable
fun VmlsJoinDialog(ask: VmlsJoinAsk, onAnswer: (Boolean) -> Unit) {
    AlertDialog(
        // Answered only by a choice: the guest is waiting for it.
        onDismissRequest = { },
        title = { Text("Let this device join ${ask.room}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("It may hold up to 64 MiB on ${ask.boxName}.")
                Text("Box ${short(ask.box)}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Text("Account ${short(ask.guest)}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Text("Device ${short(ask.device)}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton({ onAnswer(true) }) { Text("Let in") } },
        dismissButton = { TextButton({ onAnswer(false) }) { Text("Decline") } },
    )
}

/**
 * The vault's consent ask (§6.2), once per account, device, box and kind of
 * request: an approval is kept by the vault. Dismissing it denies the
 * request it was asked for; the same ask comes back later.
 */
@Composable
fun VaultConsentDialog(scope: ConsentScope, boxName: String?, onAnswer: (ConsentDecision) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAnswer(ConsentDecision.Deny) },
        title = { Text(if (scope.method == MlsVault.SIGN_METHOD) "Sign for a VMLS room?" else "Talk to your box?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (scope.method == MlsVault.SIGN_METHOD) "Let this phone's MLS device sign its place in VMLS rooms on this box? Allowing is kept."
                    else "Let this phone's MLS device sign its requests to this box for VMLS rooms? Allowing is kept.",
                )
                // The real short id first, on its own line, then the box's name as it calls itself: a crafted name cannot imitate the id (D1 R6, L4).
                Text("Box ${short(scope.homeBox)}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                if (!boxName.isNullOrBlank()) Text("Named: $boxName", style = MaterialTheme.typography.bodySmall)
                Text("Device ${short(scope.device)}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton({ onAnswer(ConsentDecision.Approve) }) { Text("Allow") } },
        dismissButton = { TextButton({ onAnswer(ConsentDecision.Deny) }) { Text("Deny") } },
    )
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { heading() })
}

private fun short(hex: String) = "${hex.take(8)}…${hex.takeLast(8)}"
