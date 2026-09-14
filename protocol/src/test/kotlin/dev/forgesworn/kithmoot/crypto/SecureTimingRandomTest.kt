package dev.forgesworn.kithmoot.crypto

import org.junit.Assert.assertTrue
import org.junit.Test

class SecureTimingRandomTest {
    @Test fun `timing source stays within requested bounds`() {
        val random = SecureTimingRandom()
        repeat(128) {
            assertTrue(random.nextLong(1_500) in 0 until 1_500)
            assertTrue(random.nextInt(37) in 0 until 37)
        }
    }
}
