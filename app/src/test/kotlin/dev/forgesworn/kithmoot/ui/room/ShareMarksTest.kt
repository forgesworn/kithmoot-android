package dev.forgesworn.kithmoot.ui.room

import dev.forgesworn.kithmoot.protocol.AnnotationPoint
import dev.forgesworn.kithmoot.protocol.ScreenAnnotation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShareMarksTest {

    private fun stroke(shareId: String = "share-1", strokeId: String = "s1", x: Double = 0.5, y: Double = 0.5) =
        ScreenAnnotation("stroke", shareId, strokeId, listOf(AnnotationPoint(x, y), AnnotationPoint(x, y)))

    private fun clear(shareId: String = "share-1") = ScreenAnnotation("clear", shareId, "", null)

    private fun author(key: String, label: String = key) = MarkAuthor(key, label)

    // --- fade timing -----------------------------------------------------------

    @Test
    fun `a mark is solid throughout the hold window`() {
        assertEquals(1f, markAlpha(0))
        assertEquals(1f, markAlpha(MARK_HOLD_MS - 1))
    }

    @Test
    fun `a mark fades linearly to zero over the fade window`() {
        assertEquals(1f, markAlpha(MARK_HOLD_MS))
        assertEquals(0.5f, markAlpha(MARK_HOLD_MS + MARK_FADE_MS / 2))
        assertEquals(0f, markAlpha(MARK_HOLD_MS + MARK_FADE_MS))
    }

    @Test
    fun `a mark never goes negative once fully faded`() {
        assertEquals(0f, markAlpha(MARK_LIFETIME_MS + 10_000))
    }

    @Test
    fun `a fresh stroke is fully solid and a mark past its lifetime is gone`() {
        var clock = 0L
        val marks = ShareMarks(now = { clock })

        marks.remember(stroke(), author("alice"))
        assertEquals(1, marks.alive("share-1").size)
        assertEquals(1f, marks.alive("share-1").single().alpha)

        clock = MARK_LIFETIME_MS + 1
        assertTrue(marks.alive("share-1").isEmpty())
    }

    // --- remember / dedup / clear ------------------------------------------------

    @Test
    fun `duplicate stroke ids on the same share are one mark`() {
        var clock = 0L
        val marks = ShareMarks(now = { clock })

        marks.remember(stroke(strokeId = "s1"), author("alice"))
        marks.remember(stroke(strokeId = "s1"), author("alice"))

        assertEquals(1, marks.alive("share-1").size)
    }

    @Test
    fun `a clear removes every stroke on that share only`() {
        var clock = 0L
        val marks = ShareMarks(now = { clock })
        marks.remember(stroke(shareId = "share-1", strokeId = "s1"), author("alice"))
        marks.remember(stroke(shareId = "share-2", strokeId = "s2"), author("bob"))

        marks.remember(clear(shareId = "share-1"), author("alice"))

        assertTrue(marks.alive("share-1").isEmpty())
        assertEquals(1, marks.alive("share-2").size)
    }

    @Test
    fun `an unknown share has no marks`() {
        assertTrue(ShareMarks().alive("nothing-here").isEmpty())
    }

    @Test
    fun `any is true only while a share has a live stroke`() {
        var clock = 0L
        val marks = ShareMarks(now = { clock })
        assertFalse(marks.any())

        marks.remember(stroke(), author("alice"))
        assertTrue(marks.any())

        clock = MARK_LIFETIME_MS + 1
        assertFalse(marks.any())
    }

    @Test
    fun `more than the per-share cap drops the oldest strokes`() {
        var clock = 0L
        val marks = ShareMarks(now = { clock })
        repeat(105) { i ->
            marks.remember(stroke(strokeId = "s$i"), author("alice"))
            clock += 1
        }
        assertEquals(100, marks.alive("share-1").size)
        assertTrue(marks.alive("share-1").none { it.annotation.strokeId == "s0" })
        assertTrue(marks.alive("share-1").any { it.annotation.strokeId == "s104" })
    }

    @Test
    fun `more than the total share cap evicts the oldest idle share`() {
        val marks = ShareMarks(now = { 0L })
        repeat(17) { i -> marks.remember(stroke(shareId = "share-$i"), author("alice")) }

        assertTrue(marks.alive("share-0").isEmpty())
        assertEquals(1, marks.alive("share-16").size)
    }

    // --- colour, ported bit-exact from the web client's hash ---------------------

    @Test
    fun `the participant hash matches the web client's Math_imul-based hash exactly`() {
        // Hand-verified against a JS emulation of (Math.imul(h,31)+code)>>>0
        // folded through MARK_COLOURS.size (7): "alice" -> 6, "bob" -> 4.
        assertEquals(MARK_COLOURS[6], colourForParticipant("alice"))
        assertEquals(MARK_COLOURS[4], colourForParticipant("bob"))
    }

    @Test
    fun `the same participant is always the same colour`() {
        assertEquals(colourForParticipant("carol"), colourForParticipant("carol"))
    }

    @Test
    fun `two different drawers on one share get different colours when the palette allows it`() {
        val colours = coloursForShare(listOf("alice", "bob"))
        assertEquals(2, colours.size)
        assertTrue(colours.getValue("alice") != colours.getValue("bob"))
    }

    @Test
    fun `colour assignment does not depend on iteration order`() {
        assertEquals(coloursForShare(listOf("alice", "bob")), coloursForShare(listOf("bob", "alice")))
    }

    @Test
    fun `a solo drawer gets their own hashed colour`() {
        assertEquals(colourForParticipant("alice"), coloursForShare(listOf("alice")).getValue("alice"))
    }

    @Test
    fun `live marks carry a colour resolved against every other live drawer on the same share`() {
        var clock = 0L
        val marks = ShareMarks(now = { clock })
        marks.remember(stroke(strokeId = "a"), author("alice"))
        marks.remember(stroke(strokeId = "b"), author("bob"))

        val live = marks.alive("share-1")

        assertEquals(2, live.size)
        assertEquals(coloursForShare(listOf("alice", "bob")), live.associate { it.author.key to it.color })
    }
}
