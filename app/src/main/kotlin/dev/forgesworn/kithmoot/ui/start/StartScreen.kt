package dev.forgesworn.kithmoot.ui.start

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import dev.forgesworn.kithmoot.R
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.account.shortNpub
import dev.forgesworn.kithmoot.storage.SavedRoomSummary
import dev.forgesworn.kithmoot.ui.StartState
import dev.forgesworn.kithmoot.ui.theme.LocalTextSizeSetting
import dev.forgesworn.kithmoot.ui.theme.TextSize

@Composable
fun StartScreen(
    state: StartState,
    onRoomNameChanged: (String) -> Unit,
    onJoinUrlChanged: (String) -> Unit,
    onRelaysChanged: (String) -> Unit,
    onPersistentGroupChanged: (Boolean) -> Unit,
    onStartRoom: () -> Unit,
    onJoin: () -> Unit,
    onReopen: (String) -> Unit,
    onForget: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onProject: (String, String) -> Unit,
    onPairBothy: (String, String) -> Unit = { _, _ -> },
    onDisconnectBothy: (String) -> Unit = {},
    onRetryStorage: () -> Unit,
    onResetStorage: () -> Unit,
    modifier: Modifier = Modifier,
    account: AccountActions = AccountActions.None,
    onAddOfferedCard: () -> Unit = {},
    onDismissCardOffer: () -> Unit = {},
    onCircleBoxesChanged: (String) -> Unit = {},
    onWebAppAddressChanged: (String) -> Boolean = { false },
    onHomeTabChanged: (String) -> Unit = {},
    projects: ProjectActions = ProjectActions(),
) {
    var relaysShown by remember { mutableStateOf(false) }
    var siteShown by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var forgetting by remember { mutableStateOf<SavedRoomSummary?>(null) }
    var renaming by remember { mutableStateOf<SavedRoomSummary?>(null) }
    var filing by remember { mutableStateOf<SavedRoomSummary?>(null) }
    var filedAs by remember { mutableStateOf("") }
    var pairingRoom by remember { mutableStateOf<SavedRoomSummary?>(null) }
    var pairingCode by remember { mutableStateOf("") }
    var disconnectingRoom by remember { mutableStateOf<SavedRoomSummary?>(null) }
    // The project tab in view, remembered on the device so the phone opens
    // on the project the person was last working in.
    val prefs = LocalContext.current.getSharedPreferences("kithmoot.display", android.content.Context.MODE_PRIVATE)
    var projectTab by remember { mutableStateOf(prefs.getString("projectTab", "") ?: "") }
    var renamed by remember { mutableStateOf("") }
    var resetting by remember { mutableStateOf(false) }
    val enabled = !state.busy && !state.loadingRooms && !state.storageError

    Box(modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().systemBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Image(painterResource(R.drawable.brand_artwork), contentDescription = null,
                        modifier = Modifier.size(80.dp).clip(RoundedCornerShape(18.dp)))
                    Text("KithMoot", style = MaterialTheme.typography.displaySmall)
                }
                Text(if (state.savedRooms.isEmpty()) "Make room for a conversation." else "Pick up the conversation.",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.busy || state.loadingRooms) {
                LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "Loading rooms" })
            }
            if (state.storageError) {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.errorContainer) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Saved rooms are unavailable", style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        Text("Your saved data has been kept. Try again before deleting anything.")
                        OutlinedButton(onRetryStorage, enabled = !state.busy && !state.loadingRooms) { Text("Try again") }
                        TextButton({ resetting = true }, enabled = !state.busy && !state.loadingRooms) { Text("Delete saved rooms…") }
                    }
                }
            } else if (state.error != null) {
                Text(state.error, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            state.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }

            // A contact card opened as a link: said for what it is, and kept
            // only on a press. Nothing is kept by merely opening the link.
            val offer = state.cardOffer
            if (offer != null) {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("This is a contact card", style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite })
                        val who = offer.name?.let { "From $it" } ?: "From a person with no name on their card"
                        val boxes = when (offer.boxes) { 0 -> "no box"; 1 -> "one box"; else -> "${offer.boxes} boxes" }
                        Text(if (offer.added) "${offer.name ?: "They"} ${if (offer.name != null) "is" else "are"} in your contacts on this phone. Their card is kept on this phone."
                            else "$who, naming $boxes. Add the card to keep their details on this phone.")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!offer.added) Button(onAddOfferedCard, Modifier.heightIn(min = 48.dp)) { Text("Add to contacts") }
                            OutlinedButton(onDismissCardOffer, Modifier.heightIn(min = 48.dp)) { Text(if (offer.added) "Done" else "Not now") }
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for ((key, label) in listOf("chats" to "Chats", "projects" to "Projects")) {
                    FilterChip(state.homeTab == key, { onHomeTabChanged(key) }, label = { Text(label) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = "$label tab" })
                }
            }
            if (state.homeTab == "projects") ProjectsPanel(state, projects)
            else {
            if (state.savedRooms.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Your rooms", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    Text("Saved on this device. Reopen as the same person.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("Find a saved room") },
                        trailingIcon = { if (query.isNotEmpty()) TextButton({ query = "" }) { Text("Clear") } })
                    // Projects, as a row of tabs, once any room has been filed under
                    // one. "All" is first and is the tab a phone with no projects
                    // never needs to see.
                    val projects = state.savedRooms.mapNotNull { it.project }.distinct().sorted()
                    val tab = if (projectTab.isNotEmpty() && projectTab != "\u0000none" && projectTab !in projects) "" else projectTab
                    if (projects.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val tabs = listOf("" to "All") + projects.map { it to it } + listOf("\u0000none" to "No project")
                            for ((value, label) in tabs) {
                                val chosen = value == tab
                                val modifier = Modifier.heightIn(min = 44.dp).semantics { contentDescription = "$label rooms" + if (chosen) ", selected" else "" }
                                val pick = { projectTab = value; prefs.edit().putString("projectTab", value).apply() }
                                if (chosen) Button(pick, modifier) { Text(label) } else OutlinedButton(pick, modifier) { Text(label) }
                            }
                        }
                    }
                    val found = state.savedRooms
                        .filter { tab.isEmpty() || (if (tab == "\u0000none") it.project == null else it.project == tab) }
                        .filter { it.name.contains(query.trim(), true) || it.id.contains(query.trim(), true) }
                    if (found.isEmpty()) Text(if (tab.isEmpty()) "No rooms match your search." else "No rooms in this project match.", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    for (room in found) {
                        key(room.id) {
                            OutlinedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp)) {
                                    TextButton({ onReopen(room.id) }, enabled = enabled,
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                        Text(room.name, style = MaterialTheme.typography.titleMedium)
                                    }
                                    // Whose room this is: the signed-in account's name when it is
                                    // that account, otherwise both ends of the npub it was joined as.
                                    val who = room.account?.let { pubkey ->
                                        val me = state.account
                                        if (me != null && me.pubkey == pubkey) (me.profile?.name ?: me.name ?: "you") else shortNpub(pubkey)
                                    }
                                    Text(listOfNotNull(who?.let { "As $it" }, room.project, if (room.secondary) "Paired device" else "Main device").joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton({ renaming = room; renamed = room.name }, enabled = enabled,
                                            modifier = Modifier.semantics { contentDescription = "Rename ${room.name}" }) { Text("Rename") }
                                        TextButton({ filing = room; filedAs = room.project.orEmpty() }, enabled = enabled,
                                            modifier = Modifier.semantics { contentDescription = "Project for ${room.name}" }) { Text("Project") }
                                        if (room.id in state.linkConnectedRooms) {
                                            TextButton({ disconnectingRoom = room }, enabled = enabled && room.account == state.account?.pubkey,
                                                modifier = Modifier.semantics { contentDescription = "Disconnect Bothy from ${room.name}" }) { Text("Disconnect Bothy") }
                                        } else TextButton({ pairingRoom = room; pairingCode = "" }, enabled = enabled && room.account == state.account?.pubkey,
                                            modifier = Modifier.semantics { contentDescription = "Connect Bothy to ${room.name}" }) { Text("Connect Bothy") }
                                        TextButton({ forgetting = room }, enabled = enabled,
                                            modifier = Modifier.semantics { contentDescription = "Forget ${room.name}" }) { Text("Forget") }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            pairingRoom?.let { room ->
                AlertDialog(onDismissRequest = { pairingRoom = null }, title = { Text("Connect Bothy") },
                    text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Paste the Bothy pairing code. Bothy will learn your public identity, ${state.account?.npub ?: "the signed-in account"}, for this room. KithMoot will switch only after its authenticated relay is ready. This permission is saved on this device; disconnecting later restores the current relays.")
                        OutlinedTextField(pairingCode, { pairingCode = it }, Modifier.fillMaxWidth(), label = { Text("Bothy pairing code") }, minLines = 3)
                    } },
                    confirmButton = { Button({ onPairBothy(room.id, pairingCode); pairingRoom = null }, enabled = enabled && pairingCode.isNotBlank()) { Text("Pair and switch") } },
                    dismissButton = { TextButton({ pairingRoom = null }) { Text("Cancel") } })
            }
            disconnectingRoom?.let { room ->
                AlertDialog(onDismissRequest = { disconnectingRoom = null }, title = { Text("Disconnect Bothy?") },
                    text = { Text("${room.name} will return to the relays it used before Bothy. Its local Link route and consent will be removed.") },
                    confirmButton = { Button({ onDisconnectBothy(room.id); disconnectingRoom = null }, enabled = enabled) { Text("Disconnect") } },
                    dismissButton = { TextButton({ disconnectingRoom = null }) { Text("Cancel") } })
            }

            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Start a room", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    OutlinedTextField(state.roomName, onRoomNameChanged, Modifier.fillMaxWidth(), enabled = enabled,
                        label = { Text("Room name (optional)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { if (enabled) onStartRoom() }))
                    Text("People can join while everyone is away.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onStartRoom, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Start a room") }
                    Text("The name is yours to recognise this room on this device.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Have an invitation?", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    OutlinedTextField(state.joinUrl, onJoinUrlChanged, Modifier.fillMaxWidth(), enabled = enabled,
                        label = { Text("Invitation link") }, maxLines = 3,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { if (enabled) onJoin() }))
                    OutlinedButton(onJoin, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Join room") }
                    Text("Only share invitations with people you want in the room. Your camera and microphone start off.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            }
            AccountSection(state, account, enabled)
            // Text size: one tap, remembered, applied everywhere. Above the
            // relay settings because it is the one everybody may want.
            val textSetting = LocalTextSizeSetting.current
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Text size", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    for (size in TextSize.entries) {
                        val chosen = size == textSetting.size
                        val label: @Composable RowScope.() -> Unit = { Text(size.label) }
                        val modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = "${size.label} text" + if (chosen) ", selected" else "" }
                        if (chosen) Button({ }, modifier, content = label)
                        else OutlinedButton({ textSetting.set(size) }, modifier, content = label)
                    }
                }
            }
            TextButton({ relaysShown = true }, enabled = enabled) { Text("Relay settings") }
            TextButton({ siteShown = true }, enabled = enabled && !state.signingIn) { Text("Site settings") }
            Text("Saved room access and identities are encrypted on this device and excluded from backups. " +
                "Room messages travel through relays encrypted. Forgetting a room does not delete those messages.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (siteShown) {
        SiteAddressDialog(state.webAppAddress, onWebAppAddressChanged, onDismiss = { siteShown = false })
    }

    if (relaysShown) {
        AlertDialog(onDismissRequest = { relaysShown = false }, title = { Text("Relay settings") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(state.relays, onRelaysChanged, Modifier.fillMaxWidth(), enabled = enabled,
                        label = { Text("Relays, one per line") }, minLines = 2, maxLines = 5)
                    Text("Used for new rooms and the next project sync. Saved rooms keep their own relays.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(state.circleBoxes, onCircleBoxesChanged, Modifier.fillMaxWidth().semantics { contentDescription = "Boxes of my circle" }, enabled = enabled,
                        label = { Text("Boxes of my circle") }, minLines = 2, maxLines = 5)
                    Text("Relays your circle's box answers on, one per line, as its keeper named them to you. A message that goes only to these shows as sheltered. A contact card alone does not confirm a message relay.", style = MaterialTheme.typography.bodySmall)
                }
            }, confirmButton = { TextButton({ relaysShown = false }) { Text("Done") } })
    }

    forgetting?.let { room ->
        AlertDialog(onDismissRequest = { forgetting = null }, title = { Text("Forget ${room.name}?") },
            text = { Text("Remove this room and your identity for it from this device. Creator controls saved here will be lost. " +
                "You will need an invitation or pairing link to return. Other members keep the room.") },
            confirmButton = { TextButton({ forgetting = null; onForget(room.id) }) { Text("Forget room") } },
            dismissButton = { TextButton({ forgetting = null }) { Text("Keep room") } })
    }
    filing?.let { room ->
        val existing = state.savedRooms.mapNotNull { it.project }.distinct().sorted()
        AlertDialog(onDismissRequest = { filing = null }, title = { Text("Project for ${room.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("A label on this device, so rooms for one piece of work sit together. Leave it empty to take the room out of its project.")
                    OutlinedTextField(filedAs, { filedAs = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Project") })
                    if (existing.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (name in existing) OutlinedButton({ filedAs = name }) { Text(name) }
                    }
                }
            },
            confirmButton = { TextButton({ filing = null; onProject(room.id, filedAs) }) { Text("Save") } },
            dismissButton = { TextButton({ filing = null }) { Text("Cancel") } })
    }

    renaming?.let { room ->
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text("Name on this device") },
            text = { OutlinedTextField(renamed, { renamed = it.take(80) }, label = { Text("Room name") }, singleLine = true) },
            confirmButton = { TextButton({ renaming = null; onRename(room.id, renamed) }, enabled = renamed.isNotBlank()) { Text("Save name") } },
            dismissButton = { TextButton({ renaming = null }) { Text("Cancel") } })
    }
    if (resetting) {
        AlertDialog(onDismissRequest = { resetting = false }, title = { Text("Delete all saved rooms?") },
            text = { Text("Permanently remove every saved room and identity from this device. You may lose access to rooms you created. " +
                "Other members and relay messages are unaffected. This cannot be undone.") },
            confirmButton = { TextButton({ resetting = false; onResetStorage() }) { Text("Delete saved rooms") } },
            dismissButton = { TextButton({ resetting = false }) { Text("Keep saved data") } })
    }
}
