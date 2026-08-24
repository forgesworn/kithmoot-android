package dev.forgesworn.kithmoot.ui

import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * One thing the interface wants to do, and the permission it needs first.
 *
 * [why] is shown before the system dialog rather than after a refusal. Android's
 * own dialog says only which permission is being asked for, never what for, and
 * a request that arrives with no explanation is the one people refuse.
 */
data class PermissionAsk(
    val permission: String,
    val title: String,
    val why: String,
    val refused: String,
    val onGranted: () -> Unit,
)

/** Asks for a permission, explaining first, and only at the moment it is needed. */
fun interface PermissionAsker {
    fun ask(ask: PermissionAsk)
}

@Composable
fun rememberPermissionAsker(onRefused: (String) -> Unit): PermissionAsker {
    val context = LocalContext.current
    var explaining by remember { mutableStateOf<PermissionAsk?>(null) }
    var inFlight by remember { mutableStateOf<PermissionAsk?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val ask = inFlight
        inFlight = null
        if (ask == null) return@rememberLauncherForActivityResult
        if (granted) ask.onGranted() else onRefused(ask.refused)
    }

    explaining?.let { ask ->
        AlertDialog(
            onDismissRequest = { explaining = null },
            title = { Text(ask.title, style = MaterialTheme.typography.headlineSmall) },
            text = { Text(ask.why, style = MaterialTheme.typography.bodyLarge) },
            confirmButton = {
                TextButton(onClick = {
                    explaining = null
                    inFlight = ask
                    launcher.launch(ask.permission)
                }) { Text("Continue", style = MaterialTheme.typography.labelLarge) }
            },
            dismissButton = {
                TextButton(onClick = { explaining = null }) {
                    Text("Not now", style = MaterialTheme.typography.labelLarge)
                }
            },
        )
    }

    return PermissionAsker { ask ->
        if (isGranted(context, ask.permission)) ask.onGranted() else explaining = ask
    }
}

fun isGranted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
