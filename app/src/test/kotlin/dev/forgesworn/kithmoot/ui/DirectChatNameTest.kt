package dev.forgesworn.kithmoot.ui

import dev.forgesworn.kithmoot.ui.room.PublicProfile
import dev.forgesworn.kithmoot.ui.room.roomTitle
import kotlin.test.Test
import kotlin.test.assertEquals

class DirectChatNameTest {
    private val me = "1".repeat(64)
    private val peer = "2".repeat(64)
    private val profiles = mapOf(me to PublicProfile("Rowan", null, 1, "self"), peer to PublicProfile("Taylor", null, 1, "peer"))

    @Test fun `each person sees the counterpart instead of the shared room label`() {
        val mine = RoomState(name = "Taylor", privateConversation = true, privatePeer = peer, selfParticipant = me, profiles = profiles)
        assertEquals("Taylor", roomTitle(mine))
        assertEquals("Rowan", roomTitle(mine.copy(privatePeer = me, selfParticipant = peer)))
        assertEquals("Taylor from work", roomTitle(mine.copy(chatLabel = "Taylor from work")))
        assertEquals("Taylor", roomTitle(mine.copy(chatLabel = null)))
        assertEquals("Group name", roomTitle(mine.copy(privateConversation = false, name = "Group name", chatLabel = "Personal")))
    }

    @Test fun `offline fallback strips legacy prefix and never mistakes self for peer`() {
        assertEquals("Taylor", directChatName(rememberedName = "Private with Taylor"))
        assertEquals("Private chat", directChatName(rememberedName = "Taylor", selfName = "Taylor"))
        assertEquals("Taylor", directChatName(peerName = "Taylor", selfName = "Taylor"))
        assertEquals("Taylor", directChatName(rosterName = "Taylor", rememberedName = "Rowan"))
        assertEquals("Personal", directChatName(chatLabel = "  Personal  ", peerName = "Taylor"))
    }
}
