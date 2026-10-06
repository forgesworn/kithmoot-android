package dev.forgesworn.kithmoot.ui.start

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import dev.forgesworn.kithmoot.ui.StartState
import dev.forgesworn.kithmoot.ui.settings.SettingsSheet

/** "Already on Nostr? Sign in", from home's foot link (design-home-rooms.md
 *  section 4). Closes itself once signed in, same as the in-room account
 *  menu's own sign-in sheet. */
@Composable
fun SignInSheet(state: StartState, actions: AccountActions, onDismiss: () -> Unit) {
    LaunchedEffect(state.account?.pubkey) { if (state.account != null) onDismiss() }
    SettingsSheet(title = accountHeading(state), onDone = onDismiss, doneEnabled = !state.profileBusy) {
        AccountSection(state, actions, enabled = !state.busy, showHeading = false)
    }
}
