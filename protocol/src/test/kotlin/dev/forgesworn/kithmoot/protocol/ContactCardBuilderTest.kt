package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.Schnorr
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A card made here is a card the reader reads, and its content is byte for
 * byte what the reference writes for the same fields.
 */
class ContactCardBuilderTest {

    private val vectors: JsonObject by lazy {
        val stream = requireNotNull(javaClass.getResourceAsStream("/contact-card-vectors.json"))
        Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
    }

    private fun str(e: kotlinx.serialization.json.JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** The vector's content, rebuilt from its own fields, is the vector's content. */
    @Test
    fun contentMatchesTheReferenceByteForByte() {
        val checked = ArrayList<String>()
        for (case in vectors.getValue("cases").jsonArray.map { it.jsonObject }) {
            if (case.getValue("expect").jsonObject.getValue("ok").jsonPrimitive.content != "true") continue
            val event = case.getValue("card").jsonObject
            val contentText = str(event["content"])!!
            val c = Json.parseToJsonElement(contentText).jsonObject
            // Cases with keys the draft does not name are stripped on read; the
            // builder never writes them, so those cannot round-trip by design.
            if (c.keys.any { it !in setOf("v", "rz", "name", "relays", "boxes", "eph", "attest", "bond") }) continue
            if (c.getValue("boxes").jsonArray.any { b -> b.jsonObject.keys.any { it !in setOf("p", "claim", "card", "carriers") } }) continue
            val boxes = c.getValue("boxes").jsonArray.map { it.jsonObject }.map { b ->
                CardBox(str(b["p"])!!, str(b["claim"])!!, str(b["card"])!!, (b["carriers"] as? JsonArray)?.map { str(it)!! })
            }
            val built = ContactCardBuilder.content(
                rz = str(c["rz"])!!,
                eph = str(c["eph"])!!,
                relays = c.getValue("relays").jsonArray.map { str(it)!! },
                boxes = boxes,
                name = str(c["name"]),
                attest = str(c["attest"]),
                bond = c["bond"] as? JsonObject,
            )
            assertEquals(str(case["name"]), contentText, built)
            checked.add(str(case["name"])!!)
        }
        assertTrue(checked.toString(), "passes" in checked && "no-boxes-no-bond" in checked)
    }

    @Test
    fun aCardMadeHereReadsBackUnderItsKey() {
        val key = Entropy.bytes(32)
        val pubkey = Schnorr.publicKeyHex(key)
        val now = 1_800_000_000L
        val rz = Schnorr.publicKeyHex(Entropy.bytes(32))
        val eph = Schnorr.publicKeyHex(Entropy.bytes(32))
        val content = ContactCardBuilder.content(rz, eph, listOf("wss://relay.example", "wss://nos.example"), name = "Ada")
        val event = Events.sign(key, ContactCards.KIND, now - 60, ContactCardBuilder.tags(now + 7 * 24 * 3600), content)

        val link = ContactCardBuilder.link("https://kithmoot.example/j/#old", event)
        assertTrue(link.startsWith("https://kithmoot.example/j/#"))
        val r = ContactCards.read(link, now)
        assertTrue((r as? CardResult.Refused)?.let { "step ${it.step} ${it.reason}" } ?: "ok", r is CardResult.Ok)
        val card = (r as CardResult.Ok).card
        assertEquals(pubkey, card.p)
        assertEquals("Ada", card.name)
        assertEquals(rz, card.rz)
        assertEquals(eph, card.eph)
        assertEquals(listOf("wss://relay.example", "wss://nos.example"), card.relays)
        assertEquals(0, card.boxes.size)
        assertEquals(now - 60, card.issued)
        assertEquals(now + 7 * 24 * 3600, card.expires)
        assertEquals(event.id, card.id)
        assertNull(card.attest)
        assertNull(card.bond)
    }

    @Test
    fun theBareBodyReadsAsWellAsTheLink() {
        val key = Entropy.bytes(32)
        val now = 1_800_000_000L
        val content = ContactCardBuilder.content(Schnorr.publicKeyHex(Entropy.bytes(32)), Schnorr.publicKeyHex(Entropy.bytes(32)), emptyList())
        val event = Events.sign(key, ContactCards.KIND, now, ContactCardBuilder.tags(now + 3600), content)
        assertTrue(ContactCards.read(ContactCardBuilder.encode(event), now) is CardResult.Ok)
    }

    @Test
    fun aSignerThatAlteredTheEventIsCaughtByTheReader() {
        val key = Entropy.bytes(32)
        val now = 1_800_000_000L
        val content = ContactCardBuilder.content(Schnorr.publicKeyHex(Entropy.bytes(32)), Schnorr.publicKeyHex(Entropy.bytes(32)), emptyList())
        // A signer that added a tag: the event is valid Nostr and is not a card.
        val altered = Events.sign(key, ContactCards.KIND, now, ContactCardBuilder.tags(now + 3600) + listOf(listOf("t", "x")), content)
        val r = ContactCards.read(ContactCardBuilder.encode(altered), now)
        assertTrue(r is CardResult.Refused && r.step == 2)
    }

    @Test
    fun theBuilderRefusesWhatTheReaderWould() {
        val hex = Schnorr.publicKeyHex(Entropy.bytes(32))
        assertTrue(runCatching { ContactCardBuilder.content(hex.uppercase(), hex, emptyList()) }.isFailure)
        assertTrue(runCatching { ContactCardBuilder.content(hex, hex, listOf("http://not-a-relay")) }.isFailure)
        assertTrue(runCatching { ContactCardBuilder.content(hex, hex, List(9) { "wss://r$it.example" }) }.isFailure)
    }
}
