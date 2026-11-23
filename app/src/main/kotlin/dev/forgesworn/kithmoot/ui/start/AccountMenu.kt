package dev.forgesworn.kithmoot.ui.start

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.relay.RelayChoice
import dev.forgesworn.kithmoot.ui.StartState
import dev.forgesworn.kithmoot.ui.settings.DmRelaysPage
import dev.forgesworn.kithmoot.ui.settings.ProfilePage
import dev.forgesworn.kithmoot.ui.settings.RelaysPage
import dev.forgesworn.kithmoot.ui.settings.SettingsSheet
import dev.forgesworn.kithmoot.ui.settings.relayIssueCount
import dev.forgesworn.kithmoot.ui.settings.rememberRelayEditor

data class AccountSettingsActions(
    val contacts: SignetContactActions = SignetContactActions(),
    val loadProfile: () -> Unit = {},
    val publishProfile: (Map<String, String>) -> Unit = {},
    val saveRelays: (List<RelayChoice>) -> String? = { null },
    val roomRelayAuthentication: (String, Long, Boolean) -> String? = { _, _, _ -> "Relay authentication is unavailable." },
    val publishRelays: () -> Unit = {},
    val loadDmRelays: () -> Unit = {},
    val publishDmRelays: (List<String>) -> Unit = {},
    val retrySync: () -> Unit = {},
    val circleBoxes: (String) -> Unit = {},
    val signOut: () -> Unit = {},
    /** Leaves the open room, for signing in: an account cannot replace the identity a room was joined with. */
    val leaveRoom: () -> Unit = {},
)

/** The room's account options, anchored to its More button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountMenu(expanded: Boolean, onDismiss: () -> Unit, state: StartState, choices: List<RelayChoice>, inRoom: Boolean,
    signIn: AccountActions, actions: AccountSettingsActions,
    /** The open room is Tor-only: it never uses an account, signed in or not. */
    torOnlyRoom: Boolean = false,
    notificationSettings: @Composable () -> Unit = {},
) {
    var page by remember { mutableStateOf<String?>(null) }
    var leaving by remember { mutableStateOf(false) }
    var choosingPersona by remember { mutableStateOf<PersonaChoice?>(null) }
    val personaActions = signIn.personaActions.takeUnless { torOnlyRoom }
    val account = state.account
    LaunchedEffect(account?.pubkey) { if (account != null && page == "signin") page = null }
    LaunchedEffect(expanded) { if (expanded) personaActions?.refreshSigners?.invoke() }
    val issues = relayIssueCount(state.relayHealth.values)
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (account == null) DropdownMenuItem(text = { Text("Sign in with Nostr") }, onClick = { onDismiss(); page = "signin" })
        else {
            CurrentPersona(account, state.personas.any { it.pubkey != account.pubkey && it.personaName == account.personaName },
                accountNotInUse(inRoom, torOnlyRoom))
        }
        if (personaActions != null) {
            PersonaMenuItems(state, !state.busy && !state.signingIn, personaActions) { choice ->
                onDismiss()
                if (choice is PersonaChoice.Saved && !inRoom) personaActions.switch(choice.persona.pubkey, false)
                else choosingPersona = choice
            }
        }
        if (account != null) {
            if (accountActionsOffered(inRoom, torOnlyRoom)) {
                DropdownMenuItem(text = { Text("Edit profile") }, onClick = { onDismiss(); page = "profile"; actions.loadProfile() })
                DropdownMenuItem(text = { Text("Nostr inbox relays") }, onClick = { onDismiss(); page = "dmrelays"; actions.loadDmRelays() })
                DropdownMenuItem(text = { Text("Sync chats and projects") }, onClick = { onDismiss(); actions.retrySync() },
                    enabled = !state.roomSyncBusy)
            }
        }
        DropdownMenuItem(text = { Text(if (issues == 0) "Relays" else "Relays · $issues issue(s)") }, onClick = { onDismiss(); page = "relays" })
        DropdownMenuItem(text = { Text("Notifications & sound") }, onClick = { onDismiss(); page = "notifications" })
        if (account != null) {
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Sign out") }, onClick = { onDismiss(); leaving = true },
                enabled = !state.profileBusy && !state.roomSyncBusy && !state.projectsBusy && !state.signingIn)
        }
    }
    if (personaActions != null) choosingPersona?.let {
        PersonaChoiceDialog(it, inRoom, personaActions) { choosingPersona = null }
    }
    page?.let { current ->
        SettingsSheet(
            title = when (current) {
                "signin" -> "Sign in with Nostr"
                "profile" -> "Edit public profile"
                "notifications" -> "Notifications"
                "relays" -> "Relays"
                else -> "Nostr inbox relays"
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
