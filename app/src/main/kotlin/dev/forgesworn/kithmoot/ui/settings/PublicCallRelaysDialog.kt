package dev.forgesworn.kithmoot.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.media.PublicCallRelayMode

/** The row's line on Connections. */
internal fun publicCallRelaysSummary(view: PublicCallRelaysView): String = when {
    view.anonymous -> "Not available in anonymous mode"
    view.mode == PublicCallRelayMode.UNAVAILABLE -> "Could not be read on this device"
    view.mode == PublicCallRelayMode.ON -> "On: relays run by strangers, chosen by you"
    else -> "Off"
}

/**
 * Settings, Connections, Public call relays: the switch with the web's words,
 * and the picker for what was found. Kept apart from the Nostr relay list.
 */
@Composable
internal fun PublicCallRelaysDialog(panel: PublicCallRelaysPanel, onDismiss: () -> Unit) {
    val view by panel.view.collectAsState()
    DisposableEffect(panel) {
        panel.opened()
        onDispose { panel.closed() }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(PublicCallRelaysCopy.TITLE) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth().toggleable(value = view.switchOn, enabled = view.switchEnabled, role = Role.Switch, onValueChange = panel::setEnabled),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(PublicCallRelaysCopy.SWITCH, Modifier.weight(1f))
                Switch(view.switchOn, onCheckedChange = null, enabled = view.switchEnabled)
            }
            Text(PublicCallRelaysCopy.NOTE, style = MaterialTheme.typography.bodySmall)
            when {
                view.anonymous -> Text(PublicCallRelaysCopy.ANONYMOUS, style = MaterialTheme.typography.bodySmall)
                view.mode == PublicCallRelayMode.UNAVAILABLE -> {
                    Text(PublicCallRelaysCopy.UNREADABLE, style = MaterialTheme.typography.bodySmall)
                    TextButton(panel::clearUnreadable) { Text(PublicCallRelaysCopy.CLEAR_UNREADABLE) }
                }
                view.switchOn && view.outranked -> Text(PublicCallRelaysCopy.BLOCKED, style = MaterialTheme.typography.bodySmall)
                view.pickerShown -> Picker(view, panel)
            }
            if (view.status.isNotEmpty()) {
                Text(view.status, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
    }, confirmButton = { TextButton(onDismiss) { Text("Done") } })
}

@Composable
private fun Picker(view: PublicCallRelaysView, panel: PublicCallRelaysPanel) {
    val uriHandler = LocalUriHandler.current
    if (view.inCall) Text(PublicCallRelaysCopy.IN_CALL, style = MaterialTheme.typography.bodySmall)
    TextButton(panel::lookAgain, enabled = !view.inCall && !view.searching && !view.checking) { Text(PublicCallRelaysCopy.LOOK_AGAIN) }
    Text(PublicCallRelaysCopy.CLAIMS, style = MaterialTheme.typography.bodySmall)
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (entry in view.listing) {
            Row(
                Modifier.fillMaxWidth().selectable(selected = entry.key == view.selected, enabled = !view.inCall && !view.checking,
                    role = Role.RadioButton, onClick = { panel.select(entry.key) }),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RadioButton(entry.key == view.selected, onClick = null, enabled = !view.inCall && !view.checking)
                Column(Modifier.weight(1f)) {
                    // ASCII as advertised: an internationalised name stays xn-- encoded (and is refused anyway).
                    Text(entry.host, style = MaterialTheme.typography.bodyMedium)
                    Text(PublicCallRelaysCopy.key(entry), style = MaterialTheme.typography.bodySmall)
                    if (entry.knownHostNewKey) Text(PublicCallRelaysCopy.NEW_KEY, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                    PublicCallRelaysCopy.claim(entry)?.let { said ->
                        Text(said, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            // The operator's own link, outside the row so a tap does not choose
            // the relay. Never fetched, prefetched or previewed: it opens only
            // when tapped, in the browser, with no referrer extra.
            entry.privacy?.let { link ->
                val opens = PublicCallRelaysCopy.privacyOpens(link.host)
                TextButton(onClick = { runCatching { uriHandler.openUri(link.url) } },
                    modifier = Modifier.padding(start = 40.dp).semantics { contentDescription = "${PublicCallRelaysCopy.privacyLink(link.host)}. $opens" }) {
                    Text(PublicCallRelaysCopy.privacyLink(link.host), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    Text(PublicCallRelaysCopy.CHECK_NOTE, style = MaterialTheme.typography.bodySmall)
    TextButton(panel::checkSelected, enabled = view.canCheck) { Text(PublicCallRelaysCopy.CHECK) }
    if (view.canForget) TextButton(panel::forget, enabled = !view.checking) { Text(PublicCallRelaysCopy.FORGET) }
}
