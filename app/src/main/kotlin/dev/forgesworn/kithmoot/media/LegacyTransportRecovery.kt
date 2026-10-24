package dev.forgesworn.kithmoot.media

/** Transport repair for the released add/remove-track call profile. This does
 * not invent fixed slots or claim that a connected transport carries media. */
internal class LegacyTransportRecovery(
    private val disconnectGraceMs: Long = 6_000,
    private val restartGraceMs: Long = 8_000,
    private val rebuildWindowMs: Long = 60_000,
    private val maxRebuilds: Int = 3,
    private val restMs: Long = 60_000,
) {
    enum class Action { RESTART_ICE, REBUILD }
    private var failedAt: Long? = null
    private var restartedAt: Long? = null
    private var restingUntil = 0L
    private val rebuiltAt = ArrayDeque<Long>()

    /** Keep the rate limit when a replacement connection starts fresh. */
    fun replaced() { failedAt = null; restartedAt = null }

    /** Called only for the current live link, with a monotonic clock. Native
     * CHECKING after a restart must not erase the original failure deadline. */
    fun sample(state: String, now: Long): Action? {
        while (rebuiltAt.firstOrNull()?.let { now - it >= rebuildWindowMs } == true) rebuiltAt.removeFirst()
        if (state == "connected" || state == "completed") {
            replaced(); restingUntil = 0
            return null
        }
        if (state in setOf("disconnected", "failed") && failedAt == null) failedAt = now
        val began = failedAt ?: return null // A cold connection is not a failed one.
        if (now < restingUntil) return null
        val restart = restartedAt
        if (restart == null) {
            if (now - began < disconnectGraceMs) return null
            restartedAt = now
            return Action.RESTART_ICE
        }
        if (now - restart < restartGraceMs) return null
        if (rebuiltAt.size >= maxRebuilds) {
            restingUntil = now + restMs
            restartedAt = null
            failedAt = now
            return null
        }
        rebuiltAt.addLast(now)
        replaced()
        return Action.REBUILD
    }
}
