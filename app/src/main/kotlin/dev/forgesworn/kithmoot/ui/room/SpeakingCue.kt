package dev.forgesworn.kithmoot.ui.room

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How "this person is talking" looks, decided in exactly one place.
 *
 * Never colour alone: the border gets markedly thicker and the name label
 * goes from see-through to solid, so the cue survives colour blindness, a
 * greyscale screen and a bright room. [inverted] exists so the whole thing
 * can be flipped - a quiet person marked instead of a loud one - without
 * hunting through every tile.
 */
data class SpeakingCueStyle(
    val borderWidth: Dp,
    /** The name label has a solid background rather than a see-through one. */
    val solidLabel: Boolean,
    /** Read out by TalkBack as the tile's state. */
    val stateDescription: String?,
)

fun speakingCue(speaking: Boolean, inverted: Boolean = false): SpeakingCueStyle {
    val marked = speaking != inverted
    return if (marked) {
        SpeakingCueStyle(borderWidth = 4.dp, solidLabel = true, stateDescription = if (speaking) "Speaking" else null)
    } else {
        SpeakingCueStyle(borderWidth = 1.dp, solidLabel = false, stateDescription = if (speaking) "Speaking" else null)
    }
}
