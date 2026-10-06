package dev.forgesworn.kithmoot.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.ui.settings.SettingsNote
import dev.forgesworn.kithmoot.ui.settings.SettingsSwitchRow
import dev.forgesworn.kithmoot.update.AppUpdates.State

/**
 * The update notice above the rooms list: only once there is something to
 * act on, and Later puts it away until the next launch. Checks that fail
 * quietly are for Settings, not here.
 */
@Composable
fun UpdateNotice(updates: AppUpdates, modifier: Modifier = Modifier) {
    val state by updates.state.collectAsState()
    val dismissed by updates.dismissed.collectAsState()
    val shown = when (val s = state) {
        is State.Available -> s.versionName != dismissed
        is State.Downloading, is State.Ready, is State.Installing -> true
        is State.Failed -> s.versionName != null && s.versionName != dismissed
        else -> false
    }
    if (!shown) return
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val version = (state as? State.Available)?.versionName ?: (state as? State.Failed)?.versionName
            UpdateProgress(updates, state, later = version?.let { { updates.dismiss(it) } })
        }
    }
}

/** Settings' Updates section. */
@Composable
fun UpdateSettings(updates: AppUpdates) {
    val state by updates.state.collectAsState()
    val automatic by updates.automatic.collectAsState()
    Text("Version ${updates.versionName}", style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp))
    if (updates.installedFrom == InstalledFrom.ZAPSTORE) {
        SettingsNote("Zapstore installed this copy, so updates come through Zapstore.")
    }
    SettingsSwitchRow(
        "Check for updates automatically",
        (if (updates.automaticByDefault) "" else "Off by default in this build. ") +
            "At most every six hours while KithMoot is open, asks ${UpdateFlow.ORIGIN.removePrefix("https://")} for the signed list of releases. " +
            "An update installs only if its signature and checksum match the keys built into the app.",
        checked = automatic,
    ) { updates.setAutomatic(it) }
    val live = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (val s = state) {
            State.Checking -> Text("Checking for updates…", modifier = live)
            State.Current -> Text("KithMoot is up to date.", modifier = live)
            is State.Failed -> if (s.versionName == null) Text(s.message, modifier = live)
            else -> Unit
        }
        when (state) {
            is State.Available, is State.Downloading, is State.Ready, is State.Installing -> UpdateProgress(updates, state, later = null)
            is State.Failed -> if ((state as State.Failed).versionName != null) UpdateProgress(updates, state, later = null)
            else -> Unit
        }
        val busy = state is State.Checking || state is State.Downloading || state is State.Installing
        OutlinedButton({ updates.checkNow() }, Modifier.heightIn(min = 48.dp), enabled = !busy) { Text("Check now") }
    }
}

@Composable
private fun UpdateProgress(updates: AppUpdates, state: State, later: (() -> Unit)?) {
    val callActive by updates.callActive.collectAsState()
    var zapstoreMissing by remember { mutableStateOf(false) }
    val live = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    when (state) {
        is State.Available -> {
            if (state.viaZapstore) {
                Text("KithMoot ${state.versionName} is available in Zapstore.", style = MaterialTheme.typography.titleSmall, modifier = live)
                if (zapstoreMissing) Text("Open Zapstore to update.", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text("KithMoot ${state.versionName} is available.", style = MaterialTheme.typography.titleSmall, modifier = live)
                if (callActive) Text("Finish the call to install.", style = MaterialTheme.typography.bodyMedium)
            }
            Actions(later) {
                if (state.viaZapstore) Button({ zapstoreMissing = !updates.openZapstore() }, Modifier.heightIn(min = 48.dp)) { Text("Open Zapstore") }
                else Button({ updates.install() }, Modifier.heightIn(min = 48.dp), enabled = !callActive) { Text("Install") }
            }
        }
        is State.Downloading -> {
            Text("Downloading KithMoot ${state.versionName}… ${(state.fraction * 100).toInt()}%", style = MaterialTheme.typography.titleSmall)
            LinearProgressIndicator({ state.fraction }, Modifier.fillMaxWidth())
        }
        is State.Ready -> {
            Text("KithMoot ${state.versionName} is ready to install.", style = MaterialTheme.typography.titleSmall, modifier = live)
            if (callActive) Text("Finish the call to install.", style = MaterialTheme.typography.bodyMedium)
            Actions(null) { Button({ updates.install() }, Modifier.heightIn(min = 48.dp), enabled = !callActive) { Text("Install") } }
        }
        is State.Installing -> Text("Installing KithMoot ${state.versionName}…", style = MaterialTheme.typography.titleSmall, modifier = live)
        is State.Failed -> {
            Text(state.message, style = MaterialTheme.typography.bodyMedium, modifier = live)
            if (state.retry || later != null) Actions(later, laterLabel = "Dismiss") {
                if (state.retry) Button({ updates.install() }, Modifier.heightIn(min = 48.dp), enabled = !callActive) { Text("Try again") }
            }
        }
        else -> Unit
    }
}

@Composable
private fun Actions(later: (() -> Unit)?, laterLabel: String = "Later", primary: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
        later?.let { TextButton(it, Modifier.heightIn(min = 48.dp)) { Text(laterLabel) } }
        primary()
    }
}
