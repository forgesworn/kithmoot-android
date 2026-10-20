package dev.forgesworn.kithmoot.ui.room

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.session.PUBLIC_FILE_SERVERS
import dev.forgesworn.kithmoot.session.RUN_YOUR_OWN_FILES_URL
import dev.forgesworn.kithmoot.session.label

/**
 * Where the files go, chosen by the person: a short list of public servers run
 * by other people, or any HTTPS server typed in. Nothing here is pre-filled and
 * nothing here grants consent; tapping a server is the same as typing it, so
 * [onStorage] must clear any consent and saved choice. Emits into the caller's
 * column, after the disclosure text and before the consent checkbox.
 *
 * [retired] says a saved suggestion was just removed, and is shown only until a
 * server is chosen.
 */
@Composable
internal fun MediaStorageFields(storage: String, retired: Boolean, onStorage: (String) -> Unit) {
    val uriHandler = LocalUriHandler.current
    Text("The server's operator, not KithMoot, decides what it keeps and for how long.", Modifier.padding(top = 8.dp))
    if (retired) Text("KithMoot no longer suggests a file server. Choose where your files go to send files again.",
        Modifier.padding(top = 8.dp))
    Text("Public servers run by other people", Modifier.padding(top = 8.dp).semantics { heading() }, style = MaterialTheme.typography.labelLarge)
    for (server in PUBLIC_FILE_SERVERS) TextButton(onClick = { onStorage(server.origin) }) { Text(server.label()) }
    OutlinedTextField(storage, onStorage, label = { Text("Or your own HTTPS server") }, singleLine = true)
    // No browser to open the page is not a reason to fail the dialog.
    TextButton(onClick = { runCatching { uriHandler.openUri(RUN_YOUR_OWN_FILES_URL) } }) { Text("How to run your own file server") }
}
