package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.RoomDrops
import dev.forgesworn.kithmoot.relay.RoomTransport
import dev.forgesworn.kithmoot.support.FakeRelay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Exercise the production timer, not manual calls to tick. */
@OptIn(ExperimentalCoroutinesApi::class)
class QuietCadenceTimerTest {
    private val room = Fixtures.room()
    private val ada = Fixtures.primary(room, 1, 2)
    private val epoch = 498216L * 3600

    private fun everySlot(interval: Long) = runTest {
        val relay = FakeRelay()
        val q = QuietTransport(relay.transport(), room.roomKey, ada.participant,
            listOf(ada.participant), 0, backgroundScope, intervalSeconds = interval,
            now = { epoch + testScheduler.currentTime / 1000 }, slotOffset = { interval - 1 })
        val message = encodeChatEvent("at the deadline", ada.participant, ada.credential,
            room.roomId, room.roomKey, ada.deviceSecretKey, epoch)
        q.publish(message)
        runCurrent()
        repeat(3) { index ->
            advanceTimeBy(if (index == 0) (interval - 1) * 1000 else interval * 1000)
            runCurrent()
            assertEquals(index + 1, relay.countOfKind(RoomDrops.GIFT_WRAP_KIND))
            assertEquals(0, q.pending)
        }
        q.stop()
        advanceTimeBy(interval * 2000)
        runCurrent()
        assertEquals(3, relay.countOfKind(RoomDrops.GIFT_WRAP_KIND))
    }

    @Test fun `short slots post even at the final second`() = everySlot(8)
    @Test fun `production slots post even after the last thirty second poll`() = everySlot(300)

    @Test fun `a refused publish retries the same wrap within its slot`() = runTest {
        val relay = FakeRelay()
        val attempts = mutableListOf<NostrEvent>()
        val inner = object : RoomTransport by relay.transport() {
            override fun publish(event: NostrEvent) {
                attempts += event
                if (attempts.size == 1) error("temporarily unavailable")
                relay.publish(event)
            }
        }
        val q = QuietTransport(inner, room.roomKey, ada.participant, listOf(ada.participant),
            0, backgroundScope, intervalSeconds = 8,
            now = { epoch + testScheduler.currentTime / 1000 }, slotOffset = { 5 })
        runCurrent()
        advanceTimeBy(5000); runCurrent()
        assertEquals(1, attempts.size)
        assertEquals(0, relay.published.size)
        advanceTimeBy(1000); runCurrent()
        assertEquals(2, attempts.size)
        assertEquals(attempts[0].id, attempts[1].id)
        assertEquals(1, relay.published.size)
        q.stop()
    }
}
