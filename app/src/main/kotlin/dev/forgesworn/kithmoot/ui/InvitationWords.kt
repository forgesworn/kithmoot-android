package dev.forgesworn.kithmoot.ui

import dev.forgesworn.kithmoot.ui.room.NAMELESS

/**
 * The words of the ask-first door, the invitation cards and the room-invite
 * picker (design-calm-shell stage 6, the web's design-calm-stage6.md section
 * 13). They live together so the web's final wording lands here as one
 * change. A person with no name is [NAMELESS], never a piece of their key.
 */
internal object InvitationWords {
    // The ask-first door (GuestAdmissionDialog, before the request is sent).
    fun askToJoin(room: String): String = if (room.isBlank()) "Ask to join this room" else "Ask to join $room"
    const val ASK_LEAD = "Someone in the room needs to let you in. Check your details before sending a request."
    const val NAME_LABEL = "What should we call you?"
    const val NAME_PLACEHOLDER = "Your name"
    const val CHECKS_HEADING = "Before you request to join"
    const val CHECKS_NOTE = "Device checks stay on this phone and stop when you request to join. " +
        "Your call microphone and camera start only when you choose them."
    const val PREVIEW_CAMERA = "Preview camera"
    const val TEST_MICROPHONE = "Test microphone"
    const val REQUEST = "Request to join"
    const val CLOSE_INVITATION = "Close invitation"

    /** What the start screen says while a link opens: the room's own name when the link carries one. */
    fun opening(room: String?): String = room?.trim()?.takeIf { it.isNotEmpty() }?.let { "Opening $it…" } ?: "Opening the invitation…"

    // Invitation cards inside a chat. A received room invitation cannot name
    // its room: the name is inside the sealed link, opened only on Accept.
    fun received(sender: String?, privateChat: Boolean): String =
        "${sender ?: NAMELESS} invited you to ${if (privateChat) "a private chat" else "a room"}"
    fun sent(to: String?, privateChat: Boolean, room: String?): String = when {
        privateChat -> "You invited ${to ?: "somebody"} to a private chat"
        room.isNullOrBlank() -> "You invited ${to ?: "somebody"} to a room"
        else -> "You invited ${to ?: "somebody"} to $room"
    }
    fun sealedForYou(time: String): String = listOf("Sealed for you", time).filter { it.isNotEmpty() }.joinToString(" · ")
    fun sentAt(time: String): String = listOf("Sent", time).filter { it.isNotEmpty() }.joinToString(" · ")
    const val ACCEPT = "Accept"
    const val OPEN = "Open"

    // The picker: People's Invite, and a member's Invite to a room.
    fun pickerTitle(name: String?): String = name?.let { "Invite $it to a room" } ?: "Invite to a room"
    const val FIND_ROOM = "Find a room"
    const val NO_MATCH = "No rooms match."
    fun alreadyHere(name: String?): String = "${name ?: NAMELESS} is already here"
    fun sealedNote(name: String?): String =
        if (name == null) "The invite goes to them sealed. Only they can open it."
        else "The invite goes to $name sealed. Only $name can open it."
    fun empty(name: String?): String {
        val who = name ?: "them"
        return "You have no rooms to invite $who to yet. Start a room from Chats, then invite $who."
    }
    const val CANCEL = "Cancel"
    const val SEND = "Send invite"
    const val SENDING = "Sending…"
    const val CLOSE = "Close"
    const val MEMBER_ACTION = "Invite to a room"
    const val PEOPLE_ACTION = "Invite"
    fun invited(name: String?, room: String): String = "You invited ${name ?: "somebody"} to $room."

    /** How many rooms before the picker offers a search, as on the web. */
    const val SEARCH_FROM = 6
}

/** What an invitation card in a chat says: its line, the line under it, and its button. */
internal data class InviteCard(val title: String, val detail: String, val action: String)

/**
 * The card for a sealed invitation in a chat. [body] is the message's
 * fallback text, which only picks the wording: Accept still decides by the
 * link's policy once the signer has unsealed it. [room] is the name of a
 * room this phone has saved under the invitation's room id, which only the
 * sender's own card can use.
 */
internal fun inviteCard(body: String, sentByMe: Boolean, sender: String?, recipient: String?, room: String?, time: String): InviteCard {
    val privateChat = body == dev.forgesworn.kithmoot.session.inviteText()
    return if (sentByMe) InviteCard(InvitationWords.sent(recipient, privateChat, room), InvitationWords.sentAt(time), InvitationWords.OPEN)
    else InviteCard(InvitationWords.received(sender, privateChat), InvitationWords.sealedForYou(time), InvitationWords.ACCEPT)
}
