package dev.forgesworn.kithmoot.protocol

import kotlinx.serialization.json.*

data class SharedProject(
    val reference: ProjectReference,
    val heads: List<String>,
    val revision: Int,
    val definition: JsonObject?,
    val authority: String?,
    val joined: Boolean,
    val withdrawn: Boolean,
    val conflicted: Boolean,
) {
    val key: String get() = reference.key
    val archived: Boolean get() = definition?.get("archived")?.jsonPrimitive?.booleanOrNull == true
    val name: String? get() = definition?.projectText("name")
}

/**
 * A bounded, immutable projection. The account adapter must durably save a
 * candidate before exposing it, and serialize writers for that account.
 * Highest observed revisions survive removal and restart; a same-revision
 * fork hides the definition until an owner supplies a higher revision.
 * A relay can withhold newer events: this is not a global freshness proof.
 */
class ProjectDirectoryState private constructor(
    val recipient: String,
    private val stored: Map<String, NostrEvent>,
) {
    constructor(recipient: String) : this(recipient, emptyMap()) {
        require(Regex("[0-9a-f]{64}").matches(recipient)) { "Invalid project recipient" }
    }

    private fun group(event: NostrEvent, record: ProjectRecord): String =
        "${if (record.op == "follow") "follow" else "project"}:${record.reference(event.pubkey).key}"

    // Only records verified by accept or restore reach stored.
    private fun body(event: NostrEvent) = ProjectRecord(Json.parseToJsonElement(event.content).jsonObject)

    fun events(): List<NostrEvent> = stored.values.map { it.copy(tags = it.tags.map(List<String>::toList)) }

    fun accept(event: NostrEvent, now: Long): ProjectDirectoryState {
        val record = Projects.forRecipient(event, recipient, now) ?: return this
        if (event.id in stored) return this
        val target = group(event, record)
        val peers = stored.values.filter { group(it, body(it)) == target }
        val highest = peers.firstOrNull()?.let { body(it).revision } ?: 0
        if (record.revision < highest || record.revision == highest && peers.size >= MAX_HEADS) return this
        val references = stored.values.map { body(it).reference(it.pubkey).key }.toSet()
        if (record.reference(event.pubkey).key !in references && references.size >= MAX_PROJECTS) return this
        val candidate = stored.toMutableMap()
        if (record.revision > highest) peers.forEach { candidate.remove(it.id) }
        candidate[event.id] = event.copy(tags = event.tags.map { it.toList() })
        return ProjectDirectoryState(recipient, candidate.toMap())
    }

    fun projects(): List<SharedProject> {
        val groups = stored.values.groupBy { group(it, body(it)) }
        return groups.filterKeys { it.startsWith("project:") }.values.map { events ->
            val first = events.first()
            val record = body(first)
            val ref = record.reference(first.pubkey)
            val sole = record.takeIf { events.size == 1 }
            val definition = sole?.definition
            val follow = groups["follow:${ref.key}"]?.singleOrNull()?.let(::body)
            val member = definition?.get("members")?.jsonArray?.map { it.jsonObject }
                ?.find { it.projectText("pubkey") == recipient }
            val withdrawn = sole?.op == "withdraw"
            SharedProject(ref, events.map { it.id }.sorted(), record.revision, definition,
                definition?.let { Projects.authority(ref, it) },
                joined = !withdrawn && events.size == 1 && (ref.owner == recipient ||
                    follow?.body?.get("joined")?.jsonPrimitive?.booleanOrNull == true &&
                    follow.body.projectRevision("membership") == member?.projectRevision("epoch")),
                withdrawn = withdrawn, conflicted = events.size > 1)
        }.sortedWith(compareBy<SharedProject> { it.name ?: it.key }.thenBy { it.key })
    }

    companion object {
        const val MAX_PROJECTS = 128
        const val MAX_HEADS = 8
        const val MAX_EVENTS = MAX_PROJECTS * MAX_HEADS * 2

        /** Invalid durable state is an error, never silently treated as an empty account. */
        fun restore(recipient: String, events: List<NostrEvent>, now: Long): ProjectDirectoryState {
            require(events.size <= MAX_EVENTS) { "Project cache exceeds event limit" }
            var state = ProjectDirectoryState(recipient)
            for (event in events) {
                require(Projects.forRecipient(event, recipient, now) != null) { "Project cache failed verification" }
                val next = state.accept(event, now)
                // Refuse a cache that silently exceeds the retained-reference or fork bounds.
                require(next !== state || event.id in state.stored) { "Project cache contains stale or excessive records" }
                state = next
            }
            return state
        }
    }
}
