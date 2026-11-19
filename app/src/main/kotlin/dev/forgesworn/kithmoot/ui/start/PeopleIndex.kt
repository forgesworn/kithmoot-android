package dev.forgesworn.kithmoot.ui.start

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.Normalizer

/** The strip's characters, top to bottom: A to Z, 0 to 9, then `#` for everything else. */
internal val PEOPLE_INDEX_CHARS: List<Char> = ('A'..'Z').toList() + ('0'..'9').toList() + '#'

/** Fewer people than this and the strip is clutter, not a shortcut. */
internal const val PEOPLE_INDEX_MIN_PEOPLE = 20

/**
 * Which strip character [label] files under: the first character with
 * diacritics folded and case ignored ("Élan" is E). Digits stay digits;
 * emoji, punctuation and letters that do not fold to A to Z are `#`.
 */
internal fun peopleIndexChar(label: String): Char {
    val first = label.trimStart().firstOrNull() ?: return '#'
    val base = Normalizer.normalize(first.toString(), Normalizer.Form.NFD).firstOrNull()?.uppercaseChar() ?: return '#'
    return if (base in 'A'..'Z' || base in '0'..'9') base else '#'
}

/**
 * The list position of the first row filed under each strip character.
 * [labels] are in list order, so the first occurrence wins when sections repeat
 * a letter; [positions] gives each label's lazy-list index (headings sit between sections).
 */
internal fun peopleIndexTargets(labels: List<String>, positions: List<Int>): Map<Char, Int> {
    val targets = linkedMapOf<Char, Int>()
    for ((i, label) in labels.withIndex()) targets.putIfAbsent(peopleIndexChar(label), positions[i])
    return targets
}

/**
 * Where tapping [char] scrolls: its own first row, else the nearest following
 * character that has people, else null (nothing to jump to).
 */
internal fun peopleIndexJump(char: Char, targets: Map<Char, Int>): Int? {
    val from = PEOPLE_INDEX_CHARS.indexOf(char)
    if (from < 0) return null
    for (i in from until PEOPLE_INDEX_CHARS.size) targets[PEOPLE_INDEX_CHARS[i]]?.let { return it }
    return null
}

/** The strip character under a touch at [y] within a strip [height] tall; clamped to the ends. */
internal fun peopleIndexCharAt(y: Float, height: Float): Char {
    if (height <= 0f) return PEOPLE_INDEX_CHARS.first()
    val i = (y / height * PEOPLE_INDEX_CHARS.size).toInt().coerceIn(0, PEOPLE_INDEX_CHARS.lastIndex)
    return PEOPLE_INDEX_CHARS[i]
}

/**
 * The A to Z 0 to 9 strip down the right edge. Tapping or dragging anywhere
 * along its full height jumps via [onJump]; characters with nobody are
 * dimmed but stay put. For TalkBack it is one adjustable control.
 */
@Composable
internal fun PeopleIndexStrip(targets: Map<Char, Int>, onJump: (Int) -> Unit, modifier: Modifier = Modifier) {
    var active by remember { mutableStateOf<Char?>(null) }
    var heightPx by remember { mutableStateOf(0f) }
    fun touch(y: Float) {
        val c = peopleIndexCharAt(y, heightPx)
        active = c
        peopleIndexJump(c, targets)?.let(onJump)
    }
    Box(modifier.fillMaxHeight()) {
        Column(
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(24.dp).padding(vertical = 4.dp)
                .pointerInput(targets) {
                    detectTapGestures(onPress = { offset ->
                        touch(offset.y)
                        tryAwaitRelease()
                        active = null
                    })
                }
                .pointerInput(targets) {
                    detectVerticalDragGestures(
                        onDragStart = { offset -> touch(offset.y) },
                        onDragEnd = { active = null },
                        onDragCancel = { active = null },
                        onVerticalDrag = { change, _ -> touch(change.position.y) },
                    )
                }
                .onSizeChanged { heightPx = it.height.toFloat() }
                .clearAndSetSemantics {
                    contentDescription = "Alphabet index"
                    val current = active ?: PEOPLE_INDEX_CHARS.first()
                    stateDescription = if (current == '#') "Other characters" else current.toString()
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        PEOPLE_INDEX_CHARS.indexOf(current).toFloat(), 0f..PEOPLE_INDEX_CHARS.lastIndex.toFloat(), PEOPLE_INDEX_CHARS.size - 2)
                    setProgress { value ->
                        val c = PEOPLE_INDEX_CHARS[value.toInt().coerceIn(0, PEOPLE_INDEX_CHARS.lastIndex)]
                        active = c
                        peopleIndexJump(c, targets)?.let(onJump)
                        true
                    }
                },
        ) {
            for (c in PEOPLE_INDEX_CHARS) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(c.toString(), fontSize = 10.sp, lineHeight = 10.sp, textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = if (c in targets) 1f else 0.35f))
            }
        }
        active?.let { c ->
            Surface(Modifier.align(Alignment.Center).padding(end = 32.dp), shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer, tonalElevation = 4.dp) {
                Text(c.toString(), Modifier.padding(horizontal = 24.dp, vertical = 12.dp), style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = if (c in targets) 1f else 0.5f))
            }
        }
    }
}
