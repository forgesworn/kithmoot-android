package dev.forgesworn.kithmoot.media.effects

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * "Every now and then a fish swims past", as numbers.
 *
 * The shoal takes its clock and its randomness as arguments precisely so this
 * can be asserted rather than watched: how often a fish appears, how long it
 * takes to cross, that it never appears in the middle of the picture, and that
 * a phone that was asleep does not come back to a stampede.
 */
class ReefShoalTest {

    /** Randomness that is not random, so a spawn can be aimed. */
    private class Dice(vararg values: Float) : () -> Float {
        private val values = values.toList()
        var calls = 0
            private set
        override fun invoke(): Float = values[calls++ % values.size]
    }

    /**
     * Walk the shoal's clock forward in steps it will actually follow.
     *
     * One big jump is not the same thing: the clock clamps a step to
     * [ReefShoal.MAX_STEP_MS], because a phone that was asleep must not come
     * back to every spawn that fell due at once. Thirty-three milliseconds is
     * a frame at 30fps, which is how the scene really advances.
     */
    private fun ReefShoal.run(forMs: Long, stepMs: Long = 33) {
        var now = elapsedMs
        val until = now + forMs
        while (now < until) {
            now += stepMs
            advance(now)
        }
    }

    // --- cadence -------------------------------------------------------------

    @Test
    fun `the first fish arrives quickly, so the sea is not empty when somebody looks`() {
        val shoal = ReefShoal(random = Dice(0.5f))
        shoal.advance(0)
        shoal.run(ReefShoal.FIRST_SPAWN_MS - 100)
        assertEquals(0, shoal.spawned)
        shoal.run(200)
        assertEquals(1, shoal.spawned)
    }

    @Test
    fun `a fish every now and then, not a fish all the time`() {
        val shoal = ReefShoal(random = Dice(0.5f))
        var now = 0L
        shoal.advance(now)
        // Five minutes, stepped at about 30fps.
        repeat(5 * 60 * 30) {
            now += 33
            shoal.advance(now)
        }
        // A gap of four to twelve seconds means somewhere near 300/8 spawns in
        // five minutes, and never one a second.
        assertTrue(shoal.spawned in 20..60, "${shoal.spawned} fish in five minutes")
    }

    @Test
    fun `never more than three at once`() {
        // Randomness that always asks for the shortest possible gap.
        val shoal = ReefShoal(random = Dice(0f))
        var now = 0L
        shoal.advance(now)
        repeat(60 * 30) {
            now += 33
            shoal.advance(now)
            assertTrue(shoal.fish.size <= ReefShoal.MAX_FISH, "${shoal.fish.size} fish at once")
        }
    }

    @Test
    fun `a phone that was asleep does not come back to a stampede`() {
        val shoal = ReefShoal(random = Dice(0f))
        shoal.advance(0)
        // Four minutes in one step.
        shoal.advance(4 * 60 * 1000L)
        assertEquals(ReefShoal.MAX_STEP_MS, shoal.elapsedMs)
        assertTrue(shoal.fish.size <= ReefShoal.MAX_FISH)
    }

    @Test
    fun `the clock only starts on the second call`() {
        val shoal = ReefShoal(random = Dice(0.5f))
        shoal.advance(1_000_000)
        assertEquals(0L, shoal.elapsedMs)
        assertEquals(0, shoal.spawned)
    }

    @Test
    fun `time going backwards is ignored`() {
        val shoal = ReefShoal(random = Dice(0.5f))
        shoal.advance(1000)
        shoal.advance(1050)
        val was = shoal.elapsedMs
        shoal.advance(900)
        assertEquals(was, shoal.elapsedMs)
    }

    // --- one fish ------------------------------------------------------------

    @Test
    fun `a fish swimming right starts off the left edge and the other way round`() {
        // Caught the moment it appears, before it has swum anywhere.
        val justSpawned = ReefShoal.FIRST_SPAWN_MS + 2

        val rightwards = ReefShoal(random = Dice(0.4f)).also { it.advance(0); it.run(justSpawned, stepMs = 1) }
        assertEquals(1, rightwards.fish.single().direction)
        assertEquals(-ReefShoal.EDGE_MARGIN, rightwards.fish.single().x, 1e-3f)

        val leftwards = ReefShoal(random = Dice(0.9f)).also { it.advance(0); it.run(justSpawned, stepMs = 1) }
        assertEquals(-1, leftwards.fish.single().direction)
        assertEquals(1f + ReefShoal.EDGE_MARGIN, leftwards.fish.single().x, 1e-3f)
    }

    @Test
    fun `both directions turn up`() {
        val seen = mutableSetOf<Int>()
        for (roll in listOf(0.1f, 0.9f)) {
            val shoal = ReefShoal(random = Dice(roll))
            shoal.advance(0)
            shoal.run(1000)
            seen += shoal.fish.first().direction
        }
        assertEquals(setOf(1, -1), seen)
    }

    @Test
    fun `a fish takes fifteen to thirty seconds to cross`() {
        for (roll in listOf(0f, 0.999f)) {
            val shoal = ReefShoal(random = Dice(roll))
            shoal.advance(0)
            shoal.run(1000)
            val speed = shoal.fish.first().speed
            val seconds = 1f / speed
            assertTrue(seconds in 14f..30f, "$seconds seconds to cross at $speed")
        }
    }

    @Test
    fun `no fish swims across the light at the top or the sand at the bottom`() {
        for (roll in listOf(0f, 0.5f, 0.999f)) {
            val shoal = ReefShoal(random = Dice(roll))
            shoal.advance(0)
            shoal.run(1000)
            val y = shoal.fish.first().y
            assertTrue(y in 0.2f..0.78f, "a fish at $y")
        }
    }

    @Test
    fun `a fish is small, because it is a long way off`() {
        for (roll in listOf(0f, 0.999f)) {
            val shoal = ReefShoal(random = Dice(roll))
            shoal.advance(0)
            shoal.run(1000)
            assertTrue(shoal.fish.first().size < 0.09f, "a fish taking up ${shoal.fish.first().size} of the frame")
        }
    }

    @Test
    fun `a fish is forgotten past the far edge rather than accumulating`() {
        val shoal = ReefShoal(random = Dice(0.4f), maxGapMs = 1_000_000, firstSpawnMs = 100)
        var now = 0L
        shoal.advance(now)
        now += 99
        shoal.advance(now)
        now += 99
        shoal.advance(now)
        assertEquals(1, shoal.fish.size)
        // Long enough for the slowest fish to have crossed and gone.
        repeat(40 * 30) {
            now += 33
            shoal.advance(now)
        }
        assertTrue(shoal.fish.isEmpty(), "${shoal.fish.size} fish still swimming")
    }

    // --- which fish ----------------------------------------------------------

    @Test
    fun `the sprite is chosen from the variant and stays put`() {
        val fish = Fish(0f, 0.5f, 0.05f, 0.05f, 1, 0.005f, 1f, 0f, variant = 0.5f)
        assertEquals(3, spriteIndexFor(fish, 6))
        assertEquals(3, spriteIndexFor(fish, 6))
    }

    @Test
    fun `a variant of one does not run off the end of the list`() {
        val fish = Fish(0f, 0.5f, 0.05f, 0.05f, 1, 0.005f, 1f, 0f, variant = 1f)
        assertEquals(5, spriteIndexFor(fish, 6))
    }

    @Test
    fun `no sprites means no sprite rather than a crash`() {
        val fish = Fish(0f, 0.5f, 0.05f, 0.05f, 1, 0.005f, 1f, 0f, variant = 0.5f)
        assertEquals(-1, spriteIndexFor(fish, 0))
    }

    @Test
    fun `a sprite that failed to load changes what is drawn and not when`() {
        val fish = Fish(0f, 0.5f, 0.05f, 0.05f, 1, 0.005f, 1f, 0f, variant = 0.9f)
        assertNotEquals(spriteIndexFor(fish, 6), spriteIndexFor(fish, 5))
        assertTrue(spriteIndexFor(fish, 5) in 0..4)
    }

    // --- depth ---------------------------------------------------------------

    @Test
    fun `a smaller fish is paler, because there is more water in front of it`() {
        val far = depthAlphaOf(0.045f)
        val near = depthAlphaOf(0.087f)
        assertTrue(far < near)
        assertEquals(0.72f, far, 1e-5f)
        assertEquals(1f, near, 1e-5f)
    }

    @Test
    fun `depth is bounded whatever size it is handed`() {
        for (size in listOf(-1f, 0f, 0.05f, 10f)) {
            val alpha = depthAlphaOf(size)
            assertTrue(alpha in 0.72f..1f, "alpha $alpha for size $size")
        }
    }
}
