package dev.forgesworn.kithmoot.vectors

import kotlinx.serialization.json.jsonObject
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
            "joinUrl" to 10,
            "deviceCredential" to 4,
            "rosterEvent" to 26,
            "signalWrap" to 10,
            "signalCompatibility" to 12,
            "memberPass" to 11,
            "servicePolicy" to 7,
            "serviceScope" to 5,
            "serviceAudience" to 19,
            "kindredProof" to 3,
            "accessEvaluation" to 15,
            "turnCredential" to 4,
            "roomDescriptor" to 6,
            "roomEpoch" to 10,
            "epochRequestAdmission" to 5,
            "agentOwnership" to 8,
            "chatAttachment" to 7,
            "approvalControl" to 9,
            "verificationWords" to 6,
            "chatThread" to 6,
            "chatEdit" to 7,
            "chatOrder" to 4,
            "chatRetract" to 4,
            "chatMention" to 4,
            "chatInvite" to 3,
            "readPosition" to 6,
            "callBell" to 9,
            "roomRelays" to 4,
            "roomStorage" to 12,
            "roomName" to 15,
            "meeting" to 7,
            "chatHistory" to 5,
            "chatEnvelope2" to 14,
            "spryDeviceKey" to 5,
        )
        assertEquals("group names", expectedSizes.keys, Vectors.groups.keys)
        for ((group, size) in expectedSizes) {
            assertEquals("vectors in $group", size, Vectors.group(group).size)
        }
        assertEquals("total vectors", 289, expectedSizes.values.sum())
    }

    /**
     * `delivery-receipt-v1.json` is a separate file from `kithmoot-vectors.json`
     * (a verbatim copy of the web project's), so the group table above does not
     * count it. This pins its size, so a case (or the refusals) going missing
     * fails here.
     */
    @Test
    fun deliveryReceiptFileIsFullyCovered() {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(
            requireNotNull(VectorCoverageTest::class.java.getResourceAsStream("/delivery-receipt-v1.json")) {
                "delivery-receipt-v1.json is missing from the test resources"
            }.bufferedReader().use { it.readText() },
        ).jsonObject
        assertEquals("schema", 1L, root.number("schema"))
        assertEquals("id hashes", 2, root.list("idHashes").size)
        assertEquals("valid receipts", 12, root.list("valid").size)
        assertEquals("refused receipts", 33, root.list("refused").size)
    }

    /**
     * `spry-standard-offer-v1.json` is another separate verbatim copy of the
     * web project's file. This pins each group's size and that each carries
     * both an acceptance and a refusal.
     */
    @Test
    fun spryStandardOfferFileIsFullyCovered() {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(
            requireNotNull(VectorCoverageTest::class.java.getResourceAsStream("/spry-standard-offer-v1.json")) {
                "spry-standard-offer-v1.json is missing from the test resources"
            }.bufferedReader().use { it.readText() },
        ).jsonObject
        assertEquals("schema", 1L, root.number("schema"))
        for ((group, size, key) in listOf(Triple("cards", 20, "standard"), Triple("offers", 8, "standard"), Triple("urls", 53, "accept"))) {
            val cases = root.list(group).map { it.jsonObject }
            assertEquals("vectors in $group", size, cases.size)
            assertTrue("$group must accept something", cases.any { it.flag(key) })
            assertTrue("$group must refuse something", cases.any { !it.flag(key) })
        }
    }

    /**
     * `relay-outside-test-v1.json` is a separate verbatim copy of the web
     * project's file. This pins each group's size, that the deciding groups
     * carry both an acceptance and a refusal, and that some wrap is refused.
     */
    @Test
    fun relayOutsideTestFileIsFullyCovered() {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(
            requireNotNull(VectorCoverageTest::class.java.getResourceAsStream("/relay-outside-test-v1.json")) {
                "relay-outside-test-v1.json is missing from the test resources"
            }.bufferedReader().use { it.readText() },
        ).jsonObject
        assertEquals("schema", 1L, root.number("schema"))
        for ((group, size) in listOf("requests" to 12, "results" to 22)) {
            val cases = root.list(group).map { it.jsonObject }
            assertEquals("vectors in $group", size, cases.size)
            assertTrue("$group must accept something", cases.any { it.flag("accept") })
            assertTrue("$group must refuse something", cases.any { !it.flag("accept") })
        }
        assertEquals("verdicts", 15, root.list("verdicts").size)
        assertEquals("notRun", 2, root.list("notRun").size)
        val wraps = root.list("wraps").map { it.jsonObject }
        assertEquals("wraps", 9, wraps.size)
        assertTrue("some wrap must be refused", wraps.any { it["expect"] == kotlinx.serialization.json.JsonNull })
    }

    @Test
    fun everyDecidingGroupCarriesNegatives() {
        for (group in listOf("joinUrl", "deviceCredential", "rosterEvent", "signalWrap", "accessEvaluation", "epochRequestAdmission", "chatAttachment", "chatThread", "chatEdit", "chatOrder", "chatRetract", "chatMention", "chatInvite", "readPosition", "callBell", "roomRelays", "roomStorage", "roomName", "meeting", "chatHistory", "chatEnvelope2", "spryDeviceKey")) {
            val negatives = Vectors.group(group).count { it.text("kind") == "negative" }
            assertTrue("$group must carry negative vectors", negatives > 0)
        }
    }

    @Test
    fun pinsTheProtocolVersion() {
        assertEquals("kithmoot/v1", Vectors.root.text("protocolVersion"))
    }
}
