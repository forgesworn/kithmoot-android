package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.RoomEpoch
import dev.forgesworn.kithmoot.protocol.RosterEntry
import dev.forgesworn.kithmoot.protocol.decodeRosterEvent
import dev.forgesworn.kithmoot.protocol.deriveEpoch
import dev.forgesworn.kithmoot.protocol.encodeRosterEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SuccessorTrafficTest {
    private val stable = Fixtures.room()
    private val identity = Fixtures.primary(stable, 1, 2)
    private val successor = deriveEpoch(RoomEpoch(1, ByteArray(32) { 44 }))

    @Test fun `successor roster keeps its permanent credential scope while moving traffic roots`() {
        val entry = RosterEntry(
            identity.participant, identity.devicePubkey, identity.credential,
            updatedAt = 100,
        )
        val event = encodeRosterEvent(entry, successor.id, successor.key, identity.deviceSecretKey)
        assertNull(decodeRosterEvent(event, successor.id, successor.key, 100))
        val decoded = decodeRosterEvent(event, successor.id, successor.key, 100, credentialRoomId = stable.roomId)
        assertEquals(identity.participant, decoded?.participant)
        assertNull(decodeRosterEvent(event.copy(tags = listOf(listOf("d", stable.roomId))), successor.id, successor.key, 100, stable.roomId))
    }

    @Test fun `successor chat keeps its permanent credential scope while moving traffic roots`() {
        val event = encodeChatEvent(
            "after the rekey", identity.participant, identity.credential,
            successor.id, successor.key, identity.deviceSecretKey, 100,
            credentialRoomId = stable.roomId,
        )
        assertNull(decodeChatEvent(event, successor.id, successor.key, 100))
        val decoded = assertNotNull(decodeChatEvent(event, successor.id, successor.key, 100, credentialRoomId = stable.roomId))
        assertEquals("after the rekey", decoded.body)
        assertNull(decodeChatEvent(event.copy(tags = listOf(listOf("d", stable.roomId))), successor.id, successor.key, 100, credentialRoomId = stable.roomId))
    }
}
