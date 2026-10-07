package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.storage.DestructTombstones
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The countdown's arithmetic and words, checked against the web client's
 *  app/src/self-destruct.ts and the design's own examples. */
class SelfDestructTest {
    private val day = 86_400L
    private val hour = 3_600L
    private val utc = ZoneId.of("UTC")

    @Test fun `thresholds scale with the lifetime, as the design's examples say`() {
        // A 7-day room: amber at 24 h, red at 1 h.
        assertEquals(CountdownThresholds(86_400.0, 3_600.0), countdownThresholds(7 * day))
        // A 1-day room: amber at 6 h, red at 1 h.
        assertEquals(CountdownThresholds(21_600.0, 3_600.0), countdownThresholds(day))
        // A 2-minute room: amber at 30 s, red at 6 s, both inside the final minute.
        assertEquals(CountdownThresholds(30.0, 6.0), countdownThresholds(120))
        // Unknown, or nonsense: taken as long.
        for (unknown in listOf(null, 0L, -5L)) assertEquals(CountdownThresholds(86_400.0, 3_600.0), countdownThresholds(unknown))
    }

    @Test fun `stages by remaining time`() {
        val week = 7 * day
        assertEquals(CountdownStage.GREEN, countdownStage(4 * day, week))
        assertEquals(CountdownStage.GREEN, countdownStage(day + 1, week))
        assertEquals(CountdownStage.AMBER, countdownStage(day, week))
        assertEquals(CountdownStage.AMBER, countdownStage(hour + 1, week))
        assertEquals(CountdownStage.RED, countdownStage(hour, week))
        assertEquals(CountdownStage.RED, countdownStage(61, week))
        assertEquals(CountdownStage.FINAL, countdownStage(60, week))
        assertEquals(CountdownStage.FINAL, countdownStage(1, week))
        assertEquals(CountdownStage.GONE, countdownStage(0, week))
        assertEquals(CountdownStage.GONE, countdownStage(-30, week))
        // A one-day room is green until six hours are left, not amber from the start.
        assertEquals(CountdownStage.GREEN, countdownStage(23 * hour, day))
        assertEquals(CountdownStage.AMBER, countdownStage(6 * hour, day))
    }

    @Test fun `the pill's words, and a live clock once red`() {
        assertEquals("4 days", remainingWords(4 * day + 5 * hour, CountdownStage.GREEN))
        assertEquals("1 day 5 h", remainingWords(day + 5 * hour + 30, CountdownStage.GREEN))
        assertEquals("1 day", remainingWords(day + 59, CountdownStage.GREEN))
        assertEquals("5 h 12 m", remainingWords(5 * hour + 12 * 60 + 9, CountdownStage.AMBER))
        assertEquals("12 m", remainingWords(12 * 60 + 30, CountdownStage.AMBER))
        assertEquals("1 m", remainingWords(30, CountdownStage.AMBER))
        assertEquals("42:07", remainingWords(42 * 60 + 7, CountdownStage.RED))
        assertEquals("0:45", remainingWords(45, CountdownStage.FINAL))
        assertEquals("60:00", remainingWords(hour, CountdownStage.RED))
    }

    @Test fun `spoken words`() {
        assertEquals("5 hours 12 minutes", remainingSpoken(5 * hour + 12 * 60))
        assertEquals("1 hour", remainingSpoken(hour))
        assertEquals("1 day 1 hour", remainingSpoken(day + hour))
        assertEquals("3 days", remainingSpoken(3 * day + 7))
        assertEquals("4 minutes 5 seconds", remainingSpoken(4 * 60 + 5))
        assertEquals("12 minutes", remainingSpoken(12 * 60 + 5))
        assertEquals("1 second", remainingSpoken(1))
    }

    @Test fun `a countdown says self-destructs or ends, and gone at the end`() {
        val now = 1_800_000_000L
        val ends = now + 5 * hour + 12 * 60
        val start = ends - 7 * day
        val c = countdown(ends, start, destruct = true, now = now)
        assertEquals(CountdownStage.AMBER, c.stage)
        assertEquals("Self-destructs in 5 h 12 m", c.text)
        assertEquals("Self-destructs in 5 hours 12 minutes", c.spoken)
        val kept = countdown(now + 4 * day, start, destruct = false, now = now)
        assertEquals("Ends in 4 days", kept.text)
        assertFalse(kept.destruct)
        assertEquals("Self-destructed", countdown(ends, start, true, ends).text)
        assertEquals("Ended", countdown(ends, start, false, ends + 1).text)
        assertEquals("Self-destructs in 42:07", countdown(now + 42 * 60 + 7, start, true, now).text)
    }

    @Test fun `the accessible name moves by the minute once the clock ticks`() {
        val ends = 1_800_000_000L
        assertEquals("Self-destructs in under 43 minutes", countdownAccessibleName(ends, ends - 7 * day, true, ends - (42 * 60 + 7)))
        assertEquals("Self-destructs in under 1 minute", countdownAccessibleName(ends, ends - 7 * day, true, ends - 45))
        assertEquals("Self-destructs in 5 hours", countdownAccessibleName(ends, ends - 7 * day, true, ends - 5 * hour))
    }

    @Test fun `announcements only on the stages worth announcing`() {
        assertNull(stageAnnouncement(CountdownStage.GREEN, 4 * day))
        assertNull(stageAnnouncement(CountdownStage.GONE, 0))
        assertEquals("This room self-destructs in 1 day.", stageAnnouncement(CountdownStage.AMBER, day))
        assertEquals("This room self-destructs in 1 hour.", stageAnnouncement(CountdownStage.RED, hour))
        assertEquals("This room self-destructs in under a minute. Save anything you need now.", stageAnnouncement(CountdownStage.FINAL, 59))
        assertEquals("This room self-destructs in 0:45. Save anything you need now.", finalBannerText(45))
        assertEquals("Dark Prague self-destructs in 1 hour.", headsUpText("Dark Prague", hour))
    }

    @Test fun `details, tombstone and pending words match the web's`() {
        val at = 1_791_560_580L // Fri 9 Oct 2026, 15:43 UTC
        assertEquals("A room self-destructed · Fri 9 Oct, 15:43", tombstoneText(at, utc, Locale.UK))
        assertEquals("This room self-destructs Fri 9 Oct, 15:43. $DESTRUCT_PROMISE", destructDetails(at, utc, Locale.UK))
        assertEquals("This room self-destructs if it is ended for everyone. $DESTRUCT_PROMISE", destructDetails(null, utc, Locale.UK))
        assertTrue(DESTRUCT_PROMISE.contains("some relays ignore deletion requests"))
        assertEquals("Will not be sent: the room is self-destructing", WILL_NOT_BE_SENT)
    }

    @Test fun `a pending message is doomed only in the final minute, and only if not already on its way`() {
        val ends = 1_800_000_000L
        assertFalse(pendingDoomed(ends, true, false, ends - 61))
        assertTrue(pendingDoomed(ends, true, false, ends - 60))
        assertFalse(pendingDoomed(ends, true, true, ends - 30))
        assertFalse(pendingDoomed(ends, false, false, ends - 30))
        assertFalse(pendingDoomed(null, true, false, ends))
    }

    @Test fun `tombstones name no room, go when dismissed, and after seven days`() {
        var stored: String? = null
        val rows = DestructTombstones({ stored }, { stored = it })
        val t0 = 1_800_000_000L
        rows.add(t0, "0".repeat(16))
        rows.add(t0 + 60, "1".repeat(16))
        assertEquals(listOf("1".repeat(16), "0".repeat(16)), rows.list(t0 + 120).map { it.id })
        assertFalse(stored!!.contains("room"))
        rows.dismiss("1".repeat(16))
        assertEquals(listOf(t0), rows.list(t0 + 120).map { it.at })
        assertTrue(rows.list(t0 + DestructTombstones.TOMBSTONE_SECONDS).isEmpty())
        assertNull(stored)
        // A store someone else wrote, or a broken one, gives no rows rather than an error.
        stored = """[{"id":"not hex","at":1},{"id":"${"a".repeat(16)}","at":"x"},7]"""
        assertTrue(rows.list(0).isEmpty())
        stored = "{"
        assertTrue(rows.list(0).isEmpty())
    }
}
