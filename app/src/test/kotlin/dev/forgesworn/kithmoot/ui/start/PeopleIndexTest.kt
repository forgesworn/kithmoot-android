package dev.forgesworn.kithmoot.ui.start

import kotlin.test.*

class PeopleIndexTest {
    @Test fun `labels file under their folded first character`() {
        assertEquals('E', peopleIndexChar("Élan"))
        assertEquals('A', peopleIndexChar("alice"))
        assertEquals('N', peopleIndexChar("Ñandú"))
        assertEquals('7', peopleIndexChar("7even"))
        assertEquals('A', peopleIndexChar("  Ada"))
    }

    @Test fun `anything else is hash`() {
        for (label in listOf("", "   ", "@bob", "🙂 Sam", "Ωmega", "漢字", "٣")) assertEquals('#', peopleIndexChar(label), label)
    }

    @Test fun `the first occurrence in list order wins across sections`() {
        val targets = peopleIndexTargets(listOf("Ann", "Bea", "Alan", "2pac"), listOf(3, 4, 8, 9))
        assertEquals(3, targets['A'])
        assertEquals(4, targets['B'])
        assertEquals(9, targets['2'])
        assertNull(targets['C'])
    }

    @Test fun `an empty character jumps to the next one with people`() {
        val targets = mapOf('D' to 5, '3' to 12)
        assertEquals(5, peopleIndexJump('D', targets))
        assertEquals(5, peopleIndexJump('A', targets))
        assertEquals(12, peopleIndexJump('Z', targets))
        assertEquals(12, peopleIndexJump('0', targets))
        assertNull(peopleIndexJump('4', targets))
        assertNull(peopleIndexJump('#', targets))
    }

    @Test fun `a touch maps to a character and clamps to the ends`() {
        assertEquals('A', peopleIndexCharAt(-20f, 370f))
        assertEquals('A', peopleIndexCharAt(0f, 370f))
        assertEquals('B', peopleIndexCharAt(11f, 370f))
        assertEquals('#', peopleIndexCharAt(9999f, 370f))
        assertEquals('#', peopleIndexCharAt(369f, 370f))
        assertEquals('A', peopleIndexCharAt(5f, 0f))
    }
}
