package dev.forgesworn.kithmoot.protocol

import org.junit.Assert.*
import org.junit.Test

class DisplayNameTest {
    @Test fun unicode_spaces_match_the_reference_without_platform_flags() {
        val spaces = "\t\n\u000b\u000c\r \u00a0\u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200a\u2028\u2029\u202f\u205f\u3000\ufeff"
        for (space in spaces) assertEquals("Robin admin", DisplayName.sanitise("Robin${space}admin"))
    }

    @Test fun invisible_categories_are_removed_beyond_latin1() {
        assertEquals("Robin", DisplayName.sanitise("\u202eRo\u200bbi\ue000n\u0378\ud800"))
        // NEXT LINE is a control, not ECMAScript whitespace.
        assertEquals("Robin", DisplayName.sanitise("Rob\u0085in"))
        assertNull(DisplayName.sanitise("\u202e\u200b\ue000\ud800"))
    }

    @Test fun visible_unicode_survives_and_the_limit_counts_code_points() {
        assertEquals("雪 العربية 😀", DisplayName.sanitise(" 雪\u3000العربية 😀 "))
        assertEquals("😀".repeat(32), DisplayName.sanitise("😀".repeat(40)))
        assertNull(DisplayName.sanitise(null))
    }
}
