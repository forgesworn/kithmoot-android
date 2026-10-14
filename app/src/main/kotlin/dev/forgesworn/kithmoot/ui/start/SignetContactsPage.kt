package dev.forgesworn.kithmoot.ui.start

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.ui.StartState

data class SignetContactActions(
    val link: () -> Unit = {}, val refresh: () -> Unit = {}, val cancel: () -> Unit = {}, val confirm: () -> Unit = {},
    val message: (String) -> Unit = {}, val open: (String) -> Unit = {}, val dismiss: (String) -> Unit = {}, val retry: () -> Unit = {},
)

/** Names stay local to this account. Searching never queries a public index. */
@Composable
fun SignetContactsPage(state: StartState, actions: SignetContactActions, inRoom: Boolean, onOpening: () -> Unit) {
    var query by remember(state.account?.pubkey) { mutableStateOf("") }
    var requested by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val directory = state.signetContacts
    val clipboard = LocalClipboardManager.current
    fun choose(message: Boolean, value: String) {
        if (inRoom) requested = message to value
        else { onOpening(); if (message) actions.message(value) else actions.open(value) }
    }
    Text("Find a contact and start a private conversation, with chat, calls and collaboration.")
    Text(when (directory.status) {
        "ready" -> "Your Signet contacts are linked."
        "stale" -> "Showing saved contact names. Refresh Signet to update them."
        "waiting" -> "Waiting for Signet to share your contacts."
        "revoked" -> "Contact access was revoked in Signet."
        "lapsed" -> "Your saved contact names have expired. Link Signet again."
        "unavailable" -> "Your saved contact permissions could not be read. Reopen Contacts to retry."
        else -> "Link your Signet contacts. Signing in alone does not share your address book."
    }, style = MaterialTheme.typography.bodyMedium)
    if (directory.truncated) Text("Signet shared part of your directory. Some contacts may be missing.")
    state.contactsError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    directory.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (!state.contactsBusy) {
        Button(actions.link, enabled = state.account != null && directory.status != "unavailable") { Text("Link Signet contacts") }
        TextButton(actions.refresh, enabled = state.account != null) { Text("Refresh contacts and inbox") }
    }
    state.contactPairingUri?.let { uri ->
        Text("In Signet, share names, keys and blocked contacts. Return here for the confirmation code.")
        dev.forgesworn.kithmoot.ui.qr.QrCode(uri, "Signet contact request", Modifier.size(220.dp))
        TextButton({ clipboard.setText(AnnotatedString(uri)) }) { Text("Copy contact request") }
        state.contactPairingCode?.let { code ->
            Text("Enter this code in Signet:")
            Text(code, style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Monospace)
            Text("After Signet accepts it, confirm below to use the contact grant.")
            Button(actions.confirm) { Text("I confirmed the code in Signet") }
        }
        TextButton(actions.cancel) { Text("Cancel contact linking") }
    }
    OutlinedTextField(query, { query = it }, label = { Text("Find a contact") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    val duplicateNames = directory.contacts.mapNotNull { it.name }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    val rows = directory.contacts.filter { (it.name ?: "").contains(query, true) || dev.forgesworn.kithmoot.account.npubOf(it.pubkey).contains(query, true) || it.pubkey.contains(query, true) }
    if (rows.isEmpty()) Text(if (directory.contacts.isEmpty()) "No contacts to show." else "No matching contacts.")
    rows.forEach { person ->
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text(person.name ?: person.pubkey.take(12) + "…", style = MaterialTheme.typography.titleMedium)
            if (person.name == null || person.name in duplicateNames) Text(dev.forgesworn.kithmoot.account.npubOf(person.pubkey), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            Button({ choose(true, person.pubkey) }, enabled = state.contactInbox.ready && !state.busy && !state.contactsBusy) { Text("Message") }
        }
    }
    HorizontalDivider()
    Text("Private invitations", style = MaterialTheme.typography.titleMedium)
    state.contactInbox.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (state.contactInbox.pending > 0) {
        Text("${state.contactInbox.pending} invitation deliveries waiting. Delivery is confirmed when a relay accepts it.")
        Button(actions.retry, enabled = state.contactInbox.ready) { Text("Retry invitation delivery") }
    }
    if (state.contactInbox.invitations.isEmpty()) Text(if (state.contactInbox.ready) "No private invitations." else "Opening your private inbox…")
    state.contactInbox.invitations.sortedWith(compareBy({ it.sentAt }, { it.room })).forEach { invitation ->
        val peer = if (invitation.sender == state.account?.pubkey) invitation.recipient else invitation.sender
        val name = directory.contacts.firstOrNull { it.pubkey == peer }?.name ?: peer.take(12) + "…"
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text("Private conversation with $name")
            TextButton({ choose(false, invitation.id) }, enabled = !state.busy) { Text("Open conversation") }
            TextButton({ actions.dismiss(invitation.id) }) { Text("Dismiss invitation") }
        }
    }
    requested?.let { choice ->
        AlertDialog(onDismissRequest = { requested = null }, title = { Text("Open a private conversation?") },
            text = { Text("This leaves the current room and ends any call you are on. The room stays saved.") },
            confirmButton = { TextButton({ requested = null; onOpening(); if (choice.first) actions.message(choice.second) else actions.open(choice.second) }) { Text("Open conversation") } },
            dismissButton = { TextButton({ requested = null }) { Text("Cancel") } })
    }
}
