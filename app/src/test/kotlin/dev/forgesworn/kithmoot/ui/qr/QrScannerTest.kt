package dev.forgesworn.kithmoot.ui.qr

import kotlin.test.Test
import kotlin.test.assertEquals

class QrScannerTest {
    @Test
    fun `zoom in advances by one step`() {
        assertEquals(1.5f, steppedZoom(1f, 1f, 8f, 1.5f))
    }

    @Test
    fun `zoom never exceeds camera range`() {
        assertEquals(8f, steppedZoom(7f, 1f, 8f, 1.5f))
    }

    @Test
    fun `zoom out never falls below camera range`() {
        assertEquals(1f, steppedZoom(1.2f, 1f, 8f, 1f / 1.5f))
    }
}
