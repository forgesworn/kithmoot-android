package dev.forgesworn.kithmoot.ui.room

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.account.shortNpub
import dev.forgesworn.kithmoot.protocol.RecordingView
import dev.forgesworn.kithmoot.ui.RoomState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val clock = DateTimeFormatter.ofPattern("HH:mm")
private fun clockTime(seconds: Long): String = clock.format(Instant.ofEpochSecond(seconds).atZone(ZoneId.systemDefault()))

/** What the recording notice says, or null when there is nothing to say.
 *  Worded as the web client's banner is. */
internal fun recordingLine(view: RecordingView, description: String? = null, inRoom: Boolean = false): String? = when (view) {
    RecordingView.Off -> null
    is RecordingView.On -> "${if (inRoom) "A call in this room" else "This call"} is being recorded, since ${clockTime(view.since)}. ${description ?: "Audio and video you share may be included."} " +
        "KithMoot cannot stop anybody recording with another app, recording or not."
    is RecordingView.Unconfirmed -> "${if (inRoom) "A call in this room" else "This call"} may still be recording: the notice was last confirmed at ${clockTime(view.lastHeard)}."
}

internal fun recordingCaptureDescription(state: RoomState): String? {
    val captures = state.recordingCaptures.ifEmpty { listOfNotNull(state.recordingCapture) }
    val description = captures.mapNotNull { capture -> recordingCaptureDescription(state, capture) }
        .takeIf { it.isNotEmpty() }?.joinToString(" ") ?: return null
    val others = state.recordingWarnings.size - captures.size
    return description + if (others > 0) " There ${if (others == 1) "is" else "are"} $others other recording ${if (others == 1) "device" else "devices"} in this room." else ""
}

private fun recordingCaptureDescription(state: RoomState, capture: dev.forgesworn.kithmoot.protocol.RecordingCaptureNotice): String? {
    // Named as their tile names them: keyed only when two people here share
    // the name (design-calm-shell check 13).
    val who = callPersonName(state, capture.recorder)
    val what = when (capture.capture) {
        "audio" -> "call audio"
        "gallery" -> "gallery video and call audio"
        "speaker" -> "speaker video and call audio"
        "screen-camera" -> "a screen share with camera and call audio"
        else -> return null
    }
    return "$who is capturing $what."
}

/** What meeting mode means for this person, or null when it is off. */
internal fun meetingLine(state: RoomState): String? = when {
    !state.meetingOn -> null
    state.meetingModerator -> meetingPeople(state).count { it.handRaisedAt != null }.let { hands ->
        "Meeting mode is on. ${if (hands == 0) "No hands raised." else "$hands ${if (hands == 1) "hand" else "hands"} raised."}"
    }
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
internal fun RecordingBanner(view: RecordingView, modifier: Modifier = Modifier, description: String? = null, inRoom: Boolean = false, paused: Boolean = false) {
    val line = recordingLine(view, description, inRoom) ?: return
    val text = if (paused) "Paused on this device. $line" else line
    // One line until tapped, so a recorded call keeps its picture. TalkBack
    // reads the whole notice whatever is showing.
    var expanded by remember { mutableStateOf(false) }
    Surface(
        onClick = { expanded = !expanded },
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Text(
            "● $text",
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            maxLines = if (expanded) Int.MAX_VALUE else 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Somebody in the room, as the host's meeting list shows them. */
internal data class MeetingPerson(
    val participant: String,
    val name: String,
    val speaker: Boolean,
    /** Unix seconds their hand went up; null when it is down, or when they
     *  are already a speaker and so have nothing to ask. */
    val handRaisedAt: Long?,
)

/**
 * Everybody in the room but the host, raised hands first and oldest first -
 * who has waited longest - then everybody else in the roster's own order.
 * Mirrors the host's list in the web client's `renderMeeting`.
 */
internal fun meetingPeople(state: RoomState): List<MeetingPerson> = state.tiles
    .filter { !it.isSelf }
    .map { tile ->
        val speaker = tile.participant in state.meetingSpeakers
        MeetingPerson(
            participant = tile.participant,
            name = callPersonName(state, tile.participant),
            speaker = speaker,
            handRaisedAt = state.raisedHands[tile.participant]?.takeIf { !speaker },
        )
    }
    .sortedBy { it.handRaisedAt ?: Long.MAX_VALUE }

/**
 * The host's meeting controls: meeting mode on or off, and who is on the
 * stage. Only on the device that made the room, which is the only one
 * holding the key the room believes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MeetingSheet(
    state: RoomState,
    onDismiss: () -> Unit,
    onSetMeetingMode: (Boolean) -> Unit,
    onSetSpeaker: (String, Boolean) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 16.dp)) {
            Text("Meeting", style = MaterialTheme.typography.titleLarge)
            Text(
                if (state.meetingOn) "Only speakers can talk, show video or share a screen. Everybody else can raise their hand."
                else "In meeting mode only the speakers you choose can talk, show video or share a screen. You are one of them.",
                Modifier.padding(top = 4.dp, bottom = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = { onSetMeetingMode(!state.meetingOn) }) {
                Text(if (state.meetingOn) "End meeting mode" else "Start meeting mode")
            }
            val people = meetingPeople(state)
            Text("People", Modifier.padding(top = 20.dp, bottom = 4.dp), style = MaterialTheme.typography.titleMedium)
            if (people.isEmpty()) Text("Nobody else is in the room yet.", style = MaterialTheme.typography.bodyMedium)
            for (person in people) Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(person.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val status = when {
                        person.handRaisedAt != null -> "Hand raised"
                        person.speaker -> "Speaker"
                        else -> null
                    }
                    if (status != null) Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                OutlinedButton(onClick = { onSetSpeaker(person.participant, !person.speaker) }) {
                    Text(if (person.speaker) "Stop speaker" else "Make speaker")
                }
            }
        }
    }
}

/** Meeting mode, as an attendee needs it: why their controls are locked,
 *  and the hand to raise. The host is offered the list of people instead. */
@Composable
internal fun MeetingNotice(state: RoomState, onRaiseHand: (Boolean) -> Unit, modifier: Modifier = Modifier, onOpenMeeting: () -> Unit = {}) {
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
            if (state.meetingModerator) TextButton(onClick = onOpenMeeting) { Text("People") }
            else if (!state.meetingSpeaker) TextButton(onClick = { onRaiseHand(!state.handUp) }) {
                Text(if (state.handUp) "Lower your hand" else "Raise your hand")
            }
        }
    }
}

/** Asked before anything puts this device on a recorded call. Joining is
 *  the consent; Not now keeps the person in the room, off the call. */
/** Hide my IP address and another member's own call relay: asked once per host. Dismissing is Not now. */
@Composable
internal fun MemberRelayDialog(name: String, onAnswer: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        title = { Text(dev.forgesworn.kithmoot.ui.MemberRelayWords.title(name)) },
        text = { Text(dev.forgesworn.kithmoot.ui.MemberRelayWords.message(name)) },
        confirmButton = { TextButton(onClick = { onAnswer(true) }) { Text(dev.forgesworn.kithmoot.ui.MemberRelayWords.USE) } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text(dev.forgesworn.kithmoot.ui.MemberRelayWords.NOT_NOW) } },
    )
}

@Composable
internal fun RecordingConsentDialog(onAnswer: (Boolean) -> Unit, description: String? = null) {
    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        title = { Text("This call is being recorded") },
        text = {
            Text(
                "${description ?: "Audio and video you share may be included."} If you join, the media you share may be recorded. " +
                    "You can stay in the room and read the chat without joining the call.",
            )
        },
        confirmButton = { TextButton(onClick = { onAnswer(true) }) { Text("Join and be recorded") } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text("Not now") } },
    )
}
