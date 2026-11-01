package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.storage.RoomStorage
import dev.forgesworn.kithmoot.support.FakeRelay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.*

/**
 * A session in these tests is clocked on virtual time. Journal work that left the
 * test scheduler let `runTest` run the session's background timers meanwhile, and
 * a slow disk (a loaded build machine) moved the clock days on during one Retry:
 * past the unsent cap, so Retry did nothing. Kept on the scheduler, it cannot.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PendingChatClockTest {
    @Test fun `a slow journal does not move a virtual-time session's clock, and Retry sends`() = runTest {
        val room = Fixtures.room(); val owner = Fixtures.primary(room, 1, 2)
        val relay = FakeRelay()
        val store = object : RoomStorage {
            var value: ByteArray? = null
            override fun read(): ByteArray? { Thread.sleep(20); return value?.clone() }
            override fun write(value: ByteArray) { Thread.sleep(20); this.value = value.clone() }
            override fun reset() { value = null }
        }
        val outbox = PendingChatOutbox(store, room.roomId, owner.participant, owner.devicePubkey, io = EmptyCoroutineContext)
        val mine = session(room, owner, relay, chatOutbox = outbox)
        mine.join(); advanceTimeBy(1_000); runCurrent()
        val start = currentTime
        relay.confirmsPublications = false
        assertFalse(mine.sendChatDurable("Keep this"))
        relay.confirmsPublications = true
        assertTrue(mine.retryPendingChat())
        assertEquals(start, currentTime)
        assertEquals(1, relay.countOfKind(KIND_CHAT))
    }
}
