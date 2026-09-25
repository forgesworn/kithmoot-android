package dev.forgesworn.kithmoot.ui.room

import dev.forgesworn.kithmoot.session.Roles
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * How the call view is arranged, decided without Compose so it can be tested.
 *
 * The rule of thumb is Signal's: the people you are talking to fill the
 * screen, you are a small picture you can move out of the way, and what is
 * large changes only when there is a reason.
 */
enum class CallLayoutMode {
    /** Nobody else here: your own picture and the invitation. */
    ALONE,
    /** One other person, full screen. */
    ONE_TO_ONE,
    /** Two to three others, equal tiles. Also the grid toggle for larger calls. */
    GRID,
    /** Four or more others: whoever is talking is large, everyone else in a strip. */
    SPEAKER,
    /** Somebody is sharing a screen: the share is large, people in a strip. */
    SHARE,
}

/** One box on the call view: a person's camera, or one of their shared screens. */
data class CallItem(val participant: String, val screen: TileTrack? = null) {
    val isScreen: Boolean get() = screen != null
}

data class CallArrangement(
    val mode: CallLayoutMode,
    /** What is large. Null in [CallLayoutMode.GRID]. */
    val stage: CallItem? = null,
    /** Equal tiles, in [CallLayoutMode.GRID]. */
    val grid: List<CallItem> = emptyList(),
    /** The row of small equal tiles beside a stage. */
    val strip: List<CallItem> = emptyList(),
    /** The small picture that can be dragged about. Usually you. */
    val floating: CallItem? = null,
)

/** Past this many other people, a large speaker beats a grid of postage stamps. */
const val SPEAKER_LAYOUT_FROM: Int = 4

/**
 * Arranges the call.
 *
 * @param activeSpeaker whoever [ActiveSpeaker] has settled on, if anyone.
 * @param preferGrid the person asked for equal tiles in a large call.
 * @param swapped in a one-to-one call, your picture is large and theirs small.
 * @param hideSelf your own picture is tucked away. You are never removed
 *   from a stage you asked for, only from the floating tile.
 */
fun arrangeCall(
    tiles: List<ParticipantTile>,
    activeSpeaker: String? = null,
    preferGrid: Boolean = false,
    swapped: Boolean = false,
    hideSelf: Boolean = false,
): CallArrangement {
    val self = tiles.firstOrNull { it.isSelf }
    val others = tiles.filter { !it.isSelf }
    val selfItem = self?.let { CallItem(it.participant) }
    val floatingSelf = selfItem.takeIf { !hideSelf }

    // Somebody else's share. Your own is not put on your own stage: you
    // would be looking at your screen, inside your screen.
    val share = others.firstNotNullOfOrNull { tile ->
        tile.videos.firstOrNull { it.role == Roles.SCREEN }?.let { CallItem(tile.participant, it) }
    }
    if (share != null) {
        val people = others.map { CallItem(it.participant) } + listOfNotNull(floatingSelf)
        return CallArrangement(CallLayoutMode.SHARE, stage = share, strip = people)
    }

    return when {
        others.isEmpty() -> CallArrangement(CallLayoutMode.ALONE, stage = selfItem)
        others.size == 1 -> {
            val other = CallItem(others.single().participant)
            if (swapped && selfItem != null) CallArrangement(CallLayoutMode.ONE_TO_ONE, stage = selfItem, floating = other)
            else CallArrangement(CallLayoutMode.ONE_TO_ONE, stage = other, floating = floatingSelf)
        }
        others.size < SPEAKER_LAYOUT_FROM || preferGrid ->
            CallArrangement(CallLayoutMode.GRID, grid = others.map { CallItem(it.participant) }, floating = floatingSelf)
        else -> {
            val lead = others.firstOrNull { it.participant == activeSpeaker } ?: others.first()
            CallArrangement(
                CallLayoutMode.SPEAKER,
                stage = CallItem(lead.participant),
                strip = others.filter { it !== lead }.map { CallItem(it.participant) },
                floating = floatingSelf,
            )
        }
    }
}

/** Columns and rows for [count] equal tiles. Two across on a phone held upright. */
fun gridShape(count: Int, landscape: Boolean): Pair<Int, Int> {
    if (count <= 0) return 0 to 0
    val columns = when {
        count == 1 -> 1
        count == 2 -> if (landscape) 2 else 1
        count == 4 -> 2
        landscape -> minOf(count, ceil(sqrt(count * 16.0 / 9.0)).toInt().coerceAtLeast(2))
        else -> 2
    }
    val rows = (count + columns - 1) / columns
    return columns to rows
}

/**
 * Who is on the stage of a large call, with some reluctance to change.
 *
 * A stage that follows every "mm" and cough is worse than one that never
 * moves. The rule: somebody new takes the stage once they have spoken for
 * [holdMs] without a break, while whoever holds it is quiet. Crosstalk
 * leaves it where it is. A person who leaves hands it on at once.
 */
class ActiveSpeaker(private val holdMs: Long = 1_500) {
    var current: String? = null
        private set
    private var candidate: String? = null

    /** Somebody is working towards the stage: only then does the answer
     *  change with time alone, and only then does a caller need to tick. */
    val pending: Boolean get() = candidate != null
    private var candidateSince = 0L

    fun update(speaking: Set<String>, present: List<String>, now: Long): String? {
        if (present.isEmpty()) {
            current = null
            candidate = null
            return null
        }
        val holder = current
        if (holder == null || holder !in present) {
            current = present.firstOrNull { it in speaking } ?: present.first()
            candidate = null
            return current
        }
        val challenger = candidate?.takeIf { it in speaking && it in present && it != holder }
            ?: present.firstOrNull { it != holder && it in speaking }
        if (challenger == null) {
            candidate = null
            return holder
        }
        if (challenger != candidate) {
            candidate = challenger
            candidateSince = now
        }
        if (holder !in speaking && now - candidateSince >= holdMs) {
            current = challenger
            candidate = null
        }
        return current
    }
}

/**
 * Whether the controls and the top bar may fade away on their own.
 *
 * Only while there is a picture to look at: on an audio-only call there is
 * nothing to make room for, and a hidden mute button is a hazard. Never while
 * a sheet or dialog has the window, and never for somebody using an
 * accessibility service, who may not be able to find a control that is not
 * there.
 */
fun controlsMayAutoHide(videoShowing: Boolean, windowFocused: Boolean, accessibilityOn: Boolean): Boolean =
    videoShowing && windowFocused && !accessibilityOn

/** How long the controls stay after the last touch. */
const val CONTROLS_HIDE_AFTER_MS: Long = 4_000

/** The corners the floating picture settles into, in absolute (not RTL-mirrored) terms. */
enum class Corner(val top: Boolean, val left: Boolean) {
    TOP_LEFT(true, true),
    TOP_RIGHT(true, false),
    BOTTOM_LEFT(false, true),
    BOTTOM_RIGHT(false, false),
    ;

    companion object {
        val DEFAULT = BOTTOM_RIGHT
        fun parse(name: String?): Corner = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * Where a dragged picture comes to rest.
 *
 * The drop point is carried on by the fling for a fifth of a second, so a
 * flick towards a corner lands there even if the finger lifted short of the
 * middle line. All values are pixels within the area the picture may occupy.
 */
fun nearestCorner(
    x: Float,
    y: Float,
    tileWidth: Float,
    tileHeight: Float,
    areaWidth: Float,
    areaHeight: Float,
    velocityX: Float = 0f,
    velocityY: Float = 0f,
): Corner {
    val centreX = x + tileWidth / 2 + velocityX * FLING_PROJECTION_S
    val centreY = y + tileHeight / 2 + velocityY * FLING_PROJECTION_S
    val left = centreX < areaWidth / 2
    val top = centreY < areaHeight / 2
    return Corner.entries.first { it.left == left && it.top == top }
}

/** The top-left position of the picture resting in [corner], [margin] in from the edges. */
fun cornerPosition(
    corner: Corner,
    tileWidth: Float,
    tileHeight: Float,
    areaWidth: Float,
    areaHeight: Float,
    margin: Float,
): Pair<Float, Float> {
    val x = if (corner.left) margin else areaWidth - tileWidth - margin
    val y = if (corner.top) margin else areaHeight - tileHeight - margin
    // A window too small for the tile and its margins pins it to the start
    // rather than pushing it off the top or left edge.
    return x.coerceAtLeast(0f) to y.coerceAtLeast(0f)
}

private const val FLING_PROJECTION_S = 0.2f
