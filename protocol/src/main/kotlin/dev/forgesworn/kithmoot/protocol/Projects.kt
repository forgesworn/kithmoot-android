package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import kotlinx.serialization.json.*
import java.net.URI
import java.util.Base64

data class ProjectReference(val owner: String, val project: String) {
    val key: String get() = Projects.key(this)
}

/** A verified directory record. It grants no room, context, tool or execution access. */
class ProjectRecord internal constructor(val body: JsonObject) {
    val op: String get() = body.projectText("op")!!
    val project: String get() = body.projectText("project")!!
    val revision: Int get() = body.projectRevision("revision")!!
    val parents: List<String> get() = body.getValue("parents").jsonArray.map { it.jsonPrimitive.content }
    val definition: JsonObject? get() = body["definition"] as? JsonObject
    fun reference(author: String): ProjectReference = ProjectReference(if (op == "follow") body.projectText("owner")!! else author, project)
}

internal fun JsonObject.projectText(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.only(vararg fields: String) = keys.all { it in fields }
private fun JsonObject.projectBoolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
internal fun JsonObject.projectRevision(key: String): Int? {
    // JSON numbers such as 1.0 or 1e0 are integers to the web reader too.
    val n = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull ?: return null
    return n.takeIf { it >= 1 && it <= 1_000_000 && it == it.toInt().toDouble() }?.toInt()
}

/** Wire-compatible with KithMoot src/projects.ts. All checks precede decryption/signing callbacks. */
object Projects {
    const val APP = "kithmoot.projects.v1"
    const val KIND = 30078
    const val WRAP_KIND = 1059
    const val MAX_BYTES = 32_768
    const val MAX_WRAP_BYTES = 100_000
    private val hex = Regex("[0-9a-f]{64}")
    private val request = Regex("[a-zA-Z0-9_-]{16,80}")
    private fun hash(value: String) = Digests.sha256(value.toByteArray(Charsets.UTF_8)).toHex()
    private fun label(value: String?) = value != null && value.isNotEmpty() && value.length <= 64 && DisplayName.sanitise(value) == value
    private fun JsonObject.hasHex(key: String) = projectText(key)?.matches(hex) == true

    fun key(ref: ProjectReference): String {
        require(hex.matches(ref.owner) && hex.matches(ref.project)) { "Invalid project reference" }
        return "${ref.owner}:${ref.project}"
    }

    fun id(owner: String, requestId: String): String {
        require(hex.matches(owner) && request.matches(requestId)) { "Invalid project creation request" }
        return hash("$APP:$owner:$requestId")
    }

    /** A directory may carry invitations, never an identity-bearing Android pairing link. */
    private fun invitation(link: String): Boolean {
        return try {
            val uri = URI(link)
            val fragment = uri.rawFragment ?: return false
            val payload = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(fragment), Charsets.UTF_8)).jsonObject
            uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrEmpty() && uri.rawUserInfo == null &&
                // Android pairing fields k/x and legacy traffic key s are private credentials.
                listOf("k", "x", "s", "c").none { it in payload } &&
                listOf("r", "i").all { (payload[it] as? JsonArray)?.size?.let { size -> size <= 8 } != false } &&
                decodeInvitationUrl(link)?.invitation?.persistent == true
        } catch (_: Exception) { false }
    }

    fun validDefinition(value: JsonObject, owner: String): Boolean {
        return try {
            if (!value.only("name", "members", "rooms", "archived", "authorityRevision") || !label(value.projectText("name")) ||
                value.projectBoolean("archived") == null || value.projectRevision("authorityRevision") == null) return false
            val members = value["members"] as? JsonArray ?: return false
            val rooms = value["rooms"] as? JsonArray ?: return false
            if (members.size !in 1..64 || rooms.size > 32) return false
            val keys = mutableSetOf<String>()
            for (raw in members) {
                val m = raw as? JsonObject ?: return false
                if (!m.only("pubkey", "kind", "name", "epoch") || !m.hasHex("pubkey") || !keys.add(m.projectText("pubkey")!!) ||
                    m.projectRevision("epoch") == null || m.projectText("kind") !in listOf("person", "agent") ||
                    ("name" in m && !label(m.projectText("name")))) return false
            }
            if (owner !in keys) return false
            val roomIds = mutableSetOf<String>()
            for (raw in rooms) {
                val room = raw as? JsonObject ?: return false
                val link = room.projectText("link") ?: return false
                if (!room.only("room", "name", "link") || !room.hasHex("room") || !roomIds.add(room.projectText("room")!!) ||
                    !label(room.projectText("name")) || link.length > 4096 || !invitation(link)) return false
            }
            true
        } catch (_: Exception) { false }
    }

    /** Metadata changes preserve this digest; membership epochs and authority revisions do not. */
    fun authority(ref: ProjectReference, definition: JsonObject): String {
        key(ref)
        require(validDefinition(definition, ref.owner)) { "Invalid shared project" }
        return hash(buildJsonObject {
            put("app", APP); put("owner", ref.owner); put("project", ref.project)
            put("archived", definition.projectBoolean("archived")!!)
            put("authorityRevision", definition.projectRevision("authorityRevision")!!)
            put("members", JsonArray(definition.getValue("members").jsonArray.map { it.jsonObject }
                .sortedBy { it.projectText("pubkey") }.map { member -> buildJsonObject {
                    put("pubkey", member.projectText("pubkey")!!); put("kind", member.projectText("kind")!!)
                    put("epoch", member.projectRevision("epoch")!!)
                } }))
            put("rooms", JsonArray(definition.getValue("rooms").jsonArray.map { it.jsonObject.projectText("room")!! }
                .sorted().map(::JsonPrimitive)))
        }.toString())
    }

    fun parse(content: String, author: String): ProjectRecord? {
        return try {
            if (!hex.matches(author) || content.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return null
            val p = Json.parseToJsonElement(content) as? JsonObject ?: return null
            val revision = p.projectRevision("revision") ?: return null
            val parents = p["parents"] as? JsonArray ?: return null
            if (p.projectRevision("v") != 1 || !p.hasHex("project") || p.projectText("request")?.matches(request) != true ||
                parents.size > 8 || parents.any { it !is JsonPrimitive || !it.isString || !it.content.matches(hex) } ||
                parents.toSet().size != parents.size || (if (revision == 1) parents.isNotEmpty() else parents.isEmpty())) return null
            val common = arrayOf("v", "project", "revision", "request", "parents", "op")
            val valid = when (p.projectText("op")) {
                "snapshot" -> {
                    val definition = p["definition"] as? JsonObject ?: return null
                    p.only(*common, "definition") && validDefinition(definition, author) &&
                        definition.projectRevision("authorityRevision")!! <= revision &&
                        definition.getValue("members").jsonArray.all { it.jsonObject.projectRevision("epoch")!! <= revision }
                }
                "withdraw" -> p.only(*common, "recipient") && p.hasHex("recipient") && p.projectText("recipient") != author
                "follow" -> p.only(*common, "owner", "joined", "membership", "invitation") && p.hasHex("owner") &&
                    p.projectBoolean("joined") != null && p.projectRevision("membership") != null && p.hasHex("invitation")
                else -> false
            }
            if (valid) ProjectRecord(p) else null
        } catch (_: Exception) { null }
    }

    fun record(event: NostrEvent, now: Long): ProjectRecord? {
        if (event.kind != KIND || event.content.toByteArray(Charsets.UTF_8).size > MAX_BYTES ||
            event.createdAt < 0 || event.createdAt > 9_007_199_254_740_991L ||
            event.createdAt > now.coerceAtMost(Long.MAX_VALUE - 60) + 60 || !Events.verify(event)) return null
        val body = parse(event.content, event.pubkey) ?: return null
        return body.takeIf { event.tags == listOf(listOf("d", body.project), listOf("l", APP)) }
    }

    fun forRecipient(event: NostrEvent, recipient: String, now: Long): ProjectRecord? {
        val p = record(event, now) ?: return null
        val included = when (p.op) {
            "snapshot" -> p.definition!!.getValue("members").jsonArray.any { it.jsonObject.projectText("pubkey") == recipient }
            "withdraw" -> p.body.projectText("recipient") == recipient
            else -> event.pubkey == recipient
        }
        return p.takeIf { included }
    }

    suspend fun sign(author: String, body: JsonObject, now: Long,
        signer: suspend (Int, Long, List<List<String>>, String) -> NostrEvent,
    ): NostrEvent {
        val content = body.toString()
        val parsed = requireNotNull(parse(content, author)) { "Invalid shared project update" }
        require(now in 0..9_007_199_254_740_991L) { "Invalid project time" }
        val tags = listOf(listOf("d", parsed.project), listOf("l", APP))
        val signed = signer(KIND, now, tags, content)
        require(signed.pubkey == author && signed.kind == KIND && signed.createdAt == now && signed.tags == tags &&
            signed.content == content && record(signed, now) != null) { "The signer changed or refused the project update" }
        return signed.copy(tags = signed.tags.map { it.toList() })
    }

    fun wrap(event: NostrEvent, recipient: String, now: Long): NostrEvent {
        require(hex.matches(recipient) && forRecipient(event, recipient, now) != null) { "Recipient is not included in project update" }
        val ephemeral = Entropy.bytes(32)
        try {
            val key = Nip44.conversationKey(ephemeral, recipient.hexToBytes())
            val content = try { Nip44.encrypt(event.toCompactJson(), key) } finally { key.fill(0) }
            require(content.length <= MAX_WRAP_BYTES) { "Encrypted project exceeds transport limit" }
            return Events.sign(ephemeral, WRAP_KIND, RoomDrops.randomPast(now).coerceAtLeast(0),
                listOf(listOf("p", recipient), listOf("l", APP)), content)
        } finally { ephemeral.fill(0) }
    }

    fun isWrap(event: NostrEvent, recipient: String): Boolean = event.kind == WRAP_KIND &&
        event.content.length <= MAX_WRAP_BYTES && event.tags == listOf(listOf("p", recipient), listOf("l", APP)) && Events.verify(event)

    /** Strict wire types: the general Nostr reader accepts some numeric strings. */
    fun decodeEvent(value: JsonElement): NostrEvent? {
        return try {
            val p = value as? JsonObject ?: return null
            if (listOf("content", "pubkey", "id", "sig").any { p.projectText(it) == null }) return null
            fun number(key: String, max: Double): Long? {
                val n = (p[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull ?: return null
                return n.takeIf { it >= 0 && it <= max && it == it.toLong().toDouble() }?.toLong()
            }
            val kind = number("kind", Int.MAX_VALUE.toDouble())?.toInt() ?: return null
            val createdAt = number("created_at", 9_007_199_254_740_991.0) ?: return null
            val tags = p["tags"] as? JsonArray ?: return null
            if (tags.any { tag -> tag !is JsonArray || tag.any { it !is JsonPrimitive || !it.isString } }) return null
            NostrEvent(kind, createdAt, tagsFromJson(tags), p.projectText("content")!!, p.projectText("pubkey")!!,
                p.projectText("id")!!, p.projectText("sig")!!)
        } catch (_: Exception) { null }
    }

    suspend fun unwrap(event: NostrEvent, recipient: String, now: Long,
        decrypt: suspend (String, String) -> String,
    ): NostrEvent? {
        if (!isWrap(event, recipient)) return null
        // Cancellation from an external signer is owned by the caller, not treated as bad ciphertext.
        val plaintext = decrypt(event.pubkey, event.content)
        if (plaintext.toByteArray(Charsets.UTF_8).size > MAX_WRAP_BYTES) return null
        val inner = try { decodeEvent(Json.parseToJsonElement(plaintext)) } catch (_: Exception) { null } ?: return null
        return inner.takeIf { forRecipient(it, recipient, now) != null }
    }
}
