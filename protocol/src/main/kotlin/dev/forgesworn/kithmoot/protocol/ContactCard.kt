package dev.forgesworn.kithmoot.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * A contact card, read by the draft's §3 in order, written from the draft
 * and checked against its known-answer vectors: the second implementation
 * of the reader, kept apart from the first. What comes back is rebuilt from
 * the fields the draft names and nothing else on the wire.
 */
class CardBox(val p: String, val claim: String, val card: String, val carriers: List<String>?)

class BondHandshake(val pubkey: String, val nonce: String, val displayName: String?, val personas: List<Persona>?) {
    class Persona(val pubkey: String, val label: String?)
}

class ContactCard(
    val p: String,
    val rz: String,
    val name: String?,
    val issued: Long,
    val expires: Long,
    val relays: List<String>,
    val boxes: List<CardBox>,
    val eph: String,
    val attest: String?,
    val bond: BondHandshake?,
    val id: String,
    val sig: String,
    /** The signed event the card travels as, for carrying on. */
    val event: NostrEvent,
)

sealed class CardResult {
    class Ok(val card: ContactCard, val boxes: List<Pair<CardBox, LinkCard>>) : CardResult()
    class Refused(val step: Int, val reason: String) : CardResult()
}

object ContactCards {
    /** The reserved addressable kind a card rides in. Never published to a relay. */
    const val KIND: Int = 30641
    const val MAX_CARD_BYTES: Int = 16384
    const val MAX_AGE_SECONDS: Long = 30L * 24 * 3600
    const val MAX_RELAYS: Int = 8
    const val MAX_BOXES: Int = 4
    const val MAX_NAME: Int = 100
    const val MAX_PERSONAS: Int = 16
    private const val MAX_SAFE: Long = (1L shl 53) - 1

    private val HEX64 = Regex("^[0-9a-f]{64}$")
    private val HEX128 = Regex("^[0-9a-f]{128}$")
    private val HEX32 = Regex("^[0-9a-f]{32}$")
    private val B64URL = Regex("^[A-Za-z0-9_-]+$")
    private val CARRIER = Regex("^[A-Za-z0-9._-]{1,32}$")
    private val INVISIBLE = setOf(0x200B, 0x200C, 0x200E, 0x200F, 0x202A, 0x202B, 0x202C, 0x202D, 0x202E,
        0x2060, 0x2061, 0x2062, 0x2063, 0x2064, 0x2066, 0x2067, 0x2068, 0x2069, 0xFEFF, 0x00AD, 0x061C, 0x180E)
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * A name, display name or persona label a person will see, by §1's text
     * rule: 1 to 100 code points, at least one visible, no control, surrogate,
     * unassigned or private-use character, no separator but an ordinary space
     * and none at the ends, no invisible or direction-changing format
     * character, no run of five combining marks; the joiners and tag
     * characters emoji need only beside a pictographic character.
     */
    fun isGoodName(s: String): Boolean {
        val cps = s.codePoints().toArray()
        if (cps.isEmpty() || cps.size > MAX_NAME) return false
        if (s.startsWith(" ") || s.endsWith(" ")) return false
        var visible = false
        var marks = 0
        for (i in cps.indices) {
            val cp = cps[i]
            val type = Character.getType(cp).toByte()
            when (type) {
                Character.CONTROL, Character.SURROGATE, Character.UNASSIGNED, Character.PRIVATE_USE,
                Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> return false
                Character.SPACE_SEPARATOR -> if (cp != 0x20) return false
                Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK -> { marks += 1; if (marks >= 5) return false }
                else -> {}
            }
            if (type != Character.NON_SPACING_MARK && type != Character.ENCLOSING_MARK && type != Character.COMBINING_SPACING_MARK) marks = 0
            if (cp in INVISIBLE) return false
            if (isVisible(type)) visible = true
            if (type == Character.FORMAT) {
                val prev = if (i > 0) cps[i - 1] else -1
                val next = if (i + 1 < cps.size) cps[i + 1] else -1
                if (cp == 0x200D && (Pictographic.contains(prev) || Pictographic.contains(next))) continue
                if (cp in 0xE0020..0xE007F && (maxOf(0, i - 8) until i).any { Pictographic.contains(cps[it]) }) continue
                return false
            }
        }
        return visible
    }

    private fun isVisible(type: Byte): Boolean = when (type) {
        Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER, Character.MODIFIER_LETTER, Character.OTHER_LETTER,
        Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER,
        Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL, Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL,
        Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION,
        Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION -> true
        else -> false
    }

    private fun isUnprintableOrInvisible(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            when (Character.getType(cp).toByte()) {
                Character.CONTROL, Character.SURROGATE, Character.UNASSIGNED, Character.PRIVATE_USE, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> return true
                else -> {}
            }
            if (cp in INVISIBLE) return true
            i += Character.charCount(cp)
        }
        return false
    }

    private fun hasSeparator(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            when (Character.getType(cp).toByte()) {
                Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> return true
                else -> {}
            }
            i += Character.charCount(cp)
        }
        return false
    }

    class HandshakeException(message: String) : IllegalArgumentException(message)

    /** A bond handshake with only the fields the draft names, each checked. */
    fun cleanBond(e: JsonElement?): BondHandshake {
        val h = e as? JsonObject ?: throw HandshakeException("handshake: not an object")
        h["v"]?.let { if (!(it is JsonPrimitive && !it.isString && it.content == "1")) throw HandshakeException("handshake: v must be 1") }
        val pubkey = (h["pubkey"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.lowercase() ?: ""
        val nonce = (h["nonce"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.lowercase() ?: ""
        if (!HEX64.matches(pubkey)) throw HandshakeException("handshake: pubkey must be 64 hex chars")
        if (!HEX32.matches(nonce)) throw HandshakeException("handshake: nonce must be 32 hex chars")
        val displayName = h["displayName"]?.let { text(it)?.takeIf(::isGoodName) ?: throw HandshakeException("handshake: displayName") }
        val personas = h["personas"]?.let { arr ->
            val list = arr as? JsonArray ?: throw HandshakeException("handshake: personas")
            if (list.size > MAX_PERSONAS) throw HandshakeException("handshake: personas")
            list.map { x ->
                val o = x as? JsonObject
                val pk = o?.get("pubkey")?.let(::text)?.lowercase() ?: ""
                if (!HEX64.matches(pk)) throw HandshakeException("handshake: persona pubkey")
                val label = o?.get("label")?.let { text(it)?.takeIf(::isGoodName) ?: throw HandshakeException("handshake: persona label") }
                BondHandshake.Persona(pk, label)
            }
        }
        return BondHandshake(pubkey, nonce, displayName, personas)
    }

    /** The canonical bytes of a bond handshake: fixed key order, no whitespace, absent keys omitted. */
    fun handshakeBytes(b: BondHandshake): ByteArray {
        val o = buildJsonObject {
            put("v", 1)
            put("pubkey", b.pubkey)
            if (b.displayName != null) put("displayName", b.displayName)
            put("nonce", b.nonce)
            if (b.personas != null) put("personas", buildJsonArray {
                for (p in b.personas) add(buildJsonObject { put("pubkey", p.pubkey); if (p.label != null) put("label", p.label) })
            })
        }
        return Json.encodeToString(JsonObject.serializer(), o).toByteArray(Charsets.UTF_8)
    }

    private fun text(e: JsonElement): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** A JSON number that is an integer a reader can hold, else null. */
    private fun safeInteger(e: JsonElement?): Long? {
        val p = e as? JsonPrimitive ?: return null
        if (p.isString) return null
        val d = p.content.toBigDecimalOrNull() ?: return null
        if (d.stripTrailingZeros().scale() > 0) return null
        val v = d.toBigIntegerExact()
        if (v.bitLength() > 53) return null
        val l = v.toLong()
        return if (l > MAX_SAFE || l < -MAX_SAFE) null else l
    }

    private val EXPIRATION = Regex("^(0|[1-9][0-9]{0,15})$")

    /** §3 steps 1 to 5, in order; the result names the step that failed. */
    fun read(encoded: String, now: Long): CardResult {
        val body = encoded.substring(encoded.lastIndexOf('#') + 1)
        if (body.isEmpty() || body.length > MAX_CARD_BYTES) return CardResult.Refused(1, "size")
        val bytes = try { Base64.getUrlDecoder().decode(body.trimEnd('=')) } catch (_: Exception) { return CardResult.Refused(1, "decode") }
        if (bytes.size >= 3 && bytes[0] == 0xef.toByte() && bytes[1] == 0xbb.toByte() && bytes[2] == 0xbf.toByte()) return CardResult.Refused(1, "decode")
        val textBody = Text.strictUtf8(bytes) ?: return CardResult.Refused(1, "decode")
        val root = try { json.parseToJsonElement(textBody) } catch (_: Exception) { return CardResult.Refused(1, "decode") }
        val ev = root as? JsonObject ?: return CardResult.Refused(1, "version")
        val kind = ev["kind"] as? JsonPrimitive
        if (kind == null || kind.isString || kind.content != KIND.toString()) return CardResult.Refused(1, "version")
        val contentText = ev["content"]?.let(::text) ?: return CardResult.Refused(1, "version")
        val c = try { json.parseToJsonElement(contentText) as? JsonObject } catch (_: Exception) { null } ?: return CardResult.Refused(1, if (runCatching { json.parseToJsonElement(contentText) }.isSuccess) "version" else "decode")
        val v = c["v"] as? JsonPrimitive
        if (v == null || v.isString || v.content != "1") return CardResult.Refused(1, "version")

        // Step 2: the event's own shape, then the card's fields.
        val p = (ev["pubkey"]?.let(::text) ?: return CardResult.Refused(2, "p")).lowercase()
        if (!HEX64.matches(p)) return CardResult.Refused(2, "p")
        val id = (ev["id"]?.let(::text) ?: return CardResult.Refused(2, "id")).lowercase()
        if (!HEX64.matches(id)) return CardResult.Refused(2, "id")
        val sig = (ev["sig"]?.let(::text) ?: return CardResult.Refused(2, "sig")).lowercase()
        if (!HEX128.matches(sig)) return CardResult.Refused(2, "sig")
        val tagsJson = ev["tags"] as? JsonArray ?: return CardResult.Refused(2, "tags")
        if (tagsJson.size != 2) return CardResult.Refused(2, "tags")
        val tags = tagsJson.map { t ->
            val arr = t as? JsonArray ?: return CardResult.Refused(2, "tags")
            if (arr.size != 2) return CardResult.Refused(2, "tags")
            arr.map { x -> text(x) ?: return CardResult.Refused(2, "tags") }
        }
        val dTag = tags.firstOrNull { it[0] == "d" }
        val expTag = tags.firstOrNull { it[0] == "expiration" }
        if (dTag == null || expTag == null || dTag[1] != "card") return CardResult.Refused(2, "tags")
        val hex = HashMap<String, String>()
        for (f in listOf("rz", "eph")) {
            val s = c[f]?.let(::text) ?: return CardResult.Refused(2, f)
            val lower = s.lowercase()
            if (!HEX64.matches(lower)) return CardResult.Refused(2, f)
            hex[f] = lower
        }
        val name = c["name"]?.let { text(it)?.takeIf(::isGoodName) ?: return CardResult.Refused(2, "name") }
        val relaysJson = c["relays"] as? JsonArray ?: return CardResult.Refused(2, "relays")
        if (relaysJson.size > MAX_RELAYS) return CardResult.Refused(2, "relays")
        val relays = relaysJson.map { r -> text(r)?.takeIf(LinkCards::isRelayUrl) ?: return CardResult.Refused(2, "relays") }
        val boxesJson = c["boxes"] as? JsonArray ?: return CardResult.Refused(2, "boxes")
        if (boxesJson.size > MAX_BOXES) return CardResult.Refused(2, "boxes")
        val boxes = ArrayList<CardBox>()
        for (bj in boxesJson) {
            val b = bj as? JsonObject ?: return CardResult.Refused(2, "box")
            val bp = b["p"]?.let(::text)?.lowercase() ?: ""
            val claim = b["claim"]?.let(::text)?.lowercase() ?: ""
            if (!HEX64.matches(bp)) return CardResult.Refused(2, "box p")
            if (!HEX64.matches(claim)) return CardResult.Refused(2, "box claim")
            val card = b["card"]?.let(::text)?.takeIf { B64URL.matches(it) } ?: return CardResult.Refused(2, "box card")
            val carriers = b["carriers"]?.let { arr ->
                val list = arr as? JsonArray ?: return CardResult.Refused(2, "box carriers")
                if (list.isEmpty() || list.size > 8) return CardResult.Refused(2, "box carriers")
                list.map { x -> text(x)?.takeIf { CARRIER.matches(it) } ?: return CardResult.Refused(2, "box carriers") }
            }
            boxes.add(CardBox(bp, claim, card, carriers))
        }
        val attest = c["attest"]?.let { a ->
            val s = text(a) ?: return CardResult.Refused(2, "attest")
            if (s.isEmpty() || s.contains(':') || isUnprintableOrInvisible(s) || hasSeparator(s) || s.length > 512) return CardResult.Refused(2, "attest")
            s
        }
        val bond = c["bond"]?.let { try { cleanBond(it) } catch (_: HandshakeException) { return CardResult.Refused(2, "bond") } }

        // Step 3: issued is the event's created_at, expires the expiration tag's value.
        val issued = safeInteger(ev["created_at"]) ?: return CardResult.Refused(3, "times")
        if (!EXPIRATION.matches(expTag[1])) return CardResult.Refused(3, "times")
        val expires = expTag[1].toLongOrNull()?.takeIf { it <= MAX_SAFE } ?: return CardResult.Refused(3, "times")
        if (expires <= now) return CardResult.Refused(3, "expired")
        if (expires <= issued || expires - issued > MAX_AGE_SECONDS) return CardResult.Refused(3, "expiry window")
        if (issued > now + 300) return CardResult.Refused(3, "issued in the future")

        // Step 4: the NIP-01 id over the six fields as carried, and the signature under p.
        val event = NostrEvent(KIND, issued, tags, contentText, text(ev.getValue("pubkey")) ?: return CardResult.Refused(2, "p"), id, sig)
        if (!Events.verify(event)) return CardResult.Refused(4, "signature")
        val card = ContactCard(p, hex.getValue("rz"), name, issued, expires, relays, boxes, hex.getValue("eph"), attest, bond, id, sig, event)
        val verified = ArrayList<Pair<CardBox, LinkCard>>()
        for (b in card.boxes) {
            val linkBytes = try { Base64.getUrlDecoder().decode(b.card) } catch (_: Exception) { return CardResult.Refused(5, "link: decode") }
            when (val lv = LinkCards.verify(linkBytes, now)) {
                is LinkVerdict.Refused -> return CardResult.Refused(5, "link: ${lv.reason}")
                is LinkVerdict.Ok -> verified.add(b to lv.card)
            }
        }
        return CardResult.Ok(card, verified)
    }
}

/**
 * Unicode Extended_Pictographic, as the reference engine (Node 24, Unicode
 * 17.0) has it, for the one place a name rule needs it: a zero-width joiner
 * or a tag character is allowed only beside one of these. Generated from the
 * engine's own regex over every code point; a copy, never an edit.
 */
object Pictographic {
    private val ranges: IntArray = intArrayOf(
        0xa9, 0xa9, 0xae, 0xae, 0x203c, 0x203c, 0x2049, 0x2049, 0x2122, 0x2122, 0x2139, 0x2139,
        0x2194, 0x2199, 0x21a9, 0x21aa, 0x231a, 0x231b, 0x2328, 0x2328, 0x23cf, 0x23cf, 0x23e9, 0x23f3,
        0x23f8, 0x23fa, 0x24c2, 0x24c2, 0x25aa, 0x25ab, 0x25b6, 0x25b6, 0x25c0, 0x25c0, 0x25fb, 0x25fe,
        0x2600, 0x2604, 0x260e, 0x260e, 0x2611, 0x2611, 0x2614, 0x2615, 0x2618, 0x2618, 0x261d, 0x261d,
        0x2620, 0x2620, 0x2622, 0x2623, 0x2626, 0x2626, 0x262a, 0x262a, 0x262e, 0x262f, 0x2638, 0x263a,
        0x2640, 0x2640, 0x2642, 0x2642, 0x2648, 0x2653, 0x265f, 0x2660, 0x2663, 0x2663, 0x2665, 0x2666,
        0x2668, 0x2668, 0x267b, 0x267b, 0x267e, 0x267f, 0x2692, 0x2697, 0x2699, 0x2699, 0x269b, 0x269c,
        0x26a0, 0x26a1, 0x26a7, 0x26a7, 0x26aa, 0x26ab, 0x26b0, 0x26b1, 0x26bd, 0x26be, 0x26c4, 0x26c5,
        0x26c8, 0x26c8, 0x26ce, 0x26cf, 0x26d1, 0x26d1, 0x26d3, 0x26d4, 0x26e9, 0x26ea, 0x26f0, 0x26f5,
        0x26f7, 0x26fa, 0x26fd, 0x26fd, 0x2702, 0x2702, 0x2705, 0x2705, 0x2708, 0x270d, 0x270f, 0x270f,
        0x2712, 0x2712, 0x2714, 0x2714, 0x2716, 0x2716, 0x271d, 0x271d, 0x2721, 0x2721, 0x2728, 0x2728,
        0x2733, 0x2734, 0x2744, 0x2744, 0x2747, 0x2747, 0x274c, 0x274c, 0x274e, 0x274e, 0x2753, 0x2755,
        0x2757, 0x2757, 0x2763, 0x2764, 0x2795, 0x2797, 0x27a1, 0x27a1, 0x27b0, 0x27b0, 0x27bf, 0x27bf,
        0x2934, 0x2935, 0x2b05, 0x2b07, 0x2b1b, 0x2b1c, 0x2b50, 0x2b50, 0x2b55, 0x2b55, 0x3030, 0x3030,
        0x303d, 0x303d, 0x3297, 0x3297, 0x3299, 0x3299, 0x1f004, 0x1f004, 0x1f02c, 0x1f02f, 0x1f094, 0x1f09f,
        0x1f0af, 0x1f0b0, 0x1f0c0, 0x1f0c0, 0x1f0cf, 0x1f0d0, 0x1f0f6, 0x1f0ff, 0x1f170, 0x1f171, 0x1f17e, 0x1f17f,
        0x1f18e, 0x1f18e, 0x1f191, 0x1f19a, 0x1f1ae, 0x1f1e5, 0x1f201, 0x1f20f, 0x1f21a, 0x1f21a, 0x1f22f, 0x1f22f,
        0x1f232, 0x1f23a, 0x1f23c, 0x1f23f, 0x1f249, 0x1f25f, 0x1f266, 0x1f321, 0x1f324, 0x1f393, 0x1f396, 0x1f397,
        0x1f399, 0x1f39b, 0x1f39e, 0x1f3f0, 0x1f3f3, 0x1f3f5, 0x1f3f7, 0x1f3fa, 0x1f400, 0x1f4fd, 0x1f4ff, 0x1f53d,
        0x1f549, 0x1f54e, 0x1f550, 0x1f567, 0x1f56f, 0x1f570, 0x1f573, 0x1f57a, 0x1f587, 0x1f587, 0x1f58a, 0x1f58d,
        0x1f590, 0x1f590, 0x1f595, 0x1f596, 0x1f5a4, 0x1f5a5, 0x1f5a8, 0x1f5a8, 0x1f5b1, 0x1f5b2, 0x1f5bc, 0x1f5bc,
        0x1f5c2, 0x1f5c4, 0x1f5d1, 0x1f5d3, 0x1f5dc, 0x1f5de, 0x1f5e1, 0x1f5e1, 0x1f5e3, 0x1f5e3, 0x1f5e8, 0x1f5e8,
        0x1f5ef, 0x1f5ef, 0x1f5f3, 0x1f5f3, 0x1f5fa, 0x1f64f, 0x1f680, 0x1f6c5, 0x1f6cb, 0x1f6d2, 0x1f6d5, 0x1f6e5,
        0x1f6e9, 0x1f6e9, 0x1f6eb, 0x1f6f0, 0x1f6f3, 0x1f6ff, 0x1f7da, 0x1f7ff, 0x1f80c, 0x1f80f, 0x1f848, 0x1f84f,
        0x1f85a, 0x1f85f, 0x1f888, 0x1f88f, 0x1f8ae, 0x1f8af, 0x1f8bc, 0x1f8bf, 0x1f8c2, 0x1f8cf, 0x1f8d9, 0x1f8ff,
        0x1f90c, 0x1f93a, 0x1f93c, 0x1f945, 0x1f947, 0x1f9ff, 0x1fa58, 0x1fa5f, 0x1fa6e, 0x1faff, 0x1fc00, 0x1fffd,
    )

    fun contains(cp: Int): Boolean {
        if (cp < 0) return false
        var lo = 0
        var hi = ranges.size / 2 - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val start = ranges[mid * 2]
            val end = ranges[mid * 2 + 1]
            if (cp < start) hi = mid - 1 else if (cp > end) lo = mid + 1 else return true
        }
        return false
    }
}
