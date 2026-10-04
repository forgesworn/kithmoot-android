package dev.forgesworn.kithmoot.ui

import dev.forgesworn.kithmoot.service.DeliveryCandidate
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HiddenRoomParkTest {
    private val room = DeliveryCandidate(roomId = "r".repeat(64), anonymous = false, quiet = false, ended = false, epochId = "e")
    private val idle = ParkCheck(
        delivery = room, listenerReceiving = true, chatOnly = false, onCall = false, callJoinPending = false,
        callChanging = false, mediaStarting = false, screenOn = false, recording = false, busy = false,
    )

    @Test fun `an idle saved room the listener will pick up is parked`() = assertTrue(shouldParkHiddenRoom(idle))

    @Test fun `a room is never parked without a listener receiving for it`() {
        assertFalse(shouldParkHiddenRoom(idle.copy(listenerReceiving = false)))
        assertFalse(shouldParkHiddenRoom(idle.copy(delivery = null)))
    }

    @Test fun `rooms the listener leaves alone stay open`() {
        assertFalse(shouldParkHiddenRoom(idle.copy(delivery = room.copy(anonymous = true))))
        assertFalse(shouldParkHiddenRoom(idle.copy(delivery = room.copy(quiet = true))))
        assertFalse(shouldParkHiddenRoom(idle.copy(delivery = room.copy(ended = true))))
        assertFalse(shouldParkHiddenRoom(idle.copy(delivery = room.copy(epochId = null))))
        assertFalse(shouldParkHiddenRoom(idle.copy(delivery = room.copy(needsBunker = true))))
    }

    @Test fun `a call in any stage keeps the room open`() {
        assertFalse(shouldParkHiddenRoom(idle.copy(onCall = true)))
        assertFalse(shouldParkHiddenRoom(idle.copy(callJoinPending = true)))
        assertFalse(shouldParkHiddenRoom(idle.copy(callChanging = true)))
        assertFalse(shouldParkHiddenRoom(idle.copy(mediaStarting = true)))
    }

    @Test fun `a share, a recording or work in flight keeps the room open`() {
        assertFalse(shouldParkHiddenRoom(idle.copy(screenOn = true)))
        assertFalse(shouldParkHiddenRoom(idle.copy(recording = true)))
        assertFalse(shouldParkHiddenRoom(idle.copy(busy = true)))
    }

    @Test fun `a chat opened beside a call is never parked`() = assertFalse(shouldParkHiddenRoom(idle.copy(chatOnly = true)))
}
