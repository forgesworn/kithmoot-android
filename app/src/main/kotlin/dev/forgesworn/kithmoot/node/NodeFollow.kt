package dev.forgesworn.kithmoot.node

import dev.forgesworn.kithmoot.protocol.NodeRoomRecord
import dev.forgesworn.kithmoot.protocol.NodeRooms
import dev.forgesworn.kithmoot.storage.SavedRoom
import kotlinx.serialization.json.JsonObject
import java.net.URI

/** What a saved room makes of a destination announced in its control channel.
 * Only [Followable] is followed or holds a message; the others only say why not. */
internal sealed interface NodeDestinationView {
    /** Signed by the authority this copy pinned, for this same room and policy:
     * this device follows it, and holds what its person sends until it has. */
    data class Followable(val node: NodeRoomRecord) : NodeDestinationView {
        val host: String get() = URI(node.origin).host
    }
    /** Signed by the authority this copy pinned, but it does not match the copy
     * (another policy, end or self-destruct): the move is real, and removing the
     * copy and opening the room's link again is the way to follow it. */
    data class Unconfirmed(val host: String) : NodeDestinationView
    /** This copy pinned no authority, so anybody holding the room key could have
     * written the record: neither its host nor any advice to remove the room is shown. */
    data object Unverified : NodeDestinationView
}

/** Null for anything that is not a destination for this room at all, and for a
 * record a pinned copy did not get from its authority: anybody holding the room
 * key can write to its control channel, and must not be able to put a lasting
 * banner naming their own host on every member's screen. */
internal fun viewNodeDestination(source: SavedRoom, raw: JsonObject, now: Long): NodeDestinationView? {
    if (source.nodeRoom != null) return null
    val pinned = source.authority
    val record = NodeRooms.read(raw, now, pinned) ?: return null
    if (record.existing == null || record.room != source.id) return null
    return when {
        pinned == null -> NodeDestinationView.Unverified
        existingNodeMatches(source, record) -> NodeDestinationView.Followable(record)
        else -> NodeDestinationView.Unconfirmed(URI(record.origin).host)
    }
}

/** The lasting words a room shows while this device has not followed its move. The web says the same. */
internal object NodeFollowWords {
    fun waiting(host: String) = "This room has moved to $host. This device will follow as soon as one of the owner’s devices is online. " +
        "Chat messages you send meanwhile will go once it has moved."
    fun unconfirmed(host: String) = "This room has moved to $host, but this device cannot confirm the move from its saved copy. " +
        "Remove the room from this device, then open the room’s link again to rejoin it there."
    /** For a copy with no pinned authority: names no host and advises nothing it cannot check. */
    const val UNVERIFIED = "Someone in this room says it has moved, but this device cannot check that from its saved copy. " +
        "Ask the room’s owner for its current link."
    /** Opening the node link beside a saved copy that cannot confirm the move: the same way out as [unconfirmed]. */
    const val UNCONFIRMED_LINK = "This device cannot confirm this room’s move from its saved copy. " +
        "Remove the room from this device, then open this link again to rejoin it there."
    /** A send that keeps nothing on the phone (a quiet room's) cannot wait for the move, so it is refused. */
    const val NOT_SENT_WHILE_MOVING = "Nothing was sent: this room is moving to its node. Send it again once this device has moved."
    /** A kept message waiting for the move, beside it in the chat. */
    const val WAITING_TO_MOVE = "Waiting to move"
}

/** How long a device that has not followed its room's move waits before asking
 * again: about 15 s, 30 s, 1 min and 2 min, then every 5 min. */
internal class NodeFollowBackoff {
    private var attempt = 0
    @Synchronized fun next(): Long = DELAYS_MS.getOrElse(attempt) { STEADY_MS }.also { attempt++ }
    @Synchronized fun reset() { attempt = 0 }
    companion object {
        val DELAYS_MS = listOf(15_000L, 30_000L, 60_000L, 120_000L)
        const val STEADY_MS = 300_000L
    }
}
