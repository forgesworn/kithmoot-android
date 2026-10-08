package dev.forgesworn.kithmoot.ui.room

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.account.npubOf

/** The caller supplies only profiles allowed by the current lookup setting. */
@Composable
fun ParticipantDetails(participant: String, assertedName: String?, profile: PublicProfile?, onClose: () -> Unit, onMessage: (() -> Unit)? = null) {
    val context = LocalContext.current
    var copied by remember(participant) { mutableStateOf<String?>(null) }
    fun copy(label: String, value: String) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, value))
        copied = label
    }
    AlertDialog(onDismissRequest = onClose, title = { Text("Participant details") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ProfileAvatar(participant, assertedName, profile, Modifier.size(64.dp))
            Text(profile?.name ?: assertedName ?: "Participant", style = MaterialTheme.typography.titleMedium)
            Text(if (profile != null) "Nostr profile · names and pictures are self-reported." else "Room participant")
            val npub = npubOf(participant)
            Text(npub, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { copy("npub", npub) }) { Text(if (copied == "npub") "Copied npub" else "Copy npub") }
            profile?.nip05?.let { address ->
                Text("Self-reported NIP-05 address", style = MaterialTheme.typography.labelMedium)
                Text(address)
                TextButton(onClick = { copy("NIP-05", address) }) { Text(if (copied == "NIP-05") "Copied NIP-05" else "Copy NIP-05") }
            }
            onMessage?.let { TextButton(onClick = it) { Text("Message privately") } }
        }
    }, confirmButton = { TextButton(onClick = onClose) { Text("Close participant details") } })
}
