package dev.forgesworn.kithmoot.media

/**
 * What a session description *says*, with the parts that change on every
 * re-render taken out.
 *
 * Two descriptions of the same session are rarely the same bytes. A stack
 * re-renders `o=` with a higher session version each time it is asked for one,
 * and a description re-read off a live connection carries every candidate
 * gathered since - which is exactly how a retransmitted offer goes back out
 * (see `localDescription` in [PeerLink]'s channel). So byte equality answers
 * "are these the same object", never "do these describe the same session", and
 * the second question is the one negotiation has to ask.
 *
 * The shape keeps everything that decides what the pair will actually do:
 * the media sections and their order, the direction attributes - `a=sendrecv`,
 * `a=sendonly`, `a=recvonly`, `a=inactive` - and the ICE credentials
 * `a=ice-ufrag` and `a=ice-pwd`, which are what make an ICE restart a different
 * session rather than the same one described again.
 *
 * Two answers to the same offer whose shapes differ mean the two ends of the
 * pair completed different negotiations: one side ends up `sendonly` while the
 * other believes it is `sendrecv`, RTP arrives and is never played, and neither
 * end's signalling state says anything is wrong. Comparing shapes is how that
 * is noticed at all.
 */
object SdpShape {

    /**
     * The shape of one description: normalised line endings, no candidates, and
     * an `o=` line whose session version is flattened.
     *
     * Deliberately a string rather than a parsed structure. It is only ever
     * compared with another shape, it has to survive being held for the life of
     * a connection, and a structure would invite the temptation to interpret
     * lines this has no business interpreting.
     */
    fun of(sdp: String): String = buildString {
        for (raw in sdp.split('\n')) {
            val line = raw.trimEnd('\r')
            if (line.isEmpty()) continue
            // A candidate is gathered, not negotiated: the same session
            // description re-read a second later carries more of them, and a
            // trickled candidate arrives outside the description entirely.
            if (line.startsWith("a=candidate:")) continue
            if (line.startsWith("a=end-of-candidates")) continue
            // The three things a stack rewrites the moment it has a candidate
            // to put in them. They say where the media would go, which is a
            // gathering result, not a negotiated one - and `a=rtcp-mux`,
            // `a=rtcp-fb` and `a=rtcp-rsize` are none of them, so only the bare
            // `a=rtcp:` line goes.
            if (line.startsWith("a=rtcp:")) continue
            append(
                when {
                    line.startsWith("o=") -> originWithoutVersion(line)
                    line.startsWith("m=") -> mediaWithoutPort(line)
                    line.startsWith("c=") -> "c=IN IP4 0.0.0.0"
                    else -> line
                },
            )
            append('\n')
        }
    }

    /**
     * `m=<media> <port> <proto> <formats...>` with the port flattened.
     *
     * libwebrtc offers port 9 - the discard port - until it has a candidate,
     * and then rewrites the line with the port of whichever one it chose. The
     * media, the transport and the formats are the negotiation; the port is
     * where this render happened to point.
     */
    private fun mediaWithoutPort(line: String): String {
        val fields = line.split(' ')
        if (fields.size < 3) return line
        return fields.mapIndexed { index, field -> if (index == 1) "9" else field }.joinToString(" ")
    }

    /** Whether two descriptions describe the same session, whatever their bytes. */
    fun same(first: String, second: String): Boolean = of(first) == of(second)

    /**
     * `o=<username> <sess-id> <sess-version> <nettype> <addrtype> <address>`
     * with the third field flattened.
     *
     * The session id stays: a description carrying a different one is a
     * different session and must not compare equal. The version is bumped by
     * the stack every time it renders the session it already holds, which is
     * the thing being normalised away.
     */
    private fun originWithoutVersion(line: String): String {
        val fields = line.removePrefix("o=").split(' ')
        if (fields.size < 3) return line
        return "o=" + fields.mapIndexed { index, field -> if (index == 2) "0" else field }.joinToString(" ")
    }
}
