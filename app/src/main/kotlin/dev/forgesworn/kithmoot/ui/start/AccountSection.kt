package dev.forgesworn.kithmoot.ui.start

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.ui.StartState
import dev.forgesworn.kithmoot.account.BunkerPointer
import dev.forgesworn.kithmoot.ui.qr.QrScanner
import dev.forgesworn.kithmoot.ui.room.ProfileAvatar

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
    val retained = state.retainedAccount
    var rendezvousIndex by remember(account?.pubkey) { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (account != null) "Your Nostr account" else if (retained != null) "Keep your preview account" else "Keep your rooms with you",
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
            if (account.method == "bunker") {
                HorizontalDivider()
                Text("Vennel rendezvous", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Ask a compatible Heartwood bunker to provision its root-derived rendezvous child to this phone. Choose the person's current index exactly; KithMoot never sees the child in account, room or contact storage.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.rendezvous?.activeIndex?.let { active ->
                    Text("This phone currently holds index $active. Enter an owner-selected index to rotate it.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedTextField(
                    value = rendezvousIndex,
                    onValueChange = { rendezvousIndex = it.filter(Char::isDigit).take(10) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Rendezvous index") },
                    placeholder = { Text("Owner-selected current index") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                val requestedIndex = rendezvousIndex.toLongOrNull()?.takeIf { it in 0..0xffffffffL }
                val rendezvousBusy = state.rendezvous?.busy == true
                Button(
                    onClick = { requestedIndex?.let(actions.onProvisionRendezvous) },
                    enabled = enabled && !rendezvousBusy && requestedIndex != null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(if (rendezvousBusy) "Waiting for Heartwood…" else "Ask Heartwood to provision this phone") }
                state.rendezvous?.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            OutlinedButton(actions.onSignOut, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Sign out") }
        } else {
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
                Text("Sign in as yourself, with your Nostr profile and the public key your agents recognise. " +
                    "Rooms you open are joined as that account. Without it, each room gets its own key on this phone.",
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
    var scanningBunker by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // A bunker URI is often pasted with the IME open. Keep the focused field
    // and its action reachable on short phones instead of letting the sheet be
    // covered by the keyboard.
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding()
        .padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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

            if (scanningBunker) {
                Text("Point the camera at the bunker QR your signer shows. Nothing is connected until you choose Connect.")
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
    }
}

/** Reads a value only after the person explicitly asks to paste it. */
internal fun clipboardText(context: Context): String? {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    val clip = clipboard.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()?.trim()?.takeIf { it.isNotBlank() }
}
