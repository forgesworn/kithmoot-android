package dev.forgesworn.kithmoot.ui.room

import dev.forgesworn.kithmoot.session.Roles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CallLayoutTest {

    private fun person(id: String, self: Boolean = false, vararg roles: String) =
        ParticipantTile(id, self, 1, roles.map { TileTrack("$id-device", "$it-$id", it) }, null, false)

    private val me = person("me", self = true, Roles.CAMERA)
    private fun others(count: Int) = (1..count).map { person("p$it", false, Roles.CAMERA) }

    // --- choosing a layout by head count -------------------------------------

    @Test
    fun `alone puts your own picture on the stage`() {
        val arrangement = arrangeCall(listOf(me))
        assertEquals(CallLayoutMode.ALONE, arrangement.mode)
        assertEquals("me", arrangement.stage?.participant)
        assertNull(arrangement.floating)
    }

    @Test
    fun `one other person fills the screen and you float`() {
        val arrangement = arrangeCall(listOf(me) + others(1))
        assertEquals(CallLayoutMode.ONE_TO_ONE, arrangement.mode)
        assertEquals("p1", arrangement.stage?.participant)
        assertEquals("me", arrangement.floating?.participant)
    }

    @Test
    fun `tapping your picture swaps the two`() {
        val arrangement = arrangeCall(listOf(me) + others(1), swapped = true)
        assertEquals("me", arrangement.stage?.participant)
        assertEquals("p1", arrangement.floating?.participant)
    }

    @Test
    fun `hiding your picture leaves the other person alone on screen`() {
        val arrangement = arrangeCall(listOf(me) + others(1), hideSelf = true)
        assertEquals("p1", arrangement.stage?.participant)
        assertNull(arrangement.floating)
    }

    @Test
    fun `three and four people are an equal grid with you floating`() {
        for (count in 2..3) {
            val arrangement = arrangeCall(listOf(me) + others(count))
            assertEquals(CallLayoutMode.GRID, arrangement.mode, "$count others")
            assertEquals(count, arrangement.grid.size)
            assertTrue(arrangement.grid.none { it.participant == "me" })
            assertEquals("me", arrangement.floating?.participant)
        }
    }

    @Test
    fun `five or more people put the active speaker on the stage`() {
        val arrangement = arrangeCall(listOf(me) + others(4), activeSpeaker = "p3")
        assertEquals(CallLayoutMode.SPEAKER, arrangement.mode)
        assertEquals("p3", arrangement.stage?.participant)
        assertEquals(listOf("p1", "p2", "p4"), arrangement.strip.map { it.participant })
        assertEquals("me", arrangement.floating?.participant)
    }

    @Test
    fun `a large call without a speaker yet leads with the first person`() {
        assertEquals("p1", arrangeCall(listOf(me) + others(5)).stage?.participant)
    }

    @Test
    fun `a large call can ask for the grid instead`() {
        val arrangement = arrangeCall(listOf(me) + others(6), activeSpeaker = "p2", preferGrid = true)
        assertEquals(CallLayoutMode.GRID, arrangement.mode)
        assertEquals(6, arrangement.grid.size)
    }

    @Test
    fun `somebody's shared screen takes the stage and people go to the strip`() {
        val sharer = person("p2", false, Roles.CAMERA, Roles.SCREEN)
        val arrangement = arrangeCall(listOf(me, person("p1", false, Roles.CAMERA), sharer))
        assertEquals(CallLayoutMode.SHARE, arrangement.mode)
        assertEquals("p2", arrangement.stage?.participant)
        assertTrue(arrangement.stage!!.isScreen)
        assertEquals(listOf("p1", "p2", "me"), arrangement.strip.map { it.participant })
        assertNull(arrangement.floating)
    }

    @Test
    fun `your own share is not put on your own stage`() {
        val sharingMe = person("me", true, Roles.CAMERA, Roles.SCREEN)
        assertEquals(CallLayoutMode.ONE_TO_ONE, arrangeCall(listOf(sharingMe) + others(1)).mode)
    }

    @Test
    fun `grid shapes stay two across upright`() {
        assertEquals(1 to 1, gridShape(1, landscape = false))
        assertEquals(1 to 2, gridShape(2, landscape = false))
        assertEquals(2 to 2, gridShape(3, landscape = false))
        assertEquals(2 to 2, gridShape(4, landscape = false))
        assertEquals(2 to 3, gridShape(6, landscape = false))
        assertEquals(2 to 1, gridShape(2, landscape = true))
        assertEquals(3 to 1, gridShape(3, landscape = true))
        assertEquals(2 to 2, gridShape(4, landscape = true))
        assertEquals(0 to 0, gridShape(0, landscape = true))
    }

    // --- active speaker ------------------------------------------------------

    @Test
    fun `the stage goes to the first talker and stays through a short interjection`() {
        val speaker = ActiveSpeaker(holdMs = 1_500)
        val present = listOf("a", "b", "c")
        assertEquals("b", speaker.update(setOf("b"), present, 0))
        // b pauses; c says "yes" for a second.
        assertEquals("b", speaker.update(setOf("c"), present, 1_000))
        assertEquals("b", speaker.update(setOf("c"), present, 2_000))
        assertEquals("b", speaker.update(emptySet(), present, 2_200))
    }

    @Test
    fun `somebody who keeps talking takes the stage after the hold`() {
        val speaker = ActiveSpeaker(holdMs = 1_500)
        val present = listOf("a", "b")
        speaker.update(setOf("a"), present, 0)
        assertEquals("a", speaker.update(setOf("b"), present, 1_000))
        assertEquals("a", speaker.update(setOf("b"), present, 2_400))
        assertEquals("b", speaker.update(setOf("b"), present, 2_500))
    }

    @Test
    fun `crosstalk leaves the stage where it is`() {
        val speaker = ActiveSpeaker(holdMs = 1_500)
        val present = listOf("a", "b")
        speaker.update(setOf("a"), present, 0)
        assertEquals("a", speaker.update(setOf("a", "b"), present, 100))
        assertEquals("a", speaker.update(setOf("a", "b"), present, 5_000))
    }

    @Test
    fun `only a waiting challenger needs the clock`() {
        val speaker = ActiveSpeaker(holdMs = 1_500)
        val present = listOf("a", "b")
        speaker.update(setOf("a"), present, 0)
        assertFalse(speaker.pending, "a settled stage needs no ticking")
        speaker.update(setOf("b"), present, 100)
        assertTrue(speaker.pending)
        speaker.update(setOf("b"), present, 1_600)
        assertFalse(speaker.pending, "the challenger took the stage")
        speaker.update(emptySet(), present, 1_700)
        assertFalse(speaker.pending)
    }

    @Test
    fun `a departing speaker hands the stage on at once`() {
        val speaker = ActiveSpeaker()
        speaker.update(setOf("a"), listOf("a", "b", "c"), 0)
        assertEquals("c", speaker.update(setOf("c"), listOf("b", "c"), 10))
        assertNull(speaker.update(emptySet(), emptyList(), 20))
    }

    // --- controls ------------------------------------------------------------

    @Test
    fun `controls hide only over video, with the window and without accessibility`() {
        assertTrue(controlsMayAutoHide(videoShowing = true, windowFocused = true, accessibilityOn = false))
        assertFalse(controlsMayAutoHide(videoShowing = false, windowFocused = true, accessibilityOn = false), "audio-only")
        assertFalse(controlsMayAutoHide(videoShowing = true, windowFocused = false, accessibilityOn = false), "a sheet is open")
        assertFalse(controlsMayAutoHide(videoShowing = true, windowFocused = true, accessibilityOn = true), "TalkBack")
    }

    // --- the floating picture ------------------------------------------------

    @Test
    fun `a dropped picture snaps to the corner of its half`() {
        val w = 1080f; val h = 2000f; val tw = 300f; val th = 400f
        assertEquals(Corner.TOP_LEFT, nearestCorner(100f, 100f, tw, th, w, h))
        assertEquals(Corner.TOP_RIGHT, nearestCorner(700f, 300f, tw, th, w, h))
        assertEquals(Corner.BOTTOM_LEFT, nearestCorner(0f, 1500f, tw, th, w, h))
        assertEquals(Corner.BOTTOM_RIGHT, nearestCorner(700f, 1500f, tw, th, w, h))
    }

    @Test
    fun `a flick carries the picture past the middle`() {
        val w = 1080f; val h = 2000f
        // Released just left of centre but flung right.
        assertEquals(Corner.TOP_RIGHT, nearestCorner(350f, 100f, 300f, 400f, w, h, velocityX = 3_000f))
        assertEquals(Corner.BOTTOM_LEFT, nearestCorner(100f, 700f, 300f, 400f, w, h, velocityY = 3_000f))
    }

    @Test
    fun `corner positions keep the margin and never go off screen`() {
        assertEquals(16f to 16f, cornerPosition(Corner.TOP_LEFT, 300f, 400f, 1080f, 2000f, 16f))
        assertEquals(764f to 1584f, cornerPosition(Corner.BOTTOM_RIGHT, 300f, 400f, 1080f, 2000f, 16f))
        assertEquals(0f to 0f, cornerPosition(Corner.BOTTOM_RIGHT, 300f, 400f, 200f, 300f, 16f))
    }

    @Test
    fun `a saved corner survives a round trip and junk falls back`() {
        for (corner in Corner.entries) assertEquals(corner, Corner.parse(corner.name))
        assertEquals(Corner.BOTTOM_RIGHT, Corner.parse(null))
        assertEquals(Corner.BOTTOM_RIGHT, Corner.parse("sideways"))
    }

    // --- the speaking cue ----------------------------------------------------

    @Test
    fun `speaking is thicker and solid, never colour alone`() {
        val quiet = speakingCue(false)
        val talking = speakingCue(true)
        assertTrue(talking.borderWidth > quiet.borderWidth)
        assertTrue(talking.solidLabel)
        assertFalse(quiet.solidLabel)
        assertEquals("Speaking", talking.stateDescription)
        assertNull(quiet.stateDescription)
    }

    @Test
    fun `the cue can be inverted in one place`() {
        assertTrue(speakingCue(false, inverted = true).solidLabel)
        assertFalse(speakingCue(true, inverted = true).solidLabel)
        assertEquals("Speaking", speakingCue(true, inverted = true).stateDescription)
    }
}
