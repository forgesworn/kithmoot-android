package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.hexToBytes
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** A scanned Bothy code with its bearer secret deliberately redacted from logs. */
class BothyPairing private constructor(
    val card: ByteArray,
    /** Bothy's Nostr identity, used by the claim/status authority. */
    val bothyPubkey: String,
    /** The distinct ForgeSworn Link transport identity signed by [card]. */
    val linkNodeId: String,
    val pairingSecret: ByteArray,
    val expiresAt: Long,
    val role: String,
    val name: String,
) {
    override fun toString() = "BothyPairing(bothy=$bothyPubkey, link=$linkNodeId, expiresAt=$expiresAt, role=$role, name=$name, secret=<redacted>)"

    companion object {
        private const val MAX_TTL_SECONDS = 600L
        private val URI_BODY = Regex("[A-Za-z0-9_-]{1,8192}")
        private val STANDARD_BASE64 = Regex("[A-Za-z0-9+/]+={0,2}")
        private val HEX_64 = Regex("[0-9a-f]{64}")
        private val SECRET = Regex("[0-9a-f]{32}")

        /** Refuses malformed, stale or differently-pinned QR payloads before Link sees a secret. */
        fun parse(uri: String, now: Long): BothyPairing {
            require(now in 0..LinkCards.MAX_SAFE)
            require(uri.startsWith("bothy:")) { "This is not a Bothy pairing code." }
            val encoded = uri.removePrefix("bothy:")
            require(URI_BODY.matches(encoded)) { "The pairing code is not valid." }
            val root = Json.parseToJsonElement(Base64.getUrlDecoder().decode(encoded).decodeToString()).jsonObject
            require(root.keys == setOf("v", "card", "bothy", "secret", "exp", "role", "name")) { "The pairing code has an unsupported shape." }
            require(root.getValue("v").jsonPrimitive.long == 2L) { "This pairing code has an unsupported version." }
            val encodedCard = root.getValue("card").jsonPrimitive.content
            require(STANDARD_BASE64.matches(encodedCard)) { "The pairing card is not valid." }
            val card = Base64.getDecoder().decode(encodedCard)
            require(Base64.getEncoder().encodeToString(card) == encodedCard) { "The pairing card is not canonical." }
            val verified = LinkCards.verify(card, now)
            require(verified is LinkVerdict.Ok) { "The pairing card was refused." }
            val bothy = root.getValue("bothy").jsonPrimitive.content
            require(HEX_64.matches(bothy)) { "The Bothy identity is not valid." }
            val secretHex = root.getValue("secret").jsonPrimitive.content
            require(SECRET.matches(secretHex)) { "The pairing secret is not valid." }
            val expiresAt = root.getValue("exp").jsonPrimitive.long
            require(expiresAt > now && expiresAt - now <= MAX_TTL_SECONDS) { "This pairing code has expired. Show it again on Bothy." }
            val role = root.getValue("role").jsonPrimitive.content
            require(role == "phone" || role == "box") { "The pairing role is not valid." }
            val name = root.getValue("name").jsonPrimitive.content
            require(name.isNotBlank() && name.length <= 80 && name.none { it.isISOControl() }) { "The pairing name is not valid." }
            return BothyPairing(card, bothy, verified.card.nodeId, secretHex.hexToBytes().also { require(it.size == 16) }, expiresAt, role, name)
        }
    }
}
