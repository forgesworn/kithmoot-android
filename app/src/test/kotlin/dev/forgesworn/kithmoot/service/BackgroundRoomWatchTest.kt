package dev.forgesworn.kithmoot.service

import dev.forgesworn.kithmoot.notifications.CallRingMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackgroundRoomWatchTest {
    private fun watch(id: String) = BackgroundRoomWatch(id, "Room $id", id, ByteArray(32), listOf("wss://relay.example"), "self-$id")

    @Test
    fun `only Ring me rooms not already open in the app are watched`() {
        val candidates = listOf(watch("a"), watch("b"), watch("c"))
        val ringMode = mapOf("a" to CallRingMode.RING, "b" to CallRingMode.QUIET, "c" to CallRingMode.RING)
        val openInApp = setOf("c")

        val watched = roomsToWatch(candidates, { ringMode.getValue(it) }, { it in openInApp })

        assertEquals(listOf("a"), watched.map { it.stableRoomId })
    }

    @Test
    fun `nothing is watched when every room is Notify quietly or Nothing`() {
        val candidates = listOf(watch("a"), watch("b"))
        val ringMode = mapOf("a" to CallRingMode.QUIET, "b" to CallRingMode.NOTHING)

        assertTrue(roomsToWatch(candidates, { ringMode.getValue(it) }, { false }).isEmpty())
    }

    @Test
    fun `the listener only runs while the toggle is on and a room still wants Ring me`() {
        assertFalse(shouldRunBackgroundListener(toggleEnabled = false, savedRoomIds = listOf("a"), ringMode = { CallRingMode.RING }))
        assertFalse(shouldRunBackgroundListener(toggleEnabled = true, savedRoomIds = listOf("a"), ringMode = { CallRingMode.QUIET }))
        assertFalse(shouldRunBackgroundListener(toggleEnabled = true, savedRoomIds = emptyList(), ringMode = { CallRingMode.RING }))
        assertTrue(shouldRunBackgroundListener(toggleEnabled = true, savedRoomIds = listOf("a", "b"), ringMode = { if (it == "b") CallRingMode.RING else CallRingMode.NOTHING }))
    }
}
