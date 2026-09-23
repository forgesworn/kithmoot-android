package dev.forgesworn.kithmoot.ui.room

import dev.forgesworn.kithmoot.protocol.AnnotationPoint
import dev.forgesworn.kithmoot.protocol.ScreenAnnotation
import dev.forgesworn.kithmoot.protocol.isValidScreenAnnotation
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

    @Test fun `a trembling finger adds no points, as on the web`() {
        val sent = mutableListOf<ScreenAnnotation>()
        val stroke = StrokeSegments("screen", sent::add)
        stroke.start(AnnotationPoint(0.5, 0.5), 0)
        stroke.move(AnnotationPoint(0.5005, 0.5005), 10)
        stroke.move(AnnotationPoint(0.51, 0.5), 20)
        stroke.finish()
        assertEquals(listOf(AnnotationPoint(0.5, 0.5), AnnotationPoint(0.51, 0.5)), sent.single().points)
    }

    @Test fun `a long quick stroke is split at the point cap and every segment is valid`() {
        val sent = mutableListOf<ScreenAnnotation>()
        val stroke = StrokeSegments("screen", sent::add)
        stroke.start(AnnotationPoint(0.0, 0.0), 0)
        for (i in 1..300) stroke.move(AnnotationPoint(i / 400.0, 0.0), 0)
        stroke.finish()
        assertTrue(sent.size >= 3)
        assertTrue(sent.all { isValidScreenAnnotation(it) })
    }

    @Test fun `clear marks is a valid clear on the wire`() {
        val clear = clearMarks("screen")
        assertTrue(isValidScreenAnnotation(clear))
        assertFalse(clear.toJson().containsKey("points"))
    }
}
