package dev.forgesworn.kithmoot.ui.room

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.ui.ContactRow

/**
 * Contact cards, as a person meets them: pasted in, listed, forgotten, and
 * one's own handed out.
 *
 * What a card changes is said in the words the reference uses (`app/src/main.ts`):
 * the person's tile says a card is held for them, and a message to their box
 * shows as sheltered, because the box on a card is one of the circle's relays
 * and nothing else ever is. Forgetting the card puts both back, which is why
 * forgetting asks twice.
 */
@Composable
fun ContactCardsSheet(
    contacts: List<ContactRow>,
    status: String?,
    myCard: String?,
    canShowCard: Boolean,
    onAdd: (String) -> Unit,
    onForget: (String) -> Unit,
    onShowMyCard: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var pasted by remember { mutableStateOf("") }
    var forgetting by remember { mutableStateOf<ContactRow?>(null) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
    ) {
        Text(
            text = "Contact cards",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "A card is one link that makes a stranger a contact and names their box. " +
                "Kept on this phone only, never published.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = pasted,
            onValueChange = { pasted = it },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Contact card link" },
            label = { Text("Paste a contact card link") },
            minLines = 2,
            maxLines = 4,
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { onAdd(pasted); pasted = "" },
            enabled = pasted.isNotBlank(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) {
            Text("Add card", style = MaterialTheme.typography.titleMedium)
        }
        if (status != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }

        Spacer(Modifier.height(24.dp))
        Text(
            text = "Your contacts",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(8.dp))
        if (contacts.isEmpty()) {
            Text(
                text = "No contact cards yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        for (c in contacts) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(14.dp),
            ) {
                Text(
                    text = c.name ?: "A person without a name on their card",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = c.npub,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                if (c.boxes.isEmpty()) {
                    Text(
                        text = "No box on this card. Messages to them go by public relays.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                for (b in c.boxes) {
                    Text(
                        text = "Box: $b",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { copy(context, c.npub) }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Copy npub")
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { forgetting = c }, modifier = Modifier.semantics { contentDescription = "Forget ${c.name ?: "this"} card" }) {
                        Text("Forget")
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            text = "Your card",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(8.dp))
        if (!canShowCard) {
            Text(
                text = "Only the device that holds your identity can make your card.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (myCard == null) {
            Text(
                text = "Signed by whatever holds your key, with this room's relays on it. Good for seven days. " +
                    "This phone runs no box, so the card names none.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onShowMyCard, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text("Show my card", style = MaterialTheme.typography.titleMedium)
            }
        } else {
            Text(
                text = myCard,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(14.dp)
                    .heightIn(max = 180.dp)
                    .semantics { contentDescription = "Your contact card link" },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { copy(context, myCard) },
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Copy card", style = MaterialTheme.typography.titleSmall)
                }
                Spacer(Modifier.width(10.dp))
                OutlinedButton(onClick = { share(context, myCard) }, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text("Send", style = MaterialTheme.typography.titleSmall)
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text("Done", style = MaterialTheme.typography.titleMedium)
        }
    }

    val target = forgetting
    if (target != null) {
        AlertDialog(
            onDismissRequest = { forgetting = null },
            title = { Text("Forget this card?") },
            text = {
                Text(
                    "Their box will show as a public relay again, and a message to it will say so. " +
                        "The card can be added back from the link.",
                )
            },
            confirmButton = { TextButton({ onForget(target.p); forgetting = null }) { Text("Forget card") } },
            dismissButton = { TextButton({ forgetting = null }) { Text("Keep") } },
        )
    }
}

private fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("KithMoot contact card", text))
}

private fun share(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "Send card"))
}
