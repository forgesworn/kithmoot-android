package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.crypto.*
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.session.dmPolicy
import kotlinx.serialization.json.*

/** The NIP-17 envelope carries an invitation, while chat/calls/files use the
 * existing two-person room. The outer event never names the sender or room. */
data class ContactInvitation(val id: String, val sender: String, val recipient: String,
    val room: String, val link: String, val sentAt: Long, val node: Boolean = false)

object ContactInvitations {
    private val hex = Regex("^[0-9a-f]{64}$")
    private const val MAX_BYTES = 40_000
    fun read(rumor: JsonObject, self: String, now: Long): ContactInvitation? = try {
        val r = NostrEvent.fromJson(JsonObject(rumor + ("sig" to JsonPrimitive(""))))
        val p = r.tags.getOrNull(0)
        val marker = r.tags.getOrNull(1)
        if (r.kind != 14 || !hex.matches(r.pubkey) || !hex.matches(r.id) || r.createdAt < 0 || r.createdAt > now + 60 ||
            r.content.length > 12_000 || r.tags.size != 2 || p?.size != 2 || p[0] != "p" || !hex.matches(p[1]) || p[1] == r.pubkey ||
            marker?.size != 3 || marker[0] != "kithmoot-invitation" || !hex.matches(marker[2]) ||
            self != r.pubkey && self != p[1] ||
            Events.eventId(r.pubkey, r.createdAt, r.kind, r.tags, r.content) != r.id) null
        else {
            val policy = dmPolicy(r.pubkey, p[1])
            val valid = when (marker[1]) {
                "1" -> decodeInvitationUrl(r.content)?.let { it.invitation.persistent && it.policy == policy } == true
                "2" -> NodeRooms.parseLink(r.content, r.createdAt)?.let {
                    it.room == marker[2] && it.existing?.policy == policy.toJson()
                } == true
                else -> false
            }
            if (valid) ContactInvitation(r.id, r.pubkey, p[1], marker[2], r.content, r.createdAt, marker[1] == "2") else null
        }
    } catch (_: Exception) { null }

    fun rumor(sender: String, recipient: String, room: String, link: String, now: Long): JsonObject {
        val version = if (NodeRooms.parseLink(link, now) != null) "2" else "1"
        val tags = listOf(listOf("p", recipient), listOf("kithmoot-invitation", version, room))
        val event = NostrEvent(14, now, tags, link, sender, Events.eventId(sender, now, 14, tags, link), "")
        val rumor = JsonObject(event.toJson() - "sig")
        require(read(rumor, sender, now) != null) { "Invalid private conversation invitation" }
        return rumor
    }

    private fun shuffledTime(now: Long): Long {
        val random = Entropy.bytes(4)
        val number = random.fold(0L) { n, b -> (n shl 8) or (b.toLong() and 255) }
        return maxOf(0, now - number % 172800)
    }
    suspend fun wrap(rumor: JsonObject, actor: ParticipantSigner, recipient: String, now: Long, current: () -> Boolean): NostrEvent {
        val invite = requireNotNull(read(rumor, recipient, now))
        require(rumor["pubkey"] == JsonPrimitive(actor.pubkey) && current())
        val content = if (invite.node) actor.sealInboxText(recipient, rumor.toString(), current) else actor.nip44Encrypt(recipient, rumor.toString())
        check(current()) { "Contact permission changed" }
        val at = shuffledTime(now)
        val signed = if (invite.node) requireNotNull(actor.signInboxEvent(13, at, emptyList(), content, current)) else actor.sign(13, at, emptyList(), content)
        val seal = checkedSignedEvent(signed, actor.pubkey, 13, at, emptyList(), content)
        check(current()) { "Contact permission changed" }
        val ephemeral = Entropy.bytes(32)
        return try {
            val key = Nip44.conversationKey(ephemeral, recipient.hexToBytes())
            val payload = try { Nip44.encrypt(seal.toCompactJson(), key) } finally { key.fill(0) }
            check(payload.length <= MAX_BYTES)
            Events.sign(ephemeral, 1059, shuffledTime(now), listOf(listOf("p", recipient)), payload)
        } finally { ephemeral.fill(0) }
    }
    fun isWrap(event: NostrEvent, self: String, now: Long): Boolean = event.kind == 1059 &&
        event.tags == listOf(listOf("p", self)) && event.content.length <= MAX_BYTES && event.createdAt in 0..(now + 60) && Events.verify(event)

    suspend fun unwrap(event: NostrEvent, actor: ParticipantSigner, now: Long): JsonObject? = try {
        if (!isWrap(event, actor.pubkey, now)) null else {
            val seal = NostrEvent.fromJson(Json.parseToJsonElement(actor.nip44Decrypt(event.pubkey, event.content)))
            if (seal.kind != 13 || seal.tags.isNotEmpty() || seal.content.length > MAX_BYTES || seal.createdAt !in 0..(now + 60) || !Events.verify(seal)) null
            else {
                val rumor = Json.parseToJsonElement(actor.nip44Decrypt(seal.pubkey, seal.content)).jsonObject
                rumor.takeIf { it["pubkey"] == JsonPrimitive(seal.pubkey) && read(it, actor.pubkey, now) != null }
            }
        }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
}
