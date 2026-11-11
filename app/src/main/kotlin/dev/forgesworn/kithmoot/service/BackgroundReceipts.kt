package dev.forgesworn.kithmoot.service

import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.SecureTimingRandom
import dev.forgesworn.kithmoot.crypto.hexEquals
import dev.forgesworn.kithmoot.protocol.CredentialCheck
import dev.forgesworn.kithmoot.protocol.DecodeDeliveryReceiptOptions
import dev.forgesworn.kithmoot.protocol.DeliveryReceiptStream
import dev.forgesworn.kithmoot.protocol.EncodeDeliveryReceiptOptions
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.RoomPolicy
import dev.forgesworn.kithmoot.protocol.decodeDeliveryReceipt
import dev.forgesworn.kithmoot.protocol.encodeDeliveryReceipt
import dev.forgesworn.kithmoot.protocol.evaluateAccess
import dev.forgesworn.kithmoot.protocol.verifyDeviceCredential
import dev.forgesworn.kithmoot.session.ChatMessage
import dev.forgesworn.kithmoot.session.DeliveryReceiptWriter
import dev.forgesworn.kithmoot.session.ReceiptPacing
import dev.forgesworn.kithmoot.session.isTickMessage
import dev.forgesworn.kithmoot.storage.HeadlessSigning
import dev.forgesworn.kithmoot.storage.RoomStorage
import dev.forgesworn.kithmoot.storage.SavedRoom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random

/**
 * What the closed-app listener ([BackgroundCallListenerService]) says to the people who wrote
 * the messages it receives: one delivery receipt per stream (kind 1465, the delivery ticks
 * design's "Android" section), so a sender sees two ticks for a message that reached this phone
 * while KithMoot was closed. The open room confirms its own messages; this is the same writer
 * ([DeliveryReceiptWriter]) for a room nobody has open.
 *
 * It publishes nothing else, subscribes to nothing, and adds no filter: reading receipts
 * happens only in an open room. A message is offered here only when all of these hold; the
 * first, second and fourth are asked again when the receipt is signed:
 * - the person's "Confirm when messages reach this device" setting is on ([enabled]);
 * - the room is not open in the app ([roomOpen]), which confirms what it shows itself, and
 *   the room is not quiet or anonymous (the caller never builds one for those);
 * - the room admits this person without an admission proof, which a closed app does not hold
 *   (a receipt a reader would refuse is worse than none: the open room never offers a
 *   message this one has acknowledged again);
 * - [signing] gives this device's key and a credential for the room that is valid now, names
 *   this device and this participant, and needed no signer to make: the one kept with the room,
 *   or for a room whose person's key is on this phone, one made from that key
 *   (`SavedRoom.headlessSigning`, through [receiptSigning], which also asks that a room joined
 *   as an account still has that account signed in). Otherwise nothing is offered, and the
 *   open room confirms the backlog the next time it is opened;
 * - the writer's own rules hold: somebody else's message that renders as a row, sent after
 *   this device first saw the room (less a minute) and within seven days, not acknowledged
 *   before.
 *
 * What was acknowledged is kept in [acks], the same file the open room's writer uses, so
 * opening the room later confirms nothing twice. A writer lives as long as the room's
 * background watch, and is [cancel]led when the watch closes, which includes the room being
 * opened, so none outlives a turn of the open room's own writer.
 *
 * Every receipt is checked as a reader would check it ([decodeDeliveryReceipt] with the
 * room's policy) before it is sent. It is sent over [publish], the room's own background
 * connection.
 */
internal class BackgroundReceipts(
    private val roomId: String,
    private val participant: String,
    private val device: String,
    private val policy: RoomPolicy?,
    /** Every chat stream the watch reads: the current epoch's and each left one's, with its key. Not wiped here. */
    private val streams: List<DeliveryReceiptStream>,
    acks: RoomStorage?,
    scope: CoroutineScope,
    /** The person's setting, asked for every message and again at every flush. */
    private val enabled: () -> Boolean,
    /** Whether the room is open in the app now. */
    private val roomOpen: () -> Boolean,
    /**
     * This device's signing material for the room at a unix second, read fresh each time (a renewal
     * rewrites the saved room), or null when only a signer could make a credential, the room has
     * moved or ended, or it must send nothing. A fresh copy of the device key each time: [BackgroundReceipts] zeroes it.
     */
    private val signing: (nowSeconds: Long) -> HeadlessSigning?,
    /** A room that ends lowers every receipt's expiration to its end. */
    private val roomEnds: (nowSeconds: Long) -> Long? = { null },
    /** Sends one receipt over the room's background connection; true once a relay took it. */
    private val publish: suspend (NostrEvent) -> Boolean,
    /** Milliseconds. */
    private val clock: () -> Long = System::currentTimeMillis,
    context: CoroutineContext = Dispatchers.IO,
    random: Random = SecureTimingRandom(),
    pacing: ReceiptPacing = ReceiptPacing.CLOSED_APP,
) {
    private val writer = DeliveryReceiptWriter(acks, scope, context, clock, random, enabled, pacing)
    private val lock = Any()
    private var checkedUntil = 0L
    private var ready = false

    /** Reads what was acknowledged, off the main thread, and fixes when this device first saw the room if it never has. */
    fun preload() = writer.preload()

    /**
     * Somebody else's message was received, decrypted and checked on this phone: confirm it if every rule above
     * allows, at the end of the burst it arrived in. Whether the inbox counted it as unread is no part of that.
     * Never throws.
     */
    fun offer(event: NostrEvent, message: ChatMessage) {
        try {
            if (!enabled() || roomOpen()) return
            // Cheap questions first: a reaction, an edit or the person's own message is not worth a read of the saved room.
            if (!isTickMessage(message) || message.participant.hexEquals(participant)) return
            val d = event.tagValue("d") ?: return
            val at = clock() / 1000
            if (!admitted(at) || !canSign(at)) return
            writer.received(message, d, participant) { stream, ids, createdAt, index -> publishReceipt(stream, ids, createdAt, index) }
        } catch (_: Exception) { /* A receipt is never worth a message. */ }
    }

    /** Stops everything waiting, including a flush in flight. What was acknowledged stays. */
    fun cancel() = writer.cancel()

    /** How many ids wait to go. For tests. */
    fun waiting(): Int = writer.waiting()

    private fun admitted(at: Long): Boolean =
        policy == null || (policy.quiet != true && evaluateAccess(policy, participant, null, at, roomId).admitted)

    /**
     * Whether [signing] has something to sign with, remembered for [SIGNING_CHECK_SECONDS]: a replay of
     * a room's recent chat offers every message again, and reading the saved rooms costs a decryption each.
     * The receipt is signed from a fresh read, so a credential that lapses in the meantime is still refused.
     */
    private fun canSign(at: Long): Boolean {
        synchronized(lock) {
            if (at < checkedUntil) return ready
            val held = signing(at)
            ready = try { held != null && names(held, at) } finally { held?.deviceSecretKey?.fill(0) }
            checkedUntil = at + SIGNING_CHECK_SECONDS
            return ready
        }
    }

    /** The credential is valid at [at] and names this device and this participant, whose key it is. */
    private fun names(held: HeadlessSigning, at: Long): Boolean {
        val verdict = verifyDeviceCredential(held.credential, roomId, at)
        return verdict is CredentialCheck.Valid && verdict.device.hexEquals(device) && verdict.participant.hexEquals(participant) &&
            held.participant.hexEquals(participant) && Schnorr.publicKeyHex(held.deviceSecretKey).hexEquals(device)
    }

    private suspend fun publishReceipt(d: String, messageIds: List<String>, createdAt: Long, index: Int): Boolean {
        // Handed over: the open room confirms what it shows, and the watch is about to close.
        if (!enabled() || roomOpen()) return false
        val stream = streams.firstOrNull { it.d.equals(d, ignoreCase = true) } ?: return false
        val held = signing(createdAt) ?: return false
        try {
            if (!names(held, createdAt)) return false
            val event = encodeDeliveryReceipt(EncodeDeliveryReceiptOptions(
                roomId = roomId,
                stream = stream,
                deviceSecretKey = held.deviceSecretKey,
                credential = held.credential,
                proof = null,
                messageIds = messageIds,
                createdAt = createdAt,
                index = index,
                roomEnds = roomEnds(createdAt),
            ))
            // Exactly what a reader will accept, or nothing: a receipt refused by every reader would still
            // be remembered as acknowledged, and the open room would never confirm those messages.
            decodeDeliveryReceipt(event, DecodeDeliveryReceiptOptions(roomId, streams, createdAt, policy)) ?: return false
            return publish(event)
        } finally {
            held.deviceSecretKey.fill(0)
        }
    }

    companion object {
        /** How long a look at the saved room's signing material is remembered. */
        const val SIGNING_CHECK_SECONDS: Long = 30
    }
}

/**
 * What the closed app signs a delivery receipt for [saved] with at unix second [now], or null when it
 * must send none: the room is not saved, is off the Internet, is anonymous, or has moved to a node whose
 * door this route may not use. Otherwise [SavedRoom.headlessSigning]: this device's key and a credential that
 * needs no signer. A room joined as an account speaks as that account only, so it signs only while that same
 * account is signed in on this phone ([signedIn], asked only when needed; null for nobody, or an account that
 * cannot be read): the kept credential would still verify after the person signed out or changed account.
 * A bunker account needs no exclusion, because the kept credential signs without the bunker.
 */
internal fun receiptSigning(saved: SavedRoom?, signedIn: () -> String?, now: Long): HeadlessSigning? {
    if (saved == null || !saved.route.internet || saved.anonymous || saved.announcedNode != null) return null
    if (saved.viaAccount && signedIn()?.equals(saved.participant, ignoreCase = true) != true) return null
    return saved.headlessSigning(now)
}
