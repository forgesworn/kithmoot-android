package dev.forgesworn.kithmoot.ui

import dev.forgesworn.kithmoot.protocol.DisplayName
import dev.forgesworn.kithmoot.ui.start.privateChatLabel

/** A personal label belongs to this account, never to the room's control channel. */
internal fun directChatName(chatLabel: String? = null, peerName: String? = null, rosterName: String? = null,
    rememberedName: String? = null, selfName: String? = null): String {
    val remembered = rememberedName?.let(::privateChatLabel)?.let(DisplayName::sanitise)
    return DisplayName.sanitise(chatLabel) ?: DisplayName.sanitise(peerName) ?: DisplayName.sanitise(rosterName)
        ?: remembered?.takeUnless { it == DisplayName.sanitise(selfName) || it == "Untitled room" }
        ?: "Private chat"
}
