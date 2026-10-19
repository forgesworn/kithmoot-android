package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.account.ParticipantSigner
import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.hexEquals
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.normaliseHex
import dev.forgesworn.kithmoot.protocol.KindredTier
import dev.forgesworn.kithmoot.protocol.InvitationPayload
import dev.forgesworn.kithmoot.protocol.RoomPolicy
import dev.forgesworn.kithmoot.protocol.decodeInvitationUrl

/**
 * Direct messages. A DM is a room whose link admits two members; its link
 * travels sealed to the other member inside a chat message in a room both
 * already share. See docs/messages.md in the reference implementation and
 * the `chatInvite` vectors.
 */

/** Open in tier, shut to everybody but the pair. Sorted, so both sides agree. */
fun dmPolicy(a: String, b: String): RoomPolicy {
    val members = listOf(a.normaliseHex(), b.normaliseHex()).sorted()
    require(members[0] != members[1]) { "a direct message needs two people" }
    return RoomPolicy(KindredTier.OPEN, null, members)
}

fun isDmPolicy(policy: RoomPolicy?): Boolean = policy?.members?.size == 2

/** The other member of a DM this participant is in, or null. */
fun dmPeer(policy: RoomPolicy?, self: String): String? {
    if (!isDmPolicy(policy)) return null
    val members = policy!!.members!!
    if (members.none { it.hexEquals(self) }) return null
    return members.firstOrNull { !it.hexEquals(self) }
}

/** The peer whose signer can open an invitation, from either participant's copy. */
fun invitePeer(invite: ChatInvite, self: String, sender: String): String? = when {
    invite.to.hexEquals(self) -> sender.normaliseHex()
    sender.hexEquals(self) -> invite.to.normaliseHex()
    else -> null
}

/**
 * The link inside an invitation, for the addressee or for the sender's own
 * other devices, or null for anybody else: the conversation key is the same
 * from either end, and nobody else is given a decrypt to try.
 */
fun openInvite(invite: ChatInvite, self: String, sender: String, participantSecretKey: ByteArray): String? {
    val peer = invitePeer(invite, self, sender) ?: return null
    return runCatching {
        Nip44.decrypt(invite.link, Nip44.conversationKey(participantSecretKey, peer.normaliseHex().hexToBytes()))
    }.getOrNull()?.takeIf { it.isNotEmpty() }
}

/** Open a DM invitation without bringing the participant secret onto this device. */
suspend fun openInvite(invite: ChatInvite, self: String, sender: String, signer: ParticipantSigner): String? {
    if (!signer.pubkey.hexEquals(self)) return null
    val peer = invitePeer(invite, self, sender) ?: return null
    return runCatching { signer.nip44Decrypt(peer, invite.link) }.getOrNull()?.takeIf { it.isNotEmpty() }
}

/** Seal a DM room's link to one participant. */
fun sealInvite(link: String, to: String, room: String, participantSecretKey: ByteArray, nonce: ByteArray? = null): ChatInvite {
    require(link.isNotEmpty()) { "an invitation needs a link" }
    val key = Nip44.conversationKey(participantSecretKey, to.normaliseHex().hexToBytes())
    val sealed = if (nonce == null) Nip44.encrypt(link, key) else Nip44.encrypt(link, key, nonce)
    return ChatInvite(to.normaliseHex(), room.normaliseHex(), sealed)
}

/** Seal a DM invitation without bringing the participant secret onto this device. */
suspend fun sealInvite(link: String, to: String, room: String, signer: ParticipantSigner): ChatInvite {
    require(link.isNotEmpty()) { "an invitation needs a link" }
    val peer = to.normaliseHex()
    return ChatInvite(peer, room.normaliseHex(), signer.nip44Encrypt(peer, link))
}

/**
 * What an opened invitation turns out to be. The same sealed `invite` field
 * carries two things: a private conversation (a persistent room of exactly
 * the two people) or an invitation to an ordinary room of the sender's, which
 * the web client sends from a roster row. Which one is decided by the link's
 * policy, never by the message text.
 */
sealed interface OpenedInvitation {
    val payload: InvitationPayload
    data class PrivateConversation(override val payload: InvitationPayload) : OpenedInvitation
    data class Room(override val payload: InvitationPayload) : OpenedInvitation
}

/**
 * Sort an opened envelope into a private conversation or a room, or refuse it.
 * A two-member policy must be exactly this pair's: a room of two that is not
 * ours is refused rather than treated as an ordinary room. An ordinary room
 * must be a persistent invitation; temporary rooms go through the guest
 * admission gate on the home screen, and a pairing link (another device of
 * the sender's) is never an invitation to accept.
 */
fun classifyOpenedInvite(
    link: String,
    invite: ChatInvite,
    self: String,
    sender: String,
): OpenedInvitation? = try {
    val peer = invitePeer(invite, self, sender)
    val payload = decodeInvitationUrl(link)
    when {
        peer == null || payload == null -> null
        isDmPolicy(payload.policy) ->
            payload.takeIf { it.invitation.persistent && it.policy == dmPolicy(self, peer) }
                ?.let { OpenedInvitation.PrivateConversation(it) }
        !payload.invitation.persistent || decodeInvitationPairingLink(link) != null -> null
        else -> OpenedInvitation.Room(payload)
    }
} catch (_: Exception) {
    null
}

/** Why [classifyOpenedInvite] refused a link, in words for the person who tapped Open. */
fun refusedInvitationReason(link: String): String {
    val payload = runCatching { decodeInvitationUrl(link) }.getOrNull()
    return when {
        payload == null -> "This invitation could not be read."
        isDmPolicy(payload.policy) -> "This invitation is not for the claimed two-person conversation."
        !payload.invitation.persistent ->
            "This invitation is for a temporary room. Ask for its link and open it from the home screen."
        else -> "This invitation could not be opened here."
    }
}

/** Refuse an opened envelope unless it is the exact persistent two-person room it claims to be. */
fun decodePrivateConversationInvite(
    link: String,
    invite: ChatInvite,
    self: String,
    sender: String,
): InvitationPayload? = try {
    val peer = invitePeer(invite, self, sender) ?: return null
    val payload = decodeInvitationUrl(link) ?: return null
    payload.takeIf { it.invitation.persistent && it.policy == dmPolicy(self, peer) }
} catch (_: Exception) {
    null
}
