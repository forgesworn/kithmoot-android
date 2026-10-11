package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException
import kotlin.coroutines.*

class ProjectLogoTest {
    private val fixture = Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/shared-project-web.json"))
        .bufferedReader().use { it.readText() }).jsonObject
    private val directory = fixture.getValue("entries").jsonArray.first {
        it.jsonObject.getValue("id") == JsonPrimitive("kithmoot-initial")
    }.jsonObject.getValue("event").let { requireNotNull(Projects.decodeEvent(it)) }
    private val owner = directory.pubkey
    private val member = fixture.getValue("identities").jsonObject.getValue("member").jsonPrimitive.content
    private val now = fixture.getValue("now").jsonPrimitive.long
    private val context = ProjectLogoContext(ProjectReference(owner, Json.parseToJsonElement(directory.content).jsonObject.projectText("project")!!),
        Json.parseToJsonElement(directory.content).jsonObject.getValue("definition").jsonObject)
    private val image = Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/logo-images-v1.json"))
        .bufferedReader().use { it.readText() }).jsonObject.getValue("vectors").jsonArray.first().jsonObject.getValue("payload")
    private val ownerSecret = ByteArray(32) { 1 }
    private val memberSecret = ByteArray(32) { 2 }
    private fun body(image: JsonElement = this.image) = buildJsonObject {
        put("v", 1); put("op", "logo"); put("project", context.reference.project)
        put("authority", Projects.authority(context.reference, context.definition)); put("version", 1)
        put("request", "native-logo-change-00001"); put("image", image)
    }
    private fun raw(body: JsonObject = body(), secret: ByteArray = ownerSecret, at: Long = now,
        tags: List<List<String>> = listOf(listOf("d", "logo:${context.reference.project}"), listOf("l", ProjectLogos.APP)),
    ) = Events.sign(secret, Projects.KIND, at, tags, body.toString())
    private fun <T> immediate(block: suspend () -> T): T {
        var result: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(answer: Result<T>) { result = answer }
        })
        return requireNotNull(result).getOrThrow()
    }
    private fun decrypt(pubkey: String, ciphertext: String): String {
        val key = Nip44.conversationKey(memberSecret, pubkey.hexToBytes())
        return try { Nip44.decrypt(ciphertext, key) } finally { key.fill(0) }
    }

    @Test fun `signed inline image and explicit removal round trip only to current members`() {
        for (payload in listOf(image, JsonNull)) {
            val inner = immediate { ProjectLogos.sign(owner, context, body(payload), now) { kind, at, tags, content ->
                Events.sign(ownerSecret, kind, at, tags, content)
            } }
            assertEquals(body(payload), ProjectLogos.record(inner, context, now)!!.body)
            val outer = ProjectLogos.wrap(inner, member, context, now)
            assertFalse(outer.tags.flatten().contains(context.reference.project))
            assertEquals(inner.toJson(), immediate { ProjectLogos.unwrap(outer, member, listOf(context), now, ::decrypt) }!!.toJson())
        }
    }

    @Test fun `non owners and hostile images are refused before signing`() {
        var called = false
        for ((author, value) in listOf(member to body(), owner to body(JsonObject(image.jsonObject + ("data" to JsonPrimitive("https://tracker.invalid/image.png")))))) {
            assertTrue(runCatching { immediate { ProjectLogos.sign(author, context, value, now) { _, _, _, _ -> called = true; raw() } } }.isFailure)
        }
        assertFalse(called)
    }

    @Test fun `signature time label owner and unexpected fields are independently checked`() {
        val inner = raw()
        assertNull(ProjectLogos.record(inner.copy(content = body(JsonNull).toString()), context, now))
        assertNull(ProjectLogos.record(raw(secret = memberSecret), context, now))
        assertNull(ProjectLogos.record(raw(at = now + 61), context, now))
        assertNull(ProjectLogos.record(raw(tags = directory.tags), context, now))
        assertNull(ProjectLogos.record(raw(JsonObject(body() + ("extra" to JsonPrimitive(true)))), context, now))
        assertNull(ProjectLogos.record(inner, context.copy(reference = context.reference.copy(project = "ab".repeat(32))), now))
    }

    @Test fun `changed member epochs invalidate earlier artwork even after a member returns`() {
        val inner = raw()
        for (epoch in listOf(2, 3)) {
            val changed = JsonObject(context.definition + mapOf("authorityRevision" to JsonPrimitive(epoch),
                "members" to JsonArray(context.definition.getValue("members").jsonArray.map { raw ->
                    val m = raw.jsonObject
                    if (m.projectText("pubkey") == member) JsonObject(m + ("epoch" to JsonPrimitive(epoch))) else m
                })))
            assertNull(ProjectLogos.record(inner, context.copy(definition = changed), now))
        }
    }

    @Test fun `invalid recipients directories and altered outer envelopes never prompt decryption`() {
        val inner = raw(); val outer = ProjectLogos.wrap(inner, member, context, now)
        val stranger = Schnorr.publicKeyHex(ByteArray(32) { 3 })
        assertTrue(runCatching { ProjectLogos.wrap(inner, stranger, context, now) }.isFailure)
        var called = false
        val decrypt: suspend (String, String) -> String = { _, _ -> called = true; inner.toCompactJson() }
        assertNull(immediate { ProjectLogos.unwrap(outer, stranger, listOf(context), now, decrypt) })
        assertNull(immediate { ProjectLogos.unwrap(outer, member, listOf(context.copy(definition = JsonObject(emptyMap()))), now, decrypt) })
        assertNull(immediate { ProjectLogos.unwrap(outer.copy(content = outer.content + "x"), member, listOf(context), now, decrypt) })
        assertNull(immediate { ProjectLogos.unwrap(outer.copy(content = "x".repeat(ProjectLogos.MAX_WRAP_BYTES + 1)), member, listOf(context), now, decrypt) })
        assertFalse(called)
    }

    @Test fun `a valid envelope decrypts once across candidate projects and refuses a borrowed inner owner`() {
        val inner = raw(); val outer = ProjectLogos.wrap(inner, member, context, now)
        val unrelated = context.copy(reference = context.reference.copy(project = "cd".repeat(32)))
        var count = 0
        assertEquals(inner.toJson(), immediate { ProjectLogos.unwrap(outer, member, listOf(unrelated, context), now) { pubkey, ciphertext ->
            count++; decrypt(pubkey, ciphertext)
        } }!!.toJson())
        assertEquals(1, count)
        assertNull(immediate { ProjectLogos.unwrap(outer, member, listOf(context), now) { _, _ -> raw(secret = memberSecret).toCompactJson() } })
    }

    @Test fun `signer substitutions and cancellation remain visible to the caller`() {
        assertTrue(runCatching { immediate { ProjectLogos.sign(owner, context, body(JsonNull), now) { _, _, _, _ -> raw() } } }.isFailure)
        val outer = ProjectLogos.wrap(raw(), member, context, now)
        val error = runCatching { immediate { ProjectLogos.unwrap(outer, member, listOf(context), now) { _, _ -> throw CancellationException("Cancelled") } } }.exceptionOrNull()
        assertTrue(error is CancellationException)
    }

    @Test fun `numeric spellings agree with web bounds and quoted versions and oversized content are refused`() {
        val decimal = JsonObject(body() + mapOf("v" to Json.parseToJsonElement("1e0"), "version" to Json.parseToJsonElement("1.0")))
        assertEquals(1, ProjectLogos.parse(decimal.toString(), context)!!.version)
        for (version in listOf(JsonPrimitive("1"), JsonPrimitive(0), JsonPrimitive(1_000_001), JsonPrimitive(1.5))) {
            assertNull(ProjectLogos.parse(JsonObject(body() + ("version" to version)).toString(), context))
        }
        assertNull(ProjectLogos.parse(" ".repeat(ProjectLogos.MAX_BYTES) + body(), context))
    }

    @Test fun `signed browser companion fixtures agree without dropping accepted fields`() {
        val vectors = Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/project-logo-web-v1.json"))
            .bufferedReader().use { it.readText() }).jsonObject
        assertEquals(member, vectors.getValue("member").jsonPrimitive.content)
        val cases = vectors.getValue("cases").jsonArray
        assertEquals(13, cases.size)
        for (raw in cases) {
            val entry = raw.jsonObject; val id = entry.getValue("id").jsonPrimitive.content
            val c = entry.getValue("context").jsonObject; val reference = c.getValue("reference").jsonObject
            val context = ProjectLogoContext(ProjectReference(reference.projectText("owner")!!, reference.projectText("project")!!), c.getValue("definition").jsonObject)
            val event = requireNotNull(Projects.decodeEvent(entry.getValue("event")))
            val accepted = entry.getValue("accepted").jsonPrimitive.boolean
            if (entry.getValue("mode") == JsonPrimitive("inner")) {
                val record = ProjectLogos.record(event, context, now)
                assertEquals(id, accepted, record != null)
                if (accepted) assertEquals(id, Json.parseToJsonElement(event.content), requireNotNull(record).body)
            } else {
                var decryptions = 0
                val decoded = immediate { ProjectLogos.unwrap(event, member, listOf(context), now) { pubkey, content ->
                    decryptions++; decrypt(pubkey, content)
                } }
                assertEquals(id, accepted, decoded != null)
                assertEquals(id, if (accepted) 1 else 0, decryptions)
                if (accepted) assertEquals(id, entry.getValue("inner"), requireNotNull(decoded).toJson())
            }
        }
    }
}
