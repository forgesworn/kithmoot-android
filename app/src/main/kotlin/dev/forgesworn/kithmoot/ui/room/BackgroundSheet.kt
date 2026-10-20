package dev.forgesworn.kithmoot.ui.room

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.media.effects.BackgroundChoice
import dev.forgesworn.kithmoot.media.effects.FacePrivacyMode
import dev.forgesworn.kithmoot.media.effects.SeaScene
import dev.forgesworn.kithmoot.ui.settings.SettingsSwitchRow

/**
 * "Hide what is behind you", as a sheet.
 *
 * The same wording and the same four scenes as the web client, so somebody who
 * has used one recognises the other, and the fish are a switch over whichever
 * sea has been picked rather than a fifth entry in the list - "which picture"
 * and "is anything swimming in it" are two questions.
 *
 * Nothing here sets its own text size: every label is a Material type style, so
 * it follows the app's own text size choice (see ui/theme/TextSize.kt) as well
 * as the phone's.
 *
 * The note at the bottom is not decoration. Segmentation is a guess, it is
 * worst exactly where somebody would want it to be best, and a feature sold as
 * privacy has to say what it is not.
 */
@Composable
fun BackgroundSheet(
    choice: BackgroundChoice,
    onChoose: (SeaScene?, Boolean) -> Unit,
    onDone: () -> Unit,
    onChooseFace: (FacePrivacyMode) -> Unit = {},
    faceStatus: String = "",
    /** Flip camera sits with the effects on a phone (design-calm-shell 5.5). */
    cameraOn: Boolean = false,
    onSwitchCamera: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Effects", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        if (cameraOn) androidx.compose.material3.OutlinedButton(onClick = onSwitchCamera, modifier = Modifier.heightIn(min = 48.dp)) {
            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Cameraswitch, null)
            androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
            Text("Flip camera")
        }
        Text("Face", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
        for (mode in FacePrivacyMode.entries) {
            SceneRow(if (mode == FacePrivacyMode.MASK) "Tracked mask unavailable" else mode.label, choice.face == mode, enabled = mode != FacePrivacyMode.MASK) { onChooseFace(mode) }
        }
        if (faceStatus.isNotBlank() && choice.face != FacePrivacyMode.OFF) Text(faceStatus, style = MaterialTheme.typography.bodySmall)
        Text(
            "Avatar replaces the whole camera picture. Automatic face masking is being developed. " +
                "The camera stays on locally. Your voice and shared screen can still identify you.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("Background", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp).semantics { heading() })
        Text("Background cut-outs are unavailable in this build. A background replaces the whole picture.", style = MaterialTheme.typography.bodySmall)

        SceneRow(
            label = "Off",
            selected = choice.scene == null,
            onSelect = { onChoose(null, choice.fish) },
        )
        for (scene in SeaScene.entries) {
            SceneRow(
                label = scene.label,
                selected = choice.scene == scene,
                onSelect = { onChoose(scene, choice.fish) },
            )
        }

        SettingsSwitchRow(
            title = "Fish swimming past",
            summary = "Only with a sea scene",
            checked = choice.showsFish,
            enabled = choice.scene?.asset != null,
            onCheckedChange = { wanted -> onChoose(choice.scene, wanted) },
        )

        Text(
            "This is the phone's best guess at where you stop and your room " +
                "starts, so edges, dim light and quick movement all let bits of " +
                "the room through for a moment at a time. It uses battery. It " +
                "makes your room harder to see. It is not a promise that nobody " +
                "can see it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        TextButton(onClick = onDone, modifier = Modifier.padding(top = 4.dp)) { Text("Done") }
    }
}

@Composable
private fun SceneRow(label: String, selected: Boolean, enabled: Boolean = true, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
