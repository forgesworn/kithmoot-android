package dev.forgesworn.kithmoot.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * The other half of `ContactCard.kt`: making a card of one's own.
 *
 * A card is a signed event of kind 30641 (CONTACT-CARD §1). The person's
 * key, the issue time and the expiry live on the event; everything else is
 * the content, a JSON string in the key order the reference writes it,
 * `v, rz, name, relays, boxes, eph, attest, bond`, absent keys omitted. The
 * order matters only for byte-equality with the reference's output: the
 * content is signed as the string it is, so any signer that signs events
 * signs the card, which is the whole reason it is an event.
 *
 * Nothing here signs. The unsigned event goes to whatever holds the key,
 * here a `ParticipantSigner` in the app, and the signed event comes back;
 * [encode] then makes the link body. A caller that wants to know the signer
 * returned what it was given reads the result with [ContactCards.read].
 */
object ContactCardBuilder {

    /** The content string, in the reference's key order. */
    fun content(
        rz: String,
        eph: String,
        relays: List<String>,
        boxes: List<CardBox> = emptyList(),
        name: String? = null,
        attest: String? = null,
        bond: JsonObject? = null,
    ): String {
        require(HEX64.matches(rz)) { "rz is 64 lower-case hex" }
        require(HEX64.matches(eph)) { "eph is 64 lower-case hex" }
        require(relays.size <= ContactCards.MAX_RELAYS) { "at most ${ContactCards.MAX_RELAYS} relays" }
        require(boxes.size <= ContactCards.MAX_BOXES) { "at most ${ContactCards.MAX_BOXES} boxes" }
        for (r in relays) require(LinkCards.isRelayUrl(r)) { "not a relay URL: $r" }
        val o = buildJsonObject {
            put("v", 1)
            put("rz", rz)
            if (name != null) put("name", name)
            put("relays", JsonArray(relays.map { JsonPrimitive(it) }))
            put("boxes", buildJsonArray {
                for (b in boxes) add(buildJsonObject {
                    put("p", b.p)
                    put("claim", b.claim)
                    put("card", b.card)
                    if (b.carriers != null) put("carriers", JsonArray(b.carriers.map { JsonPrimitive(it) }))
                })
            })
            put("eph", eph)
            if (attest != null) put("attest", attest)
            if (bond != null) put("bond", bond)
        }
        return o.toString()
    }

    /** The two tags a card carries, and no other (§1). */
    fun tags(expires: Long): List<List<String>> = listOf(listOf("d", "card"), listOf("expiration", expires.toString()))

    /** The link body: unpadded base64url of the event's JSON. */
    fun encode(event: NostrEvent): String {
        require(event.kind == ContactCards.KIND) { "not a card event" }
        val json = buildJsonObject {
            put("kind", event.kind)
            put("pubkey", event.pubkey)
            put("created_at", event.createdAt)
            put("tags", buildJsonArray { for (t in event.tags) add(JsonArray(t.map { JsonPrimitive(it) })) })
            put("content", event.content)
            put("id", event.id)
            put("sig", event.sig)
        }.toString()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray(Charsets.UTF_8))
    }

    /** A link a person can send: the card rides after `#`, so no server sees it. */
    fun link(base: String, event: NostrEvent): String = base.substringBefore('#') + "#" + encode(event)

    private val HEX64 = Regex("^[0-9a-f]{64}$")
}
