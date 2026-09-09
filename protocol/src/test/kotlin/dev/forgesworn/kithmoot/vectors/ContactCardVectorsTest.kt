package dev.forgesworn.kithmoot.vectors

import dev.forgesworn.kithmoot.protocol.CardResult
import dev.forgesworn.kithmoot.protocol.ContactCards
import dev.forgesworn.kithmoot.protocol.LinkCards
import dev.forgesworn.kithmoot.protocol.LinkVerdict
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * The contact card reader against `nostr-contact-card`'s known answers,
 * loaded verbatim from its `vectors/contact-card.json`: thirty-two cards
 * and the refresh cases, each with the expected verdict and the step that
 * fails. A verifier that disagrees on either is wrong.
 */
class ContactCardVectorsTest {

    private val root: JsonObject by lazy {
        val stream = requireNotNull(javaClass.getResourceAsStream("/contact-card-vectors.json")) { "contact-card-vectors.json is missing" }
        Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
    }

    @Test
    fun everyCardReadsAsTheDraftSays() {
        val now = root.number("now")
        val cases = root.getValue("cases").jsonArray.map { it.jsonObject }
        assertEquals(33, cases.size)
        val failures = ArrayList<String>()
        for (c in cases) {
            val name = c.text("name")
            val expect = c.child("expect")
            val r = ContactCards.read(c.text("encoded"), now)
            val ok = r is CardResult.Ok
            if (ok != expect.flag("ok")) {
                failures.add("$name: expected ok=${expect.flag("ok")}, got ${describe(r)}")
                continue
            }
            if (r is CardResult.Refused && r.step != expect.getValue("step").jsonPrimitive.int) {
                failures.add("$name: expected step ${expect.getValue("step").jsonPrimitive.int}, got ${describe(r)}")
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun thePassingCardComesBackRebuiltFromTheFieldsTheDraftNames() {
        val now = root.number("now")
        val passes = root.getValue("cases").jsonArray.map { it.jsonObject }.first { it.text("name") == "passes" }
        val r = ContactCards.read(passes.text("encoded"), now)
        assertTrue(describe(r), r is CardResult.Ok)
        val card = (r as CardResult.Ok).card
        // The vector carries the event; its content is the card.
        val event = passes.child("card")
        val content = Json.parseToJsonElement(event.text("content")).jsonObject
        assertEquals(event.text("pubkey"), card.p)
        assertEquals(event.text("id"), card.id)
        assertEquals(event.number("created_at"), card.issued)
        assertEquals(event.list("tags")[0].jsonArray[1].jsonPrimitive.content.toLong(), card.expires)
        assertEquals(content.text("rz"), card.rz)
        assertEquals(content.text("eph"), card.eph)
        assertEquals(content.textOrNull("name"), card.name)
        assertEquals(content.strings("relays"), card.relays)
        assertEquals(content.list("boxes").size, card.boxes.size)
        assertEquals(21641, card.event.kind)
        assertEquals(card.boxes.size, r.boxes.size)
        for ((box, link) in r.boxes) {
            assertEquals(64, link.nodeId.length)
            assertTrue(box.card.isNotEmpty())
        }
    }

    @Test
    fun refreshAgreesWithTheDraft() {
        val refresh = root.child("refresh")
        val later = refresh.number("later")
        val pinned = refresh.text("pinnedNodeId")
        val failures = ArrayList<String>()
        for (c in refresh.list("cases").map { it.jsonObject }) {
            val bytes = Base64.getUrlDecoder().decode(c.text("card"))
            val highest = if (c.isNull("highestSerial")) null else c.number("highestSerial")
            val v = LinkCards.refreshBox(c.textOrNull("pinnedNodeId") ?: pinned, bytes, later, highest)
            val ok = v is LinkVerdict.Ok
            if (ok != c.child("expect").flag("ok")) failures.add("${c.text("name")}: expected ok=${c.child("expect").flag("ok")}, got ${if (v is LinkVerdict.Refused) "rule ${v.rule} ${v.reason}" else "ok"}")
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun describe(r: CardResult): String = when (r) {
        is CardResult.Ok -> "ok"
        is CardResult.Refused -> "step ${r.step} (${r.reason})"
    }
}
