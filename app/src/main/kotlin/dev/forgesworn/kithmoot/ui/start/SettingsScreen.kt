package dev.forgesworn.kithmoot.ui.start

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.relay.RelayChoice
import dev.forgesworn.kithmoot.ui.StartState
import dev.forgesworn.kithmoot.ui.theme.LocalTextSizeSetting
import dev.forgesworn.kithmoot.ui.theme.TextSize

/**
 * Settings, full screen (design-home-rooms.md Q5): account, text size,
 * notifications and connections, each a section rather than scattered
 * across home and an avatar menu (F5).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: StartState,
    signIn: AccountActions,
    accountSettings: AccountSettingsActions,
    accountRooms: AccountRoomActions,
    relayChoices: List<RelayChoice>,
    onWebAppAddressChanged: (String) -> Boolean,
    notificationSettings: @Composable () -> Unit,
    onBack: () -> Unit,
    /** Debug builds only (P3-03b-2): null hides the row. */
    onRestoreWitness: (() -> Unit)? = null,
    /** In-app updates: version, the automatic-check switch and any update on offer. */
    updateSettings: (@Composable () -> Unit)? = null,
) {
    var relaysOpen by remember { mutableStateOf(false) }
    var profileOpen by remember { mutableStateOf(false) }
    var siteOpen by remember { mutableStateOf(false) }
    val issues = state.relayHealth.values.count { health ->
        health.connection.contains("failed", true) || health.connection.contains("retry", true) || health.connection == "Reconnecting" ||
            health.read.contains("refused", true) || health.read.contains("authentication", true) || health.write.contains("refused", true) || health.write == "No acknowledgement"
    }
    val accountSaved = state.account?.let { account -> state.savedRooms.filter { it.account == account.pubkey } }.orEmpty()
    val importableCount = accountSaved.count { saved -> state.roomBookmarks.rooms.none { it.roomId == saved.id } }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("You", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { heading() })
                AccountSection(state, signIn, enabled = !state.busy, showHeading = false)
                if (state.account != null) {
                    TextButton({ profileOpen = true; accountSettings.loadProfile() }) { Text("Edit profile") }
                    AccountSyncSection(state.roomBookmarks, importableCount, enabled = !state.busy && !state.roomSyncBusy, accountRooms)
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Text size", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { heading() })
                val textSetting = LocalTextSizeSetting.current
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    TextSize.entries.forEachIndexed { index, size ->
                        SegmentedButton(
                            selected = size == textSetting.size,
                            onClick = { textSetting.set(size) },
                            shape = SegmentedButtonDefaults.itemShape(index, TextSize.entries.size),
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text(size.label) }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Notifications & sound", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { heading() })
                notificationSettings()
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Connections", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { heading() })
                SettingsRow("Nostr relays", if (issues > 0) "${issues} ${if (issues == 1) "needs" else "need"} attention" else null) { relaysOpen = true }
                onRestoreWitness?.let { SettingsRow("Restore witness", "Debug build: enrol this account's vault at your Bothy box", it) }
                SettingsRow("KithMoot site", runCatching { dev.forgesworn.kithmoot.session.WebAppAddress.parse(state.webAppAddress).origin.removePrefix("https://") }.getOrDefault(state.webAppAddress)) { siteOpen = true }
            }

            updateSettings?.let {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Updates", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.semantics { heading() })
                    it()
                }
            }

            Text(
                "Saved room access and identities are encrypted on this device and excluded from backups. " +
                    "Room messages travel through relays encrypted. Forgetting a room does not delete those messages.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (relaysOpen) ModalBottomSheet(onDismissRequest = { relaysOpen = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.94f).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
                TextButton({ relaysOpen = false }, enabled = !state.profileBusy) { Text("Done") }
            }
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RelayEditor(state, relayChoices, inRoom = false, accountSettings)
            }
        }
    }
    if (profileOpen) ModalBottomSheet(onDismissRequest = { profileOpen = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.94f).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
                TextButton({ profileOpen = false }, enabled = !state.profileBusy) { Text("Done") }
            }
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ProfileEditorFields(state, accountSettings)
            }
        }
    }
    if (siteOpen) SiteAddressDialog(state.webAppAddress, onWebAppAddressChanged, onDismiss = { siteOpen = false })
}

@Composable
private fun SettingsRow(label: String, supporting: String?, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            supporting?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
