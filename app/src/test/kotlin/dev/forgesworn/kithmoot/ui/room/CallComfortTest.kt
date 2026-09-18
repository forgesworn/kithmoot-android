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

/**
 * "On the call" means membership, never "the media engine is running".
 *
 * The engine runs from the moment any room opens, because opening a room is
 * how you hear the people already in it. Reading that as being on a call told
 * a person who was only listening that they were on one, offered them Leave
 * as their only option, held their screen awake and showed an in-call
 * notification - while the Mac beside them, reading the roster, correctly
 * said nobody was on a call at all.
 */
class InACallTest {

    @Test
    fun `opening a room and listening is not being in a call`() {
        assertFalse(
            inACall(onCall = false, mediaRunning = true, micOn = false, cameraOn = false, screenOn = false),
        )
    }

    @Test
    fun `declaring membership is being in a call, with nothing switched on`() {
        // Somebody on the call from a train, camera and microphone off. The
        // whole reason the field exists.
        assertTrue(
            inACall(onCall = true, mediaRunning = true, micOn = false, cameraOn = false, screenOn = false),
        )
    }

    @Test
    fun `live local media is being in a call before the declaration lands`() {
        assertTrue(
            inACall(onCall = false, mediaRunning = true, micOn = true, cameraOn = false, screenOn = false),
        )
        assertTrue(
            inACall(onCall = false, mediaRunning = true, micOn = false, cameraOn = false, screenOn = true),
        )
    }

    @Test
    fun `a connection carrying somebody else's media counts only where it is asked about`() {
        // The screen stays awake for a call this device is receiving; the
        // notification does not ask, and gets the default.
        assertTrue(
            inACall(onCall = false, mediaRunning = true, micOn = false, cameraOn = false, screenOn = false, connected = true),
        )
        assertFalse(
            inACall(onCall = false, mediaRunning = true, micOn = false, cameraOn = false, screenOn = false),
        )
    }

    @Test
    fun `a leave that stopped the media stack ends it whatever is stale`() {
        assertFalse(
            inACall(onCall = false, mediaRunning = false, micOn = true, cameraOn = true, screenOn = true, connected = true),
        )
    }
}
