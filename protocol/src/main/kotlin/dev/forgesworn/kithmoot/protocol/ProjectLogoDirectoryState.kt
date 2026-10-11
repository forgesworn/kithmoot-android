package dev.forgesworn.kithmoot.protocol

data class ProjectLogoProjection(
    val image: LogoImage?, val heads: List<String>, val version: Int, val conflicted: Boolean,
)

/** Immutable companion projection. The account journal must save a candidate
 *  before publishing it to the UI or sending its encrypted envelopes. */
class ProjectLogoDirectoryState private constructor(private val entries: Map<String, Entry>) {
    private data class Entry(val reference: ProjectReference, val event: NostrEvent, val record: ProjectLogoRecord)
    constructor() : this(emptyMap())

    fun events(): List<NostrEvent> = entries.values.map { copy(it.event) }

    fun current(context: ProjectLogoContext): ProjectLogoProjection {
        val authority = runCatching { Projects.authority(context.reference, context.definition) }.getOrNull()
            ?: return ProjectLogoProjection(null, emptyList(), 0, false)
        val peers = entries.values.filter { it.reference == context.reference && it.record.authority == authority }
        return ProjectLogoProjection(peers.singleOrNull()?.record?.image, peers.map { it.event.id }.sorted(),
            peers.firstOrNull()?.record?.version ?: 0, peers.size > 1)
    }

    fun accept(event: NostrEvent, context: ProjectLogoContext, now: Long): ProjectLogoDirectoryState {
        val record = ProjectLogos.record(event, context, now) ?: return this
        if (event.id in entries) return this
        val peers = entries.values.filter { it.reference == context.reference && it.record.authority == record.authority }
        val highest = peers.firstOrNull()?.record?.version ?: 0
        if (record.version < highest || record.version == highest && peers.size >= MAX_HEADS) return this
        val references = entries.values.map { it.reference }.toSet()
        if (context.reference !in references && references.size >= MAX_PROJECTS) return this
        val next = entries.toMutableMap()
        // Authority changes start their own revision sequence. Once a current
        // head arrives, obsolete authority heads need no space in this cache.
        if (record.version > highest) entries.values.filter { it.reference == context.reference }.forEach { next.remove(it.event.id) }
        next[event.id] = Entry(context.reference, copy(event), record)
        return ProjectLogoDirectoryState(next.toMap())
    }

    companion object {
        const val MAX_PROJECTS = 128
        const val MAX_HEADS = 8
        private fun copy(event: NostrEvent) = event.copy(tags = event.tags.map(List<String>::toList))

        /** Signature corruption is an error; stale or unknown authorities are
         *  withheld, never used to reconstruct membership or grant access. */
        fun restore(events: List<NostrEvent>, contexts: List<ProjectLogoContext>, now: Long): ProjectLogoDirectoryState {
            require(events.size <= MAX_PROJECTS * MAX_HEADS) { "Project logo cache exceeds its limit" }
            var state = ProjectLogoDirectoryState()
            for (event in events) {
                require(event.content.length <= ProjectLogos.MAX_BYTES && Events.verify(event)) { "Project logo cache failed signature verification" }
                val context = contexts.find { event.pubkey == it.reference.owner &&
                    event.tags == listOf(listOf("d", "logo:${it.reference.project}"), listOf("l", ProjectLogos.APP)) }
                if (context != null) state = state.accept(event, context, now)
            }
            return state
        }
    }
}
