package dev.forgesworn.kithmoot.node

import dev.forgesworn.kithmoot.protocol.NodeRoomRecord
import dev.forgesworn.kithmoot.protocol.NodeRooms
import dev.forgesworn.kithmoot.storage.SavedRoom
import kotlinx.serialization.json.JsonObject
import java.net.URI

/** What a saved room makes of a destination announced in its control channel. */
internal sealed interface NodeDestinationView {
    val host: String
    /** Signed by the authority this copy pinned, for this same room and policy:
     * this device follows it, and holds what its person sends until it has. */
    data class Followable(val node: NodeRoomRecord) : NodeDestinationView {
        override val host: String get() = URI(node.origin).host
    }
    /** For this room, but this copy cannot confirm it: no pinned authority to
     * check its signature against, or the authority's record names another
     * policy. It is never followed and never holds a message; it only says why. */
    data class Unconfirmed(override val host: String) : NodeDestinationView
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
    return if (pinned != null && existingNodeMatches(source, record)) NodeDestinationView.Followable(record)
        else NodeDestinationView.Unconfirmed(URI(record.origin).host)
}

/** The lasting words a room shows while this device has not followed its move. The web says the same. */
internal object NodeFollowWords {
    fun waiting(host: String) = "This room has moved to $host. This device will follow as soon as one of the owner’s devices is online. " +
        "Messages you send meanwhile will go once it has moved."
    fun unconfirmed(host: String) = "This room has moved to $host, but this device cannot confirm the move from its saved copy of the room. " +
        "Open the room’s latest link to follow it."
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
