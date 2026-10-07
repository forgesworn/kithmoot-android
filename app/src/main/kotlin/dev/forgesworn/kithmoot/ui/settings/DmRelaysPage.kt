package dev.forgesworn.kithmoot.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.ui.StartState
import dev.forgesworn.kithmoot.ui.start.AccountSettingsActions

/**
 * The account's list of relays for private conversations (NIP-17): where
 * one-to-one conversations with this person are kept. Mirrors the web
 * client's Settings, Connections. A body with no heading of its own.
 */
@Composable
internal fun DmRelaysPage(state: StartState, actions: AccountSettingsActions) {
    SettingsNote("Private conversations that you start, or that others start with you, are kept on these relays. " +
        "Choose relays that keep messages and won't turn you away: your own, or one you pay for. Up to six.")
    val current = state.dmRelays
    if (current == null || state.profileBusy) {
        LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp).semantics { contentDescription = "Loading your list" })
    }
    if (current != null) key(state.account?.pubkey, current) {
        var draft by remember { mutableStateOf(current.joinToString("\n")) }
        if (current.isEmpty()) SettingsNote("You have no list yet. Until you save one, private conversations use the relays of the room they start from.")
        OutlinedTextField(draft, { draft = it }, Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            label = { Text("Relays, one per line") }, placeholder = { Text("wss://relay.example") }, minLines = 3, enabled = !state.profileBusy)
        Button({ actions.publishDmRelays(draft.split(Regex("[\\s,]+")).map { it.trim() }.filter { it.isNotEmpty() }) },
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp).heightIn(min = 48.dp),
            enabled = !state.profileBusy) { Text("Save to your Nostr account") }
    }
    state.profileMessage?.let { SettingsNote(it, live = true) }
    SettingsNote("This list is public, as the Nostr standard for it requires. Anyone can see which relays you use for private conversations, but not what is said on them or with whom.")
}
