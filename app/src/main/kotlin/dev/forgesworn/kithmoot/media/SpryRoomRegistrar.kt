package dev.forgesworn.kithmoot.media

import dev.forgesworn.kithmoot.protocol.SpryOffer
import dev.forgesworn.kithmoot.session.SpryRosterRow
import kotlin.random.Random

/** Keys registered per participant, so a link holder cannot churn devices to
 *  push genuine members out of a grant's 64 entries. A person's third device
 *  is let in by a member vouching for it on a call. */
internal const val SPRY_KEYS_PER_PARTICIPANT = 2
/** Stop adding other members' keys once the node reports this many entries,
 *  leaving room under its 64 for the owner's own devices and vouches. */
internal const val SPRY_GRANT_HEADROOM = 56
/** At most this many keys each way in one `/v1/grants/devices` request. */
internal const val SPRY_KEYS_PER_REQUEST = 32
/** A node without the room-grant paths is not asked again for this long. */
internal const val SPRY_UNSUPPORTED_MS = 60L * 60 * 1000
/** Retries after a refused redemption in the first minute of an offer or call. */
internal val SPRY_RETRY_MS = longArrayOf(3_000, 10_000, 30_000)
internal const val SPRY_RETRY_WINDOW_MS = 60_000L
/** A member vouches only while its own redemption of the grant is this fresh. */
internal const val SPRY_VOUCH_FRESH_MS = 10L * 60 * 1000

/**
 * A paired device's registrar for its own room grant (the web's
 * `SpryRoom.#registerDevices`, spry-turn `docs/api.md` "Room-grant paths").
 * Its own key always; present members' keys only where it shares its relay;
 * never an agent's; a blocked person's like anyone admitted, since the roster
 * it reads carries no block. A seal is asked for once no present device that
 * is not an agent lacks a key, and decided again after every answer that says
 * `sealed: false`. State is for one grant token, this session only.
 */
internal class SpryRegistrar(
    private val ownKey: String,
    private val ownParticipant: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var registeredFor: String? = null
    /** Key to the participant it was registered for. */
    private val registered = LinkedHashMap<String, String>()
    /** Every key seen per participant, so a removal can drop all of them. */
    private val seen = LinkedHashMap<String, MutableSet<String>>()
    var sealed = false
        private set
    var grantDevices = 0
        private set
    private var revision = clock()
    private var unsupportedUntil = 0L

    /** A node that does not know the room-grant paths is left alone for an hour. */
    fun unsupported(): Boolean = clock() < unsupportedUntil
    fun markUnsupported() { unsupportedUntil = clock() + SPRY_UNSUPPORTED_MS }
    fun registered(key: String): Boolean = key in registered

    /** Forget the grant: sharing stopped or the pairing changed. */
    fun reset(pairingChanged: Boolean = false) {
        registeredFor = null
        registered.clear()
        sealed = false
        if (pairingChanged) unsupportedUntil = 0
    }

    /**
     * One registration pass for grant [token]: removals for participants
     * removed from the room, then this device's own key and, when [share],
     * present members' keys, then a seal. Keys are never removed because a
     * device left: presence is not membership. [send] carries one request
     * with its revision and throws [SpryRequestException] on refusal.
     */
    suspend fun register(
        token: String,
        roster: List<SpryRosterRow>,
        removed: Set<String>,
        share: Boolean,
        send: suspend (SpryDevicesChange, Long) -> SpryDevicesAnswer,
    ) {
        if (unsupported()) return
        if (registeredFor != token) {
            registeredFor = token
            registered.clear(); sealed = false; grantDevices = 0
        }
        val gone = removed.map { it.lowercase() }.toSet()
        fun isRemoved(participant: String) = participant.lowercase() in gone
        for (row in roster) {
            val key = row.spryKey ?: continue
            if (key == ownKey || isRemoved(row.participant)) continue
            seen.getOrPut(row.participant) { linkedSetOf() } += key
        }
        val remove = mutableListOf<String>()
        val iterator = seen.entries.iterator()
        while (iterator.hasNext()) {
            val (participant, keys) = iterator.next()
            if (!isRemoved(participant)) continue
            for (key in keys) { remove += key; registered.remove(key) }
            iterator.remove()
        }
        for (batch in remove.chunked(SPRY_KEYS_PER_REQUEST)) request(SpryDevicesChange(remove = batch), send)

        val add = mutableListOf<Pair<String, String>>()
        if (ownKey !in registered) add += ownKey to ownParticipant
        val perParticipant = HashMap<String, Int>()
        for (owner in registered.values) perParticipant[owner] = (perParticipant[owner] ?: 0) + 1
        // Not shared here: nobody else holds the token, so only this device's key.
        for (row in if (share) roster else emptyList()) {
            val key = row.spryKey ?: continue
            // Agents redeem no Spry offers: their keys would only spend the grant's slots.
            if (row.agent || key == ownKey || key in registered || isRemoved(row.participant) || add.any { it.first == key }) continue
            val count = perParticipant[row.participant] ?: 0
            if (count >= SPRY_KEYS_PER_PARTICIPANT) continue
            perParticipant[row.participant] = count + 1
            add += key to row.participant
        }
        while (add.isNotEmpty()) {
            // This device's own key always goes; others only while the grant has room.
            val own = add.first().first == ownKey
            val room = if (own) SPRY_KEYS_PER_REQUEST else minOf(SPRY_KEYS_PER_REQUEST, SPRY_GRANT_HEADROOM - grantDevices)
            if (room <= 0) break
            val batch = add.take(room)
            repeat(batch.size) { add.removeAt(0) }
            request(SpryDevicesChange(add = batch.map { it.first }), send)
            for ((key, participant) in batch) registered[key] = participant
        }

        // Sealed only while every present person's device announces a key: an
        // older client present keeps the grant open, as it was, until it leaves.
        val ready = roster.isNotEmpty() && roster.all { it.agent || it.spryKey != null } && ownKey in registered
        if (!sealed && ready) request(SpryDevicesChange(seal = true), send)
    }

    /** One request, retried once with a fresh revision when the node says it
     *  is out of date. Every answer re-decides the seal. */
    private suspend fun request(change: SpryDevicesChange, send: suspend (SpryDevicesChange, Long) -> SpryDevicesAnswer) {
        var attempt = 0
        while (true) {
            revision = maxOf(clock(), revision + 1)
            try {
                val answer = send(change, revision)
                sealed = answer.sealed
                grantDevices = answer.devices
                return
            } catch (error: SpryRequestException) {
                if (error.status == 409 && attempt++ == 0) continue
                if (error.status == 404) markUnsupported()
                throw error
            }
        }
    }
}

/**
 * Members vouch for a device admitted to the room after them, once each,
 * after a random delay of one to five seconds, while their own redemption of
 * that grant is under ten minutes old: so a newcomer, relay-only or not, gets
 * the room's relay while the owner is away (the web's
 * `SpryRoom.#scheduleVouches`). Devices already present when this one could
 * first vouch are not newcomers (every key named counts against its daily
 * allowance); agents are skipped, and at most two keys per participant.
 */
internal class SpryVoucher(
    private val selfDevice: String,
    private val ownKey: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val jitter: () -> Long = { 1_000 + Random.nextLong(4_000) },
) {
    internal data class Vouch(val offer: SpryOffer, val key: String, val delayMs: Long)

    private val redeemedAt = HashMap<String, Long>()
    private val vouched = HashSet<String>()
    private var base: Set<String>? = null
    private val perParticipant = HashMap<String, Int>()

    /** This device redeemed [offer] just now. */
    @Synchronized fun redeemed(offer: SpryOffer) { redeemedAt[offer.endpoint + ":" + offer.token] = clock() }

    /** The vouches due now, each to be sent alone after its delay. [candidates]
     *  in order of preference: the offer another member shared, then the one
     *  this device holds; never its own grant ([ownToken]). */
    @Synchronized fun due(candidates: List<SpryOffer?>, ownToken: String?, roster: List<SpryRosterRow>): List<Vouch> {
        val now = clock()
        val offer = candidates.firstOrNull { candidate ->
            candidate != null && candidate.token != ownToken &&
                now - (redeemedAt[candidate.endpoint + ":" + candidate.token] ?: 0L) < SPRY_VOUCH_FRESH_MS
        } ?: return emptyList()
        val present = base ?: run { base = roster.map { it.device }.toSet(); return emptyList() }
        val out = mutableListOf<Vouch>()
        for (row in roster) {
            val key = row.spryKey ?: continue
            if (row.agent || row.device == selfDevice || key == ownKey || row.device in present || row.device in vouched) continue
            val count = perParticipant[row.participant] ?: 0
            if (count >= SPRY_KEYS_PER_PARTICIPANT) continue
            perParticipant[row.participant] = count + 1
            vouched += row.device
            out += Vouch(offer, key, jitter())
        }
        return out
    }
}
