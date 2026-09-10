package dev.forgesworn.kithmoot.relay

import dev.forgesworn.kithmoot.protocol.NostrEvent

/**
 * The small product boundary around NIP-42. The relay pool knows neither
 * account secrets nor consent persistence: it asks the currently authorised
 * product adapter to sign one challenge for one exact relay URL.
 */
interface RelayAuthenticator {
    val pubkey: String
    suspend fun sign(url: String, challenge: String): NostrEvent
}

/** Returns null for ordinary relays and for a withdrawn sheltered consent. */
fun interface RelayAuthenticatorProvider {
    fun forUrl(url: String): RelayAuthenticator?
}
