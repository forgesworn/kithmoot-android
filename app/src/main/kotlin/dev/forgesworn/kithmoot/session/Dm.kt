package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.hexEquals
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.normaliseHex
import dev.forgesworn.kithmoot.protocol.KindredTier
import dev.forgesworn.kithmoot.protocol.RoomPolicy

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

/**
 * The link inside an invitation, for the addressee or for the sender's own
 * other devices, or null for anybody else: the conversation key is the same
 * from either end, and nobody else is given a decrypt to try.
 */
fun openInvite(invite: ChatInvite, self: String, sender: String, participantSecretKey: ByteArray): String? {
    val peer = when {
        invite.to.hexEquals(self) -> sender
        sender.hexEquals(self) -> invite.to
        else -> return null
    }
    return runCatching {
        Nip44.decrypt(invite.link, Nip44.conversationKey(participantSecretKey, peer.normaliseHex().hexToBytes()))
    }.getOrNull()?.takeIf { it.isNotEmpty() }
}

/** Seal a DM room's link to one participant. */
fun sealInvite(link: String, to: String, room: String, participantSecretKey: ByteArray, nonce: ByteArray? = null): ChatInvite {
    require(link.isNotEmpty()) { "an invitation needs a link" }
    val key = Nip44.conversationKey(participantSecretKey, to.normaliseHex().hexToBytes())
    val sealed = if (nonce == null) Nip44.encrypt(link, key) else Nip44.encrypt(link, key, nonce)
    return ChatInvite(to.normaliseHex(), room.normaliseHex(), sealed)
}
