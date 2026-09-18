package dev.forgesworn.kithmoot.media

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Section 3.3's table, driven exhaustively without a peer connection of any
 * kind.
 *
 * H2 is the only hypothesis in the diagnosis that was never reproduced in a
 * browser, so it has to be pinned here instead: a pair whose two ends disagree
 * about which connection they are on, each of them offering into a session the
 * other has already replaced, each on its own rest timer, for the rest of the
 * call.
 */
class PeerGenerationTest {

    private fun input(
        incomingGen: Long,
        currentGen: Long,
        polite: Boolean = true,
        outstanding: OutstandingOffer = OutstandingOffer.NONE,
        opensGeneration: Boolean = true,
        incomingConn: String? = "bbbbbbbbbbbbbbbb",
        boundConn: String? = "bbbbbbbbbbbbbbbb",
    ) = GenerationInput(
        incomingGen = incomingGen,
        currentGen = currentGen,
        incomingConn = incomingConn,
        boundConn = boundConn,
        outstanding = outstanding,
        polite = polite,
        opensGeneration = opensGeneration,
    )

    @Test
    fun `a rebuild never reuses a number, whichever side rebuilt first`() {
        assertEquals(1, nextGeneration(0, 0))
        assertEquals(5, nextGeneration(4, 2))
        assertEquals(5, nextGeneration(2, 4), "above the far end's highest, not only our own")
        assertEquals(1, nextGeneration(-3, 0))
    }

    @Test
    fun `an older generation is dropped and told where we are`() {
        assertEquals(GenerationAction.Sync, decideOffer(input(incomingGen = 2, currentGen = 3)))
    }

    @Test
    fun `a newer generation always wins`() {
        // Whatever this side was doing. Because the higher generation always
        // wins and always cancels the loser's timers, both sides converge on
        // one connection with no negotiation about who negotiates.
        for (outstanding in OutstandingOffer.entries) {
            for (polite in listOf(true, false)) {
                assertEquals(
                    GenerationAction.Adopt(9),
                    decideOffer(input(incomingGen = 9, currentGen = 3, polite = polite, outstanding = outstanding)),
                    "gen 9 over gen 3, outstanding=$outstanding polite=$polite",
                )
            }
        }
    }

    @Test
    fun `the impolite side keeps its own offer in a glare`() {
        assertEquals(
            GenerationAction.Ignore,
            decideOffer(input(incomingGen = 2, currentGen = 2, polite = false, outstanding = OutstandingOffer.OPENING)),
        )
    }

    @Test
    fun `the polite side discards its connection when both opened a generation`() {
        // Amendment A1: a rollback does not release the four transceivers this
        // side opened, so rolling back and answering would leave the far end's
        // four m-lines with nowhere to go and the connection would make four
        // more. Discarding it is the only thing that gives them up.
        assertEquals(
            GenerationAction.RebuildConnection,
            decideOffer(
                input(
                    incomingGen = 2,
                    currentGen = 2,
                    polite = true,
                    outstanding = OutstandingOffer.OPENING,
                    opensGeneration = true,
                ),
            ),
        )
    }

    @Test
    fun `the polite side merely rolls back inside an established generation`() {
        // Nothing was created for an in-generation offer - the slots have
        // existed since the generation opened - so A1's reason does not apply
        // and throwing away a connection that is carrying media would be far
        // more expensive than it needs to be.
        assertEquals(
            GenerationAction.Rollback,
            decideOffer(
                input(
                    incomingGen = 2,
                    currentGen = 2,
                    polite = true,
                    outstanding = OutstandingOffer.IN_GENERATION,
                    opensGeneration = false,
                ),
            ),
        )
    }

    @Test
    fun `an opening offer while we hold an in-generation offer is still a rollback`() {
        assertEquals(
            GenerationAction.Rollback,
            decideOffer(
                input(
                    incomingGen = 2,
                    currentGen = 2,
                    polite = true,
                    outstanding = OutstandingOffer.IN_GENERATION,
                    opensGeneration = true,
                ),
            ),
        )
    }

    @Test
    fun `an offer from a connection we have never heard of is a protocol error`() {
        // Same generation, nothing outstanding to explain it. Going up is the
        // only repair that cannot be argued with.
        assertEquals(
            GenerationAction.RebuildGeneration(3),
            decideOffer(
                input(incomingGen = 2, currentGen = 2, incomingConn = "cccccccccccccccc"),
            ),
        )
    }

    @Test
    fun `an ordinary renegotiation inside the generation is just a negotiation`() {
        assertEquals(GenerationAction.Negotiate, decideOffer(input(incomingGen = 2, currentGen = 2)))
    }

    @Test
    fun `a first offer with no connection bound yet is a negotiation, not an error`() {
        assertEquals(
            GenerationAction.Negotiate,
            decideOffer(input(incomingGen = 2, currentGen = 2, boundConn = null)),
        )
        assertEquals(
            GenerationAction.Negotiate,
            decideOffer(input(incomingGen = 2, currentGen = 2, incomingConn = null)),
        )
    }

    @Test
    fun `two sides in glare reach opposite answers from the same table`() {
        // Politeness is decided by pubkey order and the two sides compute it
        // without exchanging a word. Exactly one of them gives way.
        val polite = decideOffer(input(incomingGen = 1, currentGen = 1, polite = true, outstanding = OutstandingOffer.OPENING))
        val impolite = decideOffer(input(incomingGen = 1, currentGen = 1, polite = false, outstanding = OutstandingOffer.OPENING))

        assertEquals(GenerationAction.RebuildConnection, polite)
        assertEquals(GenerationAction.Ignore, impolite)
    }
}
