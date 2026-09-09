package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * A room's dead drops, as `nostr-deaddrop` defines the room case: an already
 * signed room event wrapped in a kind 1059 to a drop key, with no seal and
 * no re-signing. The wrap's plaintext is `{"e": <event>, "pad": "…"}`,
 * padded so it serialises to exactly the bucket, and its created_at is
 * drawn up to two days into the past as NIP-59 does. A filler is the same
 * shape around an event nobody can decrypt, so a relay cannot tell a slot
 * with something to say from one without. Written from the library's
 * README and checked against a wrap it produced.
 */
object RoomDrops {
    const val GIFT_WRAP_KIND: Int = 1059
    const val PAD_TAG: String = "pad"
    const val DEFAULT_BUCKET: Int = 768
    /** NIP-59 randomises a wrap's created_at up to this far into the past. */
    const val CREATED_AT_JITTER: Long = 2L * 24 * 3600

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    private val HEX64 = Regex("^[0-9a-f]{64}$")
    private val json = Json

    class RumorTooLarge(val length: Int, val bucket: Int) : IllegalArgumentException("rumor serialises to $length bytes, bucket is $bucket")

    /** A uniform draw over the last two days, the same distribution as the reference. */
    fun randomPast(now: Long, random: (Int) -> ByteArray = Entropy::bytes): Long {
        val b = random(4)
        val r = ((b[0].toLong() and 0xff) shl 24) or ((b[1].toLong() and 0xff) shl 16) or ((b[2].toLong() and 0xff) shl 8) or (b[3].toLong() and 0xff)
        return now - (r * CREATED_AT_JITTER) / 0x100000000L
    }

    private fun randomAlnum(n: Int, random: (Int) -> ByteArray): String {
        val b = random(n)
        val sb = StringBuilder(n)
        for (i in 0 until n) sb.append(ALPHABET[(b[i].toInt() and 0xff) % ALPHABET.length])
        return sb.toString()
    }

    /** The plaintext of a room drop: the inner event and padding, exactly `bucket` UTF-8 bytes. */
    fun plaintext(inner: NostrEvent, bucket: Int, random: (Int) -> ByteArray = Entropy::bytes): String {
        val probe = json.encodeToString(JsonObject.serializer(), buildJsonObject { put("e", inner.toJson()); put(PAD_TAG, "") })
        val base = probe.toByteArray(Charsets.UTF_8).size
        if (base > bucket) throw RumorTooLarge(base, bucket)
        return json.encodeToString(JsonObject.serializer(), buildJsonObject { put("e", inner.toJson()); put(PAD_TAG, randomAlnum(bucket - base, random)) })
    }

    /** Wrap a signed room event to a drop key. Nothing is re-signed. */
    fun createRoomDrop(inner: NostrEvent, dropPublicKey: String, bucket: Int = DEFAULT_BUCKET, now: Long, random: (Int) -> ByteArray = Entropy::bytes): NostrEvent {
        require(HEX64.matches(dropPublicKey)) { "drop key must be lower-case 64-hex" }
        val randomKey = random(32)
        val ck = Nip44.conversationKey(randomKey, dropPublicKey.hexToBytes())
        val createdAt = randomPast(now, random)
        return Events.sign(randomKey, GIFT_WRAP_KIND, createdAt, listOf(listOf("p", dropPublicKey)), Nip44.encrypt(plaintext(inner, bucket, random), ck, random(32)))
    }

    /** A filler for an empty slot: a plausible inner event under a thrown-away key, wrapped to a thrown-away drop key, the same size as a real drop. */
    fun createRoomFiller(innerKind: Int, bucket: Int = DEFAULT_BUCKET, now: Long, random: (Int) -> ByteArray = Entropy::bytes): NostrEvent {
        val throwaway = random(32)
        val content = Nip44.encrypt("0", Nip44.conversationKey(random(32), Schnorr.publicKey(random(32))), random(32))
        val inner = Events.sign(throwaway, innerKind, randomPast(now, random), listOf(listOf("d", random(32).toHex())), content)
        return createRoomDrop(inner, Schnorr.publicKeyHex(random(32)), bucket, now, random)
    }

    fun looksLikeWrap(e: NostrEvent): Boolean = e.kind == GIFT_WRAP_KIND && HEX64.matches(e.pubkey)

    /** Open a room drop and return the signed inner event, verified; null when it is not one for this key. */
    fun openRoomDrop(wrap: NostrEvent, dropPrivateKey: ByteArray): NostrEvent? {
        if (!looksLikeWrap(wrap)) return null
        return try {
            val ck = Nip44.conversationKey(dropPrivateKey, wrap.pubkey.hexToBytes())
            val parsed = json.parseToJsonElement(Nip44.decrypt(wrap.content, ck)).jsonObject
            val inner = NostrEvent.fromJson(parsed["e"]?.jsonObject ?: return null)
            if (!Events.verify(inner)) null else inner
        } catch (_: Exception) {
            null
        }
    }

    /** The drop key a wrap is addressed to, or null. */
    fun tagOf(wrap: NostrEvent): String? = wrap.tags.firstOrNull { it.size >= 2 && it[0] == "p" }?.get(1)?.takeIf { HEX64.matches(it) }

    internal fun JsonPrimitive.str(): String = content
}
