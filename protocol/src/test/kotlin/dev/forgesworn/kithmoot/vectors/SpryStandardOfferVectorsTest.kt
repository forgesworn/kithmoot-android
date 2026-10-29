package dev.forgesworn.kithmoot.vectors

import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.SpryLink
import dev.forgesworn.kithmoot.protocol.SpryOffer
import dev.forgesworn.kithmoot.protocol.isSpryStandardOffer
import dev.forgesworn.kithmoot.protocol.isSpryStandardTurnUrls
import dev.forgesworn.kithmoot.protocol.standard
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared `spry-standard-offer-v1.json` vectors: another member's `spry:`
 * offer (docs/protocol.md, the descriptor's Spry offer). Which kind 24183
 * cards the standard-card verifier and the private-mesh verifier accept,
 * when an offer counts as a standard node a guest may redeem, and which TURN
 * URLs a standard node may answer with. A verbatim copy of the web project's
 * file, kept apart from `kithmoot-vectors.json`.
 */
class SpryStandardOfferVectorsTest {
    private val root: JsonObject = Json.parseToJsonElement(
        requireNotNull(SpryStandardOfferVectorsTest::class.java.getResourceAsStream("/spry-standard-offer-v1.json")) {
            "spry-standard-offer-v1.json is missing from the test resources"
        }.bufferedReader().use { it.readText() },
    ).jsonObject

    private fun JsonObject.cases(group: String) = list(group).map { it.jsonObject }

    @Test fun everyCardMeetsBothVerifiers() {
        for (v in root.cases("cards")) {
            val now = v.number("now")
            assertEquals("standard verifier: ${v.text("name")}", v.flag("standard"), SpryLink.standardCard(v["event"], now) != null)
            assertEquals("peer verifier: ${v.text("name")}", v.flag("peer"), SpryLink.card(v["event"], now) != null)
        }
    }

    @Test fun everyOfferIsJudgedAsTheWebJudgesIt() {
        for (v in root.cases("offers")) {
            val offer = v.child("offer")
            val node = (offer["node"] as? JsonObject)?.let(NostrEvent::fromJson)
            assertEquals("offer: ${v.text("name")}", v.flag("standard"), isSpryStandardOffer(offer.text("endpoint"), node))
        }
    }

    @Test fun everyUrlListIsJudgedAsTheWebJudgesIt() {
        for (v in root.cases("urls")) {
            assertEquals("urls: ${v.text("name")}", v.flag("accept"), isSpryStandardTurnUrls(v.strings("urls")))
        }
    }

    @Test fun aCardReadFreshKeepsItsOfferStandardAfterItLapses() {
        val card = root.cases("cards").first().child("event")
        val signed = card.number("created_at")
        val raw = buildJsonObject {
            put("v", 1); put("endpoint", root.text("endpoint")); put("token", "a".repeat(43)); put("expiresAt", signed + 86_400)
            put("node", card)
        }
        val offer = requireNotNull(SpryOffer.parse(raw, signed))
        assertEquals(card.text("id"), offer.node?.id)
        // Checked as of its own signing time, the card read while fresh still marks the offer.
        assertTrue(offer.standard)
        // Read once the card has expired, the offer carries no card and is not standard.
        val late = requireNotNull(SpryOffer.parse(raw, signed + 3600))
        assertNull(late.node)
        assertEquals(false, late.standard)
    }
}
