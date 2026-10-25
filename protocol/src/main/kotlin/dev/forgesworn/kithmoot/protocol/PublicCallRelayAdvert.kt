package dev.forgesworn.kithmoot.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Public call relays: Spry's public-mode adverts (Nostr kind 31478).
 *
 * [PublicCallRelayAdvert.verify] and [PublicRelayAdvertBook] are a port of
 * spry-turn `crates/spry-link/src/advert.rs` (client rules 2 to 4 and the
 * replay watermark of `docs/public-mode.md`), checked against spry-turn's
 * `public-advert-v1.json` fixture so the Rust, TypeScript and Kotlin readers
 * agree. Nothing here opens a socket or resolves a name.
 *
 * A signature names the key that made the statement. It says nothing about
 * the operator's identity, honesty, uptime, logging or jurisdiction, and the
 * region and note are the operator's own claims.
 */

/** The refusal names spry-link uses, so the fixture's verdicts compare directly. */
enum class PublicRelayRefusal { Key, Size, Encoding, Authentication, Expired, Destination, Overload, Replay }

/** Carries only the refusal's name, never anything the operator sent. */
class PublicRelayRefused(val reason: PublicRelayRefusal) : Exception("public call relay advert refused: $reason")

private fun refuse(reason: PublicRelayRefusal): Nothing = throw PublicRelayRefused(reason)

/** What an open advert offers. */
data class PublicRelayOffer(val credentials: String, val urls: List<String>, val note: String? = null)

enum class PublicRelayStatus { Open, Withdrawn }

/** A verified advert. */
data class PublicRelayAdvert(
    val publicKey: String,
    /** The id of the event that carried it. */
    val eventId: String,
    val createdAt: Long,
    val expiresAt: Long,
    /** The operator's own claim, never verified. */
    val region: String?,
    val status: PublicRelayStatus,
    /** Present exactly when [status] is [PublicRelayStatus.Open]. */
    val offer: PublicRelayOffer?,
    /** The ASCII-lowercase hostname every URL in the offer shares, when open. */
    val host: String?,
)

/** A watermark carried between runs: the newest `created_at` seen for a
 *  transport key and the id of the event that set it. */
data class PublicRelayWatermark(val key: String, val createdAt: Long, val id: String)

object PublicCallRelayAdvert {
    const val KIND = 31478
    const val IDENTIFIER = "spry-turn"
    const val CREDENTIAL_PATH = "/v1/public/credentials"
    /** Largest serialised advert a client accepts. */
    const val MAX_ADVERT = 4096
    /** Longest permitted gap between `created_at` and `expiration`, in seconds. */
    const val MAX_LIFETIME = 2L * 60 * 60
    /** Furthest a `created_at` may sit ahead of the reader's clock, in seconds. */
    const val MAX_FUTURE = 10L * 60
    const val MAX_NOTE = 140
    const val MAX_URLS = 4
    /** TURN ports a client will contact. Omitted means the scheme's default. */
    val TURN_PORTS: List<String> = listOf("3478", "5349", "443")
    /** Hard ceiling on transport keys one book tracks. */
    const val MAX_TRACKED = 1024

    private const val MAX_URL = 256
    private const val MAX_EXPIRATION_DIGITS = 16
    /** spry-link's `MAX_EVENT`, which bounds `content` in its shape check. */
    private const val MAX_EVENT = 24_576
    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
    private const val SCHEME = "https://"

    private val HEX64 = Regex("[0-9a-f]{64}")
    private val HEX128 = Regex("[0-9a-f]{128}")
    private val DIGITS = Regex("0|[1-9][0-9]*")
    private val LABEL = Regex("[a-z0-9-]{1,63}")
    private val HEX_NUMBER = Regex("0x[0-9a-f]*")
    private val REGION = Regex("[A-Z]{2}")

    /** Spry's own refusals for the credential host (spry-link `credential_host`). */
    private val SPRY_SUFFIXES = setOf("local", "localhost", "internal", "invalid", "test")
    /** More names that cannot be public. Spry leaves "resolves to a private
     *  address" to the client; the client also checks the addresses it gets,
     *  but these are refused before anything is looked up. */
    private val DENIED_SUFFIXES = SPRY_SUFFIXES + setOf("arpa", "lan", "home", "localdomain", "onion", "alt", "example")

    fun regionValid(region: String): Boolean = REGION.matches(region)

    fun noteValid(note: String): Boolean =
        utf8Length(note) <= MAX_NOTE && note.codePoints().noneMatch { Character.getType(it) == Character.CONTROL.toInt() }

    // ASCII only, so U+212A (Kelvin) and U+0130 never fold to a letter.
    private fun asciiFold(value: String): String = buildString(value.length) {
        for (c in value) append(if (c in 'A'..'Z') c + 32 else c)
    }

    private fun utf8Length(value: String): Int = value.toByteArray(Charsets.UTF_8).size

    private fun hasLoneSurrogate(value: String): Boolean {
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c.isHighSurrogate() && i + 1 < value.length && value[i + 1].isLowSurrogate()) i++
            else if (c.isSurrogate()) return true
            i++
        }
        return false
    }

    /** Whether the last label is an IPv4 literal to the URL parser: all digits, or a hex number. */
    private fun numericSuffix(suffix: String): Boolean = suffix.all { it in '0'..'9' } || HEX_NUMBER.matches(suffix)

    /**
     * DNS labels in canonical lowercase form, at least two. Any label starting
     * `xn--` is refused: Java's `IDN` (IDNA2003) disagrees with the UTS 46
     * parsing of the `url` crate and browsers in both directions, so no
     * Android check of a punycode name could be trusted to match. A deliberate
     * rule stricter than Rust's, until the three readers share one.
     */
    private fun labelsValid(labels: List<String>): Boolean =
        labels.size >= 2 && labels.all { LABEL.matches(it) && !it.startsWith("xn--", ignoreCase = true) }

    /**
     * Hostnames the app must not contact even though the advert verified.
     * The web design's connect-time rules: DNS labels only, at least two, no
     * IP-literal look-alike, and none of the suffixes that name private or
     * non-public places. Applied before a relay is listed and before any
     * login request; the resolved addresses are checked separately.
     */
    fun hostAllowed(host: String): Boolean {
        val labels = asciiFold(host).split('.')
        if (!labelsValid(labels)) return false
        val suffix = labels.last()
        return suffix !in DENIED_SUFFIXES && !numericSuffix(suffix)
    }

    /** Static checks only; spry-link's `credential_host`. Only the canonical
     *  spelling is accepted, so no port (not even `:443`), userinfo, query,
     *  fragment, other path, uppercase, trailing dot or IP literal. */
    private fun credentialHost(endpoint: String): String {
        if (utf8Length(endpoint) > MAX_URL) refuse(PublicRelayRefusal.Size)
        if (!endpoint.startsWith(SCHEME) || !endpoint.endsWith(CREDENTIAL_PATH) ||
            endpoint.length <= SCHEME.length + CREDENTIAL_PATH.length) refuse(PublicRelayRefusal.Destination)
        val host = endpoint.substring(SCHEME.length, endpoint.length - CREDENTIAL_PATH.length)
        val labels = host.split('.')
        if (!labelsValid(labels) || numericSuffix(labels.last()) || labels.last() in SPRY_SUFFIXES) refuse(PublicRelayRefusal.Destination)
        return host
    }

    /** spry-link's `turn_host`, parsed by hand, printable ASCII only. */
    private fun turnHost(uri: String): String {
        if (utf8Length(uri) > MAX_URL) refuse(PublicRelayRefusal.Size)
        if (uri.any { it.code !in 0x21..0x7e }) refuse(PublicRelayRefusal.Destination)
        val secure: Boolean
        val rest: String
        when {
            uri.startsWith("turns:") -> { secure = true; rest = uri.substring(6) }
            uri.startsWith("turn:") -> { secure = false; rest = uri.substring(5) }
            else -> refuse(PublicRelayRefusal.Destination)
        }
        val question = rest.indexOf('?')
        val address = if (question < 0) rest else rest.substring(0, question)
        val query = if (question < 0) null else rest.substring(question + 1)
        if (!(query == null || query == "transport=tcp" || (query == "transport=udp" && !secure))) refuse(PublicRelayRefusal.Destination)
        val colon = address.lastIndexOf(':')
        // Exact port spellings only, so no leading zeros or signs.
        if (colon >= 0 && address.substring(colon + 1) !in TURN_PORTS) refuse(PublicRelayRefusal.Destination)
        val host = if (colon < 0) address else address.substring(0, colon)
        if (host.split('.').any { it.startsWith("xn--", ignoreCase = true) }) refuse(PublicRelayRefusal.Destination)
        return host
    }

    /** Rule 4 of `docs/public-mode.md`, plus the note and size bounds. Returns
     *  the hostname the endpoint and every TURN URL share. Throws [PublicRelayRefused]. */
    fun validateOffer(offer: PublicRelayOffer): String {
        val host = credentialHost(offer.credentials)
        if (offer.urls.size !in 1..MAX_URLS) refuse(PublicRelayRefusal.Size)
        for (uri in offer.urls) if (asciiFold(turnHost(uri)) != asciiFold(host)) refuse(PublicRelayRefusal.Destination)
        if (offer.note?.let(::noteValid) == false) refuse(PublicRelayRefusal.Size)
        return host
    }

    /** The keys of the top-level JSON object in `text`, in order, duplicates
     *  included: serde refuses a repeated field where a map keeps the last.
     *  Only call this on text that already parsed as JSON. */
    internal fun topLevelKeys(text: String): List<String> {
        val keys = ArrayList<String>()
        var depth = 0
        var expectKey = false
        var index = 0
        while (index < text.length) {
            when (val char = text[index]) {
                '"' -> {
                    var end = index + 1
                    while (end < text.length && text[end] != '"') end += if (text[end] == '\\') 2 else 1
                    if (depth == 1 && expectKey) {
                        keys.add((Json.parseToJsonElement(text.substring(index, end + 1)) as JsonPrimitive).content)
                        expectKey = false
                    }
                    index = end
                }
                '{', '[' -> { depth++; if (depth == 1 && char == '{') expectKey = true }
                '}', ']' -> depth--
                ',' -> if (depth == 1) expectKey = true
            }
            index++
        }
        return keys
    }

    private fun isString(element: JsonElement?): Boolean = element is JsonPrimitive && element.isString

    private fun parseOffer(content: String): PublicRelayOffer {
        val value = try { Json.parseToJsonElement(content) } catch (_: Exception) { refuse(PublicRelayRefusal.Encoding) }
        if (value !is JsonObject) refuse(PublicRelayRefusal.Encoding)
        val keys = topLevelKeys(content)
        if (keys.toSet().size != keys.size || keys.any { it !in setOf("credentials", "urls", "note") }) refuse(PublicRelayRefusal.Encoding)
        val credentials = value["credentials"]
        val urls = value["urls"]
        val note = value["note"]
        if (!isString(credentials) || urls !is JsonArray || urls.any { !isString(it) } ||
            !(note == null || note is JsonNull || isString(note))) refuse(PublicRelayRefusal.Encoding)
        val offer = PublicRelayOffer(
            credentials = (credentials as JsonPrimitive).content,
            urls = urls.map { (it as JsonPrimitive).content },
            // A null note is no note, as serde reads an Option.
            note = (note as? JsonPrimitive)?.takeIf { it.isString }?.content,
        )
        if (hasLoneSurrogate(offer.credentials) || offer.urls.any(::hasLoneSurrogate) || offer.note?.let(::hasLoneSurrogate) == true) {
            refuse(PublicRelayRefusal.Encoding)
        }
        return offer
    }

    /** spry-link's `Event::tag`: exactly one tag of that name, of two elements. */
    private fun tag(tags: List<List<String>>, name: String, missing: PublicRelayRefusal = PublicRelayRefusal.Destination): String {
        val matches = tags.filter { it.firstOrNull() == name }
        if (matches.size != 1 || matches[0].size != 2) refuse(missing)
        return matches[0][1]
    }

    /** The seven signed fields, strictly typed, or [PublicRelayRefusal.Encoding]:
     *  a value that cannot be a Nostr event at all. Extra fields are ignored. */
    private fun eventFrom(value: JsonElement): NostrEvent {
        if (value !is JsonObject) refuse(PublicRelayRefusal.Encoding)
        fun text(name: String): String {
            val primitive = value[name] as? JsonPrimitive
            if (primitive == null || !primitive.isString) refuse(PublicRelayRefusal.Encoding)
            return primitive.content
        }
        fun number(name: String, max: Long): Long {
            val primitive = value[name] as? JsonPrimitive
            if (primitive == null || primitive.isString || !DIGITS.matches(primitive.content)) refuse(PublicRelayRefusal.Encoding)
            val parsed = primitive.content.toLongOrNull() ?: refuse(PublicRelayRefusal.Encoding)
            if (parsed > max) refuse(PublicRelayRefusal.Encoding)
            return parsed
        }
        val tagsValue = value["tags"] as? JsonArray ?: refuse(PublicRelayRefusal.Encoding)
        val tags = tagsValue.map { tag ->
            val items = tag as? JsonArray ?: refuse(PublicRelayRefusal.Encoding)
            items.map { item ->
                val primitive = item as? JsonPrimitive
                if (primitive == null || !primitive.isString) refuse(PublicRelayRefusal.Encoding)
                primitive.content
            }
        }
        return NostrEvent(
            kind = number("kind", 65_535).toInt(),
            createdAt = number("created_at", MAX_SAFE_INTEGER),
            tags = tags,
            content = text("content"),
            pubkey = text("pubkey"),
            id = text("id"),
            sig = text("sig"),
        )
    }

    /** Bytes of the seven-field event as compact JSON, as serde_json and
     *  `JSON.stringify` write it (only quote, backslash and control characters
     *  are escaped). Field order does not change the length. */
    private fun serialisedLength(event: NostrEvent): Int {
        fun string(value: String): Int {
            var n = 2
            var i = 0
            while (i < value.length) {
                val c = value[i]
                n += when {
                    c == '"' || c == '\\' || c == '\b' || c == '\u000c' || c == '\n' || c == '\r' || c == '\t' -> 2
                    c < ' ' -> 6
                    c.code < 0x80 -> 1
                    c.code < 0x800 -> 2
                    c.isHighSurrogate() && i + 1 < value.length && value[i + 1].isLowSurrogate() -> { i++; 4 }
                    else -> 3
                }
                i++
            }
            return n
        }
        // {"id":,"pubkey":,"created_at":,"kind":,"tags":,"content":,"sig":}
        var total = 65 + string(event.id) + string(event.pubkey) + event.createdAt.toString().length +
            event.kind.toString().length + string(event.content) + string(event.sig)
        total += 2 + maxOf(0, event.tags.size - 1)
        for (tag in event.tags) total += 2 + maxOf(0, tag.size - 1) + tag.sumOf(::string)
        return total
    }

    /** Client rules 2 and 4 for one event. Rule 3 needs history: use [PublicRelayAdvertBook].
     *  Throws [PublicRelayRefused]. */
    fun verify(value: JsonElement, now: Long): PublicRelayAdvert = verify(eventFrom(value), now)

    fun verify(event: NostrEvent, now: Long): PublicRelayAdvert {
        // The UTF-8 length of the seven-field JSON, checked before anything else.
        if (event.content.length > MAX_ADVERT || serialisedLength(event) > MAX_ADVERT) refuse(PublicRelayRefusal.Size)
        if (utf8Length(event.content) > MAX_EVENT - 1024 || event.tags.size > 8 ||
            event.tags.any { it.isEmpty() || it.size > 3 || it.any { item -> utf8Length(item) > 256 } }) refuse(PublicRelayRefusal.Size)
        // serde refuses a lone surrogate escape when it parses the event.
        if (hasLoneSurrogate(event.content) || event.tags.any { tag -> tag.any(::hasLoneSurrogate) }) refuse(PublicRelayRefusal.Encoding)
        if (event.kind !in 0..65_535 || event.createdAt !in 0..MAX_SAFE_INTEGER ||
            !HEX64.matches(event.id) || !HEX64.matches(event.pubkey) || !HEX128.matches(event.sig)) refuse(PublicRelayRefusal.Encoding)
        if (!Events.verify(event)) refuse(PublicRelayRefusal.Authentication)
        val tags = event.tags
        if (event.kind != KIND || tag(tags, "d") != IDENTIFIER || tag(tags, "v") != "1" || tag(tags, "auth") != "public") {
            refuse(PublicRelayRefusal.Destination)
        }
        val expiration = tag(tags, "expiration", PublicRelayRefusal.Expired)
        // Digits only: no sign, space or other character.
        if (expiration.length !in 1..MAX_EXPIRATION_DIGITS || !expiration.all { it in '0'..'9' }) refuse(PublicRelayRefusal.Expired)
        val expiresAt = expiration.toLong()
        if (event.createdAt > now + MAX_FUTURE || expiresAt <= now || expiresAt > event.createdAt + MAX_LIFETIME) {
            refuse(PublicRelayRefusal.Expired)
        }
        var region: String? = null
        if (tags.any { it.firstOrNull() == "g" }) {
            region = tag(tags, "g")
            if (!regionValid(region)) refuse(PublicRelayRefusal.Destination)
        }
        return when (tag(tags, "status")) {
            "open" -> {
                val offer = parseOffer(event.content)
                val host = validateOffer(offer)
                PublicRelayAdvert(event.pubkey, event.id, event.createdAt, expiresAt, region, PublicRelayStatus.Open, offer, asciiFold(host))
            }
            "withdrawn" -> {
                val empty = try { Json.parseToJsonElement(event.content) } catch (_: Exception) { refuse(PublicRelayRefusal.Encoding) }
                if (empty !is JsonObject || empty.isNotEmpty()) refuse(PublicRelayRefusal.Encoding)
                PublicRelayAdvert(event.pubkey, event.id, event.createdAt, expiresAt, region, PublicRelayStatus.Withdrawn, null, null)
            }
            else -> refuse(PublicRelayRefusal.Destination)
        }
    }
}

/**
 * The replay watermark of rule 3: per transport key, the newest `created_at`
 * seen. Anything not newer is refused, so a newer withdrawal cannot be undone
 * by an older open advert. Bounded and swept. One book is shared by every
 * relay read, so a relay cannot replay what another has superseded.
 *
 * Watermarks may be restored from an earlier run, without their adverts
 * (adverts are kept in memory only). The one event that set a restored
 * watermark may then be admitted once more, so a restart does not hide a
 * chosen relay until its operator next republishes. It is the identical
 * signed statement (the id binds `created_at`, status and offer), so
 * admitting it again cannot roll anything back; any other event at or below
 * the watermark is still a replay.
 */
class PublicRelayAdvertBook(capacity: Int = PublicCallRelayAdvert.MAX_TRACKED, restored: List<PublicRelayWatermark> = emptyList()) {
    private class Entry(val createdAt: Long, val id: String, val advert: PublicRelayAdvert?, val restored: Boolean)

    private val entries = LinkedHashMap<String, Entry>()
    val capacity: Int = capacity.coerceIn(0, PublicCallRelayAdvert.MAX_TRACKED)

    init {
        val hex64 = Regex("[0-9a-f]{64}")
        for (mark in restored) {
            if (entries.size >= this.capacity) break
            if (!hex64.matches(mark.key) || !hex64.matches(mark.id) || mark.createdAt < 0) continue
            val old = entries[mark.key]
            if (old == null || mark.createdAt > old.createdAt) entries[mark.key] = Entry(mark.createdAt, mark.id, null, true)
        }
    }

    // Once a watermark is two hours old, every older event has expired under
    // rule 2, so forgetting it cannot admit a replay.
    private fun sweep(now: Long) {
        entries.entries.removeAll { it.value.createdAt + PublicCallRelayAdvert.MAX_LIFETIME <= now }
    }

    /** Verify first, then advance the watermark. Throws [PublicRelayRefused]. */
    @Synchronized
    fun accept(event: JsonElement, now: Long): PublicRelayAdvert {
        sweep(now)
        return admit(PublicCallRelayAdvert.verify(event, now), now)
    }

    @Synchronized
    fun accept(event: NostrEvent, now: Long): PublicRelayAdvert {
        sweep(now)
        return admit(PublicCallRelayAdvert.verify(event, now), now)
    }

    private fun admit(advert: PublicRelayAdvert, now: Long): PublicRelayAdvert {
        val entry = entries[advert.publicKey]
        if (entry != null) {
            val repeat = entry.restored && advert.createdAt == entry.createdAt && advert.eventId == entry.id
            if (advert.createdAt <= entry.createdAt && !repeat) refuse(PublicRelayRefusal.Replay)
        } else if (entries.size >= capacity) refuse(PublicRelayRefusal.Overload)
        entries[advert.publicKey] = Entry(advert.createdAt, advert.eventId, advert.takeIf { it.status == PublicRelayStatus.Open }, false)
        return advert
    }

    @Synchronized
    fun watermark(publicKey: String): Long? = entries[publicKey]?.createdAt

    /** Unexpired open adverts. Ranking and the per-call limits are the caller's. */
    @Synchronized
    fun open(now: Long): List<PublicRelayAdvert> = entries.values.mapNotNull { it.advert }.filter { it.expiresAt > now }

    /** The current open advert from one transport key, if any. */
    @Synchronized
    fun openFrom(publicKey: String, now: Long): PublicRelayAdvert? = entries[publicKey]?.advert?.takeIf { it.expiresAt > now }

    /** Watermarks to carry to the next run: swept, newest first, bounded. */
    @Synchronized
    fun watermarks(now: Long): List<PublicRelayWatermark> {
        sweep(now)
        return entries.map { (key, entry) -> PublicRelayWatermark(key, entry.createdAt, entry.id) }
            .sortedByDescending { it.createdAt }.take(PublicCallRelayAdvert.MAX_TRACKED)
    }
}
