package dev.forgesworn.kithmoot.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deduplication and rate limiting, mirroring `src/signal-guard.test.ts`. */
class SignalGuardTest {

    private val sender = "a".repeat(64)
    private val other = "b".repeat(64)

    @Test
    fun `an event id is admitted once and refused every time after`() {
        val guard = SignalGuard()
        assertTrue(guard.admitEvent("id-1"))
        assertFalse(guard.admitEvent("id-1"))
        assertTrue(guard.admitEvent("id-2"))
    }

    @Test
    fun `the oldest ids are forgotten rather than growing without limit`() {
        val guard = SignalGuard()
        for (i in 0 until MAX_REMEMBERED_SIGNALS) assertTrue(guard.admitEvent("id-$i"))
        assertEquals(MAX_REMEMBERED_SIGNALS, guard.size())

        assertTrue(guard.admitEvent("id-new"))
        assertEquals(MAX_REMEMBERED_SIGNALS, guard.size())
        // The oldest was evicted, so it is admitted again; the newest is not.
        assertTrue(guard.admitEvent("id-0"))
        assertFalse(guard.admitEvent("id-new"))
    }

    @Test
    fun `one sender is rate-limited without deafening the room to another`() {
        val guard = SignalGuard()
        val now = 1_000_000L
        for (i in 0 until MAX_SIGNALS_PER_WINDOW) assertTrue(guard.admitSender(sender, now))
        assertFalse(guard.admitSender(sender, now))
        assertTrue(guard.admitSender(other, now))
    }

    @Test
    fun `a sender gets its budget back once the window has passed`() {
        val guard = SignalGuard()
        val now = 1_000_000L
        for (i in 0 until MAX_SIGNALS_PER_WINDOW) guard.admitSender(sender, now)
        assertFalse(guard.admitSender(sender, now))
        assertTrue(guard.admitSender(sender, now + RATE_WINDOW_SECONDS))
    }

    @Test
    fun `the sender table is bounded too`() {
        val guard = SignalGuard()
        val now = 1_000_000L
        for (i in 0 until 5_000) guard.admitSender("sender-$i", now)
        assertTrue(guard.senderCount() <= MAX_REMEMBERED_SIGNALS)
    }
}
