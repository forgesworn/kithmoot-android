package dev.forgesworn.kithmoot.ui.start

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.ui.StartState
import dev.forgesworn.kithmoot.account.BunkerPointer
import dev.forgesworn.kithmoot.ui.qr.QrScanner
import dev.forgesworn.kithmoot.ui.room.ProfileAvatar
import dev.forgesworn.kithmoot.ui.settings.SettingsSheet

/** What the start screen can do about the account. Grouped so the screen's parameter list stays readable. */
class AccountActions(
    val onRefreshSigners: () -> Unit,
    val onSignInWithApp: (String) -> Unit,
    val onSignInWithSignet: () -> Unit,
    val onSignInWithBunker: (String) -> Unit,
    val onCancelSignIn: () -> Unit,
    val onSignOut: () -> Unit,
    val onProvisionRendezvous: (Long) -> Unit,
    val onDismissError: () -> Unit,
) {
    companion object {
        val None = AccountActions({}, {}, {}, {}, {}, {}, {}, {})
    }
}

/**
 * Signing in, on the start screen: the way in for someone with no account, or
 * the preview account this phone still holds. Mirrors the web client's "Your
 * Nostr account" card. Who a signed-in phone is, and what it can do for that
 * account, is the Account page in Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSection(state: StartState, actions: AccountActions, enabled: Boolean, showHeading: Boolean = true) {
    var choosing by remember { mutableStateOf(false) }
    val retained = state.retainedAccount
    if (state.account != null) return

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showHeading) Text(accountHeading(state),
            style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        if (retained != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ProfileAvatar(retained.pubkey, retained.name, retained.profile, Modifier.size(44.dp))
                Column(Modifier.weight(1f)) {
                    Text("Preview account", style = MaterialTheme.typography.titleMedium)
                    Text(retained.npub, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.semantics { contentDescription = "Retained public key ${retained.npub}" })
                }
            }
            Text(
                "Your encrypted preview data is still on this phone. Sign in through a signer app or bunker with this same Nostr account to keep using its rooms. A different account is refused and does not replace or delete anything.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text("Sign in to find your rooms across devices. Just visiting? Open the invite link you were sent; no account needed.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
        state.signInError?.let { error ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                TextButton(actions.onDismissError) { Text("Dismiss") }
            }
        }
    }

    if (choosing) SignInChoicesSheet(state, actions) { choosing = false }
}

/** The "Sign in to KithMoot" sheet: where the key lives. Opened from the start screen's account block and from Settings' account row. */
@Composable
fun SignInChoicesSheet(state: StartState, actions: AccountActions, onDismiss: () -> Unit) {
    SettingsSheet(title = "Sign in to KithMoot", onDone = onDismiss) {
        SignInChoices(state, actions, onDismiss)
    }
}

/** What the account block is called when it has a heading of its own, or the sheet that hosts it. */
internal fun accountHeading(state: StartState): String =
    if (state.account != null) "Your Nostr account" else if (state.retainedAccount != null) "Keep your preview account" else "Keep your rooms with you"

@Composable
private fun SignInChoices(state: StartState, actions: AccountActions, done: () -> Unit) {
    var advanced by remember { mutableStateOf(false) }
    var remoteSigner by remember { mutableStateOf(false) }
    var bunker by remember { mutableStateOf("") }
    var scanningBunker by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // The sheet scrolls and clears the keyboard, so a pasted bunker link stays reachable on short phones.
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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

        OutlinedButton(
            onClick = { remoteSigner = !remoteSigner },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .semantics { contentDescription = "Use remote signer Bunker" },
        ) { Text(if (remoteSigner) "Hide remote signer" else "Use remote signer (Bunker)") }
        if (remoteSigner) {
            Text("Scan or paste the Bunker link your signer gives you. Nothing is connected until you choose Connect.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (scanningBunker) {
                Text("Point the camera at the bunker QR your signer shows.")
                QrScanner(
                    accept = { BunkerPointer.parse(it) != null },
                    onDecoded = { bunker = it; scanningBunker = false },
                    prompt = "KithMoot needs the camera to scan your signer's bunker QR.",
                )
                TextButton({ scanningBunker = false }, Modifier.heightIn(min = 48.dp)) { Text("Cancel scan") }
            } else {
                OutlinedTextField(bunker, { bunker = it }, Modifier.fillMaxWidth(), label = { Text("Bunker link") },
                    placeholder = { Text("bunker://…?relay=wss://…&secret=…") }, maxLines = 3)
                OutlinedButton(
                    onClick = { scanningBunker = true },
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Scan bunker QR" },
                ) { Text("Scan bunker QR") }
                TextButton(
                    onClick = { clipboardText(context)?.let { bunker = it } },
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Paste bunker link from clipboard" },
                ) { Text("Paste from clipboard") }
            }
            OutlinedButton({ done(); actions.onSignInWithBunker(bunker) }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = bunker.isNotBlank()) {
                Text("Connect to this signer")
            }
            Text("Any NIP-46 signer, a Heartwood included.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        TextButton({ advanced = !advanced }) { Text(if (advanced) "Hide advanced" else "Advanced") }
        if (advanced) {
            OutlinedButton({ done(); actions.onSignInWithSignet() }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Signet in a browser")
            }
            Text("For a Signet that lives in a browser rather than the My Signet app. It opens mysignet.app, you approve there, and it pairs with this app over a relay. That browser tab has to stay open to sign.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Reads a value only after the person explicitly asks to paste it. */
internal fun clipboardText(context: Context): String? {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    val clip = clipboard.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()?.trim()?.takeIf { it.isNotBlank() }
}
