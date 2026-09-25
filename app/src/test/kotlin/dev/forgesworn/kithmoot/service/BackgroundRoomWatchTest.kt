package dev.forgesworn.kithmoot.service

import dev.forgesworn.kithmoot.notifications.CallRingMode
import dev.forgesworn.kithmoot.protocol.callBellTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackgroundRoomWatchTest {
    private fun key(id: String): ByteArray = ByteArray(32) { id[0].code.toByte() }
    private fun watch(id: String, relays: List<String> = listOf("wss://relay.example")) =
        BackgroundRoomWatch(id, "Room $id", key(id), relays, "participant-$id", "self-$id")

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

    @Test
    fun `relays are deduplicated across rooms that share one`() {
        val watches = listOf(
            watch("a", listOf("wss://one.example", "wss://shared.example")),
            watch("b", listOf("wss://shared.example", "wss://two.example")),
        )

        assertEquals(
            listOf("wss://one.example", "wss://shared.example", "wss://two.example"),
            sharedRelayUrls(watches),
        )
    }

    @Test
    fun `each room's tag set is today's and the neighbouring day's, nothing further out`() {
        val watch = watch("a")
        val now = 1_800_000_000L
        val tags = callBellTagsFor(watch, now)

        assertTrue(callBellTag(watch.bellKey, now) in tags)
        assertTrue(callBellTag(watch.bellKey, now - 86_400) in tags)
        assertTrue(callBellTag(watch.bellKey, now + 86_400) in tags)
        // A bell more than a day away can never be accepted (CALL_BELL_TTL_SECONDS
        // and CALL_BELL_FUTURE_SKEW_SECONDS are both far under a day), so its tag
        // must not be included.
        assertFalse(callBellTag(watch.bellKey, now + 2 * 86_400) in tags)
    }

    @Test
    fun `the filter's tag set changes across UTC midnight`() {
        val watch = watch("a")
        val beforeMidnight = 1_800_000_000L // 2027-01-15T08:00:00Z-ish; exact instant does not matter
        val nextDay = beforeMidnight + 86_400

        val tagsBefore = callBellTagsFor(watch, beforeMidnight)
        val tagsAfter = callBellTagsFor(watch, nextDay)

        assertFalse(tagsBefore == tagsAfter)
        // But the two windows still overlap on the shared boundary day, so a
        // bell rung right at the rollover is not silently dropped either side.
        assertTrue(tagsBefore.intersect(tagsAfter).isNotEmpty())
    }

    @Test
    fun `the shared filter's tags are the union across every watched room`() {
        val a = watch("a")
        val b = watch("b")
        val now = 1_800_000_000L

        val union = callBellFilterTags(listOf(a, b), now)

        assertTrue(callBellTagsFor(a, now).all { it in union })
        assertTrue(callBellTagsFor(b, now).all { it in union })
    }
}
