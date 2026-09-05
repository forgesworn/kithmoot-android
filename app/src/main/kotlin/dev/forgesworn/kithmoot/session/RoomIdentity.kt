package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.protocol.CredentialCheck
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.createDeviceCredential
import dev.forgesworn.kithmoot.protocol.verifyDeviceCredential

/**
 * Who this device is in one room.
 *
 * A participant is a person; a device is one of the things they are sitting in
 * front of. Every device signs for itself with its own key and carries a
 * credential, signed by the participant key, saying it may speak for that
 * person in that room.
 */
sealed interface RoomIdentity {
    /** The key this device signs its own events with. */
    val deviceSecretKey: ByteArray
    val devicePubkey: String

    /** The person. Everyone's devices group under this. */
    val participant: String

    /** The participant's signed statement that this device speaks for them here. */
    val credential: NostrEvent
}

/**
 * The device the participant key lives on. It is the only one that can enrol
 * another device, because enrolling means signing a credential.
 */
class PrimaryIdentity(
    private val participantSecretKey: ByteArray,
    override val deviceSecretKey: ByteArray,
    override val credential: NostrEvent,
) : RoomIdentity {

    override val devicePubkey: String = Schnorr.publicKeyHex(deviceSecretKey)
    override val participant: String = Schnorr.publicKeyHex(participantSecretKey)

    /** Only the encrypted local room store needs a copy of this key. */
    internal fun participantKeyForStorage(): ByteArray = participantSecretKey.copyOf()

    /**
     * Mints a credential for another of this person's devices, so it can join
     * the room as them without ever being handed the participant key.
     */
    fun enrol(
        devicePubkey: String,
        roomId: String,
        expiresAt: Long,
        createdAt: Long,
    ): NostrEvent = createDeviceCredential(
        participantSecretKey = participantSecretKey,
        devicePubkey = devicePubkey,
        roomId = roomId,
        expiresAt = expiresAt,
        createdAt = createdAt,
    )

    companion object {
        /** Creates a participant and their first device in one go. */
        fun create(
            roomId: String,
            expiresAt: Long,
            createdAt: Long,
            participantSecretKey: ByteArray = Entropy.bytes(32),
            deviceSecretKey: ByteArray = Entropy.bytes(32),
        ): PrimaryIdentity = PrimaryIdentity(
            participantSecretKey = participantSecretKey,
            deviceSecretKey = deviceSecretKey,
            credential = createDeviceCredential(
                participantSecretKey = participantSecretKey,
                devicePubkey = Schnorr.publicKeyHex(deviceSecretKey),
                roomId = roomId,
                expiresAt = expiresAt,
                createdAt = createdAt,
            ),
        )
    }
}

/**
 * A second device, working from a credential and its own key and **nothing
 * else**.
 *
 * There is deliberately no field here that could hold the participant key, and
 * no constructor that accepts one. A phone that joins a room as your second
 * screen is a phone you can lose; what it can do when lost is bounded by the
 * one room its credential names and the expiry it was given, and the worst case
 * never includes losing the identity itself.
 */
class SecondaryIdentity private constructor(
    override val deviceSecretKey: ByteArray,
    override val credential: NostrEvent,
    override val participant: String,
) : RoomIdentity {

    override val devicePubkey: String = Schnorr.publicKeyHex(deviceSecretKey)

    companion object {
        /**
         * Takes up a credential, or returns null if it does not actually
         * authorise this device in this room. The participant is read off the
         * credential's signer rather than taken on trust from the caller.
         */
        fun adopt(
            credential: NostrEvent,
            deviceSecretKey: ByteArray,
            roomId: String,
            now: Long,
        ): SecondaryIdentity? {
            val devicePubkey = Schnorr.publicKeyHex(deviceSecretKey)
            val check = verifyDeviceCredential(credential, roomId, now)
            if (check !is CredentialCheck.Valid) return null
            if (check.device != devicePubkey) return null
            return SecondaryIdentity(deviceSecretKey, credential, check.participant)
        }
    }
}
