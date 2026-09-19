package dev.forgesworn.kithmoot.ui.room

import dev.forgesworn.kithmoot.protocol.AnnotationPoint
import dev.forgesworn.kithmoot.protocol.ScreenAnnotation
import kotlin.test.*

class StrokeSegmentsTest {
    @Test fun `remote viewer receives connected marks before finger lifts`() {
        val sent = mutableListOf<ScreenAnnotation>()
        val stroke = StrokeSegments("screen", sent::add)
        stroke.start(AnnotationPoint(0.1, 0.2), 0)
        stroke.move(AnnotationPoint(0.2, 0.3), 20)
        assertTrue(sent.isEmpty())
        stroke.move(AnnotationPoint(0.3, 0.4), 50)
        assertEquals(1, sent.size)
        stroke.move(AnnotationPoint(0.4, 0.5), 60)
        stroke.finish()
        assertEquals(sent[0].points!!.last(), sent[1].points!!.first())
        assertNotEquals(sent[0].strokeId, sent[1].strokeId)
        assertEquals(AnnotationPoint(0.4, 0.5), sent[1].points!!.last())
    }
}
