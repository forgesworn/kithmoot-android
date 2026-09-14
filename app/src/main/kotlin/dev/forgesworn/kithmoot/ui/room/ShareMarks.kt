package dev.forgesworn.kithmoot.ui.room

import dev.forgesworn.kithmoot.protocol.ScreenAnnotation

/**
 * Marks drawn on a screen share, kept only for as long as they are useful.
 *
 * A stroke is a pointer, not a record: "there, that button". It is drawn to
 * be seen in the moment by the person sharing, so it holds for a couple of
 * seconds and then fades, and nothing about it is kept once it has gone.
 * Direct port of the web client's `ShareMarks` (`app/src/share-marks.ts`):
 * same hold/fade timing, same palette and clash-free colour assignment,
 * same per-share and total caps, so the two clients agree on what "still
 * showing" and "whose colour" mean even though nothing about display is on
 * the wire.
 */
const val MARK_HOLD_MS: Long = 2_000
const val MARK_FADE_MS: Long = 1_000
const val MARK_LIFETIME_MS: Long = MARK_HOLD_MS + MARK_FADE_MS

/** How strongly a stroke of this age should be painted: solid, then gone. */
fun markAlpha(ageMs: Long): Float {
    if (ageMs < MARK_HOLD_MS) return 1f
    return (1f - (ageMs - MARK_HOLD_MS).toFloat() / MARK_FADE_MS).coerceAtLeast(0f)
}

/**
 * Who drew a mark, resolved once when it arrives rather than looked up
 * fresh on every paint. Colour is deliberately not part of this: see
 * [coloursForShare] for why it depends on who else is drawing on the same
 * share right now.
 */
data class MarkAuthor(
    /** The participant pubkey. Every device of theirs, and every stroke of
     *  theirs however it arrived, carries the same key, so a colour and a
     *  label stay one person's, not one wire event's. */
    val key: String,
    /** What the label chip shows: a roster display name, or the short key
     *  when there is no name yet. */
    val label: String,
)

/** Mirrors the web client's `MARK_COLOURS`. Picked to stay legible over
 *  arbitrary screen content: a dark halo under a fairly light, saturated line. */
val MARK_COLOURS: List<String> = listOf("#ffd447", "#5ec8ff", "#ff6b9d", "#7ee787", "#c792ea", "#ff9f5b", "#8fa6ff")

/**
 * Bit-identical to the web client's hash (`Math.imul(hash, 31) + charCode`,
 * kept to 32 bits, taken unsigned): Kotlin's `Int` arithmetic already wraps
 * at 32 bits the same way, so only the final unsigned interpretation needs
 * doing by hand, not every step.
 */
private fun hashIndex(participant: String): Int {
    var hash = 0
    for (c in participant) hash = hash * 31 + c.code
    val unsigned = hash.toLong() and 0xFFFFFFFFL
    return (unsigned % MARK_COLOURS.size).toInt()
}

/** A participant's colour on its own, with no regard to who else is drawing. */
fun colourForParticipant(participant: String): String = MARK_COLOURS[hashIndex(participant)]

/**
 * Colours for everyone currently drawing on one share, clash-free while the
 * palette allows it. Each participant starts at their [colourForParticipant]
 * index; sorted by pubkey - an order every device can compute alone - a
 * clash moves to the next free index, wrapping around.
 */
fun coloursForShare(participants: Collection<String>): Map<String, String> {
    val sorted = participants.toSortedSet()
    val used = mutableSetOf<Int>()
    val colours = LinkedHashMap<String, String>()
    for (participant in sorted) {
        var index = hashIndex(participant)
        if (used.size < MARK_COLOURS.size) while (index in used) index = (index + 1) % MARK_COLOURS.size
        used += index
        colours[participant] = MARK_COLOURS[index]
    }
    return colours
}

/** One stroke as it should be painted right now. */
data class LiveMark(val annotation: ScreenAnnotation, val alpha: Float, val author: MarkAuthor, val color: String)

private data class KeptStroke(val annotation: ScreenAnnotation, val at: Long, val author: MarkAuthor)

private const val MAX_STROKES_PER_SHARE = 100
private const val MAX_SHARES = 16

/**
 * Marks drawn on every screen share this device knows about right now.
 *
 * `now` is injected so tests are not at the mercy of the real clock. Every
 * public method is synchronized: [RoomViewModel] calls [remember] from a
 * signal-collecting coroutine and [alive]/[shareIds] from a UI-facing tick,
 * and the two must not interleave a half-updated share.
 */
class ShareMarks(private val now: () -> Long = { System.currentTimeMillis() }) {
    private val kept = LinkedHashMap<String, MutableList<KeptStroke>>()

    /** Keep a stroke under the author who drew it, or drop every stroke on a
     *  share for a clear. Duplicates by stroke id are ignored, so a stroke
     *  that arrives twice is one mark. */
    @Synchronized
    fun remember(annotation: ScreenAnnotation, author: MarkAuthor) {
        if (annotation.op == "clear") {
            kept.remove(annotation.shareId)
            return
        }
        val strokes = kept.getOrPut(annotation.shareId) { mutableListOf() }
        if (strokes.any { it.annotation.strokeId == annotation.strokeId }) return
        strokes.add(KeptStroke(annotation, now(), author))
        while (strokes.size > MAX_STROKES_PER_SHARE) strokes.removeAt(0)
        // Move this share to the end (most-recently-used), so an idle share
        // is what a cap eviction drops first.
        kept.remove(annotation.shareId)
        kept[annotation.shareId] = strokes
        while (kept.size > MAX_SHARES) {
            val oldest = kept.keys.firstOrNull() ?: break
            kept.remove(oldest)
        }
    }

    /** The strokes still showing on a share, each with how strongly to
     *  paint it and a colour resolved against everybody else currently on
     *  it. Strokes past their lifetime are forgotten on the way out. */
    @Synchronized
    fun alive(shareId: String): List<LiveMark> {
        val strokes = kept[shareId] ?: return emptyList()
        val t = now()
        val live = strokes.filter { t - it.at < MARK_LIFETIME_MS }
        if (live.isEmpty()) kept.remove(shareId) else if (live.size != strokes.size) kept[shareId] = live.toMutableList()
        val colours = coloursForShare(live.map { it.author.key })
        return live.map { LiveMark(it.annotation, markAlpha(t - it.at), it.author, colours.getValue(it.author.key)) }
    }

    /** Every share this instance still remembers anything for, whether or
     *  not it has already aged out - call [alive] to find out which. */
    @Synchronized
    fun shareIds(): Set<String> = kept.keys.toSet()

    /** True while any share has a stroke still showing. Drives whether a
     *  caller needs to keep ticking at all. */
    @Synchronized
    fun any(): Boolean = kept.keys.toList().any { alive(it).isNotEmpty() }
}
