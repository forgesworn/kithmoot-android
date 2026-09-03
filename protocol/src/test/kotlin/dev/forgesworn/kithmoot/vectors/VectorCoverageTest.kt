package dev.forgesworn.kithmoot.vectors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the contract itself. The vector file is the interop agreement, so a
 * vector quietly going missing - or its negative cases being dropped, which is
 * the only way an implementation that accepts everything can look green - has
 * to fail the build.
 */
class VectorCoverageTest {

    @Test
    fun everyGroupIsFullyCovered() {
        // Every group in the published file, and how many vectors it holds.
        // A group this client does not yet implement is still counted here,
        // the way `roomDescriptor` always has been: the count is what makes a
        // silently-dropped vector impossible, and the missing test is what
        // the README's gap list is for.
        val expectedSizes = mapOf(
            "roomDerivation" to 4,
            "channelDerivation" to 3,
            "joinUrl" to 9,
            "deviceCredential" to 4,
            "rosterEvent" to 12,
            "signalWrap" to 5,
            "kindredProof" to 3,
            "accessEvaluation" to 11,
            "turnCredential" to 4,
            "roomDescriptor" to 6,
            "roomEpoch" to 10,
            "agentOwnership" to 8,
            "chatAttachment" to 7,
            "approvalControl" to 9,
        )
        assertEquals("group names", expectedSizes.keys, Vectors.groups.keys)
        for ((group, size) in expectedSizes) {
            assertEquals("vectors in $group", size, Vectors.group(group).size)
        }
        assertEquals("total vectors", 95, expectedSizes.values.sum())
    }

    @Test
    fun everyDecidingGroupCarriesNegatives() {
        for (group in listOf("joinUrl", "deviceCredential", "rosterEvent", "signalWrap", "accessEvaluation")) {
            val negatives = Vectors.group(group).count { it.text("kind") == "negative" }
            assertTrue("$group must carry negative vectors", negatives > 0)
        }
    }

    @Test
    fun pinsTheProtocolVersion() {
        assertEquals("kithmoot/v1", Vectors.root.text("protocolVersion"))
    }
}
