package dev.forgesworn.kithmoot.lab

import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import dev.forgesworn.kithmoot.lab.MetadataPrimitives as P

/** A bounded, owned lab window. Snapshot data contains digests/counts, never captured values. */
internal class MetadataSession(
    val windowId: String, val ownerStage: String, terms: Map<String, String>, salt: ByteArray,
    val actor: String, val durationMs: Long, actions: List<String>, val signerOrigin: String,
    private val clock: () -> Long, private val wallStartedMs: Long,
    private val maxFrames: Int = 10_000, private val maxBytes: Long = 8_000_000,
) {
    private val terms = terms.toMutableMap()
    private val salt = salt.copyOf()
    private val records = mutableListOf<JsonObject>()
    private val lifecycle = mutableListOf<JsonObject>()
    private val controls = mutableMapOf<String, JsonObject>()
    private var lastMs: Long? = null
    private var ended = false
    private var reserved = 0
    private var wireBytes = 0L
    private var urlBytes = 0L
    private var inFlight = 0
    private var gaps = 0
    private var errors = 0
    private var overflow = false
    private var clockRegression = false
    private val startMs: Long
    val roleHashes: Map<String, String>
    val manifestDigest: String
    init {
        require(windowId.matches(Regex("[0-9a-f]{64}")) && ownerStage.matches(Regex("[a-z][a-z0-9-]{0,63}")))
        require(actor in listOf("alice", "bob") && durationMs in 1..3_600_000 && salt.size in 32..64)
        require(terms.keys == P.roles.toSet() && terms.values.all { it.isNotEmpty() && it.length <= 512 && P.wellFormed(it) && !it.contains('\uFFFD') })
        for (role in listOf("aliceRootHex", "bobRootHex", "aliceDevice", "bobDevice", "aliceRzTarget", "bobRzTarget"))
            require(terms.getValue(role).matches(Regex("[0-9a-f]{64}")))
        for (role in listOf("aliceRootNpub", "bobRootNpub")) require(terms.getValue(role).matches(Regex("npub1[023456789acdefghjklmnpqrstuvwxyz]{58}")))
        require(terms["aliceRootHex"] != terms["bobRootHex"] && terms["aliceDevice"] != terms["bobDevice"] && terms["aliceRzTarget"] != terms["bobRzTarget"])
        require(actions.isNotEmpty() && actions.size <= 32 && actions.all { it in listOf("invite", "join", "rejoin", "reply", "message-alice", "message-bob", "restart", "remove", "inspect", "control") })
        require(maxFrames in 1..10_000 && maxBytes in 1..8_000_000)
        require(P.origin(signerOrigin) == signerOrigin && signerOrigin.startsWith("wss://"))
        roleHashes = P.roles.associateWith { P.digest(this.salt, "term:$it", terms.getValue(it)) }
        val hashJson = JsonObject(roleHashes.mapValues { JsonPrimitive(it.value) })
        manifestDigest = P.digest(this.salt, "manifest", P.stringify(JsonArray(listOf(hashJson, JsonArray(actions.map(::JsonPrimitive)), JsonPrimitive(durationMs), JsonPrimitive(signerOrigin)))))
        startMs = now()
    }
    @Synchronized private fun now(): Long {
        val n = clock()
        if (n !in 0..9007199254740991L || lastMs?.let { n < it } == true) { errors++; clockRegression = true; error("metadata clock regression") }
        lastMs = n; return n
    }
    @Synchronized fun error() { errors++ }
    @Synchronized fun gap() { gaps++ }
    @Synchronized fun lifecycle(surface: String, phase: String, url: String) {
        if (ended) return
        if (lifecycle.size >= 512) { overflow = true; errors++; return }
        lifecycle += buildJsonObject { put("surface", surface); put("phase", phase); put("atMs", now()); put("endpointUrlDigest", P.digest(salt, "endpoint-url", url)) }
    }
    @Synchronized private fun reserve(url: String?, bytes: Long): Boolean {
        val extra = url?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0
        if (reserved >= maxFrames || bytes > maxBytes - wireBytes - urlBytes - extra) { overflow = true; errors++; return false }
        reserved++; wireBytes += bytes; urlBytes += extra; return true
    }
    @Synchronized private fun addBytes(count: Int): Boolean {
        if (count > maxBytes - wireBytes - urlBytes) { overflow = true; errors++; return false }
        wireBytes += count; return true
    }
    @Synchronized fun observe(direction: String, sourceLabel: String, endpointLabel: String, endpointOrigin: String,
        endpointUrl: String?, transport: String, wire: ByteArray, surface: String = "external", kind: String = "value") {
        val errorsBefore = errors
        try {
        check(!ended)
        require(direction in listOf("out", "in") && sourceLabel in listOf("app-page", "native", "bark-worker", "cambium", "other"))
        require(transport in listOf("websocket", "http-value"))
        require(endpointLabel in listOf("signer-public-relay", "ordinary-relay", "app-origin", "native-route", "other"))
        if (endpointLabel == "signer-public-relay") require(endpointOrigin == signerOrigin && transport == "websocket")
        require(endpointUrl == null || P.origin(endpointUrl) == endpointOrigin)
        val at = now()
        if (!reserve(endpointUrl, wire.size.toLong())) error("metadata budget exceeded")
        var frame: JsonElement? = null
        val decoded = ByteArrayOutputStream(wire.size)
        P.NodeUtf8 { decoded.write(it.toInt() and 255) }.also { d -> wire.forEach(d::byte); d.finish() }
        val value = decoded.toString(Charsets.UTF_8.name())
        if (transport == "websocket") try { frame = Json.parseToJsonElement(value) } catch (_: Exception) { /* opaque */ }
        var event: JsonObject? = null
        try {
            if (frame is JsonArray && frame.firstOrNull()?.jsonPrimitive?.contentOrNull == "EVENT") {
                event = frame.getOrNull(if (frame.size == 2) 1 else if (frame.size == 3) 2 else -1) as? JsonObject
                val e = requireNotNull(event)
                require(e["pubkey"]?.jsonPrimitive?.content?.matches(Regex("[0-9a-f]{64}")) == true)
                require(e["created_at"]?.jsonPrimitive?.longOrNull?.let { it in 0..9007199254740991L } == true)
                require(e["kind"]?.jsonPrimitive?.longOrNull?.let { it in 0..9007199254740991L } == true)
                require(e["content"] is JsonPrimitive && e.getValue("content").jsonPrimitive.isString)
                require(e["tags"] is JsonArray && e.getValue("tags").jsonArray.all { tag -> tag is JsonArray && tag.all { it is JsonPrimitive && it.isString } })
            }
        } catch (_: Exception) { errors++; event = null }
        val canonical = event?.let { P.digest(salt, "canonical-event", P.stringify(JsonArray(listOf(JsonPrimitive(0), it.getValue("pubkey"), it.getValue("created_at"), it.getValue("kind"), it.getValue("tags"), it.getValue("content"))))) }
        val required = mutableMapOf("aliceRootHex" to 0, "bobRootHex" to 0)
        fun mask(v: JsonElement): JsonElement {
            for (role in required.keys) if ((v as? JsonPrimitive)?.contentOrNull == terms[role]) { required[role] = required.getValue(role) + 1; return JsonPrimitive("[required-root]") }
            return v
        }
        var normalized = frame
        if (endpointLabel == "signer-public-relay" && sourceLabel in listOf("bark-worker", "cambium")) {
            require(endpointOrigin == signerOrigin && transport == "websocket")
            val list = (frame as? JsonArray)?.toMutableList()
            if (list != null && event?.get("kind")?.jsonPrimitive?.longOrNull == 24133L) {
                val e = event.toMutableMap(); e["pubkey"] = mask(e.getValue("pubkey"))
                e["tags"] = JsonArray(e.getValue("tags").jsonArray.map { t -> val a = t.jsonArray.toMutableList(); if (a.firstOrNull()?.jsonPrimitive?.contentOrNull == "p" && a.size >= 2) a[1] = mask(a[1]); JsonArray(a) })
                list[if (list.size == 2) 1 else 2] = JsonObject(e)
            } else if (list?.firstOrNull()?.jsonPrimitive?.contentOrNull == "REQ") {
                for (i in 2 until list.size) { val f = (list[i] as? JsonObject)?.toMutableMap() ?: continue
                    if ((f["kinds"] as? JsonArray)?.let { it.size == 1 && it[0].jsonPrimitive.longOrNull == 24133L } != true) continue
                    for (key in listOf("authors", "#p")) if (f[key] is JsonArray) f[key] = JsonArray(f.getValue(key).jsonArray.map(::mask))
                    list[i] = JsonObject(f)
                }
            }
            if (list != null) normalized = JsonArray(list)
        }
        val matchValue = normalized?.let(P::stringify) ?: value
        val matches = P.presence(terms, matchValue)
        records += record(at, direction, sourceLabel, endpointLabel, endpointOrigin, endpointUrl, transport,
            wire.size.toLong(), P.digest(salt, "wire", wire), matches, canonical, event?.get("created_at")?.jsonPrimitive?.longOrNull,
            required, surface, kind)
        } catch (failure: Throwable) { if (errors == errorsBefore) errors++; throw failure }
    }
    @Synchronized fun guarded(block: () -> Unit) {
        val before = errors
        try { block() } catch (_: Throwable) { if (errors == before) errors++ }
    }
    private fun record(at: Long, direction: String, source: String, endpoint: String, origin: String, url: String?, transport: String,
        bytes: Long, digest: String, matches: Map<String, Int>, canonical: String?, created: Long?, required: Map<String, Int>, surface: String, kind: String) = buildJsonObject {
        put("atMs", at); put("elapsedMs", at - startMs); put("actor", actor); put("direction", direction); put("sourceLabel", source)
        put("endpointLabel", endpoint); put("transport", transport); put("endpointOriginDigest", P.digest(salt, "endpoint-origin", origin))
        if (url != null) { put("endpointUrlDigest", P.digest(salt, "endpoint-url", url)); put("endpointUrlBytes", url.toByteArray(Charsets.UTF_8).size); put("endpointUrlMatches", JsonObject(P.presence(terms, url).mapValues { JsonPrimitive(it.value) })) }
        put("capturedValueBytes", bytes); put("wireDigest", digest); put("canonicalEventDigest", canonical?.let(::JsonPrimitive) ?: JsonNull)
        put("declaredEventCreatedAt", created?.let(::JsonPrimitive) ?: JsonNull); put("declaredBeforeWindow", created != null && wallStartedMs > 0 && created <= (wallStartedMs - 1) / 1000)
        put("historyProven", false); put("matches", JsonObject(matches.mapValues { JsonPrimitive(it.value) })); put("requiredRootMatches", JsonObject(required.mapValues { JsonPrimitive(it.value) }))
        put("requiredRootRoutingCount", required.values.sum()); put("surface", surface); put("valueKind", kind)
    }
    @Synchronized fun stream(direction: String, url: String, surface: String, kind: String): Stream? {
        if (ended || !reserve(null, 0)) return null
        inFlight++; return Stream(direction, url, surface, kind, P.Value(salt, terms))
    }
    inner class Stream(private val direction: String, private val url: String, private val surface: String, private val kind: String, private val value: P.Value) {
        private var complete = false
        @Synchronized fun write(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size - offset) {
            synchronized(this@MetadataSession) {
                if (ended || complete) return
                if (offset < 0 || count < 0 || offset > bytes.size-count) { errors++; error("invalid stream slice") }
                if (!addBytes(count)) { complete = true; inFlight--; gaps++; return }
                value.write(bytes, offset, count)
            }
        }
        @Synchronized fun finish(full: Boolean) { synchronized(this@MetadataSession) {
            if (complete) return
            complete = true; inFlight--; if (!full) gaps++
            if (ended) { errors++; return }
            val (digest, matches) = value.finish()
            records += record(now(), direction, if (surface == "native-inner") "native" else "app-page", "other", P.origin(url), null, "http-value", value.bytes, digest, matches, null, null,
                mapOf("aliceRootHex" to 0, "bobRootHex" to 0), surface, kind).let { base -> JsonObject(base + mapOf(
                    "bodyComplete" to JsonPrimitive(full), "bodyCoverage" to JsonPrimitive(if (direction == "out") { if (full) "complete-accepted-write" else "accepted-write-prefix-unqualified" } else { if (full) "complete-actual-consumption" else "partial-actual-consumption" }))) }
        } }
    }
    @Synchronized fun snapshot(): JsonObject { check(!ended); return render(now()) }
    private fun render(end: Long): JsonObject = buildJsonObject {
            put("schema", "kithmoot.lab-metadata-window.v1"); put("windowId", windowId); put("ownerStage", ownerStage); put("manifestDigest", manifestDigest)
            put("roleHashes", JsonObject(roleHashes.mapValues { JsonPrimitive(it.value) })); put("records", JsonArray(records.toList())); put("lifecycle", JsonArray(lifecycle.toList()))
            put("durationMs", end-startMs); put("capturedValueBytes", wireBytes); put("endpointUrlReferenceBytes", urlBytes)
            put("errors", errors); put("overflow", overflow); put("clockRegression", clockRegression); put("inFlight", inFlight); put("coverageGaps", gaps); put("missingRecords", reserved-records.size)
            put("positiveControls", JsonObject(controls.toMap())); put("absenceClaim", false); put("privacyReductionClaim", false)
            put("externalWirePacketCapture", false); put("nativeOuterWireObserved", false)
            put("coverage", buildJsonObject {
                put("webSocket", "application-accepted-send-and-callback-entry-receive")
                put("http", "initial-logical-request-attempt-url-headers-and-actual-body-writes")
                put("httpResponseBodiesObserved", false); put("webSocketHandshakeHeaders", "additional-view-only")
                put("nativeInner", "guarded-request-offer-return-and-socket-boundaries")
                put("nativeOuterPacketsObserved", false); put("webRtcObserved", false); put("osDnsTlsObserved", false)
                put("instrumentationLoopbackControlExcluded", true); put("thirdPartySdkHttpObserved", false)
            })
        }
    @Synchronized fun close(): JsonObject { check(!ended); val end = try { now() } catch (_: Throwable) { errors++; clockRegression = true; lastMs ?: startMs }
        ended = true
        return try { render(end) } finally { salt.fill(0); terms.clear() }
    }
}
