package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.hexToBytes
import kotlinx.serialization.json.*

/** The caller supplies its verified current directory; artwork grants no access. */
data class ProjectLogoContext(val reference: ProjectReference, val definition: JsonObject)

class ProjectLogoRecord internal constructor(val body: JsonObject, val image: LogoImage?) {
    val project: String get() = body.projectText("project")!!
    val authority: String get() = body.projectText("authority")!!
    val version: Int get() = body.projectRevision("version")!!
    val request: String get() = body.projectText("request")!!
}

/** Additive companion domain: existing strict v1 project definitions stay unchanged. */
object ProjectLogos {
    const val APP = "kithmoot.project-logo.v1"
    const val MAX_BYTES = 24_576
    const val MAX_WRAP_BYTES = 50_000
    private const val MAX_TIME = 9_007_199_254_740_991L
    private val fields = setOf("v", "op", "project", "authority", "version", "request", "image")
    private val request = Regex("[a-zA-Z0-9_-]{16,80}")
    private fun tags(project: String) = listOf(listOf("d", "logo:$project"), listOf("l", APP))
    private fun time(at: Long, now: Long) = at in 0..MAX_TIME && at <= now.coerceAtMost(Long.MAX_VALUE - 60) + 60
    private fun member(context: ProjectLogoContext, recipient: String): Boolean =
        context.definition.getValue("members").jsonArray.any { it.jsonObject.projectText("pubkey") == recipient }

    fun parse(content: String, context: ProjectLogoContext): ProjectLogoRecord? = runCatching {
        Projects.key(context.reference)
        if (content.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return null
        val p = Json.parseToJsonElement(content) as? JsonObject ?: return null
        if (p.keys != fields || p.projectRevision("v") != 1 || p.projectText("op") != "logo" ||
            p.projectText("project") != context.reference.project ||
            p.projectText("authority") != Projects.authority(context.reference, context.definition) ||
            p.projectRevision("version") == null || p.projectText("request")?.matches(request) != true) return null
        val image = if (p["image"] == JsonNull) null else LogoImages.read(p["image"]) ?: return null
        ProjectLogoRecord(p, image)
    }.getOrNull()

    fun record(event: NostrEvent, context: ProjectLogoContext, now: Long): ProjectLogoRecord? = runCatching {
        if (event.kind != Projects.KIND || event.pubkey != context.reference.owner ||
            event.content.toByteArray(Charsets.UTF_8).size > MAX_BYTES ||
            !time(event.createdAt, now) || event.tags != tags(context.reference.project) || !Events.verify(event)) return null
        parse(event.content, context)
    }.getOrNull()

    suspend fun sign(author: String, context: ProjectLogoContext, body: JsonObject, now: Long,
        signer: suspend (Int, Long, List<List<String>>, String) -> NostrEvent,
    ): NostrEvent {
        require(author == context.reference.owner) { "Only the project owner can change its logo" }
        require(now in 0..MAX_TIME && parse(body.toString(), context) != null) { "Invalid project logo update" }
        val content = body.toString()
        val tags = tags(context.reference.project)
        val signed = signer(Projects.KIND, now, tags, content)
        require(signed.content == content && signed.createdAt == now && record(signed, context, now) != null) {
            "The signer changed or refused the project logo"
        }
        return signed.copy(tags = signed.tags.map(List<String>::toList))
    }

    fun wrap(event: NostrEvent, recipient: String, context: ProjectLogoContext, now: Long): NostrEvent {
        require(record(event, context, now) != null && member(context, recipient)) {
            "This recipient is not included in the project logo update"
        }
        val ephemeral = Entropy.bytes(32)
        try {
            val key = Nip44.conversationKey(ephemeral, recipient.hexToBytes())
            val content = try { Nip44.encrypt(event.toCompactJson(), key) } finally { key.fill(0) }
            require(content.length <= MAX_WRAP_BYTES) { "The project logo exceeds the transport limit" }
            return Events.sign(ephemeral, Projects.WRAP_KIND, RoomDrops.randomPast(now).coerceAtLeast(0),
                listOf(listOf("p", recipient), listOf("l", APP)), content)
        } finally { ephemeral.fill(0) }
    }

    /** Invalid envelopes/directories never prompt a signer. Cancellation and
     *  decryption errors belong to the account adapter, as with Projects.unwrap. */
    suspend fun unwrap(event: NostrEvent, recipient: String, contexts: List<ProjectLogoContext>, now: Long,
        decrypt: suspend (String, String) -> String,
    ): NostrEvent? {
        val authorised = contexts.filter { context -> runCatching {
            Projects.authority(context.reference, context.definition)
            member(context, recipient)
        }.getOrDefault(false) }
        if (authorised.isEmpty() || event.kind != Projects.WRAP_KIND || event.content.length > MAX_WRAP_BYTES ||
            !time(event.createdAt, now) || event.tags != listOf(listOf("p", recipient), listOf("l", APP)) ||
            !Events.verify(event)) return null
        val plaintext = decrypt(event.pubkey, event.content)
        if (plaintext.toByteArray(Charsets.UTF_8).size > MAX_WRAP_BYTES) return null
        return runCatching {
            val inner = Projects.decodeEvent(Json.parseToJsonElement(plaintext)) ?: return null
            val project = (Json.parseToJsonElement(inner.content) as? JsonObject)?.projectText("project") ?: return null
            val context = authorised.find { it.reference.owner == inner.pubkey && it.reference.project == project } ?: return null
            inner.takeIf { record(it, context, now) != null }
        }.getOrNull()
    }
}
