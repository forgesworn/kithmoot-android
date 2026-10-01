package dev.forgesworn.kithmoot.service

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** When "Ring like a phone call" is asked: first time, then weekly at most, and only after a ring missed the screen. */
class FullScreenAskTest {
    private val week = FULL_SCREEN_ASK_INTERVAL_MS
    private val asked = 1_800_000_000_000L

    @Test fun `never asked is asked`() {
        assertTrue(fullScreenAskDue(null, null, 0))
    }

    @Test fun `asked and no call missed the screen is not asked again`() {
        assertFalse(fullScreenAskDue(asked, null, asked + 10 * week))
        assertFalse(fullScreenAskDue(asked, asked - 1, asked + 10 * week))
    }

    @Test fun `a ring without the screen asks again, but not within a week`() {
        assertFalse(fullScreenAskDue(asked, asked + 1, asked + week - 1))
        assertTrue(fullScreenAskDue(asked, asked + 1, asked + week))
    }

    @Test fun `a build that kept only a flag asks again after a missed ring`() {
        assertTrue(fullScreenAskDue(0, asked, asked + 1))
    }
}
