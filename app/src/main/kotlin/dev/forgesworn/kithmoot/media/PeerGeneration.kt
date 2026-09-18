package dev.forgesworn.kithmoot.media

import kotlin.math.max

/**
 * Generation ownership and glare: section 3.3 of the call reliability spec, as
 * a pure function. The Android sibling of the web client's
 * `src/peer-generation.ts`, decision for decision.
 *
 * H2 is a pair whose two ends disagree about which connection they are on.
 * Nothing on the profile-1 wire says: an offer describes a session, not which
 * session, so a side that rebuilt its connection and a side that did not both
 * believe they are talking to each other while `setRemoteDescription` rejects
 * everything - each of them on its own rest timer, chasing the other for the
 * rest of the call.
 *
 * A generation is the missing sentence. An offer opens one; a rebuild always
 * goes to `max(local, lastSeenRemote) + 1`; a higher generation always wins and
 * always cancels the loser's timers. That is what makes convergence a property
 * of the numbering rather than of who happened to be quickest, and it is why
 * there is no negotiation anywhere about who negotiates.
 *
 * Kept here, away from the connection, because the table is the part worth
 * reading on its own and the part a test should be able to drive exhaustively
 * without a peer connection of any kind.
 */

/** What this side is currently owed an answer for. */
enum class OutstandingOffer {
    /** Nothing outstanding: this side is not waiting on anybody. */
    NONE,

    /** An offer that opened the current generation, and so created this
     *  connection's four transceivers. Amendment A1's case. */
    OPENING,

    /** An offer inside a generation that is already established - an ICE
     *  restart. The slots already exist and nothing new was created for it. */
    IN_GENERATION,
}

data class GenerationInput(
    /** The `gen` on the incoming signal. */
    val incomingGen: Long,
    /** The generation this side's current connection belongs to. */
    val currentGen: Long,
    /** The `conn` on the incoming signal, when it carried one. */
    val incomingConn: String? = null,
    /** The remote connection this side's channel is bound to, if it has learned
     *  one yet. */
    val boundConn: String? = null,
    val outstanding: OutstandingOffer = OutstandingOffer.NONE,
    /** This side's perfect-negotiation politeness, decided by pubkey order. */
    val polite: Boolean,
    /** Whether the incoming offer carries a `slots` map, i.e. whether it opens
     *  a generation. */
    val opensGeneration: Boolean,
)

sealed interface GenerationAction {
    /** Older than ours: drop it and tell the far end where we are. One `sync`
     *  per two seconds, which the channel rate limits. */
    data object Sync : GenerationAction

    /** Newer than ours: abandon everything local and answer it on a fresh
     *  connection at that generation. */
    data class Adopt(val gen: Long) : GenerationAction

    /** Ordinary perfect negotiation inside the generation we are already on. */
    data object Negotiate : GenerationAction

    /** Glare, and we are impolite: keep retransmitting our own and say
     *  nothing. */
    data object Ignore : GenerationAction

    /**
     * Glare on a generation-opening offer, and we are polite.
     *
     * Amendment A1: a rollback does not release the four transceivers this side
     * opened, so rolling back and answering would leave the far end's four
     * m-lines with nowhere to go and the connection would make four more.
     * Discarding the connection object is the only thing that actually gives
     * them up.
     */
    data object RebuildConnection : GenerationAction

    /**
     * Glare inside an established generation, and we are polite.
     *
     * Nothing was created for an in-generation offer - the slots have existed
     * since the generation opened - so A1's reason does not apply and ordinary
     * rollback is both correct and much cheaper than throwing away a connection
     * that is carrying media.
     */
    data object Rollback : GenerationAction

    /** Same generation, a connection we have never heard of, and nothing
     *  outstanding to explain it. A protocol error; the repair is to go up. */
    data class RebuildGeneration(val gen: Long) : GenerationAction
}

/** Where a rebuild goes. Never reuses a number, on either side, whatever order
 *  the two sides rebuilt in. */
fun nextGeneration(localGen: Long, lastSeenRemoteGen: Long): Long =
    max(max(localGen, lastSeenRemoteGen), 0) + 1

/**
 * The table of section 3.3, for an incoming **offer**.
 *
 * Answers and candidates need none of this: they belong to an offer that has
 * already been judged, and the reliable channel's `(conn, seq)` ordering is what
 * keeps them with it.
 */
fun decideOffer(input: GenerationInput): GenerationAction {
    if (input.incomingGen < input.currentGen) return GenerationAction.Sync
    if (input.incomingGen > input.currentGen) return GenerationAction.Adopt(input.incomingGen)

    if (input.outstanding != OutstandingOffer.NONE) {
        // Glare. Politeness is decided by pubkey order and the two sides reach
        // opposite answers without exchanging a word about it.
        if (!input.polite) return GenerationAction.Ignore
        return if (input.outstanding == OutstandingOffer.OPENING && input.opensGeneration) {
            GenerationAction.RebuildConnection
        } else {
            GenerationAction.Rollback
        }
    }

    // Nothing outstanding, so this is the far end renegotiating on a connection
    // we should already know about.
    val incoming = input.incomingConn
    val bound = input.boundConn
    if (incoming != null && bound != null && incoming != bound) {
        return GenerationAction.RebuildGeneration(nextGeneration(input.currentGen, input.incomingGen))
    }
    return GenerationAction.Negotiate
}
