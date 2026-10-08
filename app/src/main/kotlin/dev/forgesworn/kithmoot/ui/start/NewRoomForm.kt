package dev.forgesworn.kithmoot.ui.start

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import kotlin.math.roundToInt
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.session.ConferenceLength

/**
 * The name field, the Tor row, when the room ends and the Start button: the whole of "starting
 * a room" in one reusable shape, used inline on the cold screen and the
 * expanded pane, and inside the New room sheet everywhere else.
 */
@Composable
internal fun NewRoomForm(
    roomName: String,
    onRoomNameChanged: (String) -> Unit,
    anonymousMode: Boolean,
    onAnonymousModeChanged: (Boolean) -> Unit,
    enabled: Boolean,
    busy: Boolean,
    error: String?,
    onStartRoom: () -> Unit,
    onCancel: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    nameFieldModifier: Modifier = Modifier,
    conferenceLength: ConferenceLength = ConferenceLength.NEVER,
    /** Null hides the choice, as the disabled preview behind the storage error does. */
    onConferenceLengthChanged: ((ConferenceLength) -> Unit)? = null,
    /** When a room with an end ends: self-destruct (the default) or keep a read-only copy. */
    roomDestruct: Boolean = true,
    onRoomDestructChanged: ((Boolean) -> Unit)? = null,
    durationSeconds: Int = 7200, onDurationChanged: (Int) -> Unit = {},
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = roomName, onValueChange = onRoomNameChanged,
            modifier = Modifier.fillMaxWidth().then(nameFieldModifier), enabled = enabled,
            label = { Text("Room name (optional)") }, placeholder = { Text("e.g. Saturday workshop") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { if (enabled && (conferenceLength != ConferenceLength.CUSTOM || durationSeconds >= 60)) onStartRoom() }),
        )
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .toggleable(value = anonymousMode, enabled = enabled, role = Role.Switch, onValueChange = onAnonymousModeChanged),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Tor-only room (Orbot)", style = MaterialTheme.typography.titleSmall)
                Text(
                    "A new identity just for this room, over Tor only. No account, profiles, agents or calls.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = anonymousMode, onCheckedChange = null, enabled = enabled)
        }
        if (onConferenceLengthChanged != null) ConferenceLengthChoice(conferenceLength, onConferenceLengthChanged, enabled)
        if (onConferenceLengthChanged != null && conferenceLength == ConferenceLength.CUSTOM) {
            RoomDurationSliders(durationSeconds, onDurationChanged, enabled, roomDestruct)
        }
        if (onConferenceLengthChanged != null && onRoomDestructChanged != null && conferenceLength != ConferenceLength.NEVER) {
            WhenItEndsChoice(roomDestruct, onRoomDestructChanged, enabled)
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onStartRoom, enabled = enabled && !busy && (conferenceLength != ConferenceLength.CUSTOM || durationSeconds >= 60), modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Start a room") }
            if (onCancel != null) TextButton(onCancel, Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
    }
}

/**
 * When the room ends: Never, the default, or a conference room that ends
 * after a day, three or seven, and is wiped from relays when it does.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConferenceLengthChoice(selected: ConferenceLength, onSelected: (ConferenceLength) -> Unit, enabled: Boolean) {
    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Ends", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (length in ConferenceLength.entries) {
                FilterChip(
                    selected = length == selected, onClick = { onSelected(length) }, enabled = enabled,
                    label = { Text(length.label) },
                    modifier = Modifier.heightIn(min = 48.dp).semantics { role = Role.RadioButton },
                )
            }
        }
        if (selected != ConferenceLength.NEVER) Text(
            "A conference room: when it ends it closes for everyone and relays delete it.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * When a room with an end ends: Self-destruct, the default (owner decision
 * D1), or Keep it read-only. Offered only for a room with an end, as on the
 * web: a room with no end self-destructs only when its authority ends it, and
 * this phone has no way to end a room early, so it does not offer that.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WhenItEndsChoice(destruct: Boolean, onSelected: (Boolean) -> Unit, enabled: Boolean) {
    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("When it ends", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((value, label) in listOf(true to "Self-destruct", false to "Keep it read-only")) {
                FilterChip(
                    selected = value == destruct, onClick = { onSelected(value) }, enabled = enabled,
                    label = { Text(label) },
                    modifier = Modifier.heightIn(min = 48.dp).semantics { role = Role.RadioButton },
                )
            }
        }
        if (destruct) Text(
            dev.forgesworn.kithmoot.session.DESTRUCT_PROMISE,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}


@Composable
internal fun RoomDurationSliders(seconds: Int, onChanged: (Int) -> Unit, enabled: Boolean, destruct: Boolean) {
    val days = seconds / 86400
    val hours = seconds % 86400 / 3600
    val minutes = seconds % 3600 / 60
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((label, value, max) in listOf(Triple("Days", days, 30), Triple("Hours", hours, 23), Triple("Minutes", minutes, 59))) {
            Text("$label: $value", style = MaterialTheme.typography.titleSmall)
            Slider(value.toFloat(), onValueChange = { position ->
                val next = position.roundToInt()
                val total = when (label) {
                    "Days" -> next * 86400 + hours * 3600 + minutes * 60
                    "Hours" -> days * 86400 + next * 3600 + minutes * 60
                    else -> days * 86400 + hours * 3600 + next * 60
                }
                onChanged(total.coerceAtMost(30 * 86400))
            }, enabled = enabled && (label == "Days" || days < 30), valueRange = 0f..max.toFloat(), steps = max - 1,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = label })
        }
        val now = dev.forgesworn.kithmoot.ui.room.rememberNow()
        Text(if (seconds >= 60) "${if (destruct) "Self-destructs" else "Ends"} ${dev.forgesworn.kithmoot.session.conferenceEndLabel(now + seconds)} · $days days, $hours hours, $minutes minutes from creation."
            else "Choose at least one minute.", style = MaterialTheme.typography.bodySmall,
            color = if (seconds >= 60) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
    }
}
