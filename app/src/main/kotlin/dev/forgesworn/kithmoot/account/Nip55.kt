package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.protocol.NostrEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * NIP-55, the Android signer intent, as Amber, Cambium and the others speak
 * it: `nostrsigner:<payload>` opened with `type`, `id`, `current_user` and
 * `permissions` extras; the answer comes back as `result` (a public key, or
 * a signature), `event` (the signed event, for `sign_event`) and `package`.
 *
 * This file is the wire contract only, with no Android type in it, so that
 * what is put in and read out can be checked on the JVM. The intents are
 * built in [Nip55Signer].
 */
object Nip55 {
    const val SCHEME = "nostrsigner"
    const val TYPE_GET_PUBLIC_KEY = "get_public_key"
    const val TYPE_SIGN_EVENT = "sign_event"
    const val TYPE_NIP44_ENCRYPT = "nip44_encrypt"
    const val TYPE_NIP44_DECRYPT = "nip44_decrypt"

    /** What KithMoot asks for up front: a credential per room, and the person's own encryption. */
    fun permissions(): String = JsonArray(listOf(
        buildJsonObject { put("type", TYPE_SIGN_EVENT); put("kind", 20460) },
        buildJsonObject { put("type", TYPE_NIP44_ENCRYPT) },
        buildJsonObject { put("type", TYPE_NIP44_DECRYPT) },
    )).toString()

    /** A signer answers `get_public_key` with an npub or hex; both are the same key. */
    fun publicKeyFromResult(result: String?): String? = result?.let(::publicKeyFrom)

    /**
     * The signed event out of a `sign_event` answer. Amber and Cambium put
     * the whole event in `event`; some older signers put only the signature
     * in `result` (or `signature`), in which case the event is the one we
     * sent with that signature on it.
     */
    fun signedEventFromResult(event: String?, signature: String?, unsignedJson: String): NostrEvent? {
        event?.let { json -> runCatching { NostrEvent.fromJson(Json.parseToJsonElement(json)) }.getOrNull()?.let { return it } }
        val sig = signature?.trim()?.takeIf { it.matches(Regex("[0-9a-fA-F]{128}")) } ?: return null
        val unsigned = runCatching { Json.parseToJsonElement(unsignedJson) }.getOrNull() ?: return null
        return runCatching {
            NostrEvent.fromJson(buildJsonObject {
                for ((key, value) in unsigned.let { it as kotlinx.serialization.json.JsonObject }) put(key, value)
                put("sig", sig.lowercase())
            })
        }.getOrNull()
    }
}
