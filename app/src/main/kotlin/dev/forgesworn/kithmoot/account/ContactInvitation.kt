package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.crypto.*
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.session.dmPolicy
import kotlinx.serialization.json.*

/** The NIP-17 envelope carries an invitation, while chat/calls/files use the
 * existing two-person room. The outer event never names the sender or room. */
data class ContactInvitation(val id: String, val sender: String, val recipient: String,
    val room: String, val link: String, val sentAt: Long)

object ContactInvitations {
    private val hex = Regex("^[0-9a-f]{64}$")
    private const val MAX_BYTES = 40_000
    fun read(rumor: JsonObject, self: String, now: Long): ContactInvitation? = try {
        val r = NostrEvent.fromJson(JsonObject(rumor + ("sig" to JsonPrimitive(""))))
        val p = r.tags.getOrNull(0)
        val marker = r.tags.getOrNull(1)
        val link = decodeInvitationUrl(r.content)
        if (r.kind != 14 || !hex.matches(r.pubkey) || !hex.matches(r.id) || r.createdAt < 0 || r.createdAt > now + 60 ||
            r.content.length > 12_000 || r.tags.size != 2 || p?.size != 2 || p[0] != "p" || !hex.matches(p[1]) || p[1] == r.pubkey ||
            marker?.size != 3 || marker[0] != "kithmoot-invitation" || marker[1] != "1" || !hex.matches(marker[2]) ||
            self != r.pubkey && self != p[1] || link == null || !link.invitation.persistent || link.policy != dmPolicy(r.pubkey, p[1]) ||
            Events.eventId(r.pubkey, r.createdAt, r.kind, r.tags, r.content) != r.id) null
        else ContactInvitation(r.id, r.pubkey, p[1], marker[2], r.content, r.createdAt)
    } catch (_: Exception) { null }

    fun rumor(sender: String, recipient: String, room: String, link: String, now: Long): JsonObject {
        val tags = listOf(listOf("p", recipient), listOf("kithmoot-invitation", "1", room))
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
        require(rumor["pubkey"] == JsonPrimitive(actor.pubkey) && read(rumor, recipient, now) != null && current())
        val content = actor.nip44Encrypt(recipient, rumor.toString())
        check(current()) { "Contact permission changed" }
        val at = shuffledTime(now)
        val seal = checkedSignedEvent(actor.sign(13, at, emptyList(), content), actor.pubkey, 13, at, emptyList(), content)
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
