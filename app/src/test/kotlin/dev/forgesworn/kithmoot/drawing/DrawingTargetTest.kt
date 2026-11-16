package dev.forgesworn.kithmoot.drawing

import org.junit.Assert.*
import org.junit.Test

class DrawingTargetTest {
    private val screen = DrawingBounds(0, 0, 1080, 2400)
    private val app = DrawingWindow(7, DrawingBounds(120, 300, 800, 1200), true)
    @Test fun `drawings stay attached to the chosen app through move and resize`() {
        val target = DrawingTarget()
        target.attachApp(app)
        assertEquals(app.bounds, target.bounds(screen, listOf(app), 800, 1200, true))
        val moved = app.copy(bounds = DrawingBounds(200, 400, 600, 900))
        assertEquals(moved.bounds, target.bounds(screen, listOf(moved), 600, 900, true))
        assertEquals(0.5, moved.bounds.point(300f, 450f).x, 0.00001)
        assertEquals(0.5, moved.bounds.point(300f, 450f).y, 0.00001)
    }
    @Test fun `another foreground app never inherits the attached drawings`() {
        val target = DrawingTarget(); target.attachApp(app)
        val other = DrawingWindow(8, app.bounds, true)
        assertNull(target.bounds(screen, listOf(other), 800, 1200, true))
        assertNull(target.bounds(screen, listOf(app.copy(active = false), other), 800, 1200, true))
        assertNull(target.bounds(screen, listOf(app), 800, 1200, false))
        target.clear()
        assertNull(target.bounds(screen, listOf(app), 800, 1200, true))
    }
    @Test fun `rotation waits for matching capture geometry before painting`() {
        val target = DrawingTarget(); target.attachApp(app)
        val landscape = app.copy(bounds = DrawingBounds(10, 20, 1200, 800))
        assertNull(target.bounds(screen, listOf(landscape), 800, 1200, true))
        assertEquals(landscape.bounds, target.bounds(screen, listOf(landscape), 1200, 800, true))
    }
    @Test fun `whole display attaches without accessibility and hides on visibility loss`() {
        val target = DrawingTarget(); target.attachScreen()
        assertEquals(screen, target.bounds(screen, emptyList(), 1080, 2400, true))
        assertNull(target.bounds(screen, emptyList(), 1080, 2400, false))
        target.attachApp(app)
        assertNull(target.bounds(screen, emptyList(), 1080, 2400, true))
    }
    @Test fun `capture size preserves single app shape and limits even encoder dimensions`() {
        assertEquals(852 to 1280, drawingCaptureSize(1200, 1800))
        assertEquals(1280 to 852, drawingCaptureSize(1800, 1200))
        assertEquals(200 to 300, drawingCaptureSize(201, 301))
        assertEquals(2 to 1280, drawingCaptureSize(1, 2400))
    }
}
