package dev.forgesworn.kithmoot.ui.start

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.mls.VmlsBoxView
import dev.forgesworn.kithmoot.mls.VmlsMemberView
import dev.forgesworn.kithmoot.mls.VmlsRoomState
import dev.forgesworn.kithmoot.mls.VmlsRoomView

/**
 * Debug builds' VMLS rooms on the home rooms list (P3-03b-3 decision 21),
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
            Text("VMLS rooms (debug)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
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
    onRemove: (String) -> Unit,
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
                        val ended = room.state == VmlsRoomState.REMOVED || room.state == VmlsRoomState.LAPSED
                        if (room.keeper && room.canSend) DropdownMenuItem(text = { Text(if (room.invite) "New invite link" else "Invite link") }, onClick = { menu = false; onInvite() })
                        if (room.keeper && room.invite) DropdownMenuItem(text = { Text("Retire invite link") }, onClick = { menu = false; onRetire() })
                        if (ended) DropdownMenuItem(text = { Text("Forget room") }, onClick = { menu = false; onForget() })
                        else if (room.keeper) DropdownMenuItem(text = { Text("Close room") }, onClick = { menu = false; confirming = Confirm.Close })
                        else DropdownMenuItem(text = { Text("Leave room") }, onClick = { menu = false; confirming = Confirm.Leave })
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
                    if (room.keeper) "Close the room to end it." else "Leave the room, then ask its keeper for a new link.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (quiet) Text("Paused while a Tor-only room is open.", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            Members(room.members, removable = room.keeper && room.canSend) { confirming = Confirm.Remove(it) }
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
            Row(Modifier.fillMaxWidth().imePadding().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    draft, { draft = it }, Modifier.weight(1f), enabled = room.canSend,
                    label = { Text(if (room.canSend) "Message" else "Sending is off") },
                )
                TextButton({ onSay(draft); draft = "" }, enabled = room.canSend && draft.isNotBlank()) { Text("Send") }
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
            "The room ends on this phone. A guest whose revocation the box did not confirm keeps its place on the box until its grant lapses.",
            "Finish", { confirming = null }) { confirming = null; onClose(true) }
        is Confirm.Remove -> ConfirmDialog(
            "Remove this member?",
            "Device ${short(ask.member.device)} is removed at the next change the box accepts; the room tries again until it is.",
            "Remove", { confirming = null }) { confirming = null; onRemove(ask.member.leaf) }
    }
}

private sealed class Confirm {
    data object Leave : Confirm()
    data object Close : Confirm()
    data object Force : Confirm()
    data class Remove(val member: VmlsMemberView) : Confirm()
}

@Composable
private fun Members(members: List<VmlsMemberView>, removable: Boolean, onRemove: (VmlsMemberView) -> Unit) {
    if (members.isEmpty()) {
        Text("No one else is in this room yet.", style = MaterialTheme.typography.bodySmall)
        return
    }
    Text("Members", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
    members.forEach { member ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(short(member.identity), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                if (member.invited) Text("invited", style = MaterialTheme.typography.labelSmall)
            }
            if (removable) TextButton({ onRemove(member) }) { Text("Remove") }
        }
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
    VmlsRoomState.RETRYING -> "retrying"
    VmlsRoomState.CHECKING -> "checking with the box"
    VmlsRoomState.STOPPED -> "stopped"
    VmlsRoomState.REMOVED -> "you were removed"
    VmlsRoomState.LAPSED -> "invitation lapsed"
    VmlsRoomState.CLOSING -> "closing"
}

private fun short(hex: String) = "${hex.take(8)}…${hex.takeLast(8)}"
