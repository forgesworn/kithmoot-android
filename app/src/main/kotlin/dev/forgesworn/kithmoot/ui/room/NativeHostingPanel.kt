package dev.forgesworn.kithmoot.ui.room

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.forgesworn.kithmoot.epoch.NativeHostingLifecycle
import dev.forgesworn.kithmoot.epoch.NativeHostingState
import dev.forgesworn.kithmoot.epoch.NativeHostingStatus

internal fun nativeHostingLine(hosting: NativeHostingState): String = when (hosting.status) {
    NativeHostingStatus.STARTING -> "Opening room hosting…"
    NativeHostingStatus.READY -> when (hosting.lifecycle) {
        NativeHostingLifecycle.RETIRED -> "Hosting existing members · invitation retired"
        NativeHostingLifecycle.CLOSED -> "Room closed"
        else -> "Hosting · epoch ${hosting.epoch}"
    }
    NativeHostingStatus.RECOVERING -> if (hosting.lifecycle == NativeHostingLifecycle.CLOSED)
        "Room closure pending" else "Hosting update pending"
    NativeHostingStatus.SUSPENDED -> "Hosting paused"
    NativeHostingStatus.FAILED -> "Hosting unavailable · reopen to inspect"
    NativeHostingStatus.CLOSED -> "Room closed"
}

@Composable
internal fun NativeHostingPanel(hosting: NativeHostingState) {
    Column {
        Text(nativeHostingLine(hosting), style = MaterialTheme.typography.titleSmall)
        hosting.epoch?.let {
            Text("Last observed epoch $it · ${hosting.approved.size} approved members · ${hosting.removed.size} removed",
                style = MaterialTheme.typography.bodySmall)
        }
        if (hosting.pendingOriginals.isNotEmpty()) Text(
            "${hosting.pendingOriginals.size} saved room updates pending. Hosting state does not confirm delivery to members.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
