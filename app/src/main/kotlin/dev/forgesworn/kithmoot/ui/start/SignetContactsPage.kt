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

/**
 * Linking Signet's contact directory: where it stands, then Link, the
 * pairing request and its confirmation code. People's Add a contact opens
 * it; the contacts themselves are listed in People and their invitations in
 * Chats. Names stay local to this account; nothing queries a public index.
 */
@Composable
fun SignetLinkSection(state: StartState, actions: SignetContactActions) {
    val directory = state.signetContacts
    val clipboard = LocalClipboardManager.current
    Text(when (directory.status) {
        "ready" -> "Your Signet contacts are linked."
        "stale" -> "Showing saved contact names. Refresh Signet to update them."
        "waiting" -> "Waiting for Signet to share your contacts."
        "revoked" -> "Contact access was revoked in Signet."
        "lapsed" -> "Your saved contact names have expired. Link Signet again."
        "unavailable" -> "Your saved contact permissions could not be read. Open People again to retry."
        else -> "Link your Signet contacts. Signing in alone does not share your address book."
    }, style = MaterialTheme.typography.bodyMedium)
    if (directory.truncated) Text("Signet shared part of your directory. Some contacts may be missing.")
    state.contactsError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    directory.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (!state.contactsBusy) {
        Button(actions.link, Modifier.heightIn(min = 48.dp), enabled = state.account != null && directory.status != "unavailable") { Text("Link Signet contacts") }
        TextButton(actions.refresh, Modifier.heightIn(min = 48.dp), enabled = state.account != null) { Text("Refresh contacts and inbox") }
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
}
