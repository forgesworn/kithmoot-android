package dev.forgesworn.kithmoot.media.recording

import kotlin.test.*

class RecordingSpeakerTest {
    private fun endpoint(person: Char, device: Char, profile: Int? = 2) = RecordingVideoEndpoint(
        RecordingEndpointKey(person.toString().repeat(64), device.toString().repeat(64)), "$person/$device", profile, true, true, false)

    @Test fun `speaker changes after hold and crosstalk retains the current person`() {
        val a = endpoint('a', '1'); val b = endpoint('b', '2')
        val selector = RecordingSpeaker()
        assertEquals(a, selector.select(listOf(b, a), setOf(a.key.participant), 0))
        assertEquals(a, selector.select(listOf(a, b), setOf(b.key.participant), 100))
        assertEquals(a, selector.select(listOf(a, b), setOf(b.key.participant), 1599))
        assertEquals(b, selector.select(listOf(a, b), setOf(b.key.participant), 1600))
        assertEquals(b, selector.select(listOf(a, b), setOf(a.key.participant, b.key.participant), 1700))
        assertEquals(b, selector.select(listOf(a, b), setOf(a.key.participant, b.key.participant), 4000))
    }

    @Test fun `selected device survives reorder and camera off and changes immediately on departure`() {
        val a = endpoint('a', '1'); val sibling = endpoint('a', '2'); val b = endpoint('b', '3')
        val selector = RecordingSpeaker()
        assertEquals(a.key, selector.select(listOf(sibling, b, a), emptySet(), 0)?.key)
        assertEquals(a.key, selector.select(listOf(b, sibling, a.copy(cameraOn = false)), emptySet(), 10)?.key)
        assertEquals(sibling.key, selector.select(listOf(b, sibling), emptySet(), 20)?.key)
        assertEquals(b, selector.select(listOf(b), emptySet(), 30))
        assertNull(selector.select(emptyList(), emptySet(), 40))
    }

    @Test fun `legacy speaker retains named fallback and permission withdrawal releases the stage`() {
        val a = endpoint('a', '1', null); val b = endpoint('b', '2')
        val selector = RecordingSpeaker()
        val selected = assertNotNull(selector.select(listOf(a, b), setOf(a.key.participant), 0))
        val plan = RecordingVideoPlan(RecordingOrigin("c".repeat(64), "d".repeat(32), "Original"),
            RecordingVideoLayout.SPEAKER, listOf(a, b), selected.key, selected.name)
        assertTrue(plan.inputs.isEmpty())
        assertEquals(a, plan.slots.single().endpoint)
        assertEquals(b, selector.select(listOf(a.copy(allowed = false), b), setOf(a.key.participant), 1))
        assertNull(selector.select(listOf(a.copy(allowed = false), b.copy(allowed = false)), emptySet(), 2))
    }
}
