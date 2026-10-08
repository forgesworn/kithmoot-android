package dev.forgesworn.kithmoot.account

import kotlinx.serialization.json.*
import java.util.UUID

const val CULT_REGISTRY = "https://raw.githubusercontent.com/600-000-000-000/600000000000/main/.well-known/nostr.json"

/** Sign locally, without publication or sending the account key to the registry. */
suspend fun unlockCultPack(signer: ParticipantSigner, registry: suspend () -> String, now: Long = System.currentTimeMillis() / 1000): Boolean {
    val tags = listOf(listOf("u", CULT_REGISTRY), listOf("method", "GET"), listOf("challenge", UUID.randomUUID().toString()))
    checkedSignedEvent(signer.sign(27235, now, tags, ""), signer.pubkey, 27235, now, tags, "")
    val text = registry()
    require(text.length <= 128_000) { "The pack membership registry is too large." }
    val names = Json.parseToJsonElement(text).jsonObject["names"] as? JsonObject ?: return false
    return names.values.any { value -> (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { it.matches(Regex("[0-9a-fA-F]{64}")) && it.equals(signer.pubkey, true) } == true }
}
