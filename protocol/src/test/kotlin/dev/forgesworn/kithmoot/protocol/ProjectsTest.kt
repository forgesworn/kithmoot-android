package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.util.concurrent.CancellationException
import java.io.File
import kotlin.coroutines.*

class ProjectsTest {
    private val fixture = Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/shared-project-web.json"))
        .bufferedReader().use { it.readText() }).jsonObject
    private val entries = fixture.getValue("entries").jsonArray.map { it.jsonObject }.associateBy { it.getValue("id").jsonPrimitive.content }
    private val now = fixture.getValue("now").jsonPrimitive.long
    private val owner = fixture.getValue("identities").jsonObject.getValue("owner").jsonPrimitive.content
    private val member = fixture.getValue("identities").jsonObject.getValue("member").jsonPrimitive.content
    private val other = fixture.getValue("identities").jsonObject.getValue("other").jsonPrimitive.content
    // Public, repeated-byte test keys from the checked-in web fixture.
    private val ownerSecret = ByteArray(32) { 1 }
    private val memberSecret = ByteArray(32) { 2 }
    private fun event(name: String = "kithmoot-initial") = Projects.decodeEvent(entries.getValue(name).getValue("event"))!!
    private fun body(name: String = "kithmoot-initial") = Json.parseToJsonElement(event(name).content).jsonObject
    private fun resigned(value: JsonObject, secret: ByteArray = ownerSecret, at: Long = now) = Events.sign(secret,
        Projects.KIND, at, listOf(listOf("d", value.getValue("project").jsonPrimitive.content), listOf("l", Projects.APP)), value.toString())
    private fun edited(original: JsonObject, key: String, value: JsonElement) = JsonObject(original + (key to value))
    private fun <T> immediate(block: suspend () -> T): T {
        var answer: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) { answer = result }
        })
        return requireNotNull(answer) { "Test callback unexpectedly suspended" }.getOrThrow()
    }

    @Test fun allFourteenWebRecordsMatchIncludingAuthorityDigestsAndRecipients() {
        assertEquals(14, entries.size)
        for ((name, entry) in entries) {
            val event = event(name)
            val expected = entry.getValue("expected")
            assertEquals(name, expected.takeUnless { it == JsonNull }, Projects.record(event, now)?.body)
            entry["authority"]?.let { authority ->
                val record = Projects.record(event, now)!!
                assertEquals(name, authority.jsonPrimitive.content, Projects.authority(record.reference(event.pubkey), record.definition!!))
            }
            val recipients = entry["recipients"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            for (key in fixture.getValue("identities").jsonObject.values.map { it.jsonPrimitive.content }) {
                assertEquals("$name recipient $key", key in recipients, Projects.forRecipient(event, key, now) != null)
            }
        }
        assertEquals(body().getValue("project").jsonPrimitive.content, Projects.id(owner, "fixture-kithmoot-01"))
    }

    @Test fun encryptedWebExampleOpensToTheExactSignedRecord() {
        val example = fixture.getValue("encryptedExample").jsonObject
        val wrap = Projects.decodeEvent(example.getValue("event"))!!
        val opened = immediate { Projects.unwrap(wrap, member, now) { peer, payload ->
            Nip44.decrypt(payload, Nip44.conversationKey(memberSecret, peer.hexToBytes()))
        } }
        assertEquals(event(), opened)
    }

    @Test fun androidWrapsArePrivateFreshAndOpenWithNip44() {
        val one = Projects.wrap(event(), member, now)
        val two = Projects.wrap(event(), member, now)
        assertNotEquals(one.pubkey, two.pubkey)
        assertNotEquals(owner, one.pubkey)
        assertEquals(listOf(listOf("p", member), listOf("l", Projects.APP)), one.tags)
        assertTrue(one.createdAt in now - 172800..now)
        assertFalse(one.content.contains("Kithmoot"))
        val raw = Nip44.decrypt(one.content, Nip44.conversationKey(memberSecret, one.pubkey.hexToBytes()))
        assertEquals(event(), Projects.decodeEvent(Json.parseToJsonElement(raw)))
        assertThrows(IllegalArgumentException::class.java) { Projects.wrap(event(), other, now) }
    }

    @Test fun emitsAnAndroidWriterFixtureForTheIndependentWebReader() {
        val inner = immediate { Projects.sign(owner, body(), now) { kind, at, tags, content ->
            Events.sign(ownerSecret, kind, at, tags, content)
        } }
        val follow = immediate { Projects.sign(member, body("member-initial-join"), now) { kind, at, tags, content ->
            Events.sign(memberSecret, kind, at, tags, content)
        } }
        val output = buildJsonObject {
            put("notice", "Synthetic interop fixture. Repeated-byte keys are public test data.")
            put("now", now); put("recipientTestSecretHex", "02".repeat(32))
            put("event", inner.toJson()); put("wrap", Projects.wrap(inner, member, now).toJson())
            put("follow", follow.toJson()); put("followWrap", Projects.wrap(follow, member, now).toJson())
            put("authority", Projects.authority(Projects.record(inner, now)!!.reference(owner), body().getValue("definition").jsonObject))
        }
        val file = File("build/interop/shared-project-android.json")
        check(file.parentFile.mkdirs() || file.parentFile.isDirectory)
        file.writeText(output.toString())
    }

    @Test fun malformedOuterEventsNeverAskTheAccountSignerToDecrypt() {
        val valid = Projects.wrap(event(), member, now)
        val invalid = listOf(valid.copy(content = "x"), valid.copy(tags = valid.tags.reversed()),
            valid.copy(kind = Projects.KIND), valid.copy(content = "x".repeat(Projects.MAX_WRAP_BYTES + 1)))
        for (wrap in invalid) assertNull(immediate { Projects.unwrap(wrap, member, now) { _, _ -> error("Decryption called") } })
        assertNull(immediate { Projects.unwrap(valid, other, now) { _, _ -> error("Decryption called") } })
    }

    @Test fun validOuterSignatureCannotSmuggleWrongRecipientOrTamperedInner() {
        val valid = Projects.wrap(event(), member, now)
        for (inner in listOf(event("research-initial"), event().copy(content = "tampered"))) {
            assertNull(immediate { Projects.unwrap(valid, member, now) { _, _ -> inner.toCompactJson() } })
        }
        assertNull(immediate { Projects.unwrap(valid, member, now) { _, _ -> "[0]" } })
        assertThrows(CancellationException::class.java) {
            immediate { Projects.unwrap(valid, member, now) { _, _ -> throw CancellationException() } }
        }
    }

    @Test fun signingValidatesBeforeCallingAnExternalSignerAndChecksItsExactAnswer() {
        val invalid = edited(body(), "privateNotes", JsonPrimitive("must never be signed"))
        assertThrows(IllegalArgumentException::class.java) {
            immediate { Projects.sign(owner, invalid, now) { _, _, _, _ -> error("Signer called") } }
        }
        val good = immediate { Projects.sign(owner, body(), now) { kind, at, tags, content -> Events.sign(ownerSecret, kind, at, tags, content) } }
        assertNotNull(Projects.record(good, now))
        assertThrows(IllegalArgumentException::class.java) {
            immediate { Projects.sign(owner, body(), now) { kind, at, tags, content -> Events.sign(ownerSecret, kind, at + 1, tags, content) } }
        }
        assertThrows(IllegalArgumentException::class.java) {
            immediate { Projects.sign(owner, body(), now) { kind, at, tags, content -> Events.sign(memberSecret, kind, at, tags, content) } }
        }
    }

    @Test fun hostileSignedBodiesAndTimestampsFailClosed() {
        val original = body()
        val definition = original.getValue("definition").jsonObject
        val members = definition.getValue("members").jsonArray
        val mutations = listOf(
            edited(original, "revision", JsonPrimitive("1")),
            edited(original, "revision", JsonPrimitive(1.5)),
            edited(original, "revision", JsonPrimitive(1_000_001)),
            edited(original, "v", JsonPrimitive(true)),
            edited(original, "parents", JsonArray(listOf(JsonPrimitive("a".repeat(64))))),
            edited(original, "definition", edited(definition, "members", JsonArray(members + members.first()))),
            edited(original, "definition", edited(definition, "members", JsonArray(members.drop(1)))),
            edited(original, "definition", edited(definition, "archived", JsonPrimitive("false"))),
            edited(original, "definition", edited(definition, "authorityRevision", JsonPrimitive(2))),
            edited(original, "definition", edited(definition, "name", JsonPrimitive("Robin\u202eadmin"))),
            edited(original, "definition", edited(definition, "privateContext", JsonPrimitive("forbidden"))),
            edited(original, "definition", edited(definition, "name", JsonPrimitive("x".repeat(Projects.MAX_BYTES)))),
        )
        for (value in mutations) assertNull(Projects.record(resigned(value), now))
        assertNull(Projects.record(resigned(original, at = now + 61), now))
        assertNull(Projects.record(resigned(original, at = -1), now))
        val numeric = edited(edited(original, "v", JsonPrimitive(1.0)), "revision", JsonPrimitive(1.0))
        assertNotNull(Projects.record(resigned(numeric), now))
    }

    @Test fun onlyPersistentInvitationsCanBeSharedAndNeverAndroidPairingCredentials() {
        val original = body()
        val definition = original.getValue("definition").jsonObject
        val room = definition.getValue("rooms").jsonArray.first().jsonObject
        val link = room.getValue("link").jsonPrimitive.content
        val payload = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(link.substringAfter('#')))).jsonObject
        val forbidden = listOf("k", "x", "s", "c").flatMap { field ->
            listOf(JsonPrimitive("01".repeat(32)), JsonPrimitive(""), JsonNull, buildJsonObject { put("credential", "synthetic") })
                .map { edited(payload, field, it) }
        } +
            listOf(edited(payload, "v", JsonPrimitive(2)))
        val links = forbidden.map { "https://fixture.invalid/j/#" + Base64.getUrlEncoder().withoutPadding().encodeToString(it.toString().toByteArray()) } +
            listOf(link.replace("https:", "http:"), link.replace("fixture.invalid", "person:password@fixture.invalid"))
        for (bad in links) {
            val d = edited(definition, "rooms", JsonArray(listOf(edited(room, "link", JsonPrimitive(bad)))))
            assertNull(Projects.record(resigned(edited(original, "definition", d)), now))
        }
    }

    @Test fun wireEventTypesCannotBeCoercedIntoSignedStringsOrNumbers() {
        val original = event().toJson()
        for ((key, value) in listOf("created_at" to JsonPrimitive(now.toString()), "kind" to JsonPrimitive("30078"),
            "content" to JsonPrimitive(42), "tags" to JsonArray(listOf(JsonArray(listOf(JsonPrimitive(42))))))) {
            assertNull(Projects.decodeEvent(edited(original, key, value)))
        }
    }

    @Test fun deliberateJoinsAndMembershipEpochsAgreeWithTheWeb() {
        for (raw in fixture.getValue("joinChecks").jsonArray) {
            val check = raw.jsonObject
            val state = ProjectDirectoryState(member).accept(event(check.getValue("project").jsonPrimitive.content), now)
                .accept(event(check.getValue("follow").jsonPrimitive.content), now)
            assertEquals(check.toString(), check.getValue("joined").jsonPrimitive.boolean, state.projects().single().joined)
        }
        assertFalse(ProjectDirectoryState(member).accept(event(), now).projects().single().joined)
        assertTrue(ProjectDirectoryState(owner).accept(event(), now).projects().single().joined)
    }

    @Test fun overlappingProjectsStaySeparateAndUnrelatedProjectsStayOut() {
        val agent = fixture.getValue("identities").jsonObject.getValue("agent").jsonPrimitive.content
        var state = ProjectDirectoryState(agent)
        for (name in listOf("kithmoot-initial", "bothy-initial", "research-initial")) state = state.accept(event(name), now)
        assertEquals(setOf("Kithmoot", "Bothy"), state.projects().map { it.name }.toSet())
        assertEquals(2, state.projects().map { it.key }.distinct().size)
        assertTrue(state.projects().none { it.joined })
    }

    @Test fun withdrawalsAndRestartRefuseOldSnapshotsAndOldJoins() {
        val before = ProjectDirectoryState(member).accept(event(), now).accept(event("member-initial-join"), now)
        val withdrawn = before.accept(event("member-withdrawal"), now)
        assertTrue(withdrawn.projects().single().withdrawn)
        assertFalse(withdrawn.projects().single().joined)
        assertNull(withdrawn.projects().single().definition)
        var restored = ProjectDirectoryState.restore(member, withdrawn.events(), now)
        assertSame(restored, restored.accept(event(), now))
        restored = restored.accept(event("kithmoot-readded"), now)
        assertFalse(restored.projects().single().joined)
        assertTrue(restored.accept(event("member-renewed-join"), now).projects().single().joined)
        assertThrows(IllegalArgumentException::class.java) { ProjectDirectoryState.restore(other, before.events(), now) }
        assertThrows(IllegalArgumentException::class.java) { ProjectDirectoryState.restore(member, listOf(event().copy(sig = "00")), now) }
    }

    @Test fun equalRevisionForksHideTheDefinitionInEitherArrivalOrderAndSurviveRestart() {
        val original = body()
        val fork = resigned(edited(original, "definition", edited(original.getValue("definition").jsonObject, "name", JsonPrimitive("Fork"))))
        for (events in listOf(listOf(event(), fork), listOf(fork, event()))) {
            var state = events.fold(ProjectDirectoryState(member)) { s, e -> s.accept(e, now) }
            state = ProjectDirectoryState.restore(member, state.events(), now)
            val project = state.projects().single()
            assertTrue(project.conflicted); assertFalse(project.joined); assertNull(project.definition); assertNull(project.authority)
            assertEquals(events.map { it.id }.sorted(), project.heads)
            val renamed = state.accept(event("kithmoot-renamed"), now).projects().single()
            assertFalse(renamed.conflicted)
            assertNotNull(renamed.definition)
        }
    }

    @Test fun forkAndOrphanFollowGrowthAreBounded() {
        var state = ProjectDirectoryState(member)
        for (n in 0..10) {
            val original = body()
            val fork = resigned(edited(original, "definition", edited(original.getValue("definition").jsonObject, "name", JsonPrimitive("Fork $n"))))
            state = state.accept(fork, now)
        }
        assertEquals(8, state.events().size)
        val follow = body("member-initial-join")
        state = ProjectDirectoryState(member)
        for (n in 0..140) {
            state = state.accept(resigned(edited(follow, "project", JsonPrimitive(n.toString(16).padStart(64, '0'))), memberSecret), now)
        }
        assertEquals(128, state.events().size)
        assertTrue(state.projects().isEmpty())
    }
}
