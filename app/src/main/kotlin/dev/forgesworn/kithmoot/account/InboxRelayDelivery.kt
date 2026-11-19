package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.relay.PublicationNotOfferedException
import dev.forgesworn.kithmoot.relay.RelayAuthenticator
import dev.forgesworn.kithmoot.relay.RelayAuthenticatorProvider
import dev.forgesworn.kithmoot.relay.RelayPool
import dev.forgesworn.kithmoot.relay.RelaySocketFactory
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive

/**
 * A one-use NIP-42 identity for writing a gift wrap to someone else's inbox relay.
 *
 * Some inbox relays (auth.nostr1.com, for one) refuse every write until the socket
 * authenticates, but accept any key. Authenticating as the account would tell the
 * recipient's relay who is writing to them, which the gift wrap exists to hide, and
 * would ask the signer for every delivery. A fresh key for each delivery does
 * neither. Signing is local; [close] wipes the key.
 */
internal class EphemeralInboxAuthenticator(private val now: () -> Long) : RelayAuthenticator, AutoCloseable {
    private val secret = Entropy.bytes(32)
    override val pubkey: String = Schnorr.publicKeyHex(secret)

    override suspend fun sign(url: String, challenge: String): NostrEvent =
        Events.sign(secret, 22242, now(), authenticationTags(url, challenge), "")

    override fun close() = secret.fill(0)
}

/**
 * Offers one gift wrap to each of [relays] on its own pool, in parallel, and returns
 * the relays that accepted it. One relay answering first must not cut short another
 * that is still authenticating.
 */
internal suspend fun deliverToInboxRelays(event: NostrEvent, relays: List<String>, sockets: RelaySocketFactory,
    nowSeconds: () -> Long, current: () -> Boolean, log: (String) -> Unit = {}): List<String> = coroutineScope {
    relays.map { url -> async { url.takeIf { deliverToInboxRelay(event, it, sockets, nowSeconds, current, log) } } }
        .awaitAll().filterNotNull()
}

/** One gift wrap to one inbox relay. A relay that challenges is answered with an
 *  [EphemeralInboxAuthenticator]. An unaccepted first offer is repeated once, only
 *  after this connection has authenticated; a relay that never challenges is not asked twice. */
internal suspend fun deliverToInboxRelay(event: NostrEvent, url: String, sockets: RelaySocketFactory,
    nowSeconds: () -> Long, current: () -> Boolean, log: (String) -> Unit = {}): Boolean = coroutineScope {
    val auth = EphemeralInboxAuthenticator(nowSeconds)
    val pool = RelayPool(listOf(url), sockets, this, readRelays = emptySet(),
        authenticators = RelayAuthenticatorProvider { if (it == url && current()) auth else null },
        publicAuthOnChallenge = true)
    pool.start()
    try {
        for (attempt in 1..2) {
            val accepted = try { pool.publishConfirmedGuarded(event, pool.publicationGeneration(), current, INBOX_WRITE_TIMEOUT_MS) }
                catch (_: TimeoutCancellationException) { false }
                catch (_: PublicationNotOfferedException) { false }
            ensureActive()
            val write = pool.health.value[url]?.write
            log("inbox relay ${url.substringAfter("://")} attempt=$attempt accepted=$accepted write=$write")
            if (accepted) return@coroutineScope true
            // A challenge can close the link between connecting and the write, or refuse
            // the write before it is answered. Either way, offer it again once authenticated.
            if (attempt == 2 || !pool.awaitAuthentication(url, INBOX_AUTH_TIMEOUT_MS)) return@coroutineScope false
        }
        false
    } finally { pool.stop(); auth.close() }
}

private const val INBOX_WRITE_TIMEOUT_MS = 8_000L
private const val INBOX_AUTH_TIMEOUT_MS = 5_000L
