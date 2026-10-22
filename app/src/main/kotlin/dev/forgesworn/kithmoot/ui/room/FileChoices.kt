package dev.forgesworn.kithmoot.ui.room

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.session.*
import dev.forgesworn.kithmoot.ui.FileChoice
import dev.forgesworn.kithmoot.ui.FileNotice

/** Settings › Privacy › Where files go, and the same choices from a room: what each option does. */
class FileChoiceActions(
    val onPublic: () -> Unit = {},
    /** Null when the server is saved, else what to say. */
    val onOwn: (String) -> String? = { null },
    val onNowhere: () -> Unit = {},
)

/** Everything the composer needs to know and do about files. */
class ComposerFiles(
    val choice: FileChoice = FileChoice(),
    val notice: FileNotice? = null,
    val off: FileDestination.Off? = null,
    val onPick: (Uri) -> Unit = {},
    val onAcceptNotice: () -> Unit = {},
    val onDismissNotice: () -> Unit = {},
    val onDismissOff: () -> Unit = {},
    val actions: FileChoiceActions = FileChoiceActions(),
)

/** Settings text for the page (spec 9.5, with section 16's added sentence). */
const val WHERE_FILES_GO = "Where files go"
const val WHERE_FILES_GO_NOTE = "Files are encrypted on this device before they leave. The server you choose sees your IP address, " +
    "when files are uploaded and opened, and how big they are, and decides how long to keep them. Opening a file connects you to the " +
    "server it’s on. Changing this doesn’t move or delete files you’ve already sent."
const val OWN_SERVER_DISCLOSURE = "People who open your files connect to your server. It sees their IP addresses, and its address shows where it runs."

/**
 * Store files I send on: a public server, my own server, or nowhere. Choosing
 * the public server or Nowhere takes effect at once; my own server takes effect
 * on Use this server. Nothing here uploads anything.
 */
@Composable
fun WhereFilesGo(choice: FileChoice, actions: FileChoiceActions, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    val public = PUBLIC_FILE_SERVERS.firstOrNull()
    val current = choice.destination
    val publicChosen = when (current) {
        is FileDestination.Default -> true
        is FileDestination.Server -> publicFileServer(current.origin) != null
        is FileDestination.Off -> false
    }
    val ownChosen = current is FileDestination.Server && !publicChosen
    var own by rememberSaveable(ownChosen) { mutableStateOf(ownChosen || (current is FileDestination.Off && !current.chosen)) }
    var address by rememberSaveable(choice.ownServer) { mutableStateOf(choice.ownServer) }
    var check by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = when {
        own -> 1
        current is FileDestination.Off && current.chosen -> 2
        publicChosen -> 0
        else -> 1
    }
    Column(modifier) {
        Text("Store files I send on", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 4.dp))
        Column(Modifier.selectableGroup()) {
            if (public != null) Option("A public server",
                "${fileHost(public.origin)}, run by ${public.operator}. Files up to ${formatLimit(public.maxBytes)}.",
                selected == 0) { own = false; check = null; actions.onPublic() }
            Option("My own server", "A Wildbloom Node or another Blossom server you run.", selected == 1) { own = true; check = null }
            if (selected == 1) Column(Modifier.padding(start = 48.dp, end = 8.dp, bottom = 8.dp)) {
                OutlinedTextField(address, { address = it; check = null }, label = { Text("Server address") }, singleLine = true,
                    placeholder = { Text("https://files.example") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                check?.let { Text(it, Modifier.padding(top = 4.dp).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall) }
                Button(onClick = {
                    val refused = actions.onOwn(address)
                    check = refused ?: "Files you send go to ${fileHost(runCatching { mediaStorageOrigin(address) }.getOrDefault(address))}."
                }, Modifier.padding(top = 8.dp)) { Text("Use this server") }
                Text(OWN_SERVER_DISCLOSURE, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                // No browser to open the page is not a reason to fail.
                TextButton(onClick = { runCatching { uriHandler.openUri(RUN_YOUR_OWN_FILES_URL) } }) { Text("How to run your own file server") }
            }
            Option("Nowhere", "You can still open files other people send.", selected == 2) { own = false; check = null; actions.onNowhere() }
        }
        Text(WHERE_FILES_GO_NOTE, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Option(title: String, detail: String, selected: Boolean, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected, role = Role.RadioButton, onClick = onSelect).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null, modifier = Modifier.padding(horizontal = 12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The one-time notice (spec 9.1, with section 16's body): where files go and
 * who runs it, before the first file leaves this device. The button is the
 * person's choice: [action] says what it does, "Upload to {host}" for a picked
 * image and "Send" for a long paste. A retired ForgeSworn choice is said first.
 */
@Composable
fun FileNoticeDialog(notice: FileNotice, retired: Boolean, action: String, onAccept: () -> Unit, onChooseAnother: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(fileNoticeTitle(notice.origin), fontWeight = FontWeight.SemiBold) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            if (retired) Text(FORGESWORN_STORAGE_CLOSED, Modifier.padding(bottom = 8.dp))
            Text(fileNoticeBody(notice.origin))
            TextButton(onClick = onChooseAnother, Modifier.padding(top = 4.dp)) { Text("Choose another server") }
        } },
        confirmButton = { Button(onClick = onAccept) { Text(action) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

/** The same choices as Settings › Privacy › Where files go, without leaving the room. */
@Composable
fun WhereFilesGoDialog(choice: FileChoice, actions: FileChoiceActions, onDone: () -> Unit) {
    AlertDialog(onDismissRequest = onDone, title = { Text(WHERE_FILES_GO) },
        text = { WhereFilesGo(choice, actions, Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = onDone) { Text("Done") } })
}

/** Why a picked file was held, with the way to change it (spec 9.1 S4 and S5). */
@Composable
fun FilesOffLine(off: FileDestination.Off, onChoose: () -> Unit, onDismiss: () -> Unit) {
    val (line, action) = fileOffLine(off)
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(line, Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onChoose) { Text(action) }
        TextButton(onClick = onDismiss) { Text("Dismiss") }
    }
}
