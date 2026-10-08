package dev.forgesworn.kithmoot.ui.start

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.mls.VmlsBoxView
import dev.forgesworn.kithmoot.mls.VmlsMemberView
import dev.forgesworn.kithmoot.mls.VmlsRemovalPlan
import dev.forgesworn.kithmoot.mls.VmlsRemovalView
import dev.forgesworn.kithmoot.mls.VmlsRoomExit
import dev.forgesworn.kithmoot.mls.VmlsRoomState
import dev.forgesworn.kithmoot.mls.exit
import dev.forgesworn.kithmoot.mls.VmlsRoomView

/**
 * VMLS rooms on the home rooms list (P3-03b-3 decision 21),
 * beside saved rooms and apart from them: their own store, their own screen.
 * A new one is offered only on a box that answers with VMLS (decision 16).
 */
@Composable
fun VmlsRoomsSection(
    rooms: List<VmlsRoomView>,
    boxes: List<VmlsBoxView>,
    busy: Boolean,
    onOpen: (String) -> Unit,
    onCreate: (box: String, name: String) -> Unit,
) {
    val hosts = boxes.filter { it.vmls == true }
    if (rooms.isEmpty() && hosts.isEmpty()) return
    var creating by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("VMLS rooms", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
            if (hosts.isNotEmpty()) TextButton({ creating = true }, enabled = !busy) { Text("New") }
        }
        rooms.forEach { room ->
            ListItem(
                headlineContent = { Text(room.name) },
                supportingContent = { Text("${if (room.keeper) "Keeper" else "Guest"} · ${stateLabel(room.state)} · ${room.boxName}") },
                modifier = Modifier.clickable { onOpen(room.session) },
            )
        }
    }
    if (creating && hosts.isNotEmpty()) NewVmlsRoomDialog(hosts, onDismiss = { creating = false }, onCreate = { box, name -> creating = false; onCreate(box, name) })
}

@Composable
private fun NewVmlsRoomDialog(hosts: List<VmlsBoxView>, onDismiss: () -> Unit, onCreate: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var box by remember { mutableStateOf(hosts.first().box) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New VMLS room") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(80) }, Modifier.fillMaxWidth(), label = { Text("Room name") }, singleLine = true)
                if (hosts.size > 1) hosts.forEach { host ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { box = host.box }) {
                        RadioButton(box == host.box, { box = host.box })
                        Text(host.name)
                    }
                } else Text("On ${hosts.first().name}.")
            }
        },
        confirmButton = { TextButton({ onCreate(box, name.trim()) }, enabled = name.isNotBlank()) { Text("Create") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

/**
 * One VMLS room (decisions 20, 22, 23 and 24): its state in words, its
 * members, its messages while the app ran, and what can be done: send,
 * invite and retire the link, remove a member (the keeper), leave (a guest),
 * close (the keeper), or forget a room that ended.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VmlsRoomScreen(
    room: VmlsRoomView?,
    error: String?,
    quiet: Boolean,
    onBack: () -> Unit,
    onSay: (String) -> Unit,
    onInvite: () -> Unit,
    onRetire: () -> Unit,
    onRemove: (target: String, person: Boolean, compromised: Boolean) -> Unit,
    plan: suspend (target: String, person: Boolean, compromised: Boolean) -> VmlsRemovalPlan?,
    onRetryRemoval: (key: String) -> Unit,
    onLeave: () -> Unit,
    onClose: (force: Boolean) -> Unit,
    onForget: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf<Confirm?>(null) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(room?.name ?: "VMLS room") },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                if (room != null && room.state == VmlsRoomState.CLOSING && room.keeper) Box {
                    // A close a revocation held up: retried, or finished without it (decision 24).
                    IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Finish closing") }, onClick = { menu = false; onClose(false) })
                        DropdownMenuItem(text = { Text("Finish without the box's confirmation") }, onClick = { menu = false; confirming = Confirm.Force })
                    }
                } else if (room != null && room.state != VmlsRoomState.CLOSING) Box {
                    IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(menu, { menu = false }) {
                        if (room.keeper && room.canSend) DropdownMenuItem(text = { Text(if (room.invite) "New invite link" else "Invite link") }, onClick = { menu = false; onInvite() })
                        if (room.keeper && room.invite) DropdownMenuItem(text = { Text("Retire invite link") }, onClick = { menu = false; onRetire() })
                        when (room.exit) {
                            VmlsRoomExit.CLOSE -> DropdownMenuItem(text = { Text("Close room") }, onClick = { menu = false; confirming = Confirm.Close })
                            VmlsRoomExit.FORGET -> DropdownMenuItem(text = { Text("Forget room") }, onClick = { menu = false; onForget() })
                            VmlsRoomExit.LEAVE -> DropdownMenuItem(text = { Text("Leave room") }, onClick = { menu = false; confirming = Confirm.Leave })
                        }
                    }
                }
            },
        )
    }) { padding ->
        if (room == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text("This room is no longer kept on this phone.") }
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "${if (room.keeper) "You keep this room" else "Guest"} on ${room.boxName} · ${stateLabel(room.state)}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            room.reason?.let { reason ->
                Text(reason, color = MaterialTheme.colorScheme.error)
                // Decision 23: no self-repair yet; the exits that exist.
                if (room.state == VmlsRoomState.STOPPED) Text(
                    // A stopped room takes no Remove; its close revokes the grants (D1 R1).
                    if (room.keeper) "Close the room to end it: that also revokes at the box the grants of guests in none of your other rooms there. " +
                        "If one may be compromised and is in another of your rooms on this box, remove it there as compromised."
                    else "Leave the room, then ask its keeper for a new link.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (quiet) Text("Paused while a Tor-only room is open.", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            Members(room.members, removable = room.keeper && room.canSend) { confirming = it }
            if (room.removals.isNotEmpty()) Removals(room.removals, onRetryRemoval)
            HorizontalDivider()
            val list = rememberLazyListState()
            LaunchedEffect(room.messages.size) { if (room.messages.isNotEmpty()) list.animateScrollToItem(room.messages.size - 1) }
            LazyColumn(Modifier.weight(1f), state = list, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (room.messages.isEmpty()) item {
                    Text("Messages show here while the app is open. They are not kept yet.", style = MaterialTheme.typography.bodySmall)
                }
                items(room.messages) { message ->
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (message.mine) Alignment.End else Alignment.Start) {
                        if (!message.mine) Text(short(message.sender), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                        Surface(
                            color = if (message.mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.medium,
                        ) { Text(message.body, Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) }
                    }
                }
            }
            // Not saved to instance state: a draft is message plaintext, and belongs to its room.
            var draft by remember(room.session) { mutableStateOf("") }
            // Held while a compromised device's Remove is not yet witnessed (P3-05b part 3); the send checks it again.
            val sendable = room.canSend && !room.held
            Row(Modifier.fillMaxWidth().imePadding().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    draft, { draft = it }, Modifier.weight(1f), enabled = sendable,
                    label = { Text(if (room.held) "Held until the compromised device is removed" else if (room.canSend) "Message" else "Sending is off") },
                )
                TextButton({ onSay(draft); draft = "" }, enabled = sendable && draft.isNotBlank()) { Text("Send") }
            }
        }
    }
    when (val ask = confirming) {
        null -> Unit
        Confirm.Leave -> ConfirmDialog(
            "Leave ${room?.name}?",
            "This phone's part in the room ends and the room is forgotten here. The keeper removes you when your place lapses.",
            "Leave", { confirming = null }) { confirming = null; onLeave() }
        Confirm.Close -> ConfirmDialog(
            "Close ${room?.name}?",
            "The invite link is retired, guests in none of your other rooms on this box lose their place there, and the room ends on this phone.",
            "Close", { confirming = null }) { confirming = null; onClose(false) }
        Confirm.Force -> ConfirmDialog(
            "Finish without the box?",
            "The room ends on this phone. A guest whose revocation the box did not confirm keeps its place on the box until a later try is confirmed or its grant lapses, and cannot be let into another of your rooms there until then.",
            "Finish", { confirming = null }) { confirming = null; onClose(true) }
        is Confirm.Remove -> {
            // What the removal touches, read before it is confirmed (P3-05b): every device and each one's grant here.
            var compromised by remember(ask) { mutableStateOf(false) }
            // Keyed on the choice too, so a changed choice shows no plan, and cannot be confirmed, until its own is read.
            var planned by remember(ask, compromised) { mutableStateOf<VmlsRemovalPlan?>(null) }
            LaunchedEffect(ask, compromised) { planned = plan(ask.target, ask.person, compromised) }
            val touched = planned?.devices?.joinToString("\n") { "Device ${it.device}: ${it.grant}" } ?: "Reading what it touches…"
            AlertDialog(
                onDismissRequest = { confirming = null },
                title = { Text(if (ask.person) "Remove this person?" else "Remove this device?") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "${ask.label} is removed from the room's next epoch, once the box accepts the change; the room tries again until it is. " +
                                "The MLS Remove and each box grant are separate, and each shows its own state under Removals.",
                        )
                        Row(Modifier.fillMaxWidth().toggleable(compromised, role = Role.Checkbox) { compromised = it }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(compromised, null)
                            Text("It may be compromised", Modifier.padding(start = 8.dp))
                        }
                        if (compromised) Text(
                            "Each grant of yours it holds is revoked at once, without the usual grace, as the plan below says, " +
                                "so it may never see that it was removed. Your new messages and joins here wait until the Remove " +
                                "is applied and witnessed; any already on their way go first. " +
                                "Until then it may still read what was already sent in this epoch.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(touched, style = MaterialTheme.typography.bodySmall)
                    }
                },
                // Not before the plan is read: what it touches is shown before it is confirmed.
                confirmButton = { TextButton({ confirming = null; onRemove(ask.target, ask.person, compromised) }, enabled = planned != null) { Text("Remove") } },
                dismissButton = { TextButton({ confirming = null }) { Text("Cancel") } },
            )
        }
    }
}

private sealed class Confirm {
    data object Leave : Confirm()
    data object Close : Confirm()
    data object Force : Confirm()
    /** A device's [target] leaf, or with [person] a person's identity. */
    data class Remove(val target: String, val person: Boolean, val label: String) : Confirm()
}

/** Members by person: each device removable on its own, and a person with several devices as a whole too. */
@Composable
private fun Members(members: List<VmlsMemberView>, removable: Boolean, onRemove: (Confirm.Remove) -> Unit) {
    if (members.isEmpty()) {
        Text("No one else is in this room yet.", style = MaterialTheme.typography.bodySmall)
        return
    }
    Text("Members", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
    members.groupBy { it.identity }.forEach { (identity, devices) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(short(identity), Modifier.weight(1f), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            if (removable && devices.size > 1) TextButton({ onRemove(Confirm.Remove(identity, true, "Person ${short(identity)}, with ${devices.size} devices,")) }) { Text("Remove person") }
        }
        devices.forEach { member ->
            Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Device ${short(member.device)}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                    if (member.invited) Text("invited", style = MaterialTheme.typography.labelSmall)
                }
                if (removable) TextButton({ onRemove(Confirm.Remove(member.leaf, false, "Device ${short(member.device)}")) }) { Text("Remove") }
            }
        }
    }
}

/**
 * The keeper's removals (contract §7, P3-05b): each component's own state,
 * then the engine's permitted claim for them, word for word, or nothing
 * more when no claim fits.
 */
@Composable
private fun Removals(removals: List<VmlsRemovalView>, onRetry: (key: String) -> Unit) {
    Text("Removals", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
    removals.forEach { removal ->
        Column(Modifier.semantics(mergeDescendants = true) {}) {
            Text(removal.target, style = MaterialTheme.typography.bodySmall)
            Text(removal.mls, style = MaterialTheme.typography.labelSmall)
            removal.credential?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            removal.grants.forEach { Text(it, style = MaterialTheme.typography.labelSmall) }
            removal.claim?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            removal.hold?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
        }
        removal.retry?.let { key -> TextButton({ onRetry(key) }) { Text("Try the Remove again") } }
    }
}

@Composable
private fun ConfirmDialog(title: String, text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

private fun stateLabel(state: VmlsRoomState): String = when (state) {
    VmlsRoomState.READY -> "ready"
    VmlsRoomState.JOINING -> "waiting to be added"
    VmlsRoomState.SENDING -> "sending"
    VmlsRoomState.LIMITED -> "waiting on the box"
    VmlsRoomState.RETRYING -> "retrying"
    VmlsRoomState.CHECKING -> "checking with the box"
    VmlsRoomState.STOPPED -> "stopped"
    VmlsRoomState.REMOVED -> "you were removed"
    VmlsRoomState.LAPSED -> "invitation lapsed"
    VmlsRoomState.CLOSING -> "closing"
}

private fun short(hex: String) = "${hex.take(8)}…${hex.takeLast(8)}"
