package dev.forgesworn.kithmoot.storage

import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.CardBox
import dev.forgesworn.kithmoot.protocol.CardResult
import dev.forgesworn.kithmoot.protocol.ContactCard
import dev.forgesworn.kithmoot.protocol.ContactCards
import dev.forgesworn.kithmoot.protocol.LinkCard
import dev.forgesworn.kithmoot.protocol.LinkCards
import dev.forgesworn.kithmoot.protocol.LinkVerdict
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI

/**
 * The contact book: people this phone holds a contact card for.
 *
 * A contact card (`nostr-contact-card`, the profile's draft) is one link or
 * QR that makes a stranger a contact, names their box and starts a bond.
 * Reading one needs nothing from the network and yields the person's key
 * and name, their public relays, their rendezvous key and a fresh ephemeral,
 * and each box they endorse with the Link address card inside it verified.
 * What this book keeps is exactly that, per person, on this phone, never
 * published: a card is a capability to reach a box and to derive keys with
 * a person, and it is handed to the person it is for.
 *
 * Two things the draft asks of a reader live here and nowhere else. The node
 * id a person endorsed is pinned per box, so a later fresh address from the
 * box is accepted only under that id. And the highest Link card serial
 * accepted per node is kept, because that is what stops an old card of the
 * same node replaying (Link SPEC §2.3 rule 8).
 *
 * A participant whose key matches a card is shown as carrying one. Link
 * relay hints do not establish ownership of a Nostr message relay. Mirrors
 * `app/src/contact-store.ts` in the reference implementation.
 *
 * One vault, one JSON document, the same lock for every read and write, as
 * the saved rooms and the account are kept.
 */
class ContactBook(private val storage: RoomStorage) {

    /** One box a contact endorsed, as this phone holds it. */
    data class Box(
        val p: String,
        val claim: String,
        /** The Link node id the person endorsed by signing the card; pinned. */
        val nodeId: String,
        /** The highest Link card serial accepted for this node. */
        val highestSerial: Long,
        val relays: List<String>,
        val onions: List<String>,
        val carriers: List<String>?,
        /** When the Link card this phone last accepted for the box expires. */
        val linkExpiresAt: Long,
        /** `card`: dialled on the person's endorsement; `refreshed`: on a fresh Link card from the box. */
        val source: String,
        val refreshedAt: Long? = null,
        /** Verified bytes belonging to highestSerial, used for identical reannouncements. */
        val card: String? = null,
        /** Replay protection and explicit consent; never an offline trust grant. */
        val discovery: JsonObject? = null,
    )

    data class Contact(
        val p: String,
        val name: String?,
        val rz: String,
        val eph: String,
        val relays: List<String>,
        val boxes: List<Box>,
        val attest: String?,
        /** The bond handshake, verbatim, for the ceremony that makes kith. */
        val bond: JsonObject?,
        val issued: Long,
        val expires: Long,
        /** Unix seconds this phone read the card. */
        val readAt: Long,
    )

    sealed class Added {
        class Ok(val contact: Contact, val replaced: Boolean) : Added()
        class Refused(val step: Int, val reason: String, val words: String) : Added()
    }

    /** Every contact, most recently read first. */
    @Synchronized fun list(): List<Contact> = guarded { document().contacts.sortedByDescending { it.readAt } }

    @Synchronized fun get(p: String): Contact? = guarded {
        val key = p.lowercase()
        if (!HEX64.matches(key)) null else document().contacts.firstOrNull { it.p == key }
    }

    /**
     * Read a card (a link, or the card itself) and keep it. A second card
     * from the same person replaces the first: a new card is how a person
     * endorses a new box, a new rendezvous index or a new name. A box already
     * held keeps the highest serial this phone accepted for its node when the
     * node id is unchanged.
     */
    @Synchronized fun add(text: String, now: Long): Added = guarded {
        val r = ContactCards.read(text.trim(), now)
        if (r is CardResult.Refused) return@guarded Added.Refused(r.step, r.reason, STEP_WORDS.getValue(r.step))
        val ok = r as CardResult.Ok
        val card: ContactCard = ok.card
        val doc = document()
        val previous = doc.contacts.firstOrNull { it.p == card.p }
        if (previous == null && doc.contacts.size >= MAX_CONTACTS) {
            return@guarded Added.Refused(2, "contact book is full", "This phone keeps at most $MAX_CONTACTS contacts. Forget one to add another.")
        }
        val boxes = ok.boxes.map { (box, link) -> boxFrom(box, link, previous?.boxes?.firstOrNull { it.p == box.p }) }
        val contact = Contact(
            p = card.p, name = card.name, rz = card.rz, eph = card.eph, relays = card.relays, boxes = boxes,
            attest = card.attest,
            bond = card.bond?.let { bondJson(it) },
            issued = card.issued, expires = card.expires, readAt = now,
        )
        write(doc.copy(contacts = doc.contacts.filter { it.p != card.p } + contact))
        Added.Ok(contact, previous != null)
    }

    @Synchronized fun forget(p: String): Unit = guarded {
        val key = p.lowercase()
        if (!HEX64.matches(key)) return@guarded
        val doc = document()
        if (doc.contacts.none { it.p == key }) return@guarded
        write(doc.copy(contacts = doc.contacts.filter { it.p != key }))
    }

    /** Apply replay state only to the same still-held card. Forgetting and
     * replacement share this lock and vault, so late callbacks cannot recreate it. */
    @Synchronized fun saveDiscovery(contactP: String, boxP: String, revision: String, state: JsonObject): Boolean = guarded {
        val doc = document()
        val contact = doc.contacts.firstOrNull { it.p == contactP } ?: return@guarded false
        val box = contact.boxes.firstOrNull { it.p == boxP } ?: return@guarded false
        if (discoveryRevision(contact, box) != revision) return@guarded false
        val updated = contact.copy(boxes = contact.boxes.map { if (it.p == boxP) it.copy(discovery = state) else it })
        write(doc.copy(contacts = doc.contacts.map { if (it.p == contactP) updated else it }))
        true
    }

    /**
     * A fresh Link card from one of a contact's boxes. Accepted only under
     * the node id the person endorsed and only above the highest serial this
     * phone has accepted for it; then the box's relays move to the fresh card.
     */
    @Synchronized fun refreshBox(contactP: String, boxP: String, freshLinkCard: ByteArray, now: Long): Result<Box> = guarded {
        val doc = document()
        val contact = doc.contacts.firstOrNull { it.p == contactP.lowercase() } ?: return@guarded Result.failure(IllegalArgumentException("no such contact"))
        val i = contact.boxes.indexOfFirst { it.p == boxP.lowercase() }
        if (i < 0) return@guarded Result.failure(IllegalArgumentException("this contact endorses no such box"))
        val held = contact.boxes[i]
        val v = LinkCards.refreshBox(held.nodeId, freshLinkCard, now, held.highestSerial)
        if (v is LinkVerdict.Refused) return@guarded Result.failure(IllegalArgumentException(v.reason))
        val link = (v as LinkVerdict.Ok).card
        val box = held.copy(
            highestSerial = maxOf(held.highestSerial, link.serial),
            relays = link.relays, onions = link.onions, linkExpiresAt = link.expiresAt,
            source = "refreshed", refreshedAt = now,
            card = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(freshLinkCard),
        )
        val updated = contact.copy(boxes = contact.boxes.toMutableList().also { it[i] = box })
        write(doc.copy(contacts = doc.contacts.map { if (it.p == contact.p) updated else it }))
        Result.success(box)
    }

    /**
     * Link relay hints locate a box's transport session; they do not prove
     * ownership of a Nostr message relay. Keep automatic attribution closed
     * until the signed box status, claim binding and endpoint are verified.
     * Existing stored cards must not grant sheltered status either.
     * Explicit circle-box marks in relay settings are independent.
     */
    fun circleRelays(): Set<String> = emptySet()

    /** No message-relay owner is established by a Link address card alone. */
    fun boxOwners(): Map<String, Contact> = emptyMap()

    /**
     * This phone's rendezvous secret, for the cards it hands out: a fresh
     * 32-byte secret made here and kept here, never the identity key. The
     * profile wants it as a child of a root the signer holds; this phone has
     * no root, so its rendezvous key is its own and rotates by being
     * replaced.
     */
    @Synchronized fun rendezvousSecret(): ByteArray = guarded {
        val doc = document()
        doc.rendezvous?.let { return@guarded it.hexToBytes() }
        val fresh = Entropy.bytes(32)
        write(doc.copy(rendezvous = fresh.toHex()))
        fresh
    }

    /** Rotate: forget the rendezvous secret, so the next card carries a new one. */
    @Synchronized fun rotateRendezvous(): Unit = guarded { write(document().copy(rendezvous = null)) }

    // --- storage -------------------------------------------------------------

    private data class Document(val contacts: List<Contact>, val rendezvous: String?)

    private fun document(): Document {
        val bytes = storage.read() ?: return Document(emptyList(), null)
        val root = Json.parseToJsonElement(bytes.decodeToString()) as? JsonObject ?: return Document(emptyList(), null)
        // A hand-edited or half-written entry is dropped rather than trusted:
        // this decides which relays are shown as sheltered.
        val contacts = (root["contacts"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::contactFrom) } ?: emptyList()
        val rz = str(root["rendezvous"])?.takeIf { HEX64.matches(it) }
        return Document(contacts.distinctBy { it.p }, rz)
    }

    private fun write(doc: Document) {
        val json = buildJsonObject {
            put("v", 1)
            put("contacts", buildJsonArray { for (c in doc.contacts) add(contactJson(c)) })
            if (doc.rendezvous != null) put("rendezvous", doc.rendezvous)
        }
        storage.write(json.toString().encodeToByteArray())
    }

    private fun contactJson(c: Contact): JsonObject = buildJsonObject {
        put("p", c.p)
        if (c.name != null) put("name", c.name)
        put("rz", c.rz)
        put("eph", c.eph)
        put("relays", strings(c.relays))
        put("boxes", buildJsonArray {
            for (b in c.boxes) add(buildJsonObject {
                put("p", b.p); put("claim", b.claim); put("nodeId", b.nodeId); put("highestSerial", b.highestSerial)
                put("relays", strings(b.relays)); put("onions", strings(b.onions))
                if (b.carriers != null) put("carriers", strings(b.carriers))
                put("linkExpiresAt", b.linkExpiresAt); put("source", b.source)
                if (b.refreshedAt != null) put("refreshedAt", b.refreshedAt)
                if (b.card != null) put("card", b.card)
                if (b.discovery != null) put("discovery", b.discovery)
            })
        })
        if (c.attest != null) put("attest", c.attest)
        if (c.bond != null) put("bond", c.bond)
        put("issued", c.issued); put("expires", c.expires); put("readAt", c.readAt)
    }

    private fun contactFrom(o: JsonObject): Contact? {
        val p = str(o["p"])?.takeIf { HEX64.matches(it) } ?: return null
        val rz = str(o["rz"])?.takeIf { HEX64.matches(it) } ?: return null
        val eph = str(o["eph"]) ?: return null
        val relays = stringList(o["relays"]) ?: return null
        val boxes = (o["boxes"] as? JsonArray)?.map { (it as? JsonObject)?.let(::boxFrom) ?: return null } ?: return null
        val issued = num(o["issued"]) ?: return null
        val expires = num(o["expires"]) ?: return null
        val readAt = num(o["readAt"]) ?: return null
        return Contact(p, str(o["name"]), rz, eph, relays, boxes, str(o["attest"]), o["bond"] as? JsonObject, issued, expires, readAt)
    }

    private fun boxFrom(o: JsonObject): Box? {
        val p = str(o["p"])?.takeIf { HEX64.matches(it) } ?: return null
        val nodeId = str(o["nodeId"])?.takeIf { HEX64.matches(it) } ?: return null
        val claim = str(o["claim"]) ?: return null
        val serial = num(o["highestSerial"]) ?: return null
        val relays = stringList(o["relays"]) ?: return null
        val onions = stringList(o["onions"]) ?: return null
        val expires = num(o["linkExpiresAt"]) ?: return null
        val source = str(o["source"])?.takeIf { it == "card" || it == "refreshed" } ?: return null
        return Box(p, claim, nodeId, serial, relays, onions, stringList(o["carriers"]), expires, source, num(o["refreshedAt"]), str(o["card"]), o["discovery"] as? JsonObject)
    }

    private fun boxFrom(box: CardBox, link: LinkCard, previous: Box?): Box {
        // The same node under a new card from the person keeps the serial this
        // phone reached; a different node id is a new pin and starts afresh.
        val samePin = previous?.nodeId == link.nodeId
        return Box(
            p = box.p, claim = box.claim, nodeId = link.nodeId,
            highestSerial = if (samePin) maxOf(previous!!.highestSerial, link.serial) else link.serial,
            relays = link.relays, onions = link.onions, carriers = box.carriers,
            linkExpiresAt = link.expiresAt, source = "card",
            card = if (samePin && previous!!.highestSerial >= link.serial) previous.card else box.card,
            discovery = previous?.discovery?.let { JsonObject(it + ("enabled" to JsonPrimitive(false))) },
        )
    }

    private fun bondJson(b: dev.forgesworn.kithmoot.protocol.BondHandshake): JsonObject =
        Json.parseToJsonElement(ContactCards.handshakeBytes(b).decodeToString()) as JsonObject

    private fun strings(l: List<String>): JsonArray = JsonArray(l.map { JsonPrimitive(it) })
    private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun num(e: JsonElement?): Long? = (e as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()
    private fun stringList(e: JsonElement?): List<String>? = (e as? JsonArray)?.map { str(it) ?: return null }

    private inline fun <T> guarded(block: () -> T): T = try { block() } catch (e: Exception) {
        if (e is RoomStorageException) throw e
        throw RoomStorageException(e)
    }

    companion object {
        fun discoveryRevision(c: Contact, b: Box): String =
            listOf(c.issued, c.expires, c.readAt, b.claim, b.nodeId, c.rz, c.eph).joinToString("|")

        /** How many contacts a phone keeps. Generous, because forgetting one
         *  silently turns a known box back into a public relay. */
        const val MAX_CONTACTS = 500
        private val HEX64 = Regex("^[0-9a-f]{64}$")

        /** The sentence a person is shown for each step the draft names. */
        val STEP_WORDS: Map<Int, String> = mapOf(
            1 to "This is not a contact card: it does not decode as one.",
            2 to "This card has a field in the wrong shape and cannot be trusted.",
            3 to "This card has expired, or its dates do not make sense.",
            4 to "This card’s signature does not verify under the key it names.",
            5 to "This card endorses a box whose address card does not verify.",
        )

        /** The same normalisation the lane check applies, so a card's URL and a room relay compare equal. */
        fun normalise(url: String): String? = runCatching {
            dev.forgesworn.kithmoot.protocol.canonicalRelayUrl(url.trim())
        }.getOrNull()

    }
}
