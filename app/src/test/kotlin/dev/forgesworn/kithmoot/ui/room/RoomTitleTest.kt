package dev.forgesworn.kithmoot.ui.room

import dev.forgesworn.kithmoot.account.shortNpub
import dev.forgesworn.kithmoot.ui.RoomState
import dev.forgesworn.kithmoot.ui.start.hasOwnName
import dev.forgesworn.kithmoot.ui.start.privateChatLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The web's final stage 6 words for rooms and people with no name (kithmoot #499). */
class RoomTitleTest {
    private val id = "d0559f3c" + "0".repeat(56)

    @Test fun `a room nobody named is Untitled room, as in Chats`() {
        assertEquals("Untitled room", roomTitle(RoomState(roomId = id, name = "Room d0559f3c")))
        assertEquals("Untitled room", roomTitle(RoomState(roomId = id, name = " ")))
        assertEquals("Release workshop", roomTitle(RoomState(roomId = id, name = "Release workshop")))
    }

    @Test fun `a private chat is the person, or Private chat, never a key`() {
        assertEquals("Priya Natarajan", roomTitle(RoomState(roomId = id, name = "Private with Priya Natarajan", privateConversation = true)))
        val key = "a2".repeat(32)
        assertEquals("Private chat", privateChatLabel("Private with ${shortNpub(key)}"))
        assertEquals("Private chat", roomTitle(RoomState(roomId = id, name = "Private with ${shortNpub(key)}", privateConversation = true)))
        // A person really called something like a key prefix keeps their name.
        assertEquals("npub fan", privateChatLabel("Private with npub fan"))
    }

    @Test fun `only a real name prefills the rename field`() {
        assertFalse(hasOwnName("Room d0559f3c", id))
        assertFalse(hasOwnName("", id))
        assertFalse(hasOwnName(null, id))
        assertTrue(hasOwnName("Untitled room", id))
        assertTrue(hasOwnName("Room d0559f3d", id))
    }

    @Test fun `a nameless person's picture says S for Somebody`() {
        assertEquals("S", personInitial(null))
        assertEquals("S", personInitial("  "))
        assertEquals("P", personInitial("priya"))
        assertEquals("4", personInitial("  42 club"))
    }
}
