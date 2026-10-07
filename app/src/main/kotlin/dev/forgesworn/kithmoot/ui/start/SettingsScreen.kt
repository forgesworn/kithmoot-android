package dev.forgesworn.kithmoot.ui.start

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.relay.RelayChoice
import dev.forgesworn.kithmoot.ui.StartState
import dev.forgesworn.kithmoot.ui.theme.LocalTextSizeSetting
import dev.forgesworn.kithmoot.ui.settings.SettingsNavRow
import dev.forgesworn.kithmoot.ui.settings.SettingsNote
import dev.forgesworn.kithmoot.ui.settings.SettingsRadioGroup
import dev.forgesworn.kithmoot.ui.settings.SettingsSection
import dev.forgesworn.kithmoot.ui.settings.SettingsSheet
import dev.forgesworn.kithmoot.ui.theme.TextSize
import dev.forgesworn.kithmoot.ui.theme.cappedTitleStyle

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
    /** P3-03b-2: null hides the row. */
    onRestoreWitness: (() -> Unit)? = null,
    onVmlsBoxes: (() -> Unit)? = null,
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
            title = { Text("Settings", style = cappedTitleStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() }) },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SettingsSection("You") {
                Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AccountSection(state, signIn, enabled = !state.busy, showHeading = false)
                    if (state.account != null) {
                        TextButton({ profileOpen = true; accountSettings.loadProfile() }) { Text("Edit profile") }
                        AccountSyncSection(state.roomBookmarks, importableCount, enabled = !state.busy && !state.roomSyncBusy, accountRooms)
                    }
                }
            }

            SettingsSection("Text size") {
                val textSetting = LocalTextSizeSetting.current
                SettingsRadioGroup(TextSize.entries, textSetting.size, { it.label }, textSetting.set)
                Text("Messages, names and menus will look like this.", style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                SettingsNote("Changes the words in KithMoot only, not the rest of your phone.")
                if (LocalContext.current.resources.configuration.fontScale > 1f) {
                    SettingsNote("This adds to your phone's own text size, up to twice the standard size.")
                }
            }

            SettingsSection("Notifications and calls") { notificationSettings() }

            SettingsSection("Connections") {
                SettingsNavRow("Nostr relays", if (issues > 0) "${issues} ${if (issues == 1) "needs" else "need"} attention" else null) { relaysOpen = true }
                SettingsNavRow("KithMoot site", runCatching { dev.forgesworn.kithmoot.session.WebAppAddress.parse(state.webAppAddress).origin.removePrefix("https://") }.getOrDefault(state.webAppAddress)) { siteOpen = true }
            }

            // VMLS rooms and the restore witness need a Bothy box the person runs; most people have none, so they are
            // kept apart and named as a preview rather than offered among the everyday connections.
            if (onRestoreWitness != null || onVmlsBoxes != null) SettingsSection("Your Bothy box (preview)") {
                Text(
                    "For people who run their own Bothy box: end-to-end encrypted VMLS rooms hosted on it, and its restore witness.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                onRestoreWitness?.let { SettingsNavRow("Restore witness", "Enrol this account's vault at your Bothy box", onClick = it) }
                onVmlsBoxes?.let { SettingsNavRow("VMLS boxes", "Pair the boxes that host this account's VMLS rooms", onClick = it) }
            }

            updateSettings?.let { SettingsSection("Updates") { it() } }

            SettingsNote(
                "Saved room access and identities are encrypted on this device and excluded from backups. " +
                    "Room messages travel through relays encrypted. Forgetting a room does not delete those messages.",
            )
        }
    }

    if (relaysOpen) SettingsSheet(title = "Relays", onDone = { relaysOpen = false }, doneEnabled = !state.profileBusy) {
        RelayEditor(state, relayChoices, inRoom = false, accountSettings)
    }
    if (profileOpen) SettingsSheet(title = "Edit public profile", onDone = { profileOpen = false }, doneEnabled = !state.profileBusy) {
        ProfileEditorFields(state, accountSettings)
    }
    if (siteOpen) SiteAddressDialog(state.webAppAddress, onWebAppAddressChanged, onDismiss = { siteOpen = false })
}
