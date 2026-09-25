package dev.forgesworn.kithmoot.media

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The per-peer progress line, built from stats the getStats callback hands
 * over. Audio has no framesDecoded, so it needs its own read of the report;
 * this is the case that misled a debugging session into reading frames=0 as
 * audio not decoding.
 */
class MediaProgressSummaryTest {

    @Test
    fun `audio inbound reads packets, loss, samples and concealment`() {
        val entries = listOf(
            StatsEntry(
                "inbound-rtp",
                mapOf(
                    "kind" to "audio",
                    "packetsReceived" to 120L,
                    "packetsLost" to 3L,
                    "totalSamplesReceived" to 48000L,
                    "concealedSamples" to 40L,
                ),
            ),
        )

        assertEquals(
            "audio: packets=120, lost=3, samples=48000, concealed=40",
            summariseMediaProgress(entries),
        )
    }

    @Test
    fun `audio inbound adds silentConcealed and level only when present`() {
        val entries = listOf(
            StatsEntry(
                "inbound-rtp",
                mapOf(
                    "kind" to "audio",
                    "packetsReceived" to 120L,
                    "packetsLost" to 0L,
                    "totalSamplesReceived" to 48000L,
                    "concealedSamples" to 0L,
                    "silentConcealedSamples" to 0L,
                    "audioLevel" to 0.02,
                ),
            ),
        )

        assertEquals(
            "audio: packets=120, lost=0, samples=48000, concealed=0, silentConcealed=0, level=0.02",
            summariseMediaProgress(entries),
        )
    }

    @Test
    fun `video inbound still reads framesDecoded and packetsLost`() {
        val entries = listOf(
            StatsEntry(
                "inbound-rtp",
                mapOf("kind" to "video", "framesDecoded" to 900L, "packetsLost" to 2L),
            ),
        )

        assertEquals("video: frames=900, lost=2", summariseMediaProgress(entries))
    }

    @Test
    fun `outbound audio contributes packetsSent, outbound video is dropped`() {
        val entries = listOf(
            StatsEntry("outbound-rtp", mapOf("kind" to "audio", "packetsSent" to 500L)),
            StatsEntry("outbound-rtp", mapOf("kind" to "video", "packetsSent" to 900L)),
        )

        assertEquals("audioSent: packets=500", summariseMediaProgress(entries))
    }

    @Test
    fun `missing counters default to zero rather than throwing`() {
        val entries = listOf(StatsEntry("inbound-rtp", mapOf("kind" to "audio")))

        assertEquals(
            "audio: packets=0, lost=0, samples=0, concealed=0",
            summariseMediaProgress(entries),
        )
    }

    @Test
    fun `a peer with both legs joins them with a semicolon, as one line`() {
        val entries = listOf(
            StatsEntry("inbound-rtp", mapOf("kind" to "audio", "packetsReceived" to 10L)),
            StatsEntry("inbound-rtp", mapOf("kind" to "video", "framesDecoded" to 30L)),
            StatsEntry("outbound-rtp", mapOf("kind" to "audio", "packetsSent" to 12L)),
        )

        assertEquals(
            "audio: packets=10, lost=0, samples=0, concealed=0; video: frames=30, lost=0; audioSent: packets=12",
            summariseMediaProgress(entries),
        )
    }
}
