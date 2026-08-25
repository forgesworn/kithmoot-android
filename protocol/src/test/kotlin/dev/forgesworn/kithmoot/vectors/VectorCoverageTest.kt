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
        val expectedSizes = mapOf(
            "roomDerivation" to 4,
            "joinUrl" to 9,
            "deviceCredential" to 4,
            "rosterEvent" to 7,
            "signalWrap" to 5,
            "kindredProof" to 3,
            "accessEvaluation" to 11,
            "turnCredential" to 4,
            "roomDescriptor" to 6,
        )
        assertEquals("group names", expectedSizes.keys, Vectors.groups.keys)
        for ((group, size) in expectedSizes) {
            assertEquals("vectors in $group", size, Vectors.group(group).size)
        }
        assertEquals("total vectors", 53, expectedSizes.values.sum())
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
