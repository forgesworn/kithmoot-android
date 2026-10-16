package dev.forgesworn.kithmoot.epoch

import java.security.SecureRandom
import kotlin.math.roundToLong

/**
 * The shortest wait before this device answers somebody else's request on its own.
 *
 * An automatic answer that leaves at a fixed, short interval after a request is a
 * presence oracle: whoever can send requests learns, from the round trip alone,
 * whether this phone is awake and the app is in front right now. A spread of a
 * second and a half is wider than the relay jitter it hides behind, and still far
 * inside the 20 to 30 second wait an epoch requester gives an answer.
 */
const val QUIET_REPLY_MIN_MS: Long = 500

/** The longest wait before an automatic answer. See [QUIET_REPLY_MIN_MS]. */
const val QUIET_REPLY_MAX_MS: Long = 2_000

private val quietRandom = SecureRandom()

/** A uniform wait in [QUIET_REPLY_MIN_MS, QUIET_REPLY_MAX_MS], from [random] in [0, 1). */
fun quietReplyDelayMs(random: () -> Double = { quietRandom.nextDouble() }): Long {
    val r = random().coerceIn(0.0, 1.0)
    return QUIET_REPLY_MIN_MS + ((QUIET_REPLY_MAX_MS - QUIET_REPLY_MIN_MS) * r).roundToLong()
}
