package dev.forgesworn.kithmoot.ui.start

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.ui.StartState
import dev.forgesworn.kithmoot.ui.room.ProfileAvatar

/** What the start screen can do about the account. Grouped so the screen's parameter list stays readable. */
class AccountActions(
    val onRefreshSigners: () -> Unit,
    val onSignInWithApp: (String) -> Unit,
    val onSignInWithSignet: () -> Unit,
    val onSignInWithBunker: (String) -> Unit,
    val onSignInWithKey: (String) -> Unit,
    val onCancelSignIn: () -> Unit,
    val onSignOut: () -> Unit,
    val onDismissError: () -> Unit,
) {
    companion object {
        val None = AccountActions({}, {}, {}, {}, {}, {}, {}, {})
    }
}

/**
 * The account, on the start screen: who this phone is signed in as, or the
 * way to sign in. Mirrors the web client's "Your Nostr account" card: the
 * name and picture from the person's kind 0, both ends of the npub, and the
 * signer the key is with.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSection(state: StartState, actions: AccountActions, enabled: Boolean) {
    var choosing by remember { mutableStateOf(false) }
    val account = state.account

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (account != null) "Your Nostr account" else "Keep your rooms with you",
            style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        if (account != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ProfileAvatar(account.pubkey, account.name, account.profile, Modifier.size(44.dp))
                Column(Modifier.weight(1f)) {
                    Text(account.shownName, style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { contentDescription = "Public key ${account.npub}" })
                    // With no name, the line above already is the npub; printing it twice reads as two keys.
                    if (account.shownName != account.short) {
                        Text(account.short, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            Text(when (account.method) {
                "nip55" -> "Signing with ${account.signerLabel ?: "a signer app"} on this phone. Rooms you open are joined as this account."
                "bunker" -> "Signing through your remote signer. Rooms you open are joined as this account; the first signature in a room needs your signer to be reachable."
                else -> "Signing with a key kept in this app's encrypted vault. Rooms you open are joined as this account."
            }, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(actions.onSignOut, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Sign out") }
        } else {
            Text("Sign in as yourself, with your Nostr profile and the public key your agents recognise. " +
                "Rooms you open are joined as that account. Without it, each room gets its own key on this phone.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.signingIn) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Waiting for your signer…", Modifier.weight(1f))
                    TextButton(actions.onCancelSignIn) { Text("Cancel") }
                }
            } else {
                Button({ actions.onRefreshSigners(); choosing = true }, enabled = enabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Sign in with Nostr") }
            }
        }
        state.signInError?.let { error ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                TextButton(actions.onDismissError) { Text("Dismiss") }
            }
        }
    }

    if (choosing) {
        ModalBottomSheet(onDismissRequest = { choosing = false }) {
            SignInChoices(state, actions) { choosing = false }
        }
    }
}

@Composable
private fun SignInChoices(state: StartState, actions: AccountActions, done: () -> Unit) {
    var advanced by remember { mutableStateOf(false) }
    var bunker by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Sign in to KithMoot", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        Text("Choose where your key lives. It never leaves your signer.", color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (state.signers.isEmpty()) {
            Text("No signer app found on this phone. My Signet, Amber or Cambium would appear here once installed.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        for (signer in state.signers) {
            Button({ done(); actions.onSignInWithApp(signer.packageName) }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Use ${signer.label}")
            }
        }
        Text("A signer app keeps your key on this phone and answers with one tap, even offline.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        TextButton({ advanced = !advanced }) { Text(if (advanced) "Hide advanced" else "Advanced") }
        if (advanced) {
            OutlinedButton({ done(); actions.onSignInWithSignet() }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Signet in a browser")
            }
            Text("For a Signet that lives in a browser rather than the My Signet app. It opens mysignet.app, you approve there, and it pairs with this app over a relay. That browser tab has to stay open to sign.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            OutlinedTextField(bunker, { bunker = it }, Modifier.fillMaxWidth(), label = { Text("Bunker link") },
                placeholder = { Text("bunker://…?relay=wss://…&secret=…") }, maxLines = 3)
            OutlinedButton({ done(); actions.onSignInWithBunker(bunker) }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = bunker.isNotBlank()) {
                Text("Connect to this signer")
            }
            Text("Any NIP-46 signer, a Heartwood included.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            OutlinedTextField(secret, { secret = it }, Modifier.fillMaxWidth(), label = { Text("Private key") },
                placeholder = { Text("nsec1…") }, singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
            OutlinedButton({ done(); actions.onSignInWithKey(secret) }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = secret.isNotBlank()) {
                Text("Use this key")
            }
            Text("Last resort. The key is kept in this app's encrypted vault on this phone, and anything that reads the app's memory can read it. A signer app keeps it out of here altogether.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}
