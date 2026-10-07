package dev.forgesworn.kithmoot.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals

/** The phone's text size times the in-app choice, held to Android's own ceiling. */
class EffectiveFontScaleTest {
    @Test fun `a phone at standard follows every in-app choice unchanged`() {
        for (size in TextSize.entries) assertEquals(size.scale, effectiveFontScale(1.0f, size.scale))
    }

    @Test fun `huge on a standard phone is one and a half`() {
        assertEquals(1.5f, effectiveFontScale(1.0f, 1.5f))
    }

    @Test fun `huge on a phone at twice standard stops at two`() {
        assertEquals(2.0f, effectiveFontScale(2.0f, 1.5f))
    }

    @Test fun `a phone at 130 percent with large is not capped`() {
        assertEquals(1.625f, effectiveFontScale(1.3f, 1.25f), 0.0001f)
    }
}
