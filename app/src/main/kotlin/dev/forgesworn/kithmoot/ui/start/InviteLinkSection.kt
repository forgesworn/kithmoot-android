package dev.forgesworn.kithmoot.ui.start

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.forgesworn.kithmoot.ui.qr.QrScanner
import kotlinx.coroutines.delay

/** "Open an invite link", collapsed by default: a link is rare enough on
 *  this screen that it does not need to compete with Start a room.
 *
 *  It sits at the foot of the home screen, which is exactly where the
 *  keyboard comes up. So the field, Open and Scan are brought into view as a
 *  group while the field has focus, again as the keyboard finishes rising
 *  (the containers pad for the IME), and once the section opens; and a scan
 *  drops focus first, so the keyboard is not over the camera or back over
 *  the link the scan just filled in. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun InviteLinkSection(joinUrl: String, onJoinUrlChanged: (String) -> Unit, enabled: Boolean, onJoin: () -> Unit,
    /** False inside the home list's bottom sheet, where the field is the whole point and there is nothing to collapse. */
    collapsible: Boolean = true) {
    var expanded by rememberSaveable { mutableStateOf(!collapsible) }
    var scanning by remember { mutableStateOf(false) }
    var fieldFocused by remember { mutableStateOf(false) }
    // Set by a person's own tap or scan, never by restoring the screen, so
    // coming back to the home screen does not scroll it to its foot.
    var reveal by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val group = remember { BringIntoViewRequester() }
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    // Restarted on every step of the keyboard's animation, so the last call
    // lands once it has settled and the padded container has shrunk.
    LaunchedEffect(fieldFocused, imeBottom) { if (fieldFocused) group.bringIntoView() }
    LaunchedEffect(reveal) {
        if (reveal) { delay(EXPAND_SETTLE_MS); group.bringIntoView(); reveal = false }
    }
    Column {
        if (collapsible) TextButton(
            onClick = { expanded = !expanded; reveal = expanded },
            modifier = Modifier.heightIn(min = 48.dp)
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed"; role = Role.Button },
        ) { Text("Open an invite link") }
        AnimatedVisibility(expanded) {
            Column(Modifier.bringIntoViewRequester(group), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = joinUrl, onValueChange = onJoinUrlChanged, enabled = enabled,
                    modifier = Modifier.fillMaxWidth().onFocusChanged { fieldFocused = it.isFocused },
                    label = { Text("Invite link") }, placeholder = { Text("https://…#…") }, maxLines = 3,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { if (enabled) onJoin() }),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onJoin, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open") }
                    TextButton({ focusManager.clearFocus(); scanning = true }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Scan QR code") }
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
                        onDecoded = { onJoinUrlChanged(it); scanning = false; reveal = true },
                        prompt = "KithMoot needs the camera to scan an invite QR code.",
                    )
                    TextButton({ scanning = false }, Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
                }
            }
        }
    }
}

/** How long the section's expand animation (or the scanner's dialog closing) takes before it is worth scrolling to. */
private const val EXPAND_SETTLE_MS = 350L

/** Keeps camera input narrow; the existing join action still parses the full link. */
internal fun looksLikeKithMootInvitation(value: String): Boolean {
    val text = value.trim()
    if (text.length !in 1..8192) return false
    return text.startsWith("kithmoot:", ignoreCase = true) ||
        Regex("^https://[^/?#]+/j(?:/|[?#])", RegexOption.IGNORE_CASE).containsMatchIn(text)
}
