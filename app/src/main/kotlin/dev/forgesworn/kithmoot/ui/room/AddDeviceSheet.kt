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
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.session.conferenceEndsLine
import dev.forgesworn.kithmoot.ui.qr.QrCode

/**
 * The pairing link, and the one sentence that has to land.
 *
 * A join link invites somebody. This one does not: it carries a device key and a
 * signed credential, so whoever opens it *is* the person who minted it, in that
 * room, until the credential expires. That difference is the whole risk in this
 * feature, so the warning is the first thing on the sheet, in the error colour,
 * above the link rather than under it.
 */
@Composable
fun AddDeviceSheet(
    link: String,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
    ) {
        Text(
            text = "Add a device",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.errorContainer)
                .padding(16.dp),
        ) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "This link is you. Only ever send it to your own device.",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }

        Spacer(Modifier.height(20.dp))
        Text(
            text = "Open it on your other phone, tablet or laptop and it joins as another " +
                "of your devices. The room sees one of you either way.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(Modifier.height(20.dp))
        Text(
            text = "Scan this on your other KithMoot device",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(12.dp))
        QrCode(
            text = link,
            contentDescription = "Device pairing QR code",
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        Spacer(Modifier.height(20.dp))
        Text(
            text = link,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(14.dp)
                .heightIn(max = 220.dp),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { copy(context, link) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
            Text("Copy link", style = MaterialTheme.typography.titleMedium)
        }

        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = { dev.forgesworn.kithmoot.ui.share(context, link) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp),
        ) {
            Text("Send to my other device", style = MaterialTheme.typography.titleMedium)
        }

        Spacer(Modifier.height(20.dp))
        Text(
            text = "The credential in it lasts a day and works in this room only. " +
                "A device that takes it up cannot pass it on.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))
        OutlinedButton(
            onClick = onDone,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
        ) {
            Text("Done", style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** A copy of the room's own link, for the people you actually mean to invite. */
@Composable
fun ShareRoomRow(joinUrl: String, modifier: Modifier = Modifier, canShare: () -> Boolean = { true }) {
    val context = LocalContext.current
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = { if (canShare()) copy(context, joinUrl) },
            modifier = Modifier.weight(1f).heightIn(min = 56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text("Copy join link", style = MaterialTheme.typography.titleSmall)
        }
        Spacer(Modifier.width(10.dp))
        OutlinedButton(
            onClick = { if (canShare()) dev.forgesworn.kithmoot.ui.share(context, joinUrl) },
            modifier = Modifier.heightIn(min = 56.dp),
        ) {
            Text("Send", style = MaterialTheme.typography.titleSmall)
        }
    }
}

/**
 * Invite people: the room's link as a large QR, for somebody across the
 * table to scan with their camera or KithMoot, with Copy and Send beneath it
 * for everybody else. Any member can show it; the link only invites, it is
 * not the room's traffic key. A conference room says when it ends, since the
 * link stops working then.
 */
@Composable
fun InviteSheet(
    joinUrl: String,
    endsAt: Long?,
    canRotateInvitation: Boolean,
    onRotateInvitation: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    canShare: () -> Boolean = { true },
) {
    if (!canShare()) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
    ) {
        Text("Invite people", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(8.dp))
        Text(
            "Anyone who scans this or is forwarded the link can walk in. It is an invitation, not the room's " +
                "traffic key. Keep this device online so it can answer new arrivals.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        endsAt?.let {
            Spacer(Modifier.height(8.dp))
            Text(conferenceEndsLine(it), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.height(16.dp))
        // White behind the code whatever the theme: scanners want the quiet
        // zone light, and a dark sheet would swallow it.
        QrCode(
            text = joinUrl,
            contentDescription = "Room invitation QR code",
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(androidx.compose.ui.graphics.Color.White)
                .padding(12.dp),
        )
        Spacer(Modifier.height(20.dp))
        ShareRoomRow(joinUrl, canShare = canShare)
        if (canRotateInvitation) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onRotateInvitation, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("New link") }
            Text(
                "The old link stops admitting new people. Anyone already in the room stays.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(20.dp))
        OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text("Done", style = MaterialTheme.typography.titleMedium)
        }
    }
}

private fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("KithMoot link", text))
}

