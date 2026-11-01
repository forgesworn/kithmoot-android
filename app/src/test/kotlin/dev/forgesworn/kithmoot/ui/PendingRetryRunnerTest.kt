package dev.forgesworn.kithmoot.ui

import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.relay.RoomTransport
import dev.forgesworn.kithmoot.session.Fixtures
import dev.forgesworn.kithmoot.session.KIND_CHAT
import dev.forgesworn.kithmoot.session.PendingChatOutbox
import dev.forgesworn.kithmoot.session.RoomSession
import dev.forgesworn.kithmoot.session.UNSENT_AUTO_RETRY_LIMIT_SECONDS
import dev.forgesworn.kithmoot.storage.RoomStorage
import dev.forgesworn.kithmoot.support.FakeRelay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PendingRetryRunnerTest {

    @Test fun `presses during a running attempt collapse into one pressed retry after it ends`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val begun = mutableListOf<RetryKind>(); val ran = mutableListOf<RetryKind>(); val waiting = mutableListOf<Boolean>()
        val runner = PendingRetryRunner(begin = { begun += it }, attempt = { ran += it; if (it == RetryKind.AUTO) gate.await() },
            waitingChanged = { waiting += it })
        assertTrue(runner.request(this, RetryKind.AUTO)); runCurrent()
        assertEquals(listOf(RetryKind.AUTO), ran)
        repeat(3) { assertFalse(runner.request(this, RetryKind.PRESSED)) }
        // Not a press: dropped, as before, since the running attempt covers it.
        assertFalse(runner.request(this, RetryKind.AUTO)); assertFalse(runner.request(this, RetryKind.OPEN))
        runCurrent()
        assertEquals(listOf(true), waiting, "The button shows the press is kept")
        assertEquals(listOf(RetryKind.AUTO), ran)

        gate.complete(Unit); runCurrent()
        assertEquals(listOf(RetryKind.AUTO, RetryKind.PRESSED), ran, "Exactly one pressed retry")
        assertEquals(listOf(RetryKind.AUTO, RetryKind.PRESSED), begun, "The kept press starts as a pressed Retry does")
        assertEquals(listOf(true, false), waiting)

        // Idle again: the next press runs at once.
        assertTrue(runner.request(this, RetryKind.PRESSED)); runCurrent()
        assertEquals(listOf(RetryKind.AUTO, RetryKind.PRESSED, RetryKind.PRESSED), ran)
        assertEquals(listOf(true, false), waiting)
    }

    @Test fun `with nothing pressed meanwhile nothing more runs and nothing shows`() = runTest {
        val ran = mutableListOf<RetryKind>(); val waiting = mutableListOf<Boolean>()
        val runner = PendingRetryRunner(begin = {}, attempt = { ran += it }, waitingChanged = { waiting += it })
        assertTrue(runner.request(this, RetryKind.AUTO)); runCurrent()
        assertTrue(runner.request(this, RetryKind.OPEN)); runCurrent()
        assertEquals(listOf(RetryKind.AUTO, RetryKind.OPEN), ran)
        assertTrue(waiting.isEmpty())
    }

    @Test fun `a kept press is dropped and the button cleared when the room closes mid-attempt`() = runTest {
        val waiting = mutableListOf<Boolean>(); val ran = mutableListOf<RetryKind>()
        val runner = PendingRetryRunner(begin = {}, attempt = { ran += it; CompletableDeferred<Unit>().await() },
            waitingChanged = { waiting += it })
        val room = kotlinx.coroutines.CoroutineScope(coroutineContext + kotlinx.coroutines.SupervisorJob(coroutineContext[kotlinx.coroutines.Job]))
        runner.request(room, RetryKind.AUTO); runCurrent()
        runner.request(room, RetryKind.PRESSED)
        room.coroutineContext[kotlinx.coroutines.Job]!!.cancel(); runCurrent()
        assertEquals(listOf(RetryKind.AUTO), ran)
        assertEquals(listOf(true, false), waiting)
        // A fresh room starts afresh.
        val next = kotlinx.coroutines.CoroutineScope(coroutineContext + kotlinx.coroutines.SupervisorJob(coroutineContext[kotlinx.coroutines.Job]))
        assertTrue(runner.request(next, RetryKind.PRESSED))
        next.coroutineContext[kotlinx.coroutines.Job]!!.cancel(); runCurrent()
    }

    /** The case that mattered: an automatic attempt holds a newer message at the relay, the person presses
     *  Retry for one past its automatic offers, and that press re-signs it once the attempt ends. */
    @Test fun `Retry pressed during an automatic attempt re-signs an expired message once it ends`() = runTest {
        val room = Fixtures.room(); val owner = Fixtures.primary(room, 1, 2)
        val relay = FakeRelay(); val gate = CompletableDeferred<Unit>()
        val inner = relay.transport(); var holdNext = false; val offered = mutableListOf<NostrEvent>()
        val transport = object : RoomTransport by inner {
            override suspend fun publishConfirmedGuarded(event: NostrEvent, generation: Long,
                stillAllowed: () -> Boolean, timeoutMs: Long): Boolean {
                if (event.kind == KIND_CHAT && stillAllowed()) offered += event
                if (event.kind == KIND_CHAT && holdNext) { holdNext = false; gate.await() }
                return inner.publishConfirmedGuarded(event, generation, stillAllowed, timeoutMs)
            }
        }
        val store = object : RoomStorage {
            var value: ByteArray? = null
            override fun read() = value?.clone()
            override fun write(value: ByteArray) { this.value = value.clone() }
            override fun reset() { value = null }
        }
        val outbox = PendingChatOutbox(store, room.roomId, owner.participant, owner.devicePubkey, io = EmptyCoroutineContext)
        val written = 1_000L; var clock = written
        val live = RoomSession(room, owner, transport, backgroundScope, timing = Fixtures.QUIET, now = { clock }, chatOutbox = outbox)
        live.join(); advanceTimeBy(1_000); runCurrent()
        relay.confirmsPublications = false
        assertFalse(live.sendChatDurable("Written yesterday"))
        val expired = outbox.items().single()
        clock = written + UNSENT_AUTO_RETRY_LIMIT_SECONDS + 10
        assertFalse(live.sendChatDurable("Written today"))
        val today = outbox.items().last()
        relay.confirmsPublications = true

        val ran = mutableListOf<RetryKind>()
        val runner = PendingRetryRunner(begin = {}, attempt = { ran += it; live.retryPendingChat(pressed = it == RetryKind.PRESSED) })
        holdNext = true
        assertTrue(runner.request(this, RetryKind.AUTO)); runCurrent()
        assertEquals(today.event.id, offered.last().id, "The automatic attempt is holding today's message")
        assertFalse(runner.request(this, RetryKind.PRESSED)); assertFalse(runner.request(this, RetryKind.PRESSED))
        runCurrent()
        assertEquals(listOf(expired.event.id), outbox.items().filter { it.event.id != today.event.id }.map { it.event.id },
            "Nothing re-signed while the attempt runs")

        gate.complete(Unit); runCurrent()
        assertEquals(listOf(RetryKind.AUTO, RetryKind.PRESSED), ran)
        val chat = relay.published.filter { it.kind == KIND_CHAT }
        assertEquals(2, chat.size)
        assertEquals(today.event.id, chat[0].id)
        assertNotEquals(expired.event.id, chat[1].id, "Signed afresh")
        assertEquals(clock, chat[1].createdAt)
        assertTrue(chat.none { it.id == expired.event.id })
        assertTrue(outbox.items().isEmpty())
        assertEquals(setOf(expired.messageId, today.messageId), live.chat.value.map { it.id }.toSet())
        live.leave()
    }
}
