package dev.forgesworn.kithmoot.ui.room

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A Join press is never thrown away.
 *
 * The room that provoked this opened at a non-active epoch, so no engine was
 * ever built, and every press answered "Audio and video are still starting.
 * Try again shortly." while nothing was arranged to make it stop being true.
 */
class CallControlTest {

    @Test
    fun `a press with an engine behind it joins now`() {
        assertEquals(
            JoinDecision.Now,
            joinDecision(onCall = false, changing = false, mediaReady = true, mediaStarting = false, mediaFault = null),
        )
    }

    @Test
    fun `a press while media is still coming is remembered rather than refused`() {
        assertEquals(
            JoinDecision.WhenReady,
            joinDecision(onCall = false, changing = false, mediaReady = false, mediaStarting = true, mediaFault = null),
        )
    }

    @Test
    fun `a press on a device whose media failed for good says why`() {
        val decision = joinDecision(
            onCall = false, changing = false, mediaReady = false, mediaStarting = true,
            mediaFault = "Audio and video are unavailable on this device: no camera",
        )
        assertTrue(decision is JoinDecision.Refuse)
        // The fault itself, not a hopeful line about starting shortly: this
        // one is never going to start.
        assertEquals("Audio and video are unavailable on this device: no camera", decision.message)
    }

    @Test
    fun `a press on a device with no media at all is refused with a reason`() {
        val decision = joinDecision(onCall = false, changing = false, mediaReady = false, mediaStarting = false, mediaFault = null)
        assertTrue(decision is JoinDecision.Refuse)
        assertTrue(decision.message.isNotBlank(), "a refusal the person cannot read is the same as no answer")
    }

    @Test
    fun `presses that mean nothing are ignored rather than remembered`() {
        assertEquals(
            JoinDecision.Ignore,
            joinDecision(onCall = true, changing = false, mediaReady = true, mediaStarting = false, mediaFault = null),
        )
        // A leave that has not settled must not leave a remembered join behind
        // it, which would put the person straight back on the call they just
        // left.
        assertEquals(
            JoinDecision.Ignore,
            joinDecision(onCall = false, changing = true, mediaReady = false, mediaStarting = true, mediaFault = null),
        )
    }

    @Test
    fun `a press before the engine arrives says the call is connecting, not that the device lacks hardware`() {
        for ((starting, pending) in listOf(true to false, false to true, true to true)) {
            val message = mediaMissingNote(mediaStarting = starting, joinPending = pending, mediaFault = null)
            assertTrue("connecting" in message, message)
            assertTrue("on this device" !in message, message)
        }
    }

    @Test
    fun `a press after media failed for good gives the fault`() {
        assertEquals(
            "Audio and video are unavailable on this device: no camera",
            mediaMissingNote(mediaStarting = true, joinPending = true, mediaFault = "Audio and video are unavailable on this device: no camera"),
        )
    }

    @Test
    fun `a press with no engine and nothing coming still says something readable`() {
        val message = mediaMissingNote(mediaStarting = false, joinPending = false, mediaFault = null)
        assertTrue(message.isNotBlank())
        assertTrue("on this device" !in message, message)
    }

    @Test
    fun `declaring a call joins the one that is on`() {
        assertEquals("call-a", callToDeclare(visible = "call-a", remembered = "call-b", pressed = false) { "fresh" })
        assertEquals("call-a", callToDeclare(visible = "call-a", remembered = null, pressed = true) { "fresh" })
    }

    @Test
    fun `a rebuilt session rejoins the call it was on while the room cannot see it yet`() {
        assertEquals("call-b", callToDeclare(visible = null, remembered = "call-b", pressed = false) { "fresh" })
    }

    @Test
    fun `only a press of Join starts a call`() {
        assertEquals("fresh", callToDeclare(visible = null, remembered = null, pressed = true) { "fresh" })
        assertNull(callToDeclare(visible = null, remembered = null, pressed = false) { "fresh" })
    }
}
