package dev.forgesworn.kithmoot.media

import android.content.Context
import dev.forgesworn.kithmoot.protocol.SpryDescriptor
import dev.forgesworn.kithmoot.protocol.SpryOffer
import dev.forgesworn.kithmoot.protocol.standard
import dev.forgesworn.kithmoot.storage.EncryptedRoomStorage
import dev.forgesworn.kithmoot.storage.RoomStorage

/**
 * Another member's own call relay (a built-in relay: a standard-profile Spry
 * node reached over `spry:` control), as the web's `app/src/spry-room.ts`
 * decides it. Pure rules, so each is testable without a room.
 */

/** Another member's offer reached over `spry:` control: not this device's own paired node. */
internal fun spryForeignControl(offer: SpryOffer, ownEndpoint: String?): Boolean =
    offer.endpoint.startsWith("spry:") && offer.endpoint != ownEndpoint

/**
 * The one participant whose descriptor offers this `spry:` endpoint, or null
 * when none or more than one does. Current clients never republish another
 * member's `spry:` offer, so only its host publishes it; a copy from anyone
 * else makes the host unknown rather than misnamed. Two devices of one person
 * still name one host. The web's `spryOfferHost`.
 */
internal fun spryOfferHost(descriptors: List<SpryDescriptor>, endpoint: String): String? =
    descriptors.filter { it.offer.endpoint == endpoint }.map { it.participant }.toSet().singleOrNull()

/**
 * Whether this device may use the member relay [host] offers. Using one shows
 * that member this device's internet address. Without Hide my IP address the
 * address is in the call's candidates anyway, so yes; with it, only after a
 * one-time choice naming them ([consented]), or when the host is this person.
 * A relay the room cannot attribute to one member (null) is not used.
 */
internal fun memberRelayAllowed(relayOnly: Boolean, self: String, host: String?, consented: Set<String>): Boolean =
    !relayOnly || host != null && (host == self || host in consented)

/**
 * Whether an offer may be redeemed and applied: this device's own node or an
 * https offer always; another member's `spry:` offer only from a standard
 * node, and only while [allowed] says yes for its host.
 */
internal fun spryOfferUsable(offer: SpryOffer, ownEndpoint: String?, allowed: () -> Boolean): Boolean =
    !spryForeignControl(offer, ownEndpoint) || offer.standard && allowed()

/** Another member's built-in relay this device holds an offer for but may
 *  not use yet, and who offers it (null when no single participant does). */
internal data class BlockedMemberRelay(val endpoint: String, val host: String?)

/**
 * The members whose own call relay this person chose to use with Hide my IP
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
