package dev.forgesworn.kithmoot.ui.room

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.protocol.RecordingView
import dev.forgesworn.kithmoot.ui.RoomState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val clock = DateTimeFormatter.ofPattern("HH:mm")
private fun clockTime(seconds: Long): String = clock.format(Instant.ofEpochSecond(seconds).atZone(ZoneId.systemDefault()))

/** What the recording notice says, or null when there is nothing to say.
 *  Worded as the web client's banner is. */
internal fun recordingLine(view: RecordingView): String? = when (view) {
    RecordingView.Off -> null
    is RecordingView.On -> "This call is being recorded, since ${clockTime(view.since)}. What is said on the call is in the recording. " +
        "KithMoot cannot stop anybody recording with another app, recording or not."
    is RecordingView.Unconfirmed -> "This call may still be recording: the notice was last confirmed at ${clockTime(view.lastHeard)}."
}

/** What meeting mode means for this person, or null when it is off. */
internal fun meetingLine(state: RoomState): String? = when {
    !state.meetingOn -> null
    state.meetingSpeaker -> "Meeting mode: you are a speaker."
    state.handUp -> "Meeting mode: your hand is up. The host can make you a speaker."
    else -> "Meeting mode: only speakers can talk or show video."
}

/**
 * The red notice everybody in the room sees while its call is recorded,
 * on the call or not: shown wherever the room is, never behind the call's
 * hidden controls.
 */
@Composable
internal fun RecordingBanner(view: RecordingView, modifier: Modifier = Modifier) {
    val line = recordingLine(view) ?: return
    Surface(
        modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Text("● $line", Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodySmall)
    }
}

/** Meeting mode, as an attendee needs it: why their controls are locked,
 *  and the hand to raise. */
@Composable
internal fun MeetingNotice(state: RoomState, onRaiseHand: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val line = meetingLine(state) ?: return
    Surface(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(line, Modifier.weight(1f).padding(vertical = 10.dp), style = MaterialTheme.typography.bodySmall)
            if (!state.meetingSpeaker) TextButton(onClick = { onRaiseHand(!state.handUp) }) {
                Text(if (state.handUp) "Lower your hand" else "Raise your hand")
            }
        }
    }
}

/** Asked before anything puts this device on a recorded call. Joining is
 *  the consent; Not now keeps the person in the room, off the call. */
@Composable
internal fun RecordingConsentDialog(onAnswer: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        title = { Text("This call is being recorded") },
        text = {
            Text(
                "The person who made this room is recording the call's sound. If you join, what you say is in the recording. " +
                    "You can stay in the room and read the chat without joining the call.",
            )
        },
        confirmButton = { TextButton(onClick = { onAnswer(true) }) { Text("Join and be recorded") } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text("Not now") } },
    )
}
