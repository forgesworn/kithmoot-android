package dev.forgesworn.kithmoot.ui.room

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CallComfortTest {

    @Test
    fun `off when nothing is on`() {
        assertFalse(isOnCall(micOn = false, cameraOn = false, screenOn = false))
    }

    @Test
    fun `on when only mic is on`() {
        assertTrue(isOnCall(micOn = true, cameraOn = false, screenOn = false))
    }

    @Test
    fun `on when only camera is on`() {
        assertTrue(isOnCall(micOn = false, cameraOn = true, screenOn = false))
    }

    @Test
    fun `on when only screen share is on`() {
        assertTrue(isOnCall(micOn = false, cameraOn = false, screenOn = true))
    }

    @Test
    fun `on when everything is on`() {
        assertTrue(isOnCall(micOn = true, cameraOn = true, screenOn = true))
    }

    @Test
    fun `matches the call tab live label for every combination`() {
        for (mic in listOf(false, true)) {
            for (camera in listOf(false, true)) {
                for (screen in listOf(false, true)) {
                    val expected = mic || camera || screen
                    assertEquals(expected, isOnCall(mic, camera, screen), "mic=$mic camera=$camera screen=$screen")
                }
            }
        }
    }
}
