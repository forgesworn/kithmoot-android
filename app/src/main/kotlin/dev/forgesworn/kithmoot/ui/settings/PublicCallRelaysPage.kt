package dev.forgesworn.kithmoot.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.media.PublicCallRelayMode

/**
 * Settings, Calls, Public call relays: the switch with the web's words, and
 * the picker for what was found. The lookup runs only while this page is
 * open (never on Calls), once when it opens and again on "Look again".
 */
@Composable
internal fun PublicCallRelaysPage(panel: PublicCallRelaysPanel) {
    val view by panel.view.collectAsState()
    DisposableEffect(panel) {
        panel.opened()
        onDispose { panel.closed() }
    }
    SettingsSection(null) {
        SettingsSwitchRow(PublicCallRelaysCopy.SWITCH, checked = view.switchOn, enabled = view.switchEnabled, onCheckedChange = panel::setEnabled)
        SettingsNote(PublicCallRelaysCopy.NOTE_1)
        SettingsNote(PublicCallRelaysCopy.NOTE_2)
        when {
            view.anonymous -> SettingsNote(PublicCallRelaysCopy.ANONYMOUS)
            view.mode == PublicCallRelayMode.UNAVAILABLE -> {
                SettingsNote(PublicCallRelaysCopy.UNREADABLE)
                SettingsActionRow(PublicCallRelaysCopy.CLEAR_UNREADABLE, onClick = panel::clearUnreadable)
            }
            view.switchOn && view.outranked -> SettingsNote(PublicCallRelaysCopy.BLOCKED, live = true)
            view.pickerShown -> Picker(view, panel)
        }
        if (view.status.isNotEmpty()) SettingsNote(view.status, live = true)
    }
}

@Composable
private fun Picker(view: PublicCallRelaysView, panel: PublicCallRelaysPanel) {
    val uriHandler = LocalUriHandler.current
    if (view.inCall) SettingsNote(PublicCallRelaysCopy.IN_CALL)
    SettingsActionRow(PublicCallRelaysCopy.LOOK_AGAIN, enabled = !view.inCall && !view.searching && !view.checking, onClick = panel::lookAgain)
    SettingsNote(PublicCallRelaysCopy.CLAIMS)
    Column(Modifier.selectableGroup().padding(horizontal = RowHorizontal), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (entry in view.listing) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = entry.key == view.selected, enabled = !view.inCall && !view.checking,
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
    SettingsNote(PublicCallRelaysCopy.CHECK_NOTE)
    SettingsActionRow(PublicCallRelaysCopy.CHECK, enabled = view.canCheck, onClick = panel::checkSelected)
    if (view.canForget) SettingsActionRow(PublicCallRelaysCopy.FORGET, enabled = !view.checking, onClick = panel::forget)
}
