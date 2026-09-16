package dev.forgesworn.kithmoot.ui.start

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.account.ProfileMetadata
import dev.forgesworn.kithmoot.relay.RelayChoice
import dev.forgesworn.kithmoot.ui.StartState
import dev.forgesworn.kithmoot.ui.room.ProfileAvatar
import kotlinx.serialization.json.JsonPrimitive

data class AccountSettingsActions(
    val loadProfile: () -> Unit = {},
    val publishProfile: (Map<String, String>) -> Unit = {},
    val saveRelays: (List<RelayChoice>) -> String? = { null },
    val publishRelays: () -> Unit = {},
    val retrySync: () -> Unit = {},
    val circleBoxes: (String) -> Unit = {},
    val signOut: () -> Unit = {},
)

/** One account entry point on home, projects and inside a room. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountMenu(state: StartState, choices: List<RelayChoice>, inRoom: Boolean,
    signIn: AccountActions, actions: AccountSettingsActions,
    showProfilePicture: Boolean = true,
    notificationSettings: @Composable () -> Unit = {},
) {
    var menu by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf<String?>(null) }
    var leaving by remember { mutableStateOf(false) }
    val account = state.account
    LaunchedEffect(account?.pubkey) { if (account != null && page == "signin") page = null }
    val issues = state.relayHealth.values.count { health ->
        health.connection.contains("failed", true) || health.connection.contains("retry", true) || health.connection == "Reconnecting" ||
            health.read.contains("refused", true) || health.read.contains("authentication", true) || health.write.contains("refused", true) || health.write == "No acknowledgement"
    }
    Box {
        if (account == null) TextButton({ menu = true }, enabled = !state.busy) {
            Icon(Icons.Outlined.AccountCircle, null); Spacer(Modifier.width(6.dp)); Text("Sign in")
        } else IconButton({ menu = true }, Modifier.size(48.dp).semantics {
            contentDescription = "Account menu for ${account.shownName}"
            role = Role.Button
            onClick(label = "Open account menu") { menu = true; true }
        }) { ProfileAvatar(account.pubkey, account.shownName, account.profile.takeIf { showProfilePicture }, Modifier.size(36.dp)) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (account == null) DropdownMenuItem(text = { Text("Sign in with Nostr") }, onClick = { menu = false; page = "signin" })
            if (account != null) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).widthIn(max = 280.dp)) {
                    Text(account.shownName, style = MaterialTheme.typography.titleMedium)
                    Text(account.short, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Edit profile") }, onClick = { menu = false; page = "profile"; actions.loadProfile() })
            }
            DropdownMenuItem(text = { Text(if (issues == 0) "Relays" else "Relays · $issues issue(s)") }, onClick = { menu = false; page = "relays" })
            DropdownMenuItem(text = { Text("Sync chats and projects") }, onClick = { menu = false; actions.retrySync() }, enabled = account != null && !state.roomSyncBusy)
            DropdownMenuItem(text = { Text("Notifications & sound") }, onClick = { menu = false; page = "notifications" })
            HorizontalDivider()
            if (account != null) DropdownMenuItem(text = { Text("Sign out") }, onClick = { menu = false; leaving = true }, enabled = !state.profileBusy && !state.roomSyncBusy && !state.projectsBusy)
        }
    }
    if (page != null) ModalBottomSheet(onDismissRequest = { if (!state.profileBusy) page = null },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.94f).imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
            TextButton({ page = null }, enabled = !state.profileBusy) { Text("Done") }
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (page) {
                "signin" -> AccountSection(state, signIn, !inRoom && !state.busy)
                "profile" -> {
                    Text("Edit public profile", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                    Text("These details are public on Nostr. Publishing updates your kind-0 profile for other clients too.", style = MaterialTheme.typography.bodySmall)
                    if (state.profileBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    val metadata = state.profileMetadata
                    if (metadata != null) key(account?.pubkey, state.profileBaseId) {
                        var values by remember { mutableStateOf(ProfileMetadata.fields.associateWith {
                            (metadata[it] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content.orEmpty()
                        }) }
                        val labels = listOf("name" to "Username", "display_name" to "Display name", "about" to "About",
                            "picture" to "Profile image URL", "banner" to "Banner image URL", "website" to "Website",
                            "nip05" to "Nostr address (NIP-05)", "lud16" to "Lightning address")
                        for ((field, label) in labels) OutlinedTextField(values[field].orEmpty(), { values = values + (field to it) },
                            Modifier.fillMaxWidth(), label = { Text(label) }, enabled = !state.profileBusy,
                            singleLine = field != "about", minLines = if (field == "about") 2 else 1)
                        Button({ actions.publishProfile(values) }, enabled = !state.profileBusy, modifier = Modifier.fillMaxWidth()) { Text("Publish profile") }
                    }
                    state.profileMessage?.let { Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                    TextButton(actions.loadProfile, enabled = !state.profileBusy) { Text("Reload profile") }
                }
                "notifications" -> notificationSettings()
                "relays" -> RelayEditor(state, choices, inRoom, actions)
            }
        }
        }
    }
    if (leaving) AlertDialog(onDismissRequest = { leaving = false }, title = { Text(if (inRoom) "Leave this room and sign out?" else "Sign out?") },
        text = { Text(if (inRoom) "This ends your call and discards this room's unsent draft. Saved rooms remain on this phone." else "Your saved rooms remain on this phone. Account rooms will be available when you sign in again.") },
        confirmButton = { TextButton({ leaving = false; actions.signOut() }) { Text("Sign out") } },
        dismissButton = { TextButton({ leaving = false }) { Text("Cancel") } })
}

@Composable
private fun RelayEditor(state: StartState, choices: List<RelayChoice>, inRoom: Boolean, actions: AccountSettingsActions) {
    var draft by remember { mutableStateOf(choices) }
    var adding by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(true) }
    var publish by remember { mutableStateOf(false) }
    val editable = !inRoom && !state.busy && !state.profileBusy
    Text("Relays", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
    Text("Read receives events. Write publishes them. Turn both off to disable a relay on this device.", style = MaterialTheme.typography.bodySmall)
    if (inRoom) Text("Leave the room to change connections. You can still check their status here.")
    for ((index, relay) in draft.withIndex()) key(relay.url, index) {
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(relay.url, style = MaterialTheme.typography.titleSmall)
                val health = state.relayHealth.entries.firstOrNull { it.key.removeSuffix("/") == relay.url.removeSuffix("/") }?.value
                Text(if (!relay.read && !relay.write) "Disabled" else health?.connection ?: "Not connected",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (relay.read) Text("Read: ${health?.read ?: "Not checked"}", style = MaterialTheme.typography.bodySmall)
                if (relay.write) Text("Write: ${health?.write ?: "Not checked"}", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(relay.read, { value -> draft = draft.toMutableList().also { it[index] = relay.copy(read = value) }; saved = false },
                        enabled = editable, modifier = Modifier.semantics { contentDescription = "Read from ${relay.url}" }); Text("Read")
                    Checkbox(relay.write, { value -> draft = draft.toMutableList().also { it[index] = relay.copy(write = value) }; saved = false },
                        enabled = editable, modifier = Modifier.semantics { contentDescription = "Write to ${relay.url}" }); Text("Write")
                    Spacer(Modifier.weight(1f))
                    TextButton({ draft = draft.filterIndexed { i, _ -> i != index }; saved = false }, enabled = editable,
                        modifier = Modifier.semantics { contentDescription = "Remove relay ${relay.url}" }) { Text("Remove") }
                }
            }
        }
    }
    OutlinedTextField(adding, { adding = it }, Modifier.fillMaxWidth(), label = { Text("Add relay URL") },
        placeholder = { Text("wss://relay.example") }, singleLine = true, enabled = editable)
    TextButton({ draft = draft + RelayChoice(adding.trim()); adding = ""; saved = false }, enabled = editable && adding.isNotBlank() && draft.size < 16) { Text("Add relay") }
    Button({ message = actions.saveRelays(draft); saved = message == null; if (saved) message = "Relay choices saved on this device. Account sync is reconnecting." },
        enabled = editable && !saved, modifier = Modifier.fillMaxWidth()) { Text("Save relay choices") }
    message?.let { Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
    Text("Account sync uses these connections. Saved rooms keep their invitation relays; your read/write choices apply to matching relays when you reopen a room.", style = MaterialTheme.typography.bodySmall)
    OutlinedButton(actions.retrySync, enabled = editable && saved) { Text("Reconnect and retry sync") }
    if (state.account != null) {
        TextButton({ publish = true }, enabled = editable && saved) { Text("Publish public relay list") }
        Text("Optional: publish these choices as your Nostr relay list (NIP-65). Disabled relays are omitted.", style = MaterialTheme.typography.bodySmall)
        state.profileMessage?.let { Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
    }
    var advanced by remember { mutableStateOf(false) }
    TextButton({ advanced = !advanced }) { Text(if (advanced) "Hide circle relay settings" else "Circle relay settings") }
    if (advanced) {
        OutlinedTextField(state.circleBoxes, actions.circleBoxes, Modifier.fillMaxWidth(), enabled = editable,
            label = { Text("Verified circle relays, one per line") }, minLines = 2)
        Text("Only mark relays that belong to your circle. This controls which delivery is labelled sheltered.", style = MaterialTheme.typography.bodySmall)
    }
    if (publish) AlertDialog(onDismissRequest = { publish = false }, title = { Text("Publish your relay list?") },
        text = { Text("These relay addresses and read/write choices will be public on Nostr and available to other clients.") },
        confirmButton = { TextButton({ publish = false; actions.publishRelays() }) { Text("Publish relay list") } },
        dismissButton = { TextButton({ publish = false }) { Text("Cancel") } })
}
