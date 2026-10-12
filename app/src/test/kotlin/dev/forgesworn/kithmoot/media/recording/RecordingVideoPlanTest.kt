package dev.forgesworn.kithmoot.media.recording

import kotlin.test.*

class RecordingVideoPlanTest {
    private val origin = RecordingOrigin("a".repeat(64), "b".repeat(32), "Original room")
    private fun endpoint(number: Int) = RecordingVideoEndpoint(
        RecordingEndpointKey("c".repeat(64), number.toString(16).padStart(64, '0')),
        "Device $number", 2, true, true, true)

    @Test fun `gallery order and geometry survive renaming camera changes and input order`() {
        val endpoints = (1..24).map(::endpoint)
        val before = RecordingVideoPlan(origin, RecordingVideoLayout.GALLERY, endpoints.reversed())
        val after = RecordingVideoPlan(origin, RecordingVideoLayout.GALLERY,
            endpoints.map { it.copy(name = "Renamed ${it.name}", cameraOn = false) })
        assertEquals(endpoints.map { it.key }, before.slots.map { it.endpoint?.key })
        assertEquals(before.slots.map { it.bounds }, after.slots.map { it.bounds })
        assertEquals(24, before.inputs.size)
        assertTrue(after.inputs.isEmpty())
        for (slot in before.slots) {
            assertTrue(slot.bounds.x + slot.bounds.width <= 1280)
            assertTrue(slot.bounds.y >= RecordingVideoPlan.HEADER)
            assertTrue(slot.bounds.y + slot.bounds.height <= 720)
            assertTrue(slot.videoHeight > 0)
        }
    }

    @Test fun `legacy and withdrawn inputs retain names without a capture source`() {
        val legacy = endpoint(1).copy(recordingProfile = null)
        val withdrawn = endpoint(2).copy(allowed = false)
        val plan = RecordingVideoPlan(origin, RecordingVideoLayout.GALLERY, listOf(legacy, withdrawn))
        assertTrue(plan.inputs.isEmpty())
        assertEquals(listOf(legacy, withdrawn), plan.slots.map { it.endpoint })
        assertEquals("Video unavailable: update this client", plan.slots[0].unavailable)
        assertEquals("Video unavailable under the meeting policy", plan.slots[1].unavailable)
    }

    @Test fun `a selected share and its camera stay on the exact device`() {
        val selected = endpoint(2).copy(cameraOn = false)
        val another = endpoint(1)
        val plan = RecordingVideoPlan(origin, RecordingVideoLayout.SCREEN_CAMERA, listOf(another, selected), selected.key)
        assertEquals(setOf(RecordingVideoKey(selected.key, RecordingVideoRole.SCREEN)), plan.inputs)
        assertTrue(plan.slots.all { it.endpoint?.key == selected.key })
        assertEquals("Camera off", plan.slots[1].unavailable)
        val ended = RecordingVideoPlan(origin, RecordingVideoLayout.SCREEN_CAMERA,
            listOf(another, selected.copy(screenOn = false)), selected.key)
        assertTrue(ended.inputs.isEmpty())
        assertEquals("Selected screen share ended", ended.slots[0].unavailable)
    }

    @Test fun `a departed selection is named rather than replaced by another source`() {
        val selected = endpoint(2)
        for (layout in listOf(RecordingVideoLayout.SPEAKER, RecordingVideoLayout.SCREEN_CAMERA)) {
            val plan = RecordingVideoPlan(origin, layout, listOf(endpoint(1)), selected.key, selected.name)
            assertTrue(plan.inputs.isEmpty())
            assertTrue(plan.slots.all { it.endpoint?.key == selected.key && it.endpoint.name == selected.name })
            assertTrue(plan.slots.all { it.unavailable == "Selected device left the call" })
        }
    }

    @Test fun `video overflow is explicit and never acquires a sink`() {
        val endpoints = (1..40).map(::endpoint)
        val plan = RecordingVideoPlan(origin, RecordingVideoLayout.GALLERY, endpoints)
        assertEquals(24, plan.inputs.size)
        val overflow = plan.slots.last()
        assertNull(overflow.source)
        assertEquals(endpoints.drop(24), overflow.overflow)
        assertEquals("16 further devices: audio only", overflow.unavailable)
    }

    @Test fun `invalid origins duplicate devices and implicit selections are refused`() {
        assertFailsWith<IllegalArgumentException> { RecordingOrigin("another room", origin.call, "Wrong") }
        assertFailsWith<IllegalArgumentException> { RecordingVideoPlan(origin, RecordingVideoLayout.GALLERY, listOf(endpoint(1), endpoint(1))) }
        assertFailsWith<IllegalArgumentException> { RecordingVideoPlan(origin, RecordingVideoLayout.SCREEN_CAMERA, listOf(endpoint(1))) }
        assertFailsWith<IllegalArgumentException> { RecordingVideoPlan(origin, RecordingVideoLayout.SPEAKER, listOf(endpoint(1))) }
    }
}
