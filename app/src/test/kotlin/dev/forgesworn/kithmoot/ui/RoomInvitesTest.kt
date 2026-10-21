package dev.forgesworn.kithmoot.ui

import dev.forgesworn.kithmoot.relay.RoomRoute
import dev.forgesworn.kithmoot.session.inviteText
import dev.forgesworn.kithmoot.session.roomInviteText
import dev.forgesworn.kithmoot.storage.SavedRoomSummary
import kotlin.test.Test
import kotlin.test.assertEquals

class RoomInvitesTest {
    private fun key(n: Int) = "%02x".format(n).repeat(32)
    private val me = key(1)
    private fun room(n: Int, name: String = "Room $n", openedAt: Long = n.toLong(), share: Boolean = true,
        ended: Boolean = false, secondary: Boolean = false, peer: String? = null, route: RoomRoute = RoomRoute.INTERNET,
        anonymous: Boolean = false, quiet: Boolean = false, account: String? = me) =
        SavedRoomSummary(key(100 + n), name, secondary, openedAt, account = account, anonymous = anonymous, ended = ended,
            canShareInvite = share, privatePeer = peer, route = route, quiet = quiet)

    @Test fun `only group rooms still going on the Internet, under this account, are offered`() {
        val rooms = listOf(
            room(1, "Town hall"),
            room(2, ended = true, share = false),
            room(3, secondary = true, share = false),
            room(4, peer = key(9)),
            room(5, route = RoomRoute.NEARBY),
            room(6, route = RoomRoute.MIXED, name = "Workshop"),
            room(7, anonymous = true),
            room(8, quiet = true),
            room(9, account = key(2)),
            room(10, account = null, name = "Book club"),
        )
        assertEquals(listOf("Book club", "Workshop", "Town hall"), invitableRooms(rooms, me, current = null).map { it.name })
    }

    @Test fun `newest first, the room on screen marked, and a nameless room is Untitled room`() {
        val rooms = listOf(room(1, "Old", openedAt = 10), room(2, " ", openedAt = 30), room(3, "Here", openedAt = 20))
        val choices = invitableRooms(rooms, me, current = key(103))
        assertEquals(listOf("Untitled room", "Here", "Old"), choices.map { it.name })
        assertEquals(listOf(false, true, false), choices.map { it.here })
    }

    @Test fun `search matches names, ignoring case`() {
        val choices = invitableRooms(listOf(room(1, "Town hall"), room(2, "Book club")), me, null)
        assertEquals(listOf("Town hall"), filterInviteRooms(choices, " town ").map { it.name })
        assertEquals(2, filterInviteRooms(choices, "").size)
        assertEquals(0, filterInviteRooms(choices, "garden").size)
    }

    @Test fun `the picker's words name the person, or say them when nameless`() {
        assertEquals("Invite Priya to a room", InvitationWords.pickerTitle("Priya"))
        assertEquals("Invite to a room", InvitationWords.pickerTitle(null))
        assertEquals("Priya is already here", InvitationWords.alreadyHere("Priya"))
        assertEquals("Somebody is already here", InvitationWords.alreadyHere(null))
        assertEquals("The invite goes to Priya sealed. Only Priya can open it.", InvitationWords.sealedNote("Priya"))
        assertEquals("The invite goes to them sealed. Only they can open it.", InvitationWords.sealedNote(null))
        assertEquals("You have no rooms to invite Priya to yet. Start a room from Chats, then invite Priya.", InvitationWords.empty("Priya"))
        assertEquals("You invited Priya to Town hall.", InvitationWords.invited("Priya", "Town hall"))
    }

    @Test fun `the ask-first door names the room in its title`() {
        assertEquals("Ask to join Release workshop", InvitationWords.askToJoin("Release workshop"))
        assertEquals("Ask to join this room", InvitationWords.askToJoin(" "))
        assertEquals("Opening Release workshop…", InvitationWords.opening(" Release workshop "))
        assertEquals("Opening the invitation…", InvitationWords.opening(null))
    }

    @Test fun `a received card cannot name the room, and never shows a key`() {
        assertEquals(InviteCard("Priya invited you to a room", "Sealed for you · 09:41", "Accept"),
            inviteCard(roomInviteText(), sentByMe = false, sender = "Priya", recipient = null, room = "Ignored", time = "09:41"))
        assertEquals(InviteCard("Somebody invited you to a private chat", "Sealed for you · 09:41", "Accept"),
            inviteCard(inviteText(), sentByMe = false, sender = null, recipient = null, room = null, time = "09:41"))
    }

    @Test fun `your own card names the room when this phone has it`() {
        assertEquals(InviteCard("You invited Priya to Town hall", "Sent · 09:41", "Open"),
            inviteCard(roomInviteText(), sentByMe = true, sender = null, recipient = "Priya", room = "Town hall", time = "09:41"))
        assertEquals("You invited somebody to a room",
            inviteCard(roomInviteText(), sentByMe = true, sender = null, recipient = null, room = null, time = "").title)
        assertEquals("You invited Priya to a private chat",
            inviteCard(inviteText(), sentByMe = true, sender = null, recipient = "Priya", room = "Town hall", time = "").title)
    }
}
