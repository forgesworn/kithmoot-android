package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.KIND_ROOM_REKEY
import dev.forgesworn.kithmoot.support.FakeRelay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A room that moves on says so, rather than going quiet.
 *
 * Following an epoch is not implemented here. What is implemented is
 * noticing, because the failure it replaces is the worst kind: the roster and
 * the chat move to an id this client is not subscribed to, everything stops,
 * and nothing anywhere says why.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomMovedOnTest {

    private val authoritySk = Fixtures.key(41)
    private val authority = Schnorr.publicKeyHex(authoritySk)
    private val impostorSk = Fixtures.key(42)

    /** A rekey as the authority publishes one: the room id in `d`, the epoch
     *  in its own tag, and a body only members of the old epoch can read -
     *  which this client is not trying to. */
    private fun rekey(roomId: String, epoch: Int, secretKey: ByteArray) = Events.sign(
        kind = KIND_ROOM_REKEY,
        createdAt = 1_000,
        tags = listOf(listOf("d", roomId), listOf("epoch", epoch.toString())),
        content = "not-read-without-the-key",
        secretKey = secretKey,
    )

    @Test
    fun `the room says it has moved on when the authority rekeys it`() = runTest {
        val room = Fixtures.room()
        val relay = FakeRelay()
        val live = session(room, Fixtures.primary(room, 1, 2), relay, authority = authority)
        live.join()
        runCurrent()
        assertNull(live.movedOn.value, "a room nobody has left has not moved")

        relay.publish(rekey(room.roomId, 1, authoritySk))
        runCurrent()
        assertEquals(1, live.movedOn.value)
    }

    @Test
    fun `a rekey from anybody but the authority is ignored`() = runTest {
        // Every member holds the room key, so without this any of them could
        // make every Android client in the room announce that it had moved.
        val room = Fixtures.room()
        val relay = FakeRelay()
        val live = session(room, Fixtures.primary(room, 1, 2), relay, authority = authority)
        live.join()
        runCurrent()

        relay.publish(rekey(room.roomId, 1, impostorSk))
        runCurrent()
        assertNull(live.movedOn.value)
    }

    @Test
    fun `the newest epoch wins, whatever order they arrive in`() = runTest {
        // A client that has been away sees several rekeys at once, in
        // whatever order a relay replays them. What a person needs told is
        // where the room is now.
        val room = Fixtures.room()
        val relay = FakeRelay()
        val live = session(room, Fixtures.primary(room, 1, 2), relay, authority = authority)
        live.join()
        runCurrent()

        relay.publish(rekey(room.roomId, 3, authoritySk))
        relay.publish(rekey(room.roomId, 1, authoritySk))
        runCurrent()
        assertEquals(3, live.movedOn.value)
    }

    @Test
    fun `a room opened from a legacy link has nobody to believe`() = runTest {
        // No inviter in the link, so no authority, so a rekey is just an
        // event from a stranger. The room goes quiet the old way, which is
        // the honest outcome when there is nobody to trust.
        val room = Fixtures.room()
        val relay = FakeRelay()
        val live = session(room, Fixtures.primary(room, 1, 2), relay)
        live.join()
        runCurrent()

        relay.publish(rekey(room.roomId, 1, authoritySk))
        runCurrent()
        assertNull(live.movedOn.value)
    }
}
