package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.NostrEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.util.Base64

data class NostrDirectMessage(val id: String, val author: String, val peer: String, val text: String,
    val sentAt: Long, val expiresAt: Long? = null)

/** Two-person NIP-17 text. Signer, peer and route are supplied by the account,
 * never by event content. Invitations remain in the invitation journal. */
internal object NostrDirectMessages {
    private val hex = Regex("[0-9a-f]{64}")
    private val fields = setOf("id", "pubkey", "created_at", "kind", "tags", "content")
    private const val MAX_WIRE = 128 * 1024
    const val HISTORY_SKEW = 172_800L

    private fun expiration(event: NostrEvent): Long {
        val tags = event.tags.filter { it.firstOrNull() == "expiration" }
        if (tags.isEmpty()) return Long.MAX_VALUE
        return tags.singleOrNull()?.takeIf { it.size == 2 }?.get(1)?.takeIf { it.matches(Regex("[0-9]{1,16}")) }
            ?.toLongOrNull() ?: 0
    }

    private fun payload(value: String): Boolean = value.length in 132..MAX_WIRE &&
        runCatching { Base64.getDecoder().decode(value).let { it.size >= 99 && it[0] == 2.toByte() } }.getOrDefault(false)

    fun addressedWrap(event: NostrEvent, self: String, now: Long): Boolean =
        event.kind == 1059 && event.createdAt in 0..now + 60 && event.tags.size <= 128 &&
            event.tags.filter { it.firstOrNull() == "p" }.let { it.size == 1 && it[0].getOrNull(1) == self } &&
            expiration(event) > now && payload(event.content) && Events.verify(event)

    suspend fun open(
        event: NostrEvent, self: String, now: Long, current: () -> Boolean,
        blocked: (String) -> Boolean, decrypt: suspend (String, String) -> String?,
        keepNodeInvitation: suspend (JsonObject) -> Unit = {},
    ): NostrDirectMessage? {
        if (!current() || !addressedWrap(event, self, now)) return null
        val seal = signed(decrypt(event.pubkey, event.content) ?: throw SignerException("Allow NIP-44 decryption in your signer.")) ?: return null
        if (!current() || seal.kind != 13 || seal.tags.isNotEmpty() || seal.createdAt !in 0..now + 60 ||
            !payload(seal.content) || !Events.verify(seal) || blocked(seal.pubkey)) return null
        val plain = decrypt(seal.pubkey, seal.content) ?: throw SignerException("Allow NIP-44 decryption in your signer.")
        if (!current() || plain.length > MAX_WIRE) return null
        val obj = runCatching { Json.parseToJsonElement(plain).jsonObject }.getOrNull() ?: return null
        if (obj.keys != fields) return null
        val rumor = runCatching {
            NostrEvent.fromJson(JsonObject(obj + ("sig" to JsonPrimitive(""))))
        }.getOrNull() ?: return null
        if (rumor.kind != 14 || rumor.pubkey != seal.pubkey || rumor.createdAt !in 0..now + 60 ||
            rumor.content.toByteArray().size > 64 * 1024 ||
            Events.eventId(rumor.pubkey, rumor.createdAt, rumor.kind, rumor.tags, rumor.content) != rumor.id) return null
        if (rumor.tags.any { it.firstOrNull() == "kithmoot-invitation" }) {
            val invite = ContactInvitations.read(obj, self, now)?.takeIf { it.node } ?: return null
            val peer = if (invite.sender == self) invite.recipient else invite.sender
            if (current() && !blocked(peer) && expiration(event) > now) keepNodeInvitation(obj)
            return null
        }
        val recipients = rumor.tags.filter { it.firstOrNull() == "p" }.map { it.getOrNull(1) }
        if (recipients.isEmpty() || recipients.any { it == null || !hex.matches(it) }) return null
        val members = (recipients.filterNotNull() + rumor.pubkey).toSet()
        if (members.size != 2 || self !in members) return null
        val peer = members.single { it != self }
        val expires = minOf(expiration(rumor), expiration(event))
        if (expires <= now || blocked(peer)) return null
        return NostrDirectMessage(rumor.id, rumor.pubkey, peer, rumor.content, rumor.createdAt, expires.takeUnless { it == Long.MAX_VALUE })
    }

    private fun signed(value: String): NostrEvent? {
        if (value.length > MAX_WIRE) return null
        return runCatching {
            val obj = Json.parseToJsonElement(value).jsonObject
            if (obj.keys == fields + "sig") NostrEvent.fromJson(obj) else null
        }.getOrNull()
    }

    suspend fun prepare(actor: ParticipantSigner, peer: String, text: String, replyTo: String?,
        now: Long, current: () -> Boolean): Pair<NostrDirectMessage, List<NostrEvent>> {
        require(hex.matches(peer) && peer != actor.pubkey && (replyTo == null || hex.matches(replyTo)))
        require(text.isNotBlank() && text.length <= 4096) { "Write a message of up to 4,096 characters." }
        val tags = listOf(listOf("p", peer)) + listOfNotNull(replyTo?.let { listOf("e", it) })
        val id = Events.eventId(actor.pubkey, now, 14, tags, text)
        val rumor = unsignedEventJson(actor.pubkey, 14, now, tags, text)
        val wraps = listOf(peer, actor.pubkey).map { recipient ->
            check(current()) { "The account or contact changed." }
            val sealed = actor.sealInboxText(recipient, rumor, current)
            check(current()) { "The account or contact changed." }
            val at = backdated(now)
            val signed = requireNotNull(actor.signInboxEvent(13, at, emptyList(), sealed, current))
            val seal = checkedSignedEvent(signed, actor.pubkey, 13, at, emptyList(), sealed)
            check(current()) { "The account or contact changed." }
            val ephemeral = Entropy.bytes(32)
            try {
                val key = Nip44.conversationKey(ephemeral, recipient.hexToBytes())
                val body = try { Nip44.encrypt(seal.toCompactJson(), key) } finally { key.fill(0) }
                Events.sign(ephemeral, 1059, backdated(now), listOf(listOf("p", recipient)), body)
            } finally { ephemeral.fill(0) }
        }
        return NostrDirectMessage(id, actor.pubkey, peer, text, now) to wraps
    }

    private fun backdated(now: Long): Long {
        val value = Entropy.bytes(4).fold(0L) { n, byte -> (n shl 8) or (byte.toLong() and 255) }
        return maxOf(0, now - value % HISTORY_SKEW)
    }
}
