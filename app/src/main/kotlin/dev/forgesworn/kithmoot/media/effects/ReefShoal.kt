package dev.forgesworn.kithmoot.media.effects

import kotlin.math.PI
import kotlin.math.floor

/**
 * When a fish appears, and where every fish has got to.
 *
 * A port of `ReefShoal` in the web client's `reef-scene.ts`, with the same
 * constants, the same spawn cadence and the same speeds, so a call between a
 * phone and a browser shows two reefs that behave alike.
 *
 * The split the web version makes is kept: this class is *when* - it owns the
 * clock, decides when a fish appears and where each one has got to, touches no
 * canvas, and takes both its clock and its randomness as arguments. "A fish
 * crosses occasionally rather than constantly" is therefore an assertion about
 * numbers in a plain JVM test rather than something to squint at on a handset.
 * `ReefOverlay` is *what it looks like*, and it is the only part that needs a
 * device.
 *
 * ## Calm on purpose
 *
 * This sits behind somebody's face while they talk about work. Everything here
 * is slow: a fish takes fifteen to thirty seconds to cross, and most of the
 * time there is no fish at all. Motion in the corner of a video call is a thing
 * the other person's eye keeps going back to, and the version of this that was
 * fun to watch was the version that was impossible to be talked to in front of.
 */
class ReefShoal(
    private val random: () -> Float = { Math.random().toFloat() },
    maxFish: Int = MAX_FISH,
    minGapMs: Long = MIN_SPAWN_GAP_MS,
    maxGapMs: Long = MAX_SPAWN_GAP_MS,
    firstSpawnMs: Long = FIRST_SPAWN_MS,
) {
    private val ceiling = maxOf(1, maxFish)
    private val minGap = maxOf(0L, minGapMs)
    private val maxGap = maxOf(this.minGap, maxGapMs)

    private val swimming = mutableListOf<Fish>()
    private var lastAt: Long? = null
    private var elapsed = 0L
    private var nextSpawnAt = maxOf(0L, firstSpawnMs)
    private var released = 0

    /** The scene's own elapsed time in milliseconds. Drawing phases come off
     *  this rather than off the wall clock, so a still frame is reproducible. */
    val elapsedMs: Long get() = elapsed

    val fish: List<Fish> get() = swimming

    /** How many have been let go since the start. Counted so a test can say
     *  "in five minutes, between this many and that many", which is the whole
     *  of what "occasionally" means. */
    val spawned: Int get() = released

    /**
     * Move everything on to wall-clock time [nowMs]. The first call only sets
     * the clock; there is no elapsed time to apply yet.
     */
    fun advance(nowMs: Long) {
        val last = lastAt
        if (last == null) {
            lastAt = nowMs
            return
        }
        lastAt = nowMs
        var dt = nowMs - last
        if (dt <= 0L) return
        if (dt > MAX_STEP_MS) dt = MAX_STEP_MS
        elapsed += dt

        val seconds = dt / 1000f
        for (i in swimming.indices.reversed()) {
            val one = swimming[i]
            one.x += one.direction * one.speed * seconds
            if (one.x < -EDGE_MARGIN || one.x > 1f + EDGE_MARGIN) swimming.removeAt(i)
        }

        while (elapsed >= nextSpawnAt) {
            if (swimming.size < ceiling) swimming.add(spawn())
            nextSpawnAt = elapsed + minGap + (random() * (maxGap - minGap)).toLong()
        }
    }

    private fun spawn(): Fish {
        val direction = if (random() < 0.5f) 1 else -1
        released += 1
        return Fish(
            x = if (direction == 1) -EDGE_MARGIN else 1f + EDGE_MARGIN,
            // Never across the very top or the very bottom: the top is where
            // the light comes from and the bottom is the sand, and both look
            // wrong with a fish in them.
            y = 0.2f + random() * 0.58f,
            // Fifteen to thirty seconds to cross. Slower than that reads as a
            // screensaver that has stuck.
            speed = 0.034f + random() * 0.033f,
            // Small. A reef fish in open water is a long way off, and the
            // first version of this drew them at a seventh of the frame, which
            // reads as an aquarium pet with its nose against the lens rather
            // than as something swimming past behind you.
            size = 0.045f + random() * 0.042f,
            direction = direction,
            bobAmplitude = 0.004f + random() * 0.009f,
            bobRate = 0.6f + random() * 0.7f,
            phase = random() * 2f * PI.toFloat(),
            variant = random(),
        )
    }

    companion object {
        /**
         * Longest step the scene clock will take in one go.
         *
         * A phone that was asleep for four minutes comes back with a
         * four-minute gap, and following it would fire every spawn that was due
         * at once and teleport whatever was on screen off the far side. The
         * scene simply did not happen while nobody was looking, which is also
         * what it cost.
         */
        const val MAX_STEP_MS = 100L

        /** At most this many fish at once. Three is already more than the calm
         *  version of this wants; it is a ceiling, not a target. */
        const val MAX_FISH = 3

        /** Gap between one fish appearing and the next being due. */
        const val MIN_SPAWN_GAP_MS = 4_000L
        const val MAX_SPAWN_GAP_MS = 12_000L

        /** The first fish, so the scene is not empty for ten seconds when
         *  somebody turns it on and looks at it to see what it is. */
        const val FIRST_SPAWN_MS = 900L

        /** How far past the edge a fish starts and is forgotten, as a fraction
         *  of the width, so it is never seen popping into existence. */
        const val EDGE_MARGIN = 0.18f
    }
}

/**
 * One fish.
 *
 * Positions are fractions of the scene rather than pixels, so the same shoal
 * survives a camera that changes resolution mid-call.
 */
data class Fish(
    /** Centre, as fractions of the scene. `x` runs outside 0..1 at the ends. */
    var x: Float,
    val y: Float,
    /** Widths per second. */
    val speed: Float,
    /** How wide it is drawn, as a fraction of the scene width. */
    val size: Float,
    /** 1 swims right, -1 swims left. */
    val direction: Int,
    val bobAmplitude: Float,
    val bobRate: Float,
    val phase: Float,
    /**
     * Which fish this one is, as a number from 0 to 1.
     *
     * Not an index, because the shoal has no idea how many photographs turned
     * up: it decides *which of them* at spawn time and the drawing side turns
     * that into a subscript. Keeping it that way round means a sprite failing
     * to load changes what is drawn and never when.
     */
    val variant: Float,
)

/**
 * Which of the loaded sprites this fish is. Deterministic, so the same fish is
 * the same fish for as long as it is on screen, and safe with an empty list.
 */
fun spriteIndexFor(fish: Fish, sprites: Int): Int {
    if (sprites <= 0) return -1
    val index = floor(fish.variant * sprites).toInt()
    return index.coerceIn(0, sprites - 1)
}

/**
 * Depth, as the two things that actually read as distance.
 *
 * A fish further away is smaller and paler against the water between you and
 * it. Size is already decided; this turns it into opacity. The web also softens
 * the far ones by a fraction of a pixel; on a handset that is a blur filter per
 * fish per frame for something nobody can see at this scale, so only the
 * opacity is kept - and the opacity is the half that sells it.
 */
fun depthAlphaOf(size: Float): Float {
    val near = ((size - 0.045f) / 0.042f).coerceIn(0f, 1f)
    return 0.72f + near * 0.28f
}
