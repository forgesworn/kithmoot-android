package dev.forgesworn.kithmoot.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/*
 * The rows every settings page is built from (settings-ux-spec section 7 and 8).
 * One row is one target: it merges its words into a single accessible node, is
 * at least 56 dp tall (72 with a summary) and never a fixed height, so it
 * grows with the text size. Type comes from the theme: bodyLarge for a title,
 * bodyMedium in onSurfaceVariant for a summary, titleSmall in primary for a
 * section header. No cards and no borders: space and the header group a section.
 */

private val RowHorizontal = 24.dp
private val DisabledAlpha = 0.6f

/** A titled group of rows. A null [title] gives the rows with no header. */
@Composable
fun SettingsSection(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = RowHorizontal, end = RowHorizontal, top = 24.dp, bottom = 8.dp)
                    .semantics { heading() })
        }
        content()
    }
}

/** The words of a row: a title and, under it, a summary. */
@Composable
private fun RowText(title: String, summary: String?, modifier: Modifier = Modifier, titleColor: Color = MaterialTheme.colorScheme.onSurface) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
        if (summary != null) {
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp))
        }
    }
}

private fun Modifier.rowFrame(summary: String?): Modifier =
    fillMaxWidth().heightIn(min = if (summary == null) 56.dp else 72.dp).padding(horizontal = RowHorizontal, vertical = 12.dp)

/** A row that opens something: another page, a dialog, or (with [external]) a screen outside the app. */
@Composable
fun SettingsNavRow(
    title: String,
    summary: String? = null,
    icon: ImageVector? = null,
    leading: (@Composable () -> Unit)? = null,
    external: Boolean = false,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val highlight = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent
    Box(Modifier.fillMaxWidth().background(highlight)) {
        if (selected) Box(Modifier.width(4.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary).align(Alignment.CenterStart))
        Row(
            Modifier.clickable(enabled = enabled, role = Role.Button, onClickLabel = if (external) "Open in Android" else "Open", onClick = onClick)
                .rowFrame(summary),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (leading != null) leading()
            else if (icon != null) Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            RowText(title, summary, Modifier.weight(1f).alpha(if (enabled) 1f else DisabledAlpha))
            Icon(
                if (external) Icons.AutoMirrored.Outlined.OpenInNew else Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A switch whose whole row toggles; the switch itself carries no separate click or name. */
@Composable
fun SettingsSwitchRow(title: String, summary: String? = null, checked: Boolean, enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange).rowFrame(summary),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RowText(title, summary, Modifier.weight(1f).alpha(if (enabled) 1f else DisabledAlpha))
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

/** One choice from a short list, each row a whole-row radio. */
@Composable
fun <T> SettingsRadioGroup(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Column(Modifier.fillMaxWidth().selectableGroup()) {
        for (option in options) {
            Row(
                Modifier.selectable(selected = option == selected, role = Role.RadioButton, onClick = { onSelect(option) })
                    .rowFrame(null),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                RadioButton(selected = option == selected, onClick = null)
                Text(label(option), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/** A row that does something once: play a sound, sign out. [destructive] draws it in the error colour. */
@Composable
fun SettingsActionRow(title: String, icon: ImageVector? = null, destructive: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val colour = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick).rowFrame(null),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(24.dp), tint = if (destructive) colour else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.bodyLarge, color = colour, modifier = Modifier.alpha(if (enabled) 1f else DisabledAlpha))
    }
}

/** Explanatory text under a section. With [live] a change is announced without moving focus. */
@Composable
fun SettingsNote(text: String, live: Boolean = false) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = RowHorizontal, vertical = 8.dp)
            .then(if (live) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier))
}

/** A row that shows or hides more rows beneath it, closed until opened and remembered across rotation. */
@Composable
fun SettingsDisclosure(title: String, summary: String? = null, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.clickable(role = Role.Button, onClickLabel = if (expanded) "Hide" else "Show") { expanded = !expanded }
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                .rowFrame(summary),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            RowText(title, summary, Modifier.weight(1f))
            Icon(if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown, null, Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnimatedVisibility(expanded, enter = expandVertically(tween(200)), exit = shrinkVertically(tween(200))) {
            Column(Modifier.fillMaxWidth()) { content() }
        }
    }
}

/**
 * The 94% bottom sheet every settings surface opens: its [title] on the left,
 * Done on the right, and the body scrolling beneath. [padded] gives a body of
 * loose controls the 24 dp margins that kit rows already carry themselves.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    title: String,
    onDone: () -> Unit,
    doneEnabled: Boolean = true,
    padded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = { if (doneEnabled) onDone() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.94f).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(start = RowHorizontal, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).semantics { heading() })
                TextButton(onDone, Modifier.heightIn(min = 48.dp), enabled = doneEnabled) { Text("Done") }
            }
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                    .then(if (padded) Modifier.padding(horizontal = RowHorizontal) else Modifier).padding(bottom = 32.dp),
                verticalArrangement = if (padded) Arrangement.spacedBy(12.dp) else Arrangement.Top,
            ) { content() }
        }
    }
}
