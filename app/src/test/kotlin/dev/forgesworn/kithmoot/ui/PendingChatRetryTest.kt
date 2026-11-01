package dev.forgesworn.kithmoot.ui

import dev.forgesworn.kithmoot.session.PendingChatState
import dev.forgesworn.kithmoot.ui.room.pendingStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PendingChatRetryTest {

    @Test fun `automatic retries back off and stop after eight offers`() {
        val retry = PendingChatRetry()
        val waits = mutableListOf<Long>()
        while (true) {
            val wait = retry.nextDelayMs() ?: break
            waits += wait
            retry.attempted(offered = true)
            check(waits.size < 100)
        }
        assertEquals(listOf(5_000L, 15_000, 30_000, 60_000, 120_000, 240_000, 480_000, 900_000), waits)
        assertTrue(retry.stopped)
        assertNull(retry.nextDelayMs())
    }

    @Test fun `attempts with no relay reachable do not use up the count`() {
        val retry = PendingChatRetry()
        repeat(20) { retry.nextDelayMs(); retry.attempted(offered = false) }
        assertFalse(retry.stopped)
        assertEquals(900_000, retry.nextDelayMs(), "The wait still grows to its last step")
    }

    @Test fun `Retry or a new message starts the count again`() {
        val retry = PendingChatRetry()
        repeat(PendingChatRetry.MAX_OFFERS) { retry.nextDelayMs(); retry.attempted(offered = true) }
        assertTrue(retry.stopped)
        retry.reset()
        assertFalse(retry.stopped)
        assertEquals(5_000, retry.nextDelayMs())
    }

    @Test fun `only coming back from no relay at all is worth an attempt at once`() {
        assertTrue(PendingChatRetry.reconnectTriggers(emptySet(), setOf("wss://a")))
        assertFalse(PendingChatRetry.reconnectTriggers(setOf("wss://a"), setOf("wss://a", "wss://b")))
        assertFalse(PendingChatRetry.reconnectTriggers(setOf("wss://a", "wss://b"), setOf("wss://b")))
        assertFalse(PendingChatRetry.reconnectTriggers(setOf("wss://a"), emptySet()))
    }

    @Test fun `a message whose retries stopped says so instead of promising another try`() {
        assertTrue(pendingStatus(PendingChatState.REFUSED).endsWith("Trying again shortly."))
        assertEquals("Not sent: the relays turned it down. Retry to try again.", pendingStatus(PendingChatState.REFUSED, retriesStopped = true))
        assertEquals("Not confirmed: no relay answered in time. Retry to try again.", pendingStatus(PendingChatState.UNKNOWN, retriesStopped = true))
    }

    @Test fun `a message past its automatic offers says Not sent, and nothing promises another try`() {
        assertEquals("Not sent.", pendingStatus(PendingChatState.WAITING, expired = true))
        assertEquals("Not sent: the relays turned it down. Retry to try again.", pendingStatus(PendingChatState.REFUSED, expired = true))
        assertEquals("Not confirmed: no relay answered in time. Retry to try again.", pendingStatus(PendingChatState.UNKNOWN, expired = true))
        assertFalse(pendingStatus(PendingChatState.UNKNOWN, internetAllowed = false, expired = true).contains("same message"))
        assertEquals(pendingStatus(PendingChatState.MOVED), pendingStatus(PendingChatState.MOVED, expired = true))
        assertEquals(pendingStatus(PendingChatState.SENDING), pendingStatus(PendingChatState.SENDING, expired = true))
        listOf(PendingChatState.WAITING, PendingChatState.REFUSED, PendingChatState.UNKNOWN).forEach {
            assertFalse(pendingStatus(it, expired = true).contains("shortly"))
            assertFalse(pendingStatus(it, expired = true).contains("will send"))
        }
    }
}
