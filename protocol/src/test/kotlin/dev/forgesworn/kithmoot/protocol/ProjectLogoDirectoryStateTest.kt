package dev.forgesworn.kithmoot.protocol

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProjectLogoDirectoryStateTest {
    private val vectors = Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/project-logo-web-v1.json"))
        .bufferedReader().use { it.readText() }).jsonObject
    private val first = vectors.getValue("cases").jsonArray.first().jsonObject
    private val rawContext = first.getValue("context").jsonObject
    private val reference = rawContext.getValue("reference").jsonObject
    private val context = ProjectLogoContext(ProjectReference(reference.projectText("owner")!!, reference.projectText("project")!!), rawContext.getValue("definition").jsonObject)
    private val image = Json.parseToJsonElement(first.getValue("event").jsonObject.getValue("content").jsonPrimitive.content).jsonObject.getValue("image")
    private val now = vectors.getValue("now").jsonPrimitive.long
    private fun event(version: Int = 1, request: Int = 1, image: JsonElement = this.image, context: ProjectLogoContext = this.context): NostrEvent {
        val body = buildJsonObject {
            put("v", 1); put("op", "logo"); put("project", context.reference.project)
            put("authority", Projects.authority(context.reference, context.definition)); put("version", version)
            put("request", "projection-request-$request"); put("image", image)
        }
        return Events.sign(ByteArray(32) { 1 }, Projects.KIND, now,
            listOf(listOf("d", "logo:${context.reference.project}"), listOf("l", ProjectLogos.APP)), body.toString())
    }

    @Test fun `replay preserves original heads and lower revisions cannot replace a newer image`() {
        val old = event(1); val current = event(2)
        val state = ProjectLogoDirectoryState().accept(current, context, now).accept(old, context, now)
        assertEquals(listOf(current.id), state.current(context).heads)
        assertEquals(image, state.current(context).image!!.toJson())
        val replay = ProjectLogoDirectoryState.restore(state.events(), listOf(context), now)
        assertEquals(current.toJson(), replay.events().single().toJson())
        assertEquals(state.current(context).heads, replay.current(context).heads)
    }

    @Test fun `same revision forks withhold artwork until a higher explicit removal resolves them`() {
        val a = event(); val b = event(request = 2, image = JsonNull)
        val state = ProjectLogoDirectoryState().accept(a, context, now).accept(b, context, now)
        assertTrue(state.current(context).conflicted)
        assertNull(state.current(context).image)
        assertEquals(listOf(a.id, b.id).sorted(), state.current(context).heads)
        val removal = event(2, 3, JsonNull)
        val resolved = state.accept(removal, context, now)
        assertFalse(resolved.current(context).conflicted)
        assertNull(resolved.current(context).image)
        assertEquals(listOf(removal.id), resolved.current(context).heads)
        assertEquals(1, resolved.events().size)
    }

    @Test fun `metadata only directory changes retain branding but membership changes withhold it`() {
        val state = ProjectLogoDirectoryState().accept(event(), context, now)
        val renamed = context.copy(definition = JsonObject(context.definition + ("name" to JsonPrimitive("Renamed project"))))
        assertEquals(image, state.current(renamed).image!!.toJson())
        val changed = context.copy(definition = JsonObject(context.definition + ("authorityRevision" to JsonPrimitive(99))))
        assertNull(state.current(changed).image)
        assertEquals(emptyList<String>(), state.current(changed).heads)
        val refreshed = event(context = changed)
        val next = state.accept(refreshed, changed, now)
        assertEquals(listOf(refreshed.id), next.current(changed).heads)
        assertEquals(1, next.events().size)
        assertNull(next.current(context).image)
    }

    @Test fun `stale and unknown authorities are withheld on restore and corrupted signatures reject the cache`() {
        val valid = event()
        val changed = context.copy(definition = JsonObject(context.definition + ("authorityRevision" to JsonPrimitive(99))))
        assertNull(ProjectLogoDirectoryState.restore(listOf(valid), listOf(changed), now).current(changed).image)
        assertEquals(0, ProjectLogoDirectoryState.restore(listOf(valid), emptyList(), now).events().size)
        assertTrue(runCatching { ProjectLogoDirectoryState.restore(listOf(valid.copy(content = "changed")), listOf(context), now) }.isFailure)
    }

    @Test fun `eight conflicting heads are bounded and a later head frees their space`() {
        var state = ProjectLogoDirectoryState()
        for (i in 1..9) state = state.accept(event(request = i), context, now)
        assertEquals(8, state.events().size)
        assertTrue(state.current(context).conflicted)
        state = state.accept(event(2, 10), context, now)
        assertEquals(1, state.events().size)
        assertFalse(state.current(context).conflicted)
    }

    @Test fun `project count is bounded without discarding existing artwork`() {
        var state = ProjectLogoDirectoryState()
        for (i in 1..129) {
            val c = context.copy(reference = context.reference.copy(project = i.toString(16).padStart(64, '0')))
            state = state.accept(event(context = c), c, now)
        }
        assertEquals(128, state.events().size)
        val first = context.copy(reference = context.reference.copy(project = "1".padStart(64, '0')))
        val last = context.copy(reference = context.reference.copy(project = "81".padStart(64, '0')))
        assertNotNull(state.current(first).image)
        assertNull(state.current(last).image)
    }

    @Test fun `input and returned tag mutations cannot change a verified cached event`() {
        val valid = event(); val tags = valid.tags.map { it.toMutableList() }.toMutableList()
        val state = ProjectLogoDirectoryState().accept(valid.copy(tags = tags), context, now)
        tags[0][1] = "changed"
        val exposed = state.events().single()
        (exposed.tags[0] as MutableList<String>)[1] = "changed again"
        assertEquals(valid.toJson(), state.events().single().toJson())
        assertEquals(image, state.current(context).image!!.toJson())
    }
}
