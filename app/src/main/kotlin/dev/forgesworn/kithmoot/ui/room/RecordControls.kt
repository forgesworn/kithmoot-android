package dev.forgesworn.kithmoot.ui.room

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** The red of a record button, the same on light and dark. */
internal val RecordRed = Color(0xFFE53935)

/**
 * Record, pause and stop as one short row of round buttons, beside the
 * recording notice rather than stacked under it, so a recorded call keeps
 * its picture.
 */
@Composable
internal fun RecordControls(
    recording: Boolean,
    paused: Boolean,
    preparing: Boolean,
    busy: Boolean,
    stopPending: Boolean,
    onRecord: () -> Unit,
    onStop: () -> Unit,
    onTogglePause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        when {
            preparing -> {
                Working("Announcing recording")
                IconButton(onClick = onStop) { Icon(Icons.Filled.Close, contentDescription = "Cancel recording start") }
            }
            busy -> Working(if (recording) "Updating recording" else "Finalising recording")
            recording -> {
                IconButton(onClick = onTogglePause) {
                    Icon(
                        if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = if (paused) "Resume recording" else "Pause recording",
                    )
                }
                IconButton(onClick = onStop) {
                    Icon(Icons.Filled.Stop, contentDescription = "Stop recording", tint = RecordRed)
                }
            }
            else -> RecordButton(enabled = !stopPending, onClick = onRecord)
        }
    }
}

/** A red dot in a ring: the record button everybody already knows. */
@Composable
private fun RecordButton(enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.semantics { contentDescription = "Record call" }) {
        Box(
            Modifier.size(28.dp).alpha(if (enabled) 1f else 0.38f)
                .border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(18.dp).background(RecordRed, CircleShape))
        }
    }
}

@Composable
private fun Working(description: String) {
    Box(Modifier.size(48.dp).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}
