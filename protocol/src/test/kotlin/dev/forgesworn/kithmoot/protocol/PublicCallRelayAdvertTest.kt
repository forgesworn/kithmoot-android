package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val LIFETIME = PublicCallRelayAdvert.MAX_LIFETIME

class PublicCallRelayAdvertTest {
    private val fixtureBytes = requireNotNull(javaClass.getResourceAsStream("/public-advert-v1.json")).readBytes()
    private val fixture = Json.parseToJsonElement(fixtureBytes.toString(Charsets.UTF_8)).jsonObject

    private fun verdict(run: () -> PublicRelayAdvert): String = try {
        when (run().status) {
            PublicRelayStatus.Open -> "open"
            PublicRelayStatus.Withdrawn -> "withdrawn"
        }
    } catch (refused: PublicRelayRefused) { refused.reason.name }

    // spry-turn docs/fixtures/public-advert-v1.json at 98831a1, byte for byte.
    @Test fun fixtureIsTheSpryTurnFileAndEveryCaseGivesItsVerdict() {
        assertEquals("d91ecbbc8f9bde8d197ca7c318ce2cbe6b5316f505d6117760eb1d5a575b11f9", Digests.sha256(fixtureBytes).toHex())
        assertEquals("spry-public-advert-v1", fixture.getValue("profile").jsonPrimitive.content)
        assertEquals(1, fixture.getValue("schema").jsonPrimitive.long.toInt())
        val now = fixture.getValue("now").jsonPrimitive.long
        val cases = fixture.getValue("cases").jsonArray
        assertTrue(cases.size >= 12)
        var checked = 0
        for (case in cases) {
            val entry = case.jsonObject
            val book = PublicRelayAdvertBook()
            val expected = entry.getValue("expect").jsonArray.map { it.jsonPrimitive.content }
            val events = entry.getValue("events").jsonArray
            assertEquals(expected.size, events.size)
            val got = events.map { event -> verdict { book.accept(event, now) } }
            assertEquals(entry.getValue("name").jsonPrimitive.content, expected, got)
            checked += events.size
        }
        assertTrue(checked >= 14)
    }

    private fun offer() = PublicRelayOffer(
        "https://relay.example/v1/public/credentials",
        listOf("turn:relay.example:3478?transport=udp", "turn:relay.example:3478?transport=tcp", "turns:relay.example:5349?transport=tcp"),
        "Synthetic operator note",
    )
    private fun refused(offer: PublicRelayOffer): Boolean = try { PublicCallRelayAdvert.validateOffer(offer); false } catch (_: PublicRelayRefused) { true }

    // The table from spry-link advert.rs `endpoint_and_turn_rules_refuse_unsafe_destinations`.
    @Test fun destinationRulesMatchSpryLink() {
        assertFalse(refused(offer()))
        for (endpoint in listOf(
            "http://relay.example/v1/public/credentials",
            "https://relay.example:443/v1/public/credentials",
            "https://relay.example:8443/v1/public/credentials",
            "https://user@relay.example/v1/public/credentials",
            "https://relay.example/v1/public/credentials?x=1",
            "https://relay.example/v1/public/credentials#x",
            "https://relay.example/other",
            "https://192.0.2.1/v1/public/credentials",
            "https://[2001:db8::1]/v1/public/credentials",
            "https://localhost/v1/public/credentials",
            "https://relay.local/v1/public/credentials",
            "https://relay.example./v1/public/credentials",
            "https://RELAY.example/v1/public/credentials",
            // Beyond the Rust table: a hex number is an IPv4 literal to a URL parser; nothing but a bare path joins.
            "https://relay.0x7f/v1/public/credentials",
            "https://relay.example//v1/public/credentials/v1/public/credentials",
            "https:///v1/public/credentials",
            "https://relay/v1/public/credentials",
            "https://relay.example/v1/public/credentials/",
            "https://rel_ay.example/v1/public/credentials",
            "https://relay.exämple/v1/public/credentials",
        )) assertTrue(endpoint, refused(offer().copy(credentials = endpoint)))
        for (uri in listOf(
            "turn:other.example:3478?transport=udp",
            "turns:relay.example:5349?transport=udp",
            "turn:relay.example:0",
            "turn:relay.example:22?transport=tcp",
            "turn:relay.example:8080?transport=udp",
            "turn:relay.example:03478?transport=udp",
            "turn:relay.example:+3478?transport=udp",
            "turn:relay.example:?transport=udp",
            "turn:relay.example:3478?transport=udp&x=1",
            "stun:relay.example:3478",
            "turn:user@relay.example:3478",
        )) assertTrue(uri, refused(offer().copy(urls = listOf(uri))))
        for (uri in listOf("turn:relay.example?transport=udp", "turns:relay.example:443?transport=tcp", "turn:relay.example:5349")) {
            assertFalse(uri, refused(offer().copy(urls = listOf(uri))))
        }
        assertTrue(refused(offer().copy(note = "x".repeat(PublicCallRelayAdvert.MAX_NOTE + 1))))
        assertTrue(refused(offer().copy(note = "line\nbreak")))
        assertTrue(refused(offer().copy(urls = List(PublicCallRelayAdvert.MAX_URLS + 1) { offer().urls[0] })))
    }

    @Test fun turnHostFoldsAsciiCaseOnlyAndTheNoteIsBoundedInBytes() {
        assertFalse(refused(offer().copy(urls = listOf("turn:RELAY.example:3478"))))
        // U+0130 lower-cases to an ASCII i, and U+212A to an ASCII k, in Java; neither may match.
        assertTrue(refused(offer().copy(urls = listOf("turn:relay.examplİ:3478"))))
        assertTrue(refused(offer().copy(credentials = "https://kea.example/v1/public/credentials", urls = listOf("turn:\u212Aea.example:3478"))))
        assertTrue(refused(offer().copy(urls = emptyList())))
        // 140 bytes is fine, 141 is not, and 71 two-byte characters are 142.
        assertFalse(refused(offer().copy(note = "x".repeat(140))))
        assertTrue(refused(offer().copy(note = "é".repeat(71))))
        assertFalse(refused(offer().copy(note = "é".repeat(70))))
        assertFalse(refused(offer().copy(note = null)))
    }

    @Test fun connectTimeHostRulesRefuseNamesThatCannotBePublic() {
        for (host in listOf("relay.example", "box.lan", "nas.home", "x.localdomain", "abc.onion", "x.alt", "1.in-addr.arpa", "relay.test", "relay.local",
            "single", "relay.localhost", "a.internal", "a.invalid", "10.0.0.1", "relay.0x7f", "relay..example", "", "relay.example.", "a_b.example.org")) {
            assertFalse(host, PublicCallRelayAdvert.hostAllowed(host))
        }
        for (host in listOf("turn.example.org", "TURN.Example.ORG", "a.b.c.d.example.com", "a-b.co", "xn.example.org", "axn--b.example.org", "relay.nx--a.example.org")) {
            assertTrue(host, PublicCallRelayAdvert.hostAllowed(host))
        }
        // A 64-character label is too long, 63 is not.
        assertTrue(PublicCallRelayAdvert.hostAllowed("${"a".repeat(63)}.example.org"))
        assertFalse(PublicCallRelayAdvert.hostAllowed("${"a".repeat(64)}.example.org"))
    }

    // Java's IDN (IDNA2003) and Rust's UTS 46 disagree, so no xn-- label is trusted on Android.
    @Test fun anyPunycodeLabelIsRefusedEverywhere() {
        // xn--a-wbb (a leading combining mark), xn--d5a and xn--zca are the cases the two parsers split on;
        // the rest are well-formed punycode, a bare prefix and the label in other positions or cases.
        for (label in listOf("xn--a-wbb", "xn--d5a", "xn--zca", "xn--b-5zg", "xn--bcher-kva", "xn--", "xn--99999999999999", "XN--ZCA", "Xn--zca")) {
            assertFalse(label, PublicCallRelayAdvert.hostAllowed("$label.example.org"))
            assertFalse(label, PublicCallRelayAdvert.hostAllowed("relay.$label.org"))
            assertFalse(label, PublicCallRelayAdvert.hostAllowed("relay.example.$label"))
            val host = "$label.example.org"
            val lower = host.lowercase()
            assertTrue(host, refused(offer().copy(credentials = "https://$lower/v1/public/credentials", urls = listOf("turn:$lower:3478"))))
            // The TURN URL alone, with any spelling of the case, is refused too.
            assertTrue(host, refused(offer().copy(urls = listOf("turn:$host:3478"))))
            assertTrue(host, refused(offer().copy(credentials = "https://relay.$lower/v1/public/credentials", urls = listOf("turn:relay.$host:3478"))))
        }
        // An all-ASCII name still passes, with the same credential and TURN host.
        assertFalse(refused(offer().copy(credentials = "https://turn.example.org/v1/public/credentials", urls = listOf("turn:turn.example.org:3478", "turns:TURN.example.org:5349"))))
        assertTrue(PublicCallRelayAdvert.hostAllowed("turn.example.org"))
    }

    // ---- beyond the fixture -------------------------------------------------

    private val secret = ByteArray(32) { 3 }
    private val other = ByteArray(32) { 4 }
    private val now = 1_793_000_000L
    private fun baseTags(at: Long = now, status: String = "open", region: String? = null) =
        listOf(listOf("d", "spry-turn"), listOf("v", "1"), listOf("status", status), listOf("auth", "public"),
            listOf("expiration", (at + LIFETIME).toString())) + (region?.let { listOf(listOf("g", it)) } ?: emptyList())
    private fun content(offer: PublicRelayOffer = offer()) = buildJsonObject {
        put("credentials", offer.credentials)
        put("urls", JsonArray(offer.urls.map(::JsonPrimitive)))
        offer.note?.let { put("note", it) }
    }.toString()
    private fun sign(
        key: ByteArray = secret, at: Long = now, status: String = "open", region: String? = null,
        tags: List<List<String>>? = null, content: String? = null,
    ): NostrEvent = Events.sign(key, 31478, at, tags ?: baseTags(at, status, region),
        content ?: if (status == "withdrawn") "{}" else content(), ByteArray(32))
    private fun reason(run: () -> Any?): String? = try { run(); null } catch (refused: PublicRelayRefused) { refused.reason.name }
    private fun verifyJson(element: JsonElement, at: Long = now) = PublicCallRelayAdvert.verify(element, at)

    @Test fun readsTheRegionAndTheOfferAndExpiresAtItsExpiration() {
        val advert = PublicCallRelayAdvert.verify(sign(region = "GB"), now)
        assertEquals(PublicRelayStatus.Open, advert.status)
        assertEquals("GB", advert.region)
        assertEquals("relay.example", advert.host)
        assertEquals(now + LIFETIME, advert.expiresAt)
        assertEquals(offer(), advert.offer)
        assertEquals(Schnorr.publicKeyHex(secret), advert.publicKey)
        assertEquals("Expired", reason { PublicCallRelayAdvert.verify(sign(), now + LIFETIME) })
        assertEquals("Destination", reason { PublicCallRelayAdvert.verify(sign(region = "gb"), now) })
        // The JSON entry point and the typed one agree.
        assertEquals(advert, verifyJson(sign(region = "GB").toJson()))
    }

    @Test fun wantsExactlyOneTwoElementTagPerName() {
        val base = baseTags(now + 0).map { if (it[0] == "expiration") listOf("expiration", (now + 60).toString()) else it }
        fun check(tags: List<List<String>>) = reason { PublicCallRelayAdvert.verify(sign(tags = tags), now) }
        assertEquals("Destination", check(base + listOf(listOf("v", "1"))))
        assertEquals("Destination", check(base.map { if (it[0] == "d") listOf("d", "spry-turn", "x") else it }))
        assertEquals("Expired", check(base + listOf(listOf("expiration", (now + 60).toString()))))
        assertEquals("Expired", check(base.map { if (it[0] == "expiration") listOf("expiration", " ${now + 60}") else it }))
        assertEquals("Expired", check(base.map { if (it[0] == "expiration") listOf("expiration", "+${now + 60}") else it }))
        assertEquals("Expired", check(base.map { if (it[0] == "expiration") listOf("expiration", "1".repeat(17)) else it }))
        assertEquals("Expired", check(base.map { if (it[0] == "expiration") listOf("expiration", "") else it }))
        assertEquals("Destination", check(base.map { if (it[0] == "auth") listOf("auth", "paired") else it }))
        assertEquals("Destination", check(base.map { if (it[0] == "v") listOf("v", "2") else it }))
        assertEquals("Destination", check(base.map { if (it[0] == "status") listOf("status", "paused") else it }))
        assertEquals("Destination", check(base.filter { it[0] != "d" }))
        // A one-element `expiration` tag is not a two-element tag.
        assertEquals("Expired", check(base.filter { it[0] != "expiration" } + listOf(listOf("expiration"))))
        // Another kind with the right tags is not an advert.
        assertEquals("Destination", reason { PublicCallRelayAdvert.verify(Events.sign(secret, 1, now, base, content(), ByteArray(32)), now) })
    }

    @Test fun refusesUnknownOrRepeatedContentFieldsAndAWithdrawalThatOffersSomething() {
        fun check(content: String, status: String = "open") = reason { PublicCallRelayAdvert.verify(sign(status = status, content = content), now) }
        assertEquals("Encoding", check(content().replace("{", "{\"extra\":1,")))
        val repeated = content().replaceFirst("{", "{\"credentials\":\"https://other.example/v1/public/credentials\",")
        assertEquals("Encoding", check(repeated))
        assertEquals("Encoding", check("{\"a\":1}", "withdrawn"))
        assertEquals("Encoding", check("[]", "withdrawn"))
        assertEquals("Encoding", check("not json"))
        assertEquals("Encoding", check("[]"))
        assertEquals("Encoding", check("{\"credentials\":5,\"urls\":[]}"))
        assertEquals("Encoding", check(content().replace("\"urls\":[", "\"urls\":[5,")))
        assertEquals("Encoding", check(content().replace("Synthetic operator note", "x\\ud800y")))
        assertEquals("Destination", reason { PublicCallRelayAdvert.verify(sign(status = "paused"), now) })
        // A null note is no note, as serde reads an Option.
        val noNote = PublicCallRelayAdvert.verify(sign(content = content(offer().copy(note = null)).dropLast(1) + ",\"note\":null}"), now)
        assertNull(noNote.offer?.note)
        // A nested duplicate or a key inside the urls array is not a top-level key.
        assertEquals(listOf("credentials", "urls", "note"), PublicCallRelayAdvert.topLevelKeys(content()))
        assertEquals(listOf("a", "a"), PublicCallRelayAdvert.topLevelKeys("{\"a\":1,\"a\":2}"))
        assertEquals(listOf("a", "b"), PublicCallRelayAdvert.topLevelKeys("{\"a\":{\"a\":1,\"c\":[\"x\",{\"d\":1}]},\"b\":\"a\\\"\"}"))
    }

    @Test fun checksSizeFirstThenShapeThenTheSignatureOnTheEventAsGiven() {
        val big = sign(content = content(offer().copy(note = "x".repeat(4000))))
        assertEquals("Size", reason { PublicCallRelayAdvert.verify(big, now) })
        // The size is of the seven-field JSON: a note exactly at the bound passes, one byte more does not.
        val fits = sign(content = content(offer().copy(note = "x".repeat(140))))
        assertTrue(fits.toCompactJson().length < 4096)
        val tampered = sign().copy(content = content(offer().copy(note = "changed")))
        assertEquals("Authentication", reason { PublicCallRelayAdvert.verify(tampered, now) })
        assertEquals("Authentication", reason { PublicCallRelayAdvert.verify(sign().copy(sig = "0".repeat(128)), now) })
        assertEquals("Encoding", reason { PublicCallRelayAdvert.verify(sign().copy(id = "A".repeat(64)), now) })
        assertEquals("Encoding", reason { PublicCallRelayAdvert.verify(sign().copy(sig = sign().sig.uppercase()), now) })
        // The digest is over the pubkey as given, so a changed key is a changed id: Authentication, as in Rust.
        assertEquals("Authentication", reason { PublicCallRelayAdvert.verify(sign().copy(pubkey = "00"), now) })
        assertEquals("Size", reason { PublicCallRelayAdvert.verify(sign(tags = baseTags() + listOf(listOf("a"), listOf("b"), listOf("c"), listOf("e"))), now) })
        assertEquals("Size", reason { PublicCallRelayAdvert.verify(sign(tags = baseTags() + listOf(listOf("x", "y".repeat(257)))), now) })
        assertEquals("Size", reason { PublicCallRelayAdvert.verify(sign(tags = baseTags() + listOf(emptyList())), now) })
        assertEquals("Size", reason { PublicCallRelayAdvert.verify(sign(tags = baseTags() + listOf(listOf("x", "y", "z", "w"))), now) })
    }

    // spry-link Event::verify order: id shape, digest, key shape, key on the curve, signature shape, signature.
    private fun withId(pubkey: String, sig: String = "0".repeat(128), createdAt: Long = now): NostrEvent {
        val tags = baseTags(createdAt)
        val content = content()
        return NostrEvent(31478, createdAt, tags, content, pubkey, Events.eventId(pubkey, createdAt, 31478, tags, content), sig)
    }

    @Test fun refusalNamesFollowRustsOrderForTheIdKeyAndSignature() {
        // The id's shape comes first, then the digest, whatever else is wrong.
        assertEquals("Encoding", reason { PublicCallRelayAdvert.verify(sign().copy(id = "00", pubkey = "zz", sig = "zz"), now) })
        assertEquals("Authentication", reason { PublicCallRelayAdvert.verify(sign().copy(id = "0".repeat(64), pubkey = "zz", sig = "zz"), now) })
        // With the id right for the pubkey as given: the key's shape, then the curve, then the signature's shape.
        assertEquals("Encoding", reason { PublicCallRelayAdvert.verify(withId("zz"), now) })
        assertEquals("Encoding", reason { PublicCallRelayAdvert.verify(withId("A".repeat(64)), now) })
        assertEquals("Encoding", reason { PublicCallRelayAdvert.verify(withId("a".repeat(63)), now) })
        val real = Schnorr.publicKeyHex(secret)
        assertEquals("Encoding", reason { PublicCallRelayAdvert.verify(withId(real, sig = "zz"), now) })
        assertEquals("Encoding", reason { PublicCallRelayAdvert.verify(withId(real, sig = "0".repeat(127)), now) })
        assertEquals("Authentication", reason { PublicCallRelayAdvert.verify(withId(real), now) })
    }

    @Test fun aPubkeyNotOnTheCurveIsKeyNotAuthentication() {
        // BIP-340 vector 5 (not on the curve), and the field prime p and p + 1 (vector 14: too large).
        for (bad in listOf(
            "eefdea4cdb677750a420fee807eacf21eb9898ae79b9768766e4faa04a2d4a34",
            "fffffffffffffffffffffffffffffffffffffffffffffffffffffffefffffc2f",
            "fffffffffffffffffffffffffffffffffffffffffffffffffffffffefffffc30",
            "f".repeat(64),
        )) assertEquals(bad, "Key", reason { PublicCallRelayAdvert.verify(withId(bad), now) })
        // Same through the JSON entry point and the book, which must not advance a watermark for it.
        val book = PublicRelayAdvertBook()
        assertEquals("Key", reason { book.accept(withId("f".repeat(64)).toJson(), now) })
        assertNull(book.watermark("f".repeat(64)))
        // The generator point's x is on the curve, so that key goes on to fail at the signature.
        val g = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
        assertEquals("Authentication", reason { PublicCallRelayAdvert.verify(withId(g), now) })
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun hugeCreatedAt(digits: String, signed: Boolean = true): JsonObject {
        val pubkey = Schnorr.publicKeyHex(secret)
        val tags = JsonArray(baseTags().map { tag -> JsonArray(tag.map(::JsonPrimitive)) })
        val body = content()
        val number = JsonUnquotedLiteral(digits)
        // The canonical text, built independently of the verifier: [0,pubkey,created_at,kind,tags,content].
        val canonical = buildJsonArray {
            add(0); add(pubkey); add(number); add(31478); add(tags); add(body)
        }.toString()
        val id = Digests.sha256(canonical.toByteArray(Charsets.UTF_8)).toHex()
        val sig = if (signed) Schnorr.sign(id.hexToBytes(), secret, ByteArray(32)).toHex() else "0".repeat(128)
        return JsonObject(mapOf(
            "id" to JsonPrimitive(id), "pubkey" to JsonPrimitive(pubkey), "created_at" to number, "kind" to JsonPrimitive(31478),
            "tags" to tags, "content" to JsonPrimitive(body), "sig" to JsonPrimitive(sig),
        ))
    }

    @Test fun aCreatedAtBeyondTheSafeIntegerRangeIsExpiredLikeRust() {
        // u64 in Rust: anything up to 2^64 - 1 parses, verifies, and then is too far ahead. Only 2^64 and over is unparseable.
        for (digits in listOf("9007199254740991", "9007199254740992", "4611686018427387904", "9223372036854775807",
            "9223372036854775808", "18446744073709551614", "18446744073709551615")) {
            assertEquals(digits, "Expired", reason { verifyJson(hugeCreatedAt(digits)) })
            // A bad signature is still caught first, because the signature is checked before the window.
            assertEquals(digits, "Authentication", reason { verifyJson(hugeCreatedAt(digits, signed = false)) })
        }
        assertEquals("Encoding", reason { verifyJson(hugeCreatedAt("18446744073709551616")) })
        assertEquals("Encoding", reason { verifyJson(hugeCreatedAt("99999999999999999999")) })
        // The same through the typed entry point, up to Long's own edge.
        for (at in listOf(1L shl 53, 1L shl 62, Long.MAX_VALUE)) {
            assertEquals("$at", "Expired", reason { PublicCallRelayAdvert.verify(Events.sign(secret, 31478, at, baseTags(at), content(), ByteArray(32)), now) })
        }
        assertEquals("Encoding", reason { PublicCallRelayAdvert.verify(sign().copy(createdAt = -1), now) })
        // Within Long's range the splice and the ordinary id agree: the same event signed both ways verifies.
        assertEquals(PublicRelayStatus.Open, verifyJson(hugeCreatedAt(now.toString())).status)
    }

    @Test fun sizeIsTheUtf8LengthOfTheSevenFieldJsonAndEscapesCount() {
        val empty = sign(content = "").toCompactJson().toByteArray(Charsets.UTF_8).size
        // Content that is not an offer is Encoding once the size passes, so the line between Size and the
        // next check is the bound itself: 4096 bytes passes, one more does not. Each unit counts as it
        // is written on the wire: a letter is 1 byte, an accented one 2, a quote or newline escapes to 2.
        fun at(unit: String, bytes: Int, total: Int) = reason {
            PublicCallRelayAdvert.verify(sign(content = unit.repeat((total - empty) / bytes)), now)
        }
        for ((unit, bytes) in listOf("x" to 1, "\u00e9" to 2, "\"" to 2, "\n" to 2, "\u20ac" to 3)) {
            val room = 4096 - empty
            val total = 4096 - room % bytes
            assertEquals(unit, "Encoding", at(unit, bytes, total))
            assertEquals(unit, "Size", at(unit, bytes, total + bytes))
        }
        // A control character escapes to six bytes: 683 of them are 4098.
        assertEquals("Encoding", reason { PublicCallRelayAdvert.verify(sign(content = "\u0001".repeat((4096 - empty) / 6)), now) })
        assertEquals("Size", reason { PublicCallRelayAdvert.verify(sign(content = "\u0001".repeat((4096 - empty) / 6 + 1)), now) })
        val long = "https://" + "a".repeat(3900) + ".example/v1/public/credentials"
        assertEquals("Size", reason { PublicCallRelayAdvert.verify(sign(content = content(offer().copy(credentials = long))), now) })
    }

    @Test fun strictJsonEventShapeIsEncoding() {
        val good = sign().toJson()
        fun with(name: String, value: JsonElement) = JsonObject(good + (name to value))
        assertEquals("Encoding", reason { verifyJson(with("created_at", JsonPrimitive(1.5))) })
        assertEquals("Encoding", reason { verifyJson(with("created_at", JsonPrimitive("1793000000"))) })
        assertEquals("Encoding", reason { verifyJson(with("created_at", JsonPrimitive(-1))) })
        assertEquals("Encoding", reason { verifyJson(with("kind", JsonPrimitive(65_536))) })
        assertEquals("Encoding", reason { verifyJson(with("kind", JsonPrimitive("31478"))) })
        assertEquals("Encoding", reason { verifyJson(with("tags", JsonArray(listOf(JsonArray(listOf(JsonPrimitive(5)))))))})
        assertEquals("Encoding", reason { verifyJson(with("tags", JsonPrimitive("x"))) })
        assertEquals("Encoding", reason { verifyJson(with("id", JsonPrimitive(5))) })
        assertEquals("Encoding", reason { verifyJson(JsonObject(good - "sig")) })
        assertEquals("Encoding", reason { verifyJson(JsonPrimitive("not an event")) })
        assertEquals("Encoding", reason { verifyJson(JsonArray(emptyList())) })
        // An extra field is not part of the signed seven and is ignored.
        assertEquals(PublicRelayStatus.Open, verifyJson(with("extra", JsonPrimitive(1))).status)
    }

    @Test fun createdAtAndExpirationWindows() {
        fun at(created: Long, expiry: Long) = sign(at = created, tags = baseTags(created).map { if (it[0] == "expiration") listOf("expiration", expiry.toString()) else it })
        assertEquals(PublicRelayStatus.Open, PublicCallRelayAdvert.verify(at(now + 600, now + 600 + LIFETIME), now).status)
        assertEquals("Expired", reason { PublicCallRelayAdvert.verify(at(now + 601, now + 601 + 60), now) })
        assertEquals("Expired", reason { PublicCallRelayAdvert.verify(at(now - 60, now), now) })
        assertEquals(PublicRelayStatus.Open, PublicCallRelayAdvert.verify(at(now - 60, now + 1), now).status)
        assertEquals("Expired", reason { PublicCallRelayAdvert.verify(at(now, now + LIFETIME + 1), now) })
    }

    // spry-link advert.rs `signed_adverts_verify_and_a_newer_withdrawal_wins`, and the restore rule.
    @Test fun theBookKeepsTheNewestPerKeyAndANewerWithdrawalWins() {
        val book = PublicRelayAdvertBook(4)
        val event = sign(region = "GB")
        book.accept(event, now)
        assertEquals(1, book.open(now).size)
        assertEquals("Replay", reason { book.accept(event, now) })
        val gone = sign(at = now + 1, status = "withdrawn")
        assertEquals(PublicRelayStatus.Withdrawn, book.accept(gone, now + 1).status)
        assertEquals(0, book.open(now + 1).size)
        assertNull(book.openFrom(Schnorr.publicKeyHex(secret), now + 1))
        assertEquals("Replay", reason { book.accept(event, now + 1) })
        assertEquals(now + 1, book.watermark(Schnorr.publicKeyHex(secret)))
    }

    @Test fun theBookIsBoundedAndCapsItsCapacity() {
        val full = PublicRelayAdvertBook(1)
        full.accept(sign(), now)
        assertEquals("Overload", reason { full.accept(sign(key = other), now) })
        // The same key is not a new entry, so a newer advert still gets in.
        full.accept(sign(at = now + 1), now + 1)
        assertEquals(1024, PublicRelayAdvertBook(5000).capacity)
        assertEquals(0, PublicRelayAdvertBook(-3).capacity)
    }

    @Test fun anEventThatFailsVerificationDoesNotAdvanceTheWatermark() {
        val book = PublicRelayAdvertBook()
        assertEquals("Authentication", reason { book.accept(sign(at = now + 5).copy(sig = "0".repeat(128)), now) })
        assertNull(book.watermark(Schnorr.publicKeyHex(secret)))
        book.accept(sign(), now)
    }

    @Test fun aWatermarkIsForgottenTwoHoursAfterItsCreatedAt() {
        val book = PublicRelayAdvertBook()
        book.accept(sign(), now)
        assertEquals(1, book.watermarks(now + LIFETIME - 1).size)
        assertEquals(0, book.watermarks(now + LIFETIME).size)
    }

    @Test fun theBookAdmitsTheOneEventBehindARestoredWatermarkOnceAndNothingOlder() {
        val event = sign()
        val first = PublicRelayAdvertBook()
        first.accept(event, now)
        val marks = first.watermarks(now + 10)
        assertEquals(listOf(PublicRelayWatermark(event.pubkey, now, event.id)), marks)
        val book = PublicRelayAdvertBook(1024, marks, now + 10)
        assertEquals(0, book.open(now + 10).size)
        assertEquals(PublicRelayStatus.Open, book.accept(event, now + 10).status)
        assertEquals("Replay", reason { book.accept(event, now + 10) })
        assertEquals("Replay", reason { PublicRelayAdvertBook(1024, marks, now + 10).accept(sign(at = now - 5), now + 10) })
        // The same second but another event (a different note, so a different id) is still a replay.
        val sibling = sign(content = content(offer().copy(note = "another")))
        assertEquals("Replay", reason { PublicRelayAdvertBook(1024, marks, now + 10).accept(sibling, now + 10) })
        // A withdrawal behind a restored watermark stays withdrawn: an open advert at the same second differs in id.
        val gone = sign(at = now + 20, status = "withdrawn")
        val withdrawnBook = PublicRelayAdvertBook()
        withdrawnBook.accept(gone, now + 20)
        val reopened = PublicRelayAdvertBook(1024, withdrawnBook.watermarks(now + 30), now + 30)
        assertEquals("Replay", reason { reopened.accept(sign(at = now + 20), now + 30) })
        assertEquals(PublicRelayStatus.Withdrawn, PublicRelayAdvertBook(1024, withdrawnBook.watermarks(now + 30), now + 30).accept(gone, now + 30).status)
    }

    @Test fun aRestoredWatermarkIsHeldToTenMinutesAheadOfTheClock() {
        val key = Schnorr.publicKeyHex(secret)
        val marks = listOf(PublicRelayWatermark(key, now + 86_400, "b".repeat(64)))
        val book = PublicRelayAdvertBook(1024, marks, now)
        // Clamped to now + 600: a genuine advert newer than that is no longer hidden for a day.
        assertEquals(now + 600, book.watermark(key))
        assertEquals(PublicRelayStatus.Open, book.accept(sign(at = now + 601), now + 601).status)
        // One inside the window is untouched, and an event at or below the clamp is still a replay.
        val inside = PublicRelayAdvertBook(1024, listOf(PublicRelayWatermark(key, now + 600, "b".repeat(64))), now)
        assertEquals(now + 600, inside.watermark(key))
        assertEquals("Replay", reason { PublicRelayAdvertBook(1024, marks, now).accept(sign(at = now + 600), now) })
        assertEquals(now - 5, PublicRelayAdvertBook(1024, listOf(PublicRelayWatermark(key, now - 5, "b".repeat(64))), now).watermark(key))
    }

    @Test fun restoredWatermarksAreValidatedBoundedAndTheNewestPerKeyWins() {
        val key = "a".repeat(64)
        val id = "b".repeat(64)
        val book = PublicRelayAdvertBook(2, listOf(
            PublicRelayWatermark("short", now, id),
            PublicRelayWatermark(key, now, "ID"),
            PublicRelayWatermark(key, -1, id),
            PublicRelayWatermark(key, now, id),
            PublicRelayWatermark(key, now - 5, id),
            PublicRelayWatermark("c".repeat(64), now, id),
            PublicRelayWatermark("d".repeat(64), now, id),
        ), now)
        assertEquals(now, book.watermark(key))
        assertEquals(now, book.watermark("c".repeat(64)))
        assertNull(book.watermark("d".repeat(64)))
        assertNull(book.watermark("short"))
        // Newest first and bounded.
        val newer = PublicRelayAdvertBook(10, listOf(PublicRelayWatermark(key, now - 5, id), PublicRelayWatermark("c".repeat(64), now, id)), now)
        assertEquals(listOf(now, now - 5), newer.watermarks(now).map { it.createdAt })
    }
}
