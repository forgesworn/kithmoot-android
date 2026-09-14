package dev.forgesworn.kithmoot.crypto

import java.security.SecureRandom
import kotlin.random.Random

/**
 * CSPRNG-backed timing jitter for behaviour observable by a relay.
 *
 * Callers that need deterministic tests continue to accept an injected
 * [Random]. Production defaults must not use `Random.Default`, whose stream
 * is not intended to resist prediction.
 */
class SecureTimingRandom(private val source: SecureRandom = SecureRandom()) : Random() {
    override fun nextBits(bitCount: Int): Int {
        require(bitCount in 0..32) { "bit count must be between 0 and 32" }
        return if (bitCount == 0) 0 else source.nextInt().ushr(32 - bitCount)
    }
}
