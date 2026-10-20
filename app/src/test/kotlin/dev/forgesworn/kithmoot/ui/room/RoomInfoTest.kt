package dev.forgesworn.kithmoot.ui.room

import dev.forgesworn.kithmoot.account.shortNpub
import dev.forgesworn.kithmoot.ui.RoomState
import kotlin.test.Test
import kotlin.test.assertEquals

class RoomInfoTest {
    private val me = "aa".repeat(32)
    private val rowan = "bb".repeat(32)
    private val tally = "cc".repeat(32)
    private fun tile(id: String, name: String? = null) = ParticipantTile(id, id == me, 1, emptyList(), null, false, name = name)

    @Test fun `the who line says who is here and who may join, as the web does`() {
        val room = RoomState(selfParticipant = me, tiles = listOf(tile(me), tile(rowan), tile(tally)), agentParticipants = setOf(tally))
        assertEquals("2 people, 1 agent · Anyone with the link can join", roomInfoWho(room))
        assertEquals("1 person · Anyone with the link can join", roomInfoWho(room.copy(tiles = listOf(tile(me)), agentParticipants = emptySet())))
        assertEquals("Anyone with the link can join", roomInfoWho(room.copy(tiles = emptyList())))
        assertEquals("Private chat · Only members can join", roomInfoWho(room.copy(privateConversation = true)))
        assertEquals("Quiet chat · Only members can join", roomInfoWho(room.copy(privateConversation = true, quiet = true)))
    }

    @Test fun `the end line joins when it ends and whether it self-destructs`() {
        val line = { _: Long -> "Ends in 3 days" }
        assertEquals("", roomInfoEnds(null, false, line))
        assertEquals("Ends in 3 days", roomInfoEnds(10, false, line))
        assertEquals("Ends in 3 days · Self-destructs", roomInfoEnds(10, true, line))
        assertEquals("Self-destructs if it is ended for everyone", roomInfoEnds(null, true, line))
    }

    @Test fun `the privacy row says how messages travel`() {
        assertEquals("Encrypted · public relays", roomInfoRoute(RoomState()))
        assertEquals("Encrypted · onion relays", roomInfoRoute(RoomState(anonymous = true)))
    }

    @Test fun `members are named as their tiles name them, and you as yourself`() {
        val room = RoomState(selfParticipant = me)
        assertEquals("Ada (you)", memberName(room, tile(me, "Ada")))
        assertEquals("You", memberName(room, tile(me)))
        assertEquals("Rowan", memberName(room, tile(rowan, "Rowan")))
        assertEquals(shortNpub(tally), memberName(room, tile(tally)))
    }
}
