package dev.forgesworn.kithmoot.ui.start

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.relay.RelayChoice
import dev.forgesworn.kithmoot.ui.StartState
import dev.forgesworn.kithmoot.ui.room.ProfileAvatar
import dev.forgesworn.kithmoot.ui.settings.DmRelaysPage
import dev.forgesworn.kithmoot.ui.settings.ProfilePage
import dev.forgesworn.kithmoot.ui.settings.RelaysPage
import dev.forgesworn.kithmoot.ui.settings.SettingsSheet
import dev.forgesworn.kithmoot.ui.settings.relayIssueCount
import dev.forgesworn.kithmoot.ui.settings.rememberRelayEditor

data class AccountSettingsActions(
    val loadProfile: () -> Unit = {},
    val publishProfile: (Map<String, String>) -> Unit = {},
    val saveRelays: (List<RelayChoice>) -> String? = { null },
    val publishRelays: () -> Unit = {},
    val loadDmRelays: () -> Unit = {},
    val publishDmRelays: (List<String>) -> Unit = {},
    val retrySync: () -> Unit = {},
    val circleBoxes: (String) -> Unit = {},
    val signOut: () -> Unit = {},
    /** Leaves the open room, for signing in: an account cannot replace the identity a room was joined with. */
    val leaveRoom: () -> Unit = {},
)

/** One account entry point on home, projects and inside a room. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountMenu(state: StartState, choices: List<RelayChoice>, inRoom: Boolean,
    signIn: AccountActions, actions: AccountSettingsActions,
    showProfilePicture: Boolean = true,
    /** The open room is Tor-only: it never uses an account, signed in or not. */
    torOnlyRoom: Boolean = false,
    notificationSettings: @Composable () -> Unit = {},
) {
    var menu by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf<String?>(null) }
    var leaving by remember { mutableStateOf(false) }
    val account = state.account
    LaunchedEffect(account?.pubkey) { if (account != null && page == "signin") page = null }
    val issues = relayIssueCount(state.relayHealth.values)
    Box {
        if (account == null) TextButton({ menu = true }, enabled = !state.busy) {
            Icon(Icons.Outlined.AccountCircle, null); Spacer(Modifier.width(6.dp)); Text("Sign in")
        } else IconButton({ menu = true }, Modifier.size(48.dp).semantics {
            contentDescription = "Account menu for ${account.shownName}" + (accountNotInUse(inRoom, torOnlyRoom)?.let { ". $it" } ?: "")
            role = Role.Button
            onClick(label = "Open account menu") { menu = true; true }
        }) { ProfileAvatar(account.pubkey, account.shownName, account.profile.takeIf { showProfilePicture }, Modifier.size(36.dp)) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (account == null) DropdownMenuItem(text = { Text("Sign in with Nostr") }, onClick = { menu = false; page = "signin" })
            if (account != null) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).widthIn(max = 280.dp)) {
                    Text(account.shownName, style = MaterialTheme.typography.titleMedium)
                    Text(account.short, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    accountNotInUse(inRoom, torOnlyRoom)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                HorizontalDivider()
                if (accountActionsOffered(inRoom, torOnlyRoom)) {
                    DropdownMenuItem(text = { Text("Edit profile") }, onClick = { menu = false; page = "profile"; actions.loadProfile() })
                    DropdownMenuItem(text = { Text("Relays for private conversations") }, onClick = { menu = false; page = "dmrelays"; actions.loadDmRelays() })
                }
            }
            DropdownMenuItem(text = { Text(if (issues == 0) "Relays" else "Relays · $issues issue(s)") }, onClick = { menu = false; page = "relays" })
            if (accountActionsOffered(inRoom, torOnlyRoom)) {
                DropdownMenuItem(text = { Text("Sync chats and projects") }, onClick = { menu = false; actions.retrySync() }, enabled = account != null && !state.roomSyncBusy)
            }
            DropdownMenuItem(text = { Text("Notifications & sound") }, onClick = { menu = false; page = "notifications" })
            HorizontalDivider()
            if (account != null) DropdownMenuItem(text = { Text("Sign out") }, onClick = { menu = false; leaving = true }, enabled = !state.profileBusy && !state.roomSyncBusy && !state.projectsBusy)
        }
    }
    page?.let { current ->
        SettingsSheet(
            title = when (current) {
                "signin" -> "Sign in with Nostr"
                "profile" -> "Edit public profile"
                "notifications" -> "Notifications and calls"
                "relays" -> "Relays"
                else -> "Relays for private conversations"
            },
            onDone = { page = null }, doneEnabled = !state.profileBusy, padded = current == "signin",
        ) {
            when (current) {
                // In a room the sheet used to open with every control disabled and no
                // reason. Say why, and offer the way there, as the web client does.
                "signin" -> if (inRoom) {
                    Text(signInFromRoom(torOnlyRoom))
                    Button({ page = null; actions.leaveRoom() }, Modifier.heightIn(min = 48.dp)) { Text("Leave to sign in") }
                } else AccountSection(state, signIn, !state.busy, showHeading = false)
                "profile" -> ProfilePage(state, actions)
                "notifications" -> notificationSettings()
                "relays" -> RelaysPage(state, rememberRelayEditor(choices, account?.pubkey), inRoom, actions)
                "dmrelays" -> DmRelaysPage(state, actions)
            }
        }
    }
    if (leaving) SignOutDialog(inRoom, onConfirm = { leaving = false; actions.signOut() }, onDismiss = { leaving = false })
}

/**
 * "Sign out?" before the account goes, from Settings and from the room menu
 * alike. Inside a room it also says the room is left, since signing out ends
 * the call and discards the draft.
 */
@Composable
internal fun SignOutDialog(inRoom: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (inRoom) "Leave this room and sign out?" else "Sign out?") },
        text = { Text(if (inRoom) "This ends your call and discards this room's unsent draft. Saved rooms remain on this phone."
            else "Your saved rooms stay on this phone. Rooms in your account come back when you sign in again.") },
        confirmButton = { TextButton(onConfirm) { Text("Sign out") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

/** Why signing in means leaving the room first, for the kind of room it is. */
internal fun signInFromRoom(torOnlyRoom: Boolean): String =
    if (torOnlyRoom) "This Tor-only room never uses an account: you are in it with just a name, a separate identity. " +
        "Leave the room to sign in with your usual account. Your saved rooms stay on this phone."
    else "You are in this room with just a name: a separate identity, not your Nostr account. " +
        "Leave the room to sign in with your usual account. Leaving ends any call you are on; your saved rooms stay on this phone."

/**
 * Whether the menu offers the actions that reach the account's own relays:
 * Edit profile, Relays for private conversations and Sync. Each opens a
 * clearnet relay pool, so inside a Tor-only room they are not offered: the
 * account's traffic would sit beside the room's Tor traffic, from the same
 * app at the same moment.
 */
internal fun accountActionsOffered(inRoom: Boolean, torOnlyRoom: Boolean): Boolean = !(inRoom && torOnlyRoom)

/** Said under a signed-in account inside a room that does not use it; null where the account is in use or may be. */
internal fun accountNotInUse(inRoom: Boolean, torOnlyRoom: Boolean): String? =
    if (inRoom && torOnlyRoom) "Not used in this Tor-only room" else null
