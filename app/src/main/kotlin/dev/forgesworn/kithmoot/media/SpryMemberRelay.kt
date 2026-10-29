package dev.forgesworn.kithmoot.media

import android.content.Context
import dev.forgesworn.kithmoot.protocol.SpryDescriptor
import dev.forgesworn.kithmoot.protocol.SpryOffer
import dev.forgesworn.kithmoot.protocol.standard
import dev.forgesworn.kithmoot.storage.EncryptedRoomStorage
import dev.forgesworn.kithmoot.storage.RoomStorage

/**
 * Another member's own call relay: a built-in relay (a standard-profile Spry
 * node reached over `spry:` control) or a paired relay they share over
 * `https:`, as the web's `app/src/spry-room.ts` decides it. Pure rules, so
 * each is testable without a room.
 */

/** Another member's offer reached over `spry:` control: not this device's own paired node. */
internal fun spryForeignControl(offer: SpryOffer, ownEndpoint: String?): Boolean =
    offer.endpoint.startsWith("spry:") && offer.endpoint != ownEndpoint

/** Another member's offer over either transport: anything but this device's own paired node. */
internal fun spryMemberOffer(offer: SpryOffer, ownEndpoint: String?): Boolean =
    offer.endpoint != ownEndpoint

/**
 * The one participant whose own descriptor carries this endpoint and token
 * unmarked, or null when none or more than one does. Current clients
 * republish another member's offer only marked as a copy (`relayed`), and a
 * marked copy never names anyone: it says nothing verifiable about who runs
 * the relay. An older client's unmarked copy makes two unmarked publishers, so
 * nobody is named. Two devices of one person still name one host. The web's
 * `spryOfferHost`.
 */
internal fun spryOfferHost(descriptors: List<SpryDescriptor>, endpoint: String, token: String): String? =
    descriptors.filter { it.offer.endpoint == endpoint && it.offer.token == token && !it.offer.relayed }
        .map { it.participant }.toSet().singleOrNull()

/**
 * What this device republishes of an offer it holds: its own node's offer
 * unmarked, any other member's offer as a marked copy (the room keeps the
 * relay while its host is away), and an offer this device may not use not at
 * all, so a guest who said Not now does not copy it. The web's `#publishable`.
 */
internal fun spryRepublication(offer: SpryOffer, ownEndpoint: String?, usable: Boolean): SpryOffer? = when {
    !spryMemberOffer(offer, ownEndpoint) -> offer.copy(relayed = false)
    usable -> offer.copy(relayed = true)
    else -> null
}

/**
 * Whether this device may use the member relay [host] runs. Using one shows
 * that member this device's internet address. Without Hide my IP address the
 * address is in the call's candidates anyway, so yes; with it, only after a
 * one-time choice naming them ([consented]), or when the host is this person.
 * A relay the room cannot attribute to one member (null), such as one only a
 * marked copy carries, is not used.
 */
internal fun memberRelayAllowed(relayOnly: Boolean, self: String, host: String?, consented: Set<String>): Boolean =
    !relayOnly || host != null && (host == self || host in consented)

/**
 * Whether an offer may be redeemed and applied: this device's own node always;
 * another member's offer only while [allowed] says yes for its host, and, when
 * it is reached over `spry:` control, only from a standard node.
 */
internal fun spryOfferUsable(offer: SpryOffer, ownEndpoint: String?, allowed: () -> Boolean): Boolean =
    !spryMemberOffer(offer, ownEndpoint) || (!spryForeignControl(offer, ownEndpoint) || offer.standard) && allowed()

/** Another member's own relay (built-in, or shared over https) this device
 *  holds an offer for but may not use yet, and who offers it (null when no
 *  single participant does). */
internal data class BlockedMemberRelay(val endpoint: String, val host: String?)

/**
 * The members whose own call relay (whatever its transport) this person chose to use with Hide my IP
 * address on, each entry `<own participant>:<host participant>`. The choice
 * is once per host and kept on this device only, encrypted: never synced,
 * published or put in a link. The web keeps the same list as
 * `kithmoot.spry-consent.v1`, in the same shape as [SpryShareStore].
 */
internal class SpryConsentStore(private val storage: RoomStorage) {
    constructor(context: Context) : this(EncryptedRoomStorage(context, ALIAS, SpryShareStore.LIMIT * 140))

    companion object {
        const val ALIAS = "kithmoot.spry-consent.v1"
        private val ENTRY = Regex("^[0-9a-f]{64}:[0-9a-f]{64}$")
        private val lock = Any()
    }

    /** The hosts [participant] chose; empty when nothing was chosen or the list cannot be read. */
    fun hosts(participant: String): Set<String> = synchronized(lock) {
        SpryShareStore.decode(runCatching { storage.read() }.getOrNull())
            .filter { it.startsWith("$participant:") }.map { it.substringAfter(':') }.toSet()
    }

    fun save(participant: String, host: String) = synchronized(lock) {
        val entry = "$participant:$host"
        require(ENTRY.matches(entry)) { "Invalid participant." }
        val kept = (SpryShareStore.decode(storage.read()).filter { it != entry } + entry).takeLast(SpryShareStore.LIMIT)
        storage.write(SpryShareStore.encode(kept))
    }
}
