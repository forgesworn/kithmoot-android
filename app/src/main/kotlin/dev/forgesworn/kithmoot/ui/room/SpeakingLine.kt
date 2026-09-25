package dev.forgesworn.kithmoot.ui.room

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.account.shortNpub
import dev.forgesworn.kithmoot.ui.RoomState
import kotlinx.coroutines.delay

/**
 * Who is talking right now, held briefly so a name does not flicker in and
 * out between syllables.
 *
 * Entry is immediate - the moment somebody is in `speaking` they are held -
 * but a name is only dropped [holdMs] after it last appeared, which is what
 * the flicker is: a person mid-sentence going in and out of `speaking` as
 * their level dips below the threshold for a moment.
 */
internal class HeldSpeakers(private val holdMs: Long = 1_000) {
    private val lastSpoken = mutableMapOf<String, Long>()

    /** True while somebody is being held past their actual speaking state,
     *  which is the only time a caller need tick this again on a timer. */
    var pending: Boolean = false
        private set

    fun update(speaking: Set<String>, present: Set<String>, now: Long): Set<String> {
        lastSpoken.keys.retainAll { it in present }
        for (id in speaking) lastSpoken[id] = now
        val held = lastSpoken.filterValues { now - it <= holdMs }.keys.toSet()
        lastSpoken.keys.retainAll(held)
        pending = held.any { it !in speaking }
        return held
    }
}

/** [HeldSpeakers.update], reread on a timer only while somebody is held past
 *  their actual speaking state - a quiet call ticks nothing. */
@Composable
internal fun rememberHeldSpeaking(speaking: Set<String>, present: Set<String>): Set<String> {
    val tracker = remember { HeldSpeakers() }
    var held by remember {
        mutableStateOf(tracker.update(speaking, present, android.os.SystemClock.elapsedRealtime()))
    }
    LaunchedEffect(speaking, present) {
        held = tracker.update(speaking, present, android.os.SystemClock.elapsedRealtime())
        while (tracker.pending) {
            delay(200)
            held = tracker.update(speaking, present, android.os.SystemClock.elapsedRealtime())
        }
    }
    return held
}

/**
 * The names to show for who is speaking, in the room's own tile order.
 *
 * Self reads "You" rather than whatever name is on record, matching every
 * other self-referring label on the call screen. A pure function so the
 * formatting can be tested without Compose.
 */
internal fun speakingNames(
    held: Set<String>,
    tiles: List<ParticipantTile>,
    profiles: Map<String, PublicProfile>,
): List<String> = tiles.filter { it.participant in held }.map { tile ->
    when {
        tile.isSelf -> "You"
        else -> profiles[tile.participant]?.name
            ?: tile.cardName?.takeIf { it.isNotBlank() }
            ?: tile.name
            ?: shortNpub(tile.participant)
    }
}

/** "Speaking: Daz, Donkey", or empty when nobody is. */
internal fun speakingLine(names: List<String>): String =
    if (names.isEmpty()) "" else "Speaking: " + names.joinToString(", ")

/**
 * One line naming who is talking, or nothing while the call is quiet.
 *
 * Deliberately plain text: no live-region semantics and no
 * `announceForAccessibility`, so TalkBack reaches it like any other label on
 * the screen rather than interrupting to announce every change of speaker.
 */
@Composable
internal fun SpeakingLine(state: RoomState, modifier: Modifier = Modifier) {
    val present = state.tiles.map { it.participant }.toSet()
    val held = rememberHeldSpeaking(state.speaking, present)
    val profiles = if (state.profilesEnabled) state.profiles else emptyMap()
    val line = speakingLine(speakingNames(held, state.tiles, profiles))
    if (line.isNotEmpty()) Text(
        line,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f), RectangleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        style = MaterialTheme.typography.bodySmall,
        color = Color.White,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
