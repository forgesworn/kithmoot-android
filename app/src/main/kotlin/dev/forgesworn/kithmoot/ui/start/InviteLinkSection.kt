package dev.forgesworn.kithmoot.ui.start

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.forgesworn.kithmoot.ui.qr.QrScanner

/** "Open an invite link", collapsed by default: a link is rare enough on
 *  this screen that it does not need to compete with Start a room. */
@Composable
internal fun InviteLinkSection(joinUrl: String, onJoinUrlChanged: (String) -> Unit, enabled: Boolean, onJoin: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    Column {
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.heightIn(min = 48.dp)
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed"; role = Role.Button },
        ) { Text("Open an invite link") }
        AnimatedVisibility(expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = joinUrl, onValueChange = onJoinUrlChanged, modifier = Modifier.fillMaxWidth(), enabled = enabled,
                    label = { Text("Invite link") }, placeholder = { Text("https://…#…") }, maxLines = 3,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { if (enabled) onJoin() }),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onJoin, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open") }
                    TextButton({ scanning = true }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Scan QR code") }
                }
            }
        }
    }
    if (scanning) {
        Dialog(onDismissRequest = { scanning = false }) {
            Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Scan an invite link", style = MaterialTheme.typography.titleLarge)
                    Text("Point the camera at a KithMoot invite QR code. Check the link, then tap Open.")
                    QrScanner(
                        accept = ::looksLikeKithMootInvitation,
                        onDecoded = { onJoinUrlChanged(it); scanning = false },
                        prompt = "KithMoot needs the camera to scan an invite QR code.",
                    )
                    TextButton({ scanning = false }, Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
                }
            }
        }
    }
}

/** Keeps camera input narrow; the existing join action still parses the full link. */
internal fun looksLikeKithMootInvitation(value: String): Boolean {
    val text = value.trim()
    if (text.length !in 1..8192) return false
    return text.startsWith("kithmoot:", ignoreCase = true) ||
        Regex("^https://[^/?#]+/j(?:/|[?#])", RegexOption.IGNORE_CASE).containsMatchIn(text)
}
