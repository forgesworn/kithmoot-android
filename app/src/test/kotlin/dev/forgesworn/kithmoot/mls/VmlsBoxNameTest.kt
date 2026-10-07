package dev.forgesworn.kithmoot.mls

import kotlin.test.Test
import kotlin.test.assertEquals

/** D1 R6: a box's name cannot hide text, reorder it, or start a line that imitates the box id in the consent ask. */
class VmlsBoxNameTest {
    @Test fun `separators, bidi and invisible characters are dropped, ordinary text and emoji kept`() {
        assertEquals("HomeBox deadbeef", VmlsRuntime.boxName("Home Box deadbeef"))
        assertEquals("HomeBox", VmlsRuntime.boxName("Home Box"))
        assertEquals("abc", VmlsRuntime.boxName("a‮b⁦c⁩"))
        assertEquals("abc", VmlsRuntime.boxName("a؜b​c﻿"))
        assertEquals("ab", VmlsRuntime.boxName("a⁠⁤⁪­b"))
        // A tag character sits outside the basic plane: judged as one code point, not two halves.
        assertEquals("ab", VmlsRuntime.boxName("a󠁁b"))
        assertEquals("Café 🏠", VmlsRuntime.boxName("Café 🏠"))
        assertEquals("Bothy box", VmlsRuntime.boxName("​ "))
    }
}
